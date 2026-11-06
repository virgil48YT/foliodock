# Changelog

## 3.0 · 2026-11-06

### Added

- Optional first-launch storage-access explanation and Android's all-files-access settings flow on Android 11+, with read-storage permission on Android 8–10.
- Background scanning of accessible shared storage and mounted volumes for supported Office, PDF, and text formats, with persistent local indexing, cancellation, and a 50,000-document limit notice.
- Direct PDF, DOCX, PPTX, XLSX and other extension chips, plus Word, Excel, and PowerPoint family filters.
- Visible previous/next slide buttons and a tappable slide counter for multi-slide PowerPoint presentations.
- Adaptive launcher icon with a larger document symbol, edge-to-edge black background, and Android 13+ monochrome themed-icon support.
- Public source repository and this release changelog.

### Changed

- Redesigned homepage with neutral black/white styling, clearer document rows and storage locations, horizontal format filters, and paginated results.
- Removed purple app branding from the homepage, controls, theme accent, and launcher icon. Original document colors remain unchanged unless night page colors are enabled.
- Kept the existing release key and package ID; increased Android's internal version-code base to 300000 so 3.0 can update the previous 2.0.0 preview in place.
- CI publishes the first 3.0 build as `v3.0`, with raw signed ARM64/x86_64 APKs and SHA-256 checksums. Subsequent builds use distinct build tags.

### Fixed

- PowerPoint navigation was hidden in reader options. Multi-slide decks now expose navigation directly and use the engine's slide rendering mode.
- The launcher no longer shrinks a legacy square icon inside another background tile.
- Large libraries no longer create a view for every indexed file at once.

### Compatibility and privacy

- Android 8.0+, 64-bit ARM or x86_64.
- Supported-format scan only, not unrestricted access to other apps' private data. Protected folders, inaccessible folders, cloud-only files, and unmounted storage are not bypassed.
- Scans are read-only. Files are copied privately only when opened, with the existing 80 MB opening limit.
- Release-signed installations update without uninstalling. The original debug-signed prototype still requires a one-time signature migration.
- APK signing does not constitute a Play Protect endorsement.

## Earlier preview · 2.0.0

- Added offline LibreOffice full-layout rendering, compact AMOLED readers, optional night page colors, persistent release signing, and raw GitHub APK release assets.
- Android update ordering uses the increasing internal version code and stable signing identity.
