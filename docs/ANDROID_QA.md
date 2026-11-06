# Android verification notes

Validation performed on November 6, 2026. This records checks actually run, not a claim that every supported format or Android device has been tested.

## Version 3.0 build verification

- **Tests:** All 50 JVM tests pass, including seven storage traversal/index tests and three format-filter tests.
- **Build and package:** Debug/release lint and signed ARM64/x86_64 assemblies pass. The package remains `app.foliodock`; version name is `3.0`, with internal version code `300006` for build 6.
- **Signing continuity:** Both APKs verify against the same certificate used for the earlier release. No key regeneration or package-name change was introduced.
- **Permissions:** The release checker now permits only `MANAGE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE` capped at API 29. No Internet or install-packages permission is declared.
- **CI release:** [Build 6](https://github.com/virgil48YT/foliodock/actions/runs/42190339900) completed successfully and published [v3.0](https://github.com/virgil48YT/foliodock/releases/tag/v3.0), targeting source commit `756c5a5baa594fd50a07cf30d919d557ee3a2229`. Its downloaded ARM64 APK matched the published SHA-256 checksum and passed the release identity, permissions, and packaging verifier.

### Version 3.0 runtime checks

Environment: Android 11 / API 30 x86_64 software emulator. Runtime checks used a locally assembled, signed release APK from the same app source, not a debug APK. CI and local APK hashes differ; the separately downloaded CI ARM64 package received static verification, not an ARM64 runtime pass.

- **In-place update:** `adb install --no-streaming -r` succeeded over the earlier release-signed preview without uninstalling. Android reported `versionCode=300006` and `versionName=3.0`. A marker in the existing app-private files directory remained unchanged, as did previously imported document copies.
- **Permission flow:** The first-launch explanation appeared with Allow storage and Not now. Allow storage opened Android's per-app All files access screen, which displayed FolioDock version 3.0. Enabling the switch and returning to the app initiated scanning.
- **Device discovery:** After correcting an accidentally private permission mode on the emulator's seeded Download fixture directory, scanning found seven supported documents across shared-storage folders. The UI displayed 23 files checked. The persisted `device-documents.index` remained available after force-stopping and relaunching the app.
- **Format filtering:** Selecting PPTX limited the list to presentations, including a newly discovered `Download/Navigation-3-slides.pptx`. The row identified the original's folder rather than presenting it as an imported copy.
- **Native multi-slide rendering:** Opening that scanned presentation initialized LibreOfficeKit successfully. Slide 1 displayed “SLIDE 1 OF 3”; Next displayed “SLIDE 2 OF 3”; the slide chooser listed all three slides and opened “SLIDE 3 OF 3.” Previous returned from slide 3 to slide 2. At the final slide, Next was visibly disabled. These were distinct rendered slide contents, not just counter changes.
- **AMOLED presentation:** The observed library, reader chrome, slide controls, and dialogs were black with white/gray controls and no purple accent. The presentation screenshots use the existing Night page colors preference; original file colors remain available.
- **Icon:** Android's application-access settings displayed the new larger monochrome adaptive document mark, replacing the earlier small purple nested-tile artwork.

Screenshots in `docs/screenshots/amoled-library.png` and `docs/screenshots/pptx-slide-3.png` show this 3.0 runtime pass. The software emulator initially suffered Android system-server watchdog restarts before installation; those boot failures are not counted as app failures or as successful checks. Testing continued only after the system stabilized.

The three-slide navigation deck was a diagnostic derivative of the public LibreOffice slide fixture listed below, with distinct slide-number text added for this test. It is not a claim that the original one-slide fixture contained three slides.

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
