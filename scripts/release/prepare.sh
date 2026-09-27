#!/usr/bin/env bash
# Checks that a release is consistent and generates its release notes.
#
#   scripts/release/prepare.sh [TAG] [OUT_DIR]
#
# TAG defaults to v<appVersionName> (a dry run of the next release). Checks:
#   - TAG is vX.Y.Z (full release) or vX.Y.Z-suffix (pre-release, e.g. v0.3.0-rc.1)
#   - X.Y.Z equals appVersionName in gradle.properties
#   - appVersionCode is higher than the one of the previous release tag (except a pre-release
#     of this same version)
#   - there is something to release: Conventional Commits since the previous full release
# Writes to OUT_DIR (default: release-out):
#   RELEASE_NOTES.md   .github/release-highlights/X.Y.Z.md if it exists (hand-written, optional),
#                      then the notes git-cliff generates from the commit messages (cliff.toml)
#   CHANGELOG_ENTRY.md the same under a "## [X.Y.Z] – date" heading, for CHANGELOG.md
# Prints key=value lines (also appended to $GITHUB_OUTPUT when set): version, tag, prerelease,
# version_code, previous_release. Needs git-cliff (pipx install git-cliff).
set -euo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"

fail() {
    echo "::error::$*" >&2
    exit 1
}

# the value of a property; exactly one line must set it (Gradle would use the last one)
prop() {
    local lines
    lines=$(grep -E "^[[:space:]]*$1[[:space:]]*[=:]" gradle.properties || true)
    [[ $(grep -c . <<< "$lines") == 1 ]] || fail "gradle.properties must set $1 exactly once"
    cut -d= -f2- <<< "$lines" | tr -d '[:space:]'
}

version_name=$(prop appVersionName)
version_code=$(prop appVersionCode)
[[ $version_name =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "appVersionName '$version_name' in gradle.properties is not X.Y.Z"
[[ $version_code =~ ^[0-9]+$ ]] || fail "appVersionCode '$version_code' in gradle.properties is not a number"

tag=${1:-v$version_name}
out_dir=${2:-release-out}

[[ $tag =~ ^v([0-9]+\.[0-9]+\.[0-9]+)(-[0-9A-Za-z][0-9A-Za-z.-]*)?$ ]] \
    || fail "tag '$tag' must be vX.Y.Z (release) or vX.Y.Z-suffix (pre-release)"
base=${BASH_REMATCH[1]}
suffix=${BASH_REMATCH[2]}
[[ $base == "$version_name" ]] \
    || fail "tag $tag does not match appVersionName $version_name in gradle.properties"
prerelease=false
[[ -n $suffix ]] && prerelease=true

# versionCode of a tag: gradle.properties since 0.2.0, the module build file before that
code_at() {
    local ref=$1 code
    code=$(git show "$ref:gradle.properties" 2>/dev/null | grep -E '^appVersionCode=' | cut -d= -f2 | tr -d '[:space:]' || true)
    if [[ -z $code ]]; then
        code=$( { git show "$ref:app/build.gradle.kts" 2>/dev/null || git show "$ref:app/build.gradle" 2>/dev/null; } \
            | grep -oE 'versionCode[ =]+[0-9]+' | grep -oE '[0-9]+' | head -1 || true)
    fi
    echo "$code"
}

# previous release: the newest v* tag other than this one that is an ancestor of HEAD; pre-releases
# of this same version (vX.Y.Z-rc.1 before vX.Y.Z) are the same build, so they may share its code
previous=$(git tag --list 'v*' --merged HEAD --sort=-creatordate | grep -vx "$tag" \
    | grep -vF "v$base-" | head -1 || true)
if [[ -n $previous ]]; then
    previous_code=$(code_at "$previous")
    if [[ -n $previous_code ]] && (( version_code <= previous_code )); then
        fail "appVersionCode $version_code must be higher than $previous_code (from $previous): Android refuses to install a lower or equal versionCode as an update"
    fi
fi

command -v git-cliff >/dev/null || fail "git-cliff is not installed (pipx install git-cliff)"

# notes cover everything since the previous full release (release candidates included)
previous_release=$(git tag --list 'v*' --merged HEAD --sort=-creatordate \
    | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | grep -vx "$tag" | head -1 || true)
range=${previous_release:+$previous_release..}HEAD
generated=$(git-cliff --config cliff.toml "$range" --tag "$tag" --strip all 2>/dev/null)
[[ -n ${generated//[[:space:]]/} ]] \
    || fail "no Conventional Commits (feat:, fix:, ...) since ${previous_release:-the first commit}: nothing to release"

highlights_file=.github/release-highlights/$base.md
mkdir -p "$out_dir"
{
    if [[ -s $highlights_file ]]; then
        cat "$highlights_file"
        echo
    fi
    printf '%s\n' "$generated"
} > "$out_dir/RELEASE_NOTES.md"
{
    echo "## [${tag#v}] – $(date -u +%Y-%m-%d)"
    echo
    cat "$out_dir/RELEASE_NOTES.md"
} > "$out_dir/CHANGELOG_ENTRY.md"

result="version=${tag#v}
tag=$tag
prerelease=$prerelease
version_code=$version_code
previous_release=$previous_release"
echo "$result"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then
    echo "$result" >> "$GITHUB_OUTPUT"
fi
