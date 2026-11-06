#!/usr/bin/env bash
set -euo pipefail

# Unmodified, checksum-pinned LibreOffice 26.2.6.3 runtime from F-Droid.
# See THIRD_PARTY_NOTICES.md for corresponding source and license information.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${FOLIODOCK_ENGINE_CACHE:-${HOME}/.cache/foliodock/libreoffice-26.2.6.3}"
ASSETS="$ROOT/app/src/main/office-assets"
LIBS="$ROOT/app/src/main/jniLibs"
VERSION="26.2.6.3-fdroid-131-129"
mkdir -p "$CACHE"

fetch() {
    local name="$1" code="$2" checksum="$3"
    local file="$CACHE/$name.apk"
    if ! printf '%s  %s\n' "$checksum" "$file" | sha256sum --check --status 2>/dev/null; then
        rm -f "$file"
        if ! curl --fail --location --retry 3 \
            "https://f-droid.org/repo/org.documentfoundation.libreoffice_${code}.apk" \
            --output "$file"; then
            curl --fail --location --retry 3 \
                "https://f-droid.org/archive/org.documentfoundation.libreoffice_${code}.apk" \
                --output "$file"
        fi
    fi
    printf '%s  %s\n' "$checksum" "$file" | sha256sum --check
}

fetch lo-arm64 131 751ac3836890b79a56801bf6d9a2788cda3761ab5131a4f0b6abfbad822bb475
fetch lo-x86_64 129 cfaf9eea5fd695c449e92eb10cc0b1617ec51d068345068cea9b7e1c67968df5

if [[ -f "$ASSETS/foliodock-engine-version.txt" ]] &&
   [[ "$(head -n 1 "$ASSETS/foliodock-engine-version.txt")" == "$VERSION" ]] &&
   [[ -s "$LIBS/arm64-v8a/liblo-native-code.so" ]] &&
   [[ -s "$LIBS/x86_64/liblo-native-code.so" ]]; then
    echo "Pinned LibreOffice runtime already prepared."
    exit 0
fi

TEMP="$(mktemp -d "$CACHE/extract.XXXXXX")"
trap 'rm -rf "$TEMP"' EXIT
unzip -q "$CACHE/lo-arm64.apk" 'assets/*' 'lib/*' -d "$TEMP"
unzip -q "$CACHE/lo-x86_64.apk" 'lib/*' -d "$TEMP"
mkdir -p "$(dirname "$ASSETS")"
rm -rf "$ASSETS" "$LIBS"
mv "$TEMP/assets" "$ASSETS"
mv "$TEMP/lib" "$LIBS"
printf '%s\n' "$VERSION" > "$ASSETS/foliodock-engine-version.txt"
echo "Prepared original LibreOffice assets and arm64-v8a / x86_64 native libraries."
