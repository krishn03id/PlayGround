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
- Saved AI/custom speech profiles with sentence prefetch, page-audio caching, and offline system-voice fallback
- Configurable 20-20-20 eye-break timer with reading goals and streak tracking
- Bookmarks and an End Line marker for resuming at an exact passage
- Hold-and-drag text selection with four highlight colors and margin notes
- Continuous line highlights with adjustable selection handles and a single-row action toolbar
- 3D spine-rotation page turns, finger-follow swipes, and Slide / Fade / Instant alternatives
- In-book chapter navigation and full-book keyword search
- Markdown export for highlights and notes
- Offline flashcards and quizzes with simple spaced-repetition scheduling
- Optional AI definitions, page questions, interactive quizzes, multiple providers, and automatic provider failover
- JSON backup and restore for preferences, study data, and book metadata
- Built-in sample book for exploring the app without uploading a file
- Per-book personal glossary with saved offline definitions and optional dotted underlines
- Exact return-to-page pins for appendix jumps, and AI recaps limited to a saved End Line
- Smart PDF margin cropping, clock-based eye settings, and optional eye-break cues
- Offline procedural rain, brown noise, fireplace, and library soundscapes
- Local music library with a rotating vinyl player, playlist, shuffle/repeat, volume, artwork, and sleep timer
- Per-book reading pace and time-left estimates, a 30-day ritual heatmap, favorites, and book/cover editing
- Persistently cached speech and AI-directed page/range/full-book audiobooks

## Run Locally

No build step or package installation is required.

For the most reliable browser APIs, serve the directory over HTTP instead of opening the file directly:

```bash
python3 -m http.server 8080
```

Then open <http://localhost:8080>.

You can also open `index.html` directly, but browser security policies may restrict features such as storage, clipboard access, wake lock, or fullscreen mode.

## Using the Reader

1. Select **Add File** or drag a PDF, EPUB, TXT, or Markdown file onto the upload area.
2. Open a book from the shelf. The latest book also appears in the **Open on Your Desk** panel.
3. Tap a text line to open line actions, or hold and drag over words to select a passage.
4. Use the action bar to mark an End Line, highlight, add a note, define, copy, or ask AI about the selection.
5. Use **Index** to access bookmarks, the table of contents, search results, highlights, and notes.
6. Open **Eye & Text** for display and voice settings. The reader’s **⋯** menu holds crop, glossary, recap, soundscape, music, ritual, book editing, and audiobook tools.

The interface keeps explanations compact; tooltips and collapsed settings expose details when needed.

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
- Saved TTS profiles and optional speech-provider keys are stored in the same local preferences.
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

## Read-aloud engines

Open **Eye & Text → Read Aloud · System & AI Voice Settings**, or the player’s gear button, to choose a saved voice profile:

- **System Offline** uses installed browser/system voices.
- **OpenAI-compatible** supports `/v1/audio/speech` endpoints, including OpenAI, Groq, Kokoro-FastAPI, LocalAI, and local servers.
- **ElevenLabs** accepts a voice ID, model ID, and API key.
- **Fish Audio / S2** supports `s2.1-pro-free` through the required `model` header, reference voice IDs, and inline bracket directions. The **S2.1 Free** preset selects this model explicitly.
- **Custom HTTP** accepts an endpoint, optional authorization/custom header, and a JSON payload template using `{{text}}`, `{{voice}}`, `{{model}}`, and `{{speed}}`. Responses can be audio blobs or base64 audio JSON.

The **Kokoro Local** preset uses `http://localhost:8880/v1/audio/speech`. Local/custom servers must permit the reader’s origin through CORS. Provider API keys are optional for servers that do not require authentication.

Read-aloud highlights the spoken passage, prefetches the next two sentences, and automatically advances through full-page reading. Generated audio is saved in IndexedDB with a content-and-voice fingerprint, so replaying the same speech—including after a reload—reuses the saved blob. Pause/resume and playback-speed changes reuse audio; selection-only reading stops at the selected passage. Saved API audio can play offline, and missing audio can fall back to a system voice. The player retains voice/engine switching, a sleep timer, and voice testing.

## AI-directed audiobooks

Open **⋯ → Audio book** and choose **Current page**, **Page range**, or **Entire book**, then select a saved API voice profile.

The director is the same saved AI profile used for chat and Define. It receives the full page, adjacent context, and the personal glossary; it selects narrative or meaningful study passages, skips navigation/header/reference machinery, and chooses chunk sizes and delivery cues. Source excerpts are checked against the page, and scripts cannot add new spoken words. Narration plans and generated clips are saved before progress is advanced.

For S2, direction tags such as `[soft]`, `[whispering]`, `[chuckle]`, `[pause]`, `[emphasis]`, or `[calm measured tone]` are passed inline. A Fish reference voice ID controls the voice. Compatible OpenAI models receive separate narration instructions instead of speaking bracket labels. API voices are required for downloadable audio; browser system voices remain available for live offline listening.

Conversion can be paused and resumed. Pausing finishes and saves the current passage first, so completed work is reused. Saved page, range, and full-book audio is available from the library’s audiobook button and plays offline without further AI or TTS requests. WAV clips with matching formats export as one WAV file; other formats export as ordered audio files with a playlist in a ZIP archive.

Audio, music files, and plans stay in this browser’s IndexedDB. Clearing site data removes them. Study-data JSON backups contain reading/glossary metadata, not the original books or music/audio blobs; use the audiobook Download action for an audio export.

## Reading sanctuary tools

- **Glossary:** Save a definition from the Define popover. Matching saved terms can be dotted-underlined, and tapping them uses the local definition. The Index glossary tab supports lookup, editing, and deletion.
- **Return pin:** A jump of more than three pages records the prior viewport and reader settings. The return pill restores that reading spot.
- **Recap:** Only the preceding pages and text through the End Line are supplied to the saved AI; later text and unrelated chat history are excluded.
- **Crop margins:** Blank PDF borders are detected at low resolution, then the cropped page is rendered at the larger text scale with aligned selection overlays.
- **Soundscape / Music:** Procedural soundscapes need no audio downloads. Imported songs are saved locally, and the vinyl-style player includes playlist, artwork, transport, volume, shuffle/repeat, and a sleep timer. Background music and ambience are ducked under narration.
- **Auto sunset:** An opt-in clock schedule uses a daytime theme from 07:00–18:00, Dark until 22:00, then OLED and increased warmth/dimming. Manual display changes disable the schedule.
- **Ritual / Book editing:** The ritual card opens the last 30 days. Book editing supports renamed titles, covers, and favorites. Reading estimates learn from timed forward page turns and exclude large appendix jumps.

## Page-turn controls

Choose **Eye & Text → Page Turn Effect** for 3D Rotate, Slide, Fade, or Instant. The choice is saved locally, and reduced-motion preferences automatically use instant turns.

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
├── tests/reader.browser.cjs  # Browser interaction and local TTS integration checks
├── tests/phase2.browser.cjs  # Glossary, crop, media, insights, and audiobook checks
└── README.md    # Project documentation
```

The project intentionally has no framework, build system, package manifest, or server component.
