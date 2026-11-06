# Android verification notes

Validation performed on November 6, 2026. This records checks actually run, not a claim that every supported format or Android device has been tested.

## Version 3.0 build verification

- **Tests:** All 50 JVM tests pass, including seven storage traversal/index tests and three format-filter tests.
- **Build and package:** Debug/release lint and signed ARM64/x86_64 assemblies pass. The package remains `app.foliodock`; version name is `3.0`, with internal version code `300006` for build 6.
- **Signing continuity:** Both APKs verify against the same certificate used for the earlier release. No key regeneration or package-name change was introduced.
- **Permissions:** The release checker now permits only `MANAGE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE` capped at API 29. No Internet or install-packages permission is declared.
- **Runtime qualification:** The software-emulated Android test environment suffered system-server watchdog restarts before the new app could be installed. These operating-system failures are not counted as successful 3.0 runtime checks. The earlier native-engine checks below are historical; new scanning, upgrade-data retention, launcher sizing, and multi-slide interactions require a separate runtime pass.

## Earlier 2.0 baseline

The following checks describe the previous preview, before the new storage permissions and 3.0 UI changes.

### Build and package checks

- **Unit tests:** 40 passing JVM tests across parser, geometry, format dispatch, and font configuration.
- **Builds:** `testDebugUnitTest lintDebug lintRelease assembleRelease` passed with Android SDK 35 and JDK 17. Lint warnings remain; there are no lint errors.
- **Release identity:** Both ARM64 and x86_64 APK signatures verified against the persistent release certificate documented in the README. APK Signature Scheme v2 verification passed.
- **Manifest:** Release APKs are not debuggable and declare no Android permissions.
- **Packaging:** All bundled Office assets are uncompressed entries required by LibreOffice's APK virtual filesystem. Native libraries are compressed for download and extracted on installation. Required bootstrap, engine, and licensing files are present.

### Runtime smoke checks

Environment: Android 11 / API 30 x86_64 emulator using software emulation. Installation used the signed, non-debuggable release APK, not a debug build.

- **Native startup:** LibreOfficeKit initialization completed. The final build's writable font cache was populated inside `app.foliodock` storage. Its private font configuration references both `/system/fonts` and the unpacked bundled fonts, not the upstream application's private directory.
- **Word:** A 52-row table rendered as actual table geometry with borders and numbered cells. After the bundled-font fix, it fits one page, matching the desktop LibreOffice baseline's page count. A separate cropped-image document displayed its embedded picture.
- **PowerPoint:** A slide containing warped text geometry and an embedded image rendered as a slide, not extracted text.
- **Excel:** Theme-based and directly styled cells rendered with their formatting. Initial sheet scale remained readable at the upper-left grid rather than shrinking the entire worksheet into the screen.
- **AMOLED:** Library, toolbar, dialogs, status/navigation areas, and surrounding reader canvas are black. Only one 56 dp reader toolbar remains. Night page colors invert displayed document pixels without changing the source document, and the preference survives restarting the app.

The public fixture files came from the same LibreOffice core revision as the bundled engine:

- Word table: https://github.com/LibreOffice/core/blob/8221e31b3ac356a1623c672912a3d2b492f7e3d1/sw/qa/extras/ooxmlexport/data/1-table-1-page.docx
- Word image: https://github.com/LibreOffice/core/blob/8221e31b3ac356a1623c672912a3d2b492f7e3d1/sw/qa/extras/ooxmlexport/data/ImageCrop.docx
- Spreadsheet: https://github.com/LibreOffice/core/blob/8221e31b3ac356a1623c672912a3d2b492f7e3d1/sc/qa/unit/data/xlsx/CalcThemeTest.xlsx
- Slide: https://github.com/LibreOffice/core/blob/8221e31b3ac356a1623c672912a3d2b492f7e3d1/sd/qa/unit/data/pptx/ShapePlusImage.pptx

## Limits of this verification

This is not a pixel-perfect comparison with Microsoft Office. Fonts, pagination, charts, and advanced objects may differ. Legacy formats, encrypted files, every supported extension, physical ARM64 phones, very large documents, and every interaction have not received a complete manual compatibility pass.

The ARM64 APK was built and package-verified, but was not executed on an ARM64 phone in this environment. Signing does not establish a Play Protect reputation or prove that every possible input document is safe.
