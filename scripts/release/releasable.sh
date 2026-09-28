#!/usr/bin/env bash
# Succeeds if there are user-facing commits since the previous full release, i.e. a push to main
# should publish a release: feat:, fix:, perf:, refactor:, any type with the security scope, and
# breaking changes (type!: or a BREAKING CHANGE: footer). build:, ci:, docs:, chore:, test: and
# style: commits alone wait for the next release. Lists the commits it found.
#
#   scripts/release/releasable.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

previous=$(git tag --list 'v*' --merged HEAD --sort=-creatordate \
    | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1 || true)
range=${previous:+$previous..}HEAD

found=$(git log --no-merges --format='%h %s%n%b%x00' "$range" | awk -v RS='\0' '
    {
        subject = $0; sub(/\n.*/, "", subject)
        message = substr(subject, index(subject, " ") + 1)
        if (message ~ /^(feat|fix|perf|refactor)(\([a-z0-9._\/-]+\))?!?: / ||
            message ~ /^[a-z]+\(security\)!?: / ||
            message ~ /^[a-z]+(\([a-z0-9._\/-]+\))?!: / ||
            $0 ~ /\nBREAKING[ -]CHANGE: /)
            print subject
    }')

if [[ -z $found ]]; then
    echo "no user-facing commits since ${previous:-the first commit}"
    exit 1
fi
echo "user-facing commits since ${previous:-the first commit}:"
echo "$found"
