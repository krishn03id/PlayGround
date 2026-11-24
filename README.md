# EyeSafe Reader

EyeSafe Reader is a mobile-friendly, privacy-focused reading and study app contained in a single `index.html` file. It opens PDF, EPUB, TXT, and Markdown files in the browser, stores books and reading progress locally, and adds eye-care, annotation, study, and optional AI tools.

## Features

- Local library with searchable 3D shelf and card views
- PDF page rendering and text extraction with PDF.js
- Offline EPUB parsing using browser ZIP and decompression APIs
- Reflowable reading for EPUB, TXT, Markdown, and extracted PDF text
- Dark, OLED, sepia, and paper themes
- Blue-light warmth and extra-dim controls
- Font, spacing, alignment, page-width, and Focus Bold options
- Pinch zoom, swipe navigation, optional edge-tap navigation, and fullscreen reading
- Reading ruler and adjustable auto-scroll teleprompter
- Browser text-to-speech with voice, speed, and sleep-timer controls
- Configurable 20-20-20 eye-break timer with reading goals and streak tracking
- Bookmarks and an End Line marker for resuming at an exact passage
- Hold-and-drag text selection with four highlight colors and margin notes
- In-book chapter navigation and full-book keyword search
- Markdown export for highlights and notes
- Offline flashcards and quizzes with simple spaced-repetition scheduling
- Optional AI definitions, page questions, interactive quizzes, multiple providers, and automatic provider failover
- JSON backup and restore for preferences, study data, and book metadata
- Built-in sample book for exploring the app without uploading a file

## Run Locally

No build step or package installation is required.

For the most reliable browser APIs, serve the directory over HTTP instead of opening the file directly:

```bash
python3 -m http.server 8000
```

Then open <http://localhost:8000>.

You can also open `index.html` directly, but browser security policies may restrict features such as storage, clipboard access, wake lock, or fullscreen mode.

## Using the Reader

1. Select **Add File** or drag a PDF, EPUB, TXT, or Markdown file onto the upload area.
2. Open a book from the shelf. The latest book also appears in the **Open on Your Desk** panel.
3. Tap a text line to open line actions, or hold and drag over words to select a passage.
4. Use the action bar to mark an End Line, highlight, add a note, define, copy, or ask AI about the selection.
5. Use **Index** to access bookmarks, the table of contents, search results, highlights, and notes.
6. Open **Eye & Text** to customize the display, typography, reading ruler, gestures, and screen wake lock.

### Keyboard Shortcuts

| Key | Action |
| --- | --- |
| `ArrowRight` / `PageDown` | Next page |
| `ArrowLeft` / `PageUp` | Previous page |
| `Space` | Pause or resume auto-scroll |
| `F` | Toggle fullscreen |
| `Escape` | Clear the current selection |

## Data and Privacy

The application has no backend. Data remains in the current browser profile:

- Uploaded books and PDF data are stored in IndexedDB (`EyeSafeReaderDB`).
- Preferences, reading statistics, sample-book annotations, AI profiles, and study cards are stored in `localStorage`.
- AI API keys are stored locally in browser storage when entered.
- Backup export excludes AI keys by default.

Clearing site data or using a different browser/profile removes access to locally stored books and settings unless a backup has been exported. Uploaded book files are not included in JSON backups; backup metadata is matched to books already present in the browser.

## AI Configuration

AI features are optional and require an internet connection plus your own provider API key.

Built-in presets support:

- Google Gemini
- OpenRouter
- Groq
- OpenAI
- Other OpenAI-compatible endpoints

Open **Eye & Text → Manage AI** to add profiles, select a model, configure automatic failover, and edit the study-companion prompt. Requests include the selected passage or current page text and book metadata so the provider can answer in context.

Do not use an AI key you are unwilling to store in the browser. Exported backups omit keys by default, but disabling that option includes them in the JSON file.

## Offline Behavior

Reading data and supported files are stored locally after import. EPUB, TXT, Markdown, annotations, bookmarks, study cards, and cached PDF text can continue to work offline.

The page references these external resources:

- Tailwind CSS CDN
- PDF.js and its worker from cdnjs
- Google Fonts

The document contains fallback CSS and system-font fallbacks, but opening a new PDF requires PDF.js to have loaded. AI tools always require a network connection. The app creates PWA metadata dynamically, but it does not install a service worker, so complete first-load offline availability depends on browser caching.

## Browser Requirements

Use a recent version of Chrome, Edge, Firefox, or Safari. Some capabilities depend on browser support:

- IndexedDB for persistent book storage
- `DecompressionStream` for compressed EPUB files
- Web Speech API for read-aloud features
- Screen Wake Lock API for **Always Screen On**
- Clipboard and Fullscreen APIs for their respective controls

Unsupported APIs fail gracefully, but the related feature may be unavailable.

## Project Structure

```text
.
├── index.html   # Complete UI, styles, storage, parsing, and application logic
└── README.md    # Project documentation
```

The project intentionally has no framework, build system, package manifest, or server component.
