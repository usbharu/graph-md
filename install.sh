#!/bin/sh

set -eu

GRAPHMD_REPOSITORY="usbharu/graph-md"
GRAPHMD_RELEASES_URL="${GRAPHMD_INSTALLER_RELEASES_URL:-https://github.com/$GRAPHMD_REPOSITORY/releases}"
GRAPHMD_STAGED_BINARY=""
GRAPHMD_TEMP_DIRECTORY=""

say() {
    printf '%s\n' "$*"
}

fail() {
    printf 'graphmd installer: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Install the GraphMD native CLI.

Usage:
  install.sh [--version VERSION] [--install-dir DIRECTORY]

Options:
  --version VERSION       Install latest (default), X.Y.Z, or vX.Y.Z.
  --install-dir DIRECTORY Install into DIRECTORY (default: ~/.local/bin).
  -h, --help              Show this help.

Environment:
  GRAPHMD_VERSION         Default version when --version is omitted.
  GRAPHMD_INSTALL_DIR     Default directory when --install-dir is omitted.
EOF
}

cleanup() {
    if [ -n "$GRAPHMD_STAGED_BINARY" ]; then
        rm -f "$GRAPHMD_STAGED_BINARY"
    fi
    if [ -n "$GRAPHMD_TEMP_DIRECTORY" ]; then
        rm -rf "$GRAPHMD_TEMP_DIRECTORY"
    fi
}

detect_target() {
    graphmd_os=$(uname -s 2>/dev/null) || fail "cannot determine the operating system"
    graphmd_arch=$(uname -m 2>/dev/null) || fail "cannot determine the CPU architecture"

    case "$graphmd_os:$graphmd_arch" in
        Darwin:arm64|Darwin:aarch64)
            printf '%s\n' "macos-arm64"
            ;;
        Darwin:x86_64|Darwin:amd64)
            printf '%s\n' "macos-x64"
            ;;
        Linux:x86_64|Linux:amd64)
            printf '%s\n' "linux-x64"
            ;;
        *)
            fail "unsupported platform: $graphmd_os $graphmd_arch (supported: macOS arm64/x64 and Linux x64)"
            ;;
    esac
}

download_file() {
    graphmd_url=$1
    graphmd_output=$2
    if command -v curl >/dev/null 2>&1; then
        curl --fail --silent --show-error --location "$graphmd_url" --output "$graphmd_output"
    elif command -v wget >/dev/null 2>&1; then
        wget --quiet "$graphmd_url" --output-document="$graphmd_output"
    else
        fail "curl or wget is required"
    fi
}

file_sha256() {
    graphmd_file=$1
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$graphmd_file" | awk '{ print tolower($1) }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$graphmd_file" | awk '{ print tolower($1) }'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$graphmd_file" | awk '{ print tolower($NF) }'
    else
        fail "sha256sum, shasum, or openssl is required for checksum verification"
    fi
}

