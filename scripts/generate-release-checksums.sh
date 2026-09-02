#!/bin/sh

set -eu

if [ "$#" -ne 2 ]; then
    printf 'Usage: %s ASSET_DIRECTORY OUTPUT_FILE\n' "$0" >&2
    exit 2
fi

asset_directory=$1
output_file=$2
[ -d "$asset_directory" ] || {
    printf 'Asset directory does not exist: %s\n' "$asset_directory" >&2
    exit 1
}

output_name=$(basename "$output_file")
temporary_output="$output_file.tmp.$$"
names_file="$output_file.names.$$"
trap 'rm -f "$temporary_output" "$names_file"' EXIT HUP INT TERM

for asset_path in "$asset_directory"/*; do
    [ -f "$asset_path" ] || continue
    asset_name=$(basename "$asset_path")
    [ "$asset_name" = "$output_name" ] || printf '%s\n' "$asset_name"
done | LC_ALL=C sort > "$names_file"
[ -s "$names_file" ] || {
    printf 'No release assets found in %s\n' "$asset_directory" >&2
    exit 1
}

: > "$temporary_output"
while IFS= read -r asset_name; do
    asset_path="$asset_directory/$asset_name"
    if command -v sha256sum >/dev/null 2>&1; then
        checksum=$(sha256sum "$asset_path" | awk '{ print tolower($1) }')
    elif command -v shasum >/dev/null 2>&1; then
        checksum=$(shasum -a 256 "$asset_path" | awk '{ print tolower($1) }')
    else
        printf 'sha256sum or shasum is required\n' >&2
        exit 1
    fi
    printf '%s  %s\n' "$checksum" "$asset_name" >> "$temporary_output"
done < "$names_file"

mv "$temporary_output" "$output_file"
rm -f "$names_file"
trap - EXIT HUP INT TERM
