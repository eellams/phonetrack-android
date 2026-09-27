#!/usr/bin/env bash
# Fails if the branch changes the app version (appVersionName / appVersionCode in
# gradle.properties) since BASE. Merging a new version to main publishes a release
# (.github/workflows/release.yml), so CI runs this on pull requests from other repositories:
# only the "Prepare release" workflow and maintainers set the version.
#
#   scripts/release/check-version-unchanged.sh origin/main
set -euo pipefail

base=${1:?usage: check-version-unchanged.sh BASE_REF}
changed=$(git diff "$base...HEAD" -- gradle.properties | grep -E '^[+-][[:space:]]*appVersion(Name|Code)[[:space:]]*[=:]' || true)
if [[ -n $changed ]]; then
    echo "::error::Pull requests from other repositories can't change the app version: merging a new version publishes a release. Please revert these lines in gradle.properties:" >&2
    echo "$changed" >&2
    exit 1
fi
echo "app version unchanged"
