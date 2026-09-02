#!/bin/sh

set -eu

project_directory=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
test_directory=$(mktemp -d "${TMPDIR:-/tmp}/graphmd-checksum-test.XXXXXX")
trap 'rm -rf "$test_directory"' EXIT HUP INT TERM

mkdir "$test_directory/assets"
printf '%s\n' beta > "$test_directory/assets/zeta.zip"
printf '%s\n' alpha > "$test_directory/assets/alpha.tar.gz"
sh "$project_directory/scripts/generate-release-checksums.sh" \
    "$test_directory/assets" "$test_directory/assets/SHA256SUMS"
first_run=$(cat "$test_directory/assets/SHA256SUMS")
sh "$project_directory/scripts/generate-release-checksums.sh" \
    "$test_directory/assets" "$test_directory/assets/SHA256SUMS"
[ "$(cat "$test_directory/assets/SHA256SUMS")" = "$first_run" ]

first_asset=$(awk 'NR == 1 { print $2 }' "$test_directory/assets/SHA256SUMS")
second_asset=$(awk 'NR == 2 { print $2 }' "$test_directory/assets/SHA256SUMS")
[ "$first_asset" = alpha.tar.gz ]
[ "$second_asset" = zeta.zip ]
[ "$(wc -l < "$test_directory/assets/SHA256SUMS" | tr -d ' ')" -eq 2 ]

while IFS='  ' read -r expected asset_name; do
    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$test_directory/assets/$asset_name" | awk '{ print $1 }')
    else
        actual=$(shasum -a 256 "$test_directory/assets/$asset_name" | awk '{ print $1 }')
    fi
    [ "$actual" = "$expected" ]
done < "$test_directory/assets/SHA256SUMS"

printf 'release checksum tests passed\n'
