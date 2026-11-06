# Third-party notices

FolioDock's original application code is MIT licensed. That license does not replace the licenses of the separately identified components below.

## LibreOffice layout engine

The Office reader embeds unmodified native libraries, fonts, dictionaries, and assets from LibreOffice Viewer 26.2.6.3, as built and signed by F-Droid. No application DEX classes, branding, or user-interface code from the upstream Viewer APK are used. The original LibreOfficeKit Java JNI bindings are included under `app/src/main/java/org/libreoffice/kit/` with their MPL-2.0 headers intact.

- **Project:** The Document Foundation and LibreOffice contributors, https://www.libreoffice.org/.
- **Engine version:** 26.2.6.3, upstream commit `8221e31b3ac356a1623c672912a3d2b492f7e3d1`.
- **Licensing:** LibreOffice source files are distributed under MPL-2.0 and applicable upstream alternative licenses. Included third-party libraries, fonts, and dictionaries retain their own license terms. Original upstream COPYING, MPL, LGPL, and component-license files are bundled in the app under `assets/licenses/`.
- **Source tree:** https://github.com/LibreOffice/core/tree/8221e31b3ac356a1623c672912a3d2b492f7e3d1.
- **F-Droid package and corresponding-source downloads:** https://f-droid.org/en/packages/org.documentfoundation.libreoffice/.
- **ARM64 binary:** https://f-droid.org/repo/org.documentfoundation.libreoffice_131.apk.
- **ARM64 corresponding source:** https://f-droid.org/repo/org.documentfoundation.libreoffice_131_src.tar.gz.
- **x86_64 binary:** https://f-droid.org/repo/org.documentfoundation.libreoffice_129.apk.
- **x86_64 corresponding source:** https://f-droid.org/repo/org.documentfoundation.libreoffice_129_src.tar.gz.
- **F-Droid build recipes:** https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.documentfoundation.libreoffice.yml.
- **Original component-license inventory:** https://github.com/LibreOffice/core/blob/8221e31b3ac356a1623c672912a3d2b492f7e3d1/readlicense_oo/license/license.xml.

The runtime extraction script verifies these SHA-256 hashes before using the downloaded APKs:

```text
751ac3836890b79a56801bf6d9a2788cda3761ab5131a4f0b6abfbad822bb475  org.documentfoundation.libreoffice_131.apk
cfaf9eea5fd695c449e92eb10cc0b1617ec51d068345068cea9b7e1c67968df5  org.documentfoundation.libreoffice_129.apk
```

FolioDock does not modify these native binaries or implement editing, save, macro-execution, or external-link navigation commands. It configures `DisableMacrosExecution=true` and macro security level 3 in its private LibreOffice profile before initialization. The app does not request Android's Internet permission.

LibreOffice, The Document Foundation, and F-Droid do not endorse or certify FolioDock. A compatible layout engine is not a promise of pixel-identical Microsoft Office output; missing fonts and unsupported features can change rendering.

## Kotlin and test tooling

Kotlin standard-library code is Apache-2.0 licensed: https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt. JUnit 4 is used only for development tests and is not bundled in the installed app: https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt.
