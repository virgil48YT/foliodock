# FolioDock 3.0

## Download

- **FolioDock-arm64-v8a.apk:** Most modern Android phones.
- **FolioDock-x86_64.apk:** Compatible Intel devices and Android emulators.
- **SHA256SUMS.txt:** Checksums for this CI build.

These are direct APK assets, not Actions ZIP archives. Android 8.0+ is required.

## What's new

- Optional storage-access prompt and device-wide discovery of supported documents in accessible shared-storage folders and mounted volumes.
- Background scan with Stop, persistent local index, and no uploading or modification of originals.
- Cleaner AMOLED homepage with black/white styling and no purple.
- Specific PDF, DOCX, PPTX, XLSX and other file-format chips, plus Word, Excel, and PowerPoint family filters.
- Visible previous/next arrows and slide selection for multi-slide PowerPoint decks.
- Larger adaptive launcher icon, without the old nested tile; themed-icon support on Android 13+.

## Update without uninstalling

Install this APK over your existing release-signed FolioDock. The package ID and signing key are unchanged, and the internal Android version code is higher than the previous preview. Preferences and private files remain in place.

The app's visible version and GitHub release are both labeled 3.0. Android also sees a higher internal version code than the previous preview.

Only the earliest debug-signed prototype cannot accept a release-key update in place. Back up any app-only copies if you need that one-time migration. Do not uninstall normal release-signed builds before updating.

## Verification

GitHub CI passed tests, Android lint, signed builds, and release-package checks. The downloaded CI ARM64 APK also matched its published checksum and the existing release certificate.

On an Android 11 x86_64 emulator, the 3.0 release build installed over the earlier signed preview without uninstalling and retained app-private data. Storage permission, discovery, index persistence, and PPTX filtering were exercised. A three-slide deck rendered three distinct slide contents; Next, Previous, and the slide chooser were checked. Physical ARM64 devices have not received a runtime pass.

See [the detailed QA record](https://github.com/virgil48YT/foliodock/blob/main/docs/ANDROID_QA.md) for scope and screenshots.

## Limits

Scanning cannot access other apps' protected folders, unmounted volumes, or cloud-only providers. The local index is capped at 50,000 supported documents with an explicit limit message, and opening remains limited to 80 MB per file. Documents are read-only; complex Office formatting can differ from Microsoft Office.

Release signing does not guarantee removal of Play Protect warnings. Do not bypass an explicit harmful-app warning.

See [the full changelog](https://github.com/virgil48YT/foliodock/blob/main/CHANGELOG.md) and [README](https://github.com/virgil48YT/foliodock#readme) for permissions, compatibility, source provenance, and licenses.
