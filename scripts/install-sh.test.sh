#!/bin/sh

set -eu

project_directory=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
GRAPHMD_INSTALLER_TESTING=1
export GRAPHMD_INSTALLER_TESTING
. "$project_directory/install.sh"

test_directory=$(mktemp -d "${TMPDIR:-/tmp}/graphmd-install-test.XXXXXX")
trap 'rm -rf "$test_directory"' EXIT HUP INT TERM

assert_file_content() {
    expected=$1
    file=$2
    actual=$(cat "$file")
    [ "$actual" = "$expected" ] || {
        printf 'Expected %s to contain "%s", got "%s"\n' "$file" "$expected" "$actual" >&2
        exit 1
    }
}

fixture_directory="$test_directory/release"
mkdir -p "$fixture_directory/archive"
printf '%s\n' 'new graphmd' > "$fixture_directory/archive/graphmd"
chmod 755 "$fixture_directory/archive/graphmd"
tar -czf "$fixture_directory/graphmd-linux-x64-1.2.3.tar.gz" -C "$fixture_directory/archive" graphmd
fixture_checksum=$(file_sha256 "$fixture_directory/graphmd-linux-x64-1.2.3.tar.gz")
printf '%s  %s\n' "$fixture_checksum" 'graphmd-linux-x64-1.2.3.tar.gz' > "$fixture_directory/SHA256SUMS"

run_fixture_install() (
    destination=$1
    shift
    detect_target() { printf '%s\n' 'linux-x64'; }
    download_file() {
        fixture_name=${1##*/}
        cp "$fixture_directory/$fixture_name" "$2"
    }
    main "$@" --install-dir "$destination"
)

# Explicit arguments override environment defaults and replace an existing binary.
argument_destination="$test_directory/argument-bin"
mkdir -p "$argument_destination"
printf '%s\n' 'old graphmd' > "$argument_destination/graphmd"
GRAPHMD_VERSION=9.9.9 GRAPHMD_INSTALL_DIR="$test_directory/environment-bin" \
    run_fixture_install "$argument_destination" --version v1.2.3 >/dev/null
assert_file_content 'new graphmd' "$argument_destination/graphmd"
[ ! -e "$test_directory/environment-bin/graphmd" ]
[ -x "$argument_destination/graphmd" ]
unset GRAPHMD_VERSION GRAPHMD_INSTALL_DIR

# The latest URL discovers the single matching asset from SHA256SUMS.
latest_destination="$test_directory/latest-bin"
run_fixture_install "$latest_destination" >/dev/null
assert_file_content 'new graphmd' "$latest_destination/graphmd"

# A checksum failure leaves an existing installation untouched.
bad_fixture_directory="$test_directory/bad-release"
mkdir -p "$bad_fixture_directory"
cp "$fixture_directory/graphmd-linux-x64-1.2.3.tar.gz" "$bad_fixture_directory/"
printf '%064d  %s\n' 0 'graphmd-linux-x64-1.2.3.tar.gz' > "$bad_fixture_directory/SHA256SUMS"
preserved_destination="$test_directory/preserved-bin"
mkdir -p "$preserved_destination"
printf '%s\n' 'keep graphmd' > "$preserved_destination/graphmd"
if (
    fixture_directory=$bad_fixture_directory
    run_fixture_install "$preserved_destination" --version 1.2.3
) >/dev/null 2>&1; then
    printf 'Checksum mismatch unexpectedly succeeded\n' >&2
    exit 1
fi
assert_file_content 'keep graphmd' "$preserved_destination/graphmd"

# Missing checksum entries and unsupported targets fail.
empty_fixture_directory="$test_directory/empty-release"
mkdir -p "$empty_fixture_directory"
: > "$empty_fixture_directory/SHA256SUMS"
if (
    fixture_directory=$empty_fixture_directory
    run_fixture_install "$test_directory/missing-checksum" --version 1.2.3
) >/dev/null 2>&1; then
    printf 'Missing checksum unexpectedly succeeded\n' >&2
    exit 1
fi

if (
    uname() {
        if [ "$1" = '-s' ]; then printf '%s\n' FreeBSD; else printf '%s\n' arm64; fi
    }
    detect_target
) >/dev/null 2>&1; then
    printf 'Unsupported platform unexpectedly succeeded\n' >&2
    exit 1
fi

assert_target() (
    expected_target=$1
    test_os=$2
    test_arch=$3
    uname() {
        if [ "$1" = '-s' ]; then printf '%s\n' "$test_os"; else printf '%s\n' "$test_arch"; fi
    }
    actual_target=$(detect_target)
    [ "$actual_target" = "$expected_target" ]
)
assert_target macos-arm64 Darwin arm64
assert_target macos-x64 Darwin x86_64
assert_target linux-x64 Linux x86_64

# wget is used when curl is unavailable.
printf '%s\n' 'wget fixture' > "$test_directory/wget-source"
(
    command() {
        [ "$1" = '-v' ] || return 1
        [ "$2" = wget ]
    }
    wget() {
        [ "$1" = '-q' ]
        [ "$2" = '-O' ]
        wget_output=$3
        wget_source=${4#file://}
        cp "$wget_source" "$wget_output"
    }
    download_file "file://$test_directory/wget-source" "$test_directory/wget-output"
)
assert_file_content 'wget fixture' "$test_directory/wget-output"

# Missing download and checksum tools produce a controlled failure.
if (PATH=/nonexistent download_file example.invalid "$test_directory/no-download") >/dev/null 2>&1; then
    printf 'Missing downloader unexpectedly succeeded\n' >&2
    exit 1
fi
if (PATH=/nonexistent file_sha256 "$fixture_directory/SHA256SUMS") >/dev/null 2>&1; then
    printf 'Missing checksum tool unexpectedly succeeded\n' >&2
    exit 1
fi

download_error=$(
    (
        detect_target() { printf '%s\n' 'linux-x64'; }
        download_file() { return 1; }
        main --version 1.2.3 --install-dir "$test_directory/download-failure"
    ) 2>&1
) && {
    printf 'Checksum download failure unexpectedly succeeded\n' >&2
    exit 1
}
case "$download_error" in
    *"required checksum file from https://github.com/usbharu/graph-md/releases/download/v1.2.3/SHA256SUMS"*) ;;
    *)
        printf 'Checksum download error did not include the attempted URL: %s\n' "$download_error" >&2
        exit 1
        ;;
esac

[ "$(normalize_version latest)" = latest ]
[ "$(normalize_version 1.2.3)" = v1.2.3 ]
[ "$(normalize_version v1.2.3)" = v1.2.3 ]
if (normalize_version 1.2 >/dev/null 2>&1); then
    printf 'Invalid version unexpectedly succeeded\n' >&2
    exit 1
fi

printf 'install.sh tests passed\n'
