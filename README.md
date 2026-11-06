# FolioDock

A native, offline Android document dock. Choose a file through Android’s document picker, keep a private local copy, and read without an account, ads, cloud conversion, or an Internet permission.

FolioDock is a real read-only viewer, not a full-fidelity Microsoft Office replacement. Office previews prioritize readable text and spreadsheet values. The original file is never modified.

## Format support

| Format | In-app support | Important limitations |
| --- | --- | --- |
| PDF | Native Android `PdfRenderer`, scrollable pages, previous/next page | No text search, text selection, annotations, pinch zoom, password entry, or forms |
| DOCX | Body paragraphs and table-cell text | No page-layout fidelity, images, headers/footers, comments, tracked-change interpretation, or embedded objects |
| XLSX | Multiple named sheets; shared/inline strings; booleans; stored numeric and formula values; horizontal table scrolling | No formula calculation, number/date formatting, styles, charts, merged cells, or preservation of missing row numbers; dates may show as serial values |
| PPTX | Separate slide-text sections in presentation order | No slide graphics, original positioning, animation, or speaker notes |
| ODT | Paragraph text, including text inside table cells | Text-first; not original page layout |
| ODS | Named sheets and text/value tables; bounded repeated rows/cells | No formula calculation, original formatting, or chart rendering |
| ODP | Separate slide-text sections | No graphics or slide-layout fidelity |
| TXT, MD | Selectable UTF-8 text | Markdown is shown as source, not rendered |
| CSV, TSV | Quoted fields, embedded newlines, escaped quotes, paginated tables | UTF-8 only; CSV uses a comma delimiter |
| RTF | Basic text, common controls, Unicode escapes, Windows-1252 hexadecimal escapes | Not a complete RTF engine; complex code pages, layout, embedded objects, and pictures are unsupported |
| DOC, XLS, PPT and other files | Imported copy can be shared or handed to another installed app | No in-app legacy binary-format rendering; an appropriate external app must be installed |

Encrypted Office documents are not supported. Password-protected PDFs may fail to open. Office ZIP/XML previews accept UTF-8 or ASCII XML only, and deliberately reject DTDs and entity declarations.

## Reader and library

- Private library, recency ordering, filename search, and PDF/Office/Text filters.
- Document-picker imports, including supported content-URI “Open with” intents.
- Find in document: highlights matching text and cycles through matching text sections or spreadsheet rows. It is not PDF search.
- Adjustable text size for paragraph and slide-text views.
- Spreadsheet paging in groups of 40 rows, with horizontal scrolling and row/column labels.
- Section, sheet, and slide navigation; tap the section title to jump directly.
- Share the unmodified imported file, open it in another installed app, or remove only FolioDock’s local copy.
- Android 8.0+ (API 26). Application ID: `app.foliodock`.

## Privacy and safety

There are no analytics, third-party runtime SDKs, ad libraries, account flows, or network permission. Imports are copied into the app’s private internal storage; source-provider access is read-only. Android backup is disabled. Uninstalling FolioDock removes its private copies, not the source files.

Sharing and external opening are explicit user actions that temporarily grant another selected app read access to the original imported file. The sharing provider is not exported, permits only read access, and verifies that requested paths remain inside the import directory.

Import size is capped at 80 MiB. Plain-text imports are capped at 2,000,000 bytes. ZIP archives are limited to 5,000 entries and 250 MiB declared expanded size. Parsed XML is limited to 12 MiB per component and 60 MiB total read; entries are bounded during decompression even if ZIP metadata is inaccurate. XML DTD/entity declarations are rejected before parsing, and an entity resolver refuses external resources. ZIP files are read in place, never extracted into the filesystem. Macros, scripts, formulas, and embedded objects are not executed.

Workbook previews are limited to 100 sheets, 2,000 existing rows and 100 columns per sheet, with an aggregate cap of 200,000 stored cells / 2,000,000 text characters. Presentations are limited to the first 500 slides; PDF previews to 2,000 pages. XML parsing is bounded but not a complete malware sandbox: as with any viewer, use a supported Android version and avoid untrusted sensitive files.

## Build

Requirements: JDK 17, Android SDK Platform 35, Android SDK Build Tools 35.0.0, and a working network connection for the initial Gradle/plugin download. Runtime use is offline.

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The project pins Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and Gradle 8.9. The Gradle wrapper is included. No external runtime library dependencies are required. A debug build is signed using the local Android debug key and is suitable for testing, not Play Store release.

GitHub Actions runs unit tests, Android lint, and builds a debug APK on pushes, pull requests, and manual dispatch. The APK is uploaded as the `FolioDock-debug-apk` workflow artifact. In a private repository, access to the workflow and artifacts remains subject to repository permissions.

## Verification

The JVM suite exercises DOCX content, XLSX values and sparse columns, PPTX relationship order, ODT/ODS/ODP parsing, CSV/TSV quoting and limits, RTF text extraction, Unicode, XML safety controls, missing components, and explicit legacy-format rejection.

These tests do not replace on-device QA. Before distributing beyond testing, check on a real Android 8+ phone and a recent edge-to-edge Android phone:

- Pick and reopen a real file in every supported format, including non-ASCII names.
- Open a document from another app’s “Open with” flow.
- Rotate, background, reopen, and navigate rapidly between documents.
- Scroll a multi-page PDF, return to library, and open a different PDF.
- Search text and switch sections; paginate and scroll a wide spreadsheet.
- Share, open externally, cancel a picker, and remove a private copy.
- Confirm expected failure messaging for malformed, encrypted, unsupported, and over-limit files.
- Check large text, accessibility focus, TalkBack labels, and screen insets.

No emulator or physical-device result is implied by a successful Gradle build. There is no editing, cloud sync, OCR, full Office rendering, or “every possible file format” claim.

## License

MIT. See [LICENSE](LICENSE).
