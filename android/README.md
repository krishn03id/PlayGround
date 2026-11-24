# EyeSafe Reader — native Android

[![Build Android APK](https://github.com/krishn03id/playground/actions/workflows/android-apk.yml/badge.svg)](https://github.com/krishn03id/playground/actions/workflows/android-apk.yml)

A native Java Android application inspired by the EyeSafe Reader interface and reading tools in [`../index.html`](../index.html). Android views, the platform PDF renderer, local SQLite storage, native file access, and Android text-to-speech power the application. The HTML is a design reference; it is not loaded or bundled as the application UI.

## Get the APK

**APK builds run through GitHub Actions.**

1. Open [Build Android APK](https://github.com/krishn03id/playground/actions/workflows/android-apk.yml).
2. Select a successful run, or use **Run workflow** on `main` to start a build.
3. Download the **EyeSafe-Reader-APK** artifact.
4. Extract the artifact and install **EyeSafe-Reader.apk** on your Android device.

The artifact also includes a SHA-256 checksum. Source changes under `android/` automatically start another build. Unit-test, lint, emulator-test, and screenshot reports are uploaded separately.

## Android support

| Setting | Value |
| --- | --- |
| Minimum Android | Android 6.0 / API 23 |
| Primary support | Android 10 and newer |
| Compile / target SDK | Android 15 / API 35 |
| Device tests | Android 10 phone and Android 15 tablet emulators |
| Package | `io.github.krishn03id.eyesafe` |
| APK architectures | Universal; platform PDF rendering supplies device-native code |

Layouts adapt to resizing, rotation, split-screen, and system insets. Devices with at least **600 dp** of available width get a persistent reading sidebar; smaller windows use the same tools through the native index panel. Reading font sizes use `sp` and controls use density-independent sizing.

## Reading and library

- Native, recycled library cards with covers, title search, and Study / Novels / Finished categories.
- Import multiple files, import a selected folder, or open a supported file shared from another Android application.
- PDF rendering with `android.graphics.pdf.PdfRenderer`; pinch zoom, double-tap zoom, pan, and page navigation.
- PDF text extraction and embedded chapter outlines using PDFBox Android.
- Native reflow text for PDF text, EPUB, UTF-8 TXT, and basic Markdown.
- EPUB chapters are read in spine order with native ZIP/XML parsing; imported EPUB images and complex publisher layouts are not rendered.
- Four reading themes, warmth, extra dim, adjustable typography and width, optional justified text, Focus Bold, and a draggable focus ruler.
- Tap a passage to save an End Line. Long-press text for native selection, copy, highlights, margin notes, flashcards, definitions, and AI questions.
- Page bookmarks, chapter navigation, keyword search, and Markdown notes export.
- Native text-to-speech with voice selection, playback speed, pause/resume, and a sleep timer. Offline speech depends on downloaded voices; playback stops when the app leaves the foreground.
- Auto-scroll in text mode, foreground reading-time tracking, daily goals, streaks, and configurable 20-second eye breaks.
- Manual flashcards and optional AI-generated quizzes, with Again / Good / Easy review scheduling.
- Full-library ZIP backup and restore, including original book files, parsed text, notes, bookmarks, cards, and display preferences.

Scanned PDF images can be displayed but require OCR before text selection, search, text-to-speech, or AI page context is available. PDF selection and annotations use **Text mode**; original page view prioritizes fast native rendering.

## File access and privacy

File permissions are granted through Android's **Storage Access Framework**:

- **Add books** uses `ACTION_OPEN_DOCUMENT` with persistable read access.
- **Folder** uses `ACTION_OPEN_DOCUMENT_TREE` for the folder you choose.
- Backup and notes export use `ACTION_CREATE_DOCUMENT` so you choose the output location.
- Imported files are copied into app-private storage; removing the original source does not remove the imported copy.

This works with scoped storage on Android 10+ without all-files access or legacy external-storage permissions. A protected `FileProvider` grants access only to explicitly shared content URIs. The only manifest permissions are internet and network-state access for the optional AI companion.

All ordinary reading features work offline. There are no runtime CDN, web-font, or JavaScript downloads. Imported data stays in app-private files and SQLite. AI keys are encrypted using Android Keystore AES-GCM, and AI requests use HTTPS. AI keys and profiles are excluded from exported backups; configure them again on a new device. Uninstalling the app removes its private library, so export a backup when moving devices.

Practical import limits are 512 MiB per original file and 24 MiB for text input. Folder imports scan up to 200 supported files and three nested directory levels. Backups restore up to 1 GiB of expanded data. DRM-protected EPUBs and password-protected PDFs are not supported.

## Tablet and performance design

- Recycled `RecyclerView` library grids use the available width, rather than stretching a phone layout.
- Native two-pane reader: chapter navigation, resume marker, and recent notes alongside the reading page.
- Reading columns are centered and capped in width for comfortable line lengths.
- File imports, parsing, text extraction, cover decoding, and PDF rendering run on a serial I/O executor. AI calls use a separate executor.
- Only the active PDF page is rendered; raster size is capped at six million pixels and released when replaced.
- Cover previews are downsampled and held in an 8 MiB LRU cache; PDF text uses a bounded page cache and temp-file-backed parsing.
- Stats writes are batched instead of written every animation frame or reading tick.
- The release build enables R8 code optimization and resource shrinking.

## GitHub build and signing

Workflow: [`../.github/workflows/android-apk.yml`](../.github/workflows/android-apk.yml).

GitHub Actions installs Java 17 and SDK 35, validates the pinned Gradle wrapper, compiles the app, runs unit tests and Android lint, builds the signed optimized APK, then tests native file import, PDF/EPUB reading, persistence, and rotation on phone/tablet emulators.

Release signing uses repository Actions secrets:

- `EYESAFE_KEY_PASSWORD`: password for the protected PKCS12 signing store.
- `EYESAFE_SIGNING_STORE_BASE64`: base64-encoded signing store, encrypted at rest by GitHub Secrets.

The initial Actions build can generate the signing store when the password secret is present. Its password-protected bootstrap artifact is promoted into `EYESAFE_SIGNING_STORE_BASE64` and removed after setup. Subsequent builds reuse that signing identity so APK updates preserve the installed application and library. No signing key or password is committed to the repository. A run without signing secrets produces a debug APK for source verification.

The Android project can be opened in Android Studio for source editing; use the GitHub workflow to build APKs.

## Source map

```text
android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/io/github/krishn03id/eyesafe/
│   │   ├── MainActivity.java       # Native/adaptive screens and reading interactions
│   │   ├── LibraryAdapter.java     # Recycled book cards
│   │   ├── PdfPageView.java        # Native PDF viewport and gestures
│   │   ├── DocumentSession.java    # Bounded PDF render/text session
│   │   ├── BookImporter.java       # Content URI imports and local copies
│   │   ├── EpubReader.java         # Native EPUB spine/parser
│   │   ├── TextPaginator.java      # UTF-8/Markdown pagination
│   │   ├── LibraryStore.java       # SQLite library, annotations and cards
│   │   ├── BackupManager.java      # Full-library ZIP backups
│   │   ├── AiClient.java           # HTTPS AI providers and failover
│   │   └── SecretVault.java        # Android Keystore encryption
│   └── res/                       # Native theme, icon and tablet dimensions
├── app/src/test/                  # Pagination, Unicode, archive and review tests
├── app/src/androidTest/           # Real native reader/emulator tests
└── gradle/wrapper/                # Pinned Gradle 8.11.1 wrapper
```