normalize_version() {
    graphmd_version=$1
    if [ "$graphmd_version" = "latest" ]; then
        printf '%s\n' "latest"
        return
    fi

    graphmd_version=${graphmd_version#v}
    if ! printf '%s\n' "$graphmd_version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
        fail "invalid version '$1' (expected latest, X.Y.Z, or vX.Y.Z)"
    fi
    printf 'v%s\n' "$graphmd_version"
}

checksum_entry() {
    graphmd_checksums=$1
    graphmd_target=$2
    graphmd_release_version=$3

    if [ "$graphmd_release_version" = "latest" ]; then
        graphmd_entries=$(awk -v target="$graphmd_target" '
            $1 ~ /^[0-9A-Fa-f]{64}$/ &&
            $2 ~ ("^graphmd-" target "-[0-9]+\\.[0-9]+\\.[0-9]+\\.tar\\.gz$") {
                print tolower($1) " " $2
            }
        ' "$graphmd_checksums")
    else
        graphmd_plain_version=${graphmd_release_version#v}
        graphmd_expected_asset="graphmd-$graphmd_target-$graphmd_plain_version.tar.gz"
        graphmd_entries=$(awk -v asset="$graphmd_expected_asset" '
            $1 ~ /^[0-9A-Fa-f]{64}$/ && $2 == asset { print tolower($1) " " $2 }
        ' "$graphmd_checksums")
    fi

    graphmd_entry_count=$(printf '%s\n' "$graphmd_entries" | awk 'NF { count++ } END { print count + 0 }')
    if [ "$graphmd_entry_count" -ne 1 ]; then
        fail "SHA256SUMS must contain exactly one checksum for graphmd-$graphmd_target"
    fi
    printf '%s\n' "$graphmd_entries"
}

main() {
    graphmd_version=${GRAPHMD_VERSION:-latest}
    if [ -n "${GRAPHMD_INSTALL_DIR:-}" ]; then
        graphmd_install_dir=$GRAPHMD_INSTALL_DIR
    else
        [ -n "${HOME:-}" ] || fail "HOME is not set; use --install-dir or GRAPHMD_INSTALL_DIR"
        graphmd_install_dir=$HOME/.local/bin
    fi

    while [ "$#" -gt 0 ]; do
        case "$1" in
            --version)
                [ "$#" -ge 2 ] || fail "--version requires a value"
                graphmd_version=$2
                shift 2
                ;;
            --version=*)
                graphmd_version=${1#*=}
                shift
                ;;
            --install-dir)
                [ "$#" -ge 2 ] || fail "--install-dir requires a value"
                graphmd_install_dir=$2
                shift 2
                ;;
            --install-dir=*)
                graphmd_install_dir=${1#*=}
                shift
                ;;
            -h|--help)
                usage
                return 0
                ;;
            *)
                fail "unknown argument '$1' (use --help for usage)"
                ;;
        esac
    done

    [ -n "$graphmd_install_dir" ] || fail "install directory must not be empty"
    graphmd_release_version=$(normalize_version "$graphmd_version")
    graphmd_target=$(detect_target)

    if [ "$graphmd_release_version" = "latest" ]; then
        graphmd_download_root="$GRAPHMD_RELEASES_URL/latest/download"
    else
        graphmd_download_root="$GRAPHMD_RELEASES_URL/download/$graphmd_release_version"
    fi

    GRAPHMD_TEMP_DIRECTORY=$(mktemp -d "${TMPDIR:-/tmp}/graphmd-install.XXXXXX") || fail "cannot create a temporary directory"
    trap cleanup EXIT HUP INT TERM
    graphmd_checksums="$GRAPHMD_TEMP_DIRECTORY/SHA256SUMS"
    graphmd_checksum_url="$graphmd_download_root/SHA256SUMS"
    if ! download_file "$graphmd_checksum_url" "$graphmd_checksums"; then
        fail "failed to download required checksum file from $graphmd_checksum_url; check network access and ensure the release publishes SHA256SUMS (v0.0.7 and earlier do not)"
    fi

    graphmd_entry=$(checksum_entry "$graphmd_checksums" "$graphmd_target" "$graphmd_release_version")
    graphmd_expected_checksum=${graphmd_entry%% *}
    graphmd_asset=${graphmd_entry#* }
    graphmd_archive="$GRAPHMD_TEMP_DIRECTORY/$graphmd_asset"
    if ! download_file "$graphmd_download_root/$graphmd_asset" "$graphmd_archive"; then
        fail "failed to download $graphmd_asset"
    fi

    graphmd_actual_checksum=$(file_sha256 "$graphmd_archive")
    if [ "$graphmd_actual_checksum" != "$graphmd_expected_checksum" ]; then
        fail "checksum mismatch for $graphmd_asset"
    fi

    mkdir "$GRAPHMD_TEMP_DIRECTORY/extracted"
    tar -xzf "$graphmd_archive" -C "$GRAPHMD_TEMP_DIRECTORY/extracted" || fail "failed to extract $graphmd_asset"
    graphmd_extracted_binary="$GRAPHMD_TEMP_DIRECTORY/extracted/graphmd"
    [ -f "$graphmd_extracted_binary" ] || fail "$graphmd_asset does not contain graphmd"

    mkdir -p "$graphmd_install_dir" || fail "cannot create $graphmd_install_dir"
    GRAPHMD_STAGED_BINARY="$graphmd_install_dir/.graphmd.install.$$"
    cp "$graphmd_extracted_binary" "$GRAPHMD_STAGED_BINARY" || fail "cannot stage graphmd in $graphmd_install_dir"
    chmod 755 "$GRAPHMD_STAGED_BINARY" || fail "cannot make graphmd executable"
    mv -f "$GRAPHMD_STAGED_BINARY" "$graphmd_install_dir/graphmd" || fail "cannot install graphmd into $graphmd_install_dir"
    GRAPHMD_STAGED_BINARY=""

    say "Installed GraphMD ($graphmd_asset) to $graphmd_install_dir/graphmd"
    case ":${PATH:-}:" in
        *:"$graphmd_install_dir":*) ;;
        *)
            say "Add $graphmd_install_dir to PATH to run graphmd from any directory."
            ;;
    esac
}

if [ "${GRAPHMD_INSTALLER_TESTING:-0}" != "1" ]; then
    main "$@"
fi
