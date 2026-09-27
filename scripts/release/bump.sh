#!/usr/bin/env bash
# Sets the next release's version in gradle.properties:
#   appVersionName  VERSION if given, else computed from the commits since the previous release
#                   (git-cliff --bumped-version, see [bump] in cliff.toml)
#   appVersionCode  the current one + 1
#
#   scripts/release/bump.sh [VERSION]
#
# Fails when the version isn't higher than the current one, e.g. because there is nothing to
# release. Prints version=X.Y.Z and version_code=N (also appended to $GITHUB_OUTPUT when set).
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

current_name=$(prop appVersionName)
current_code=$(prop appVersionCode)
[[ $current_code =~ ^[0-9]+$ ]] || fail "appVersionCode '$current_code' in gradle.properties is not a number"

version=${1:-}
if [[ -z $version ]]; then
    command -v git-cliff >/dev/null || fail "git-cliff is not installed (pipx install git-cliff)"
    version=$(git-cliff --config cliff.toml --bumped-version 2>/dev/null)
    version=${version#v}
fi
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version '$version' is not X.Y.Z"

highest=$(printf '%s\n%s\n' "$current_name" "$version" | sort -V | tail -1)
if [[ $version == "$current_name" || $highest != "$version" ]]; then
    fail "version $version is not higher than the current $current_name: no feat:/fix:/... commits since the previous release?"
fi
if git rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
    fail "tag v$version already exists"
fi
version_code=$((current_code + 1))

sed -i -E \
    -e "s/^appVersionName=.*/appVersionName=$version/" \
    -e "s/^appVersionCode=.*/appVersionCode=$version_code/" \
    gradle.properties

result="version=$version
version_code=$version_code"
echo "$result"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then
    echo "$result" >> "$GITHUB_OUTPUT"
fi
