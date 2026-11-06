#!/usr/bin/env bash
set -euo pipefail
APK="${1:?Usage: verify-release-apk.sh app.apk}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Android SDK path is required}}"
TOOLS="$SDK/build-tools/35.0.0"
EXPECTED="46a766f30654e8cfe5e3a7ced41895de4e45f62d3d64de9e9ef0ddb4b193f45a"

certificate="$("$TOOLS/apksigner" verify --verbose --print-certs "$APK")"
printf '%s\n' "$certificate"
printf '%s\n' "$certificate" | grep -Fq "certificate SHA-256 digest: $EXPECTED" || {
    echo "Unexpected release signing certificate." >&2
    exit 1
}
badging="$("$TOOLS/aapt" dump badging "$APK")"
if grep -q application-debuggable <<< "$badging"; then
    echo "Release must not be debuggable." >&2
    exit 1
fi
permissions="$("$TOOLS/aapt" dump permissions "$APK")"
if grep -q 'uses-permission:' <<< "$permissions"; then
    echo "Unexpected Android permission. Review the manifest before releasing." >&2
    printf '%s\n' "$permissions" >&2
    exit 1
fi
python3 - "$APK" <<'PY'
import sys
from zipfile import ZipFile, ZIP_STORED
with ZipFile(sys.argv[1]) as apk:
    assets = [entry for entry in apk.infolist() if entry.filename.startswith("assets/")]
    assert assets, "Missing Office engine assets"
    compressed = [entry.filename for entry in assets if entry.compress_type != ZIP_STORED]
    assert not compressed, f"LibreOffice cannot mmap compressed assets: {compressed[:5]}"
    required = {
        "assets/program/fundamentalrc", "assets/share/registry/main.xcd",
        "assets/unpack/program/sofficerc", "assets/licenses/NOTICE.txt",
        "assets/licenses/LibreOffice-COPYING.MPL"
    }
    assert required <= set(apk.namelist()), "Missing bootstrap or licensing assets"
    assert any(name.endswith("/liblo-native-code.so") for name in apk.namelist()), "Missing native engine"
print("Release identity, non-debuggable flag, permissions, and stored Office assets verified.")
PY
