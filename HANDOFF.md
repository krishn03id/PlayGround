# EyeSafe Reader handoff

- Project: `/home/namaa/playground`; GitHub: `krishn03id/PlayGround`, branch `main`.
- The application is the web reader in `index.html`. The native Android project was reverted at the user's request.
- Start locally with `python3 -m http.server 8080 --bind 127.0.0.1`; open `http://localhost:8080`.
- User preference: commit and push verified code changes after each completed task. Any future APK builds must use GitHub Actions.
- Keep the interface concise. The requested per-book scratchpad was explicitly excluded.

## Implemented

Continuous selection/highlight bands and draggable handles; single-row toolbar; 3D/slide/fade/instant page turns; offline book glossary; return pins; End-Line-limited recaps; PDF margin crop; eye scheduler and break coach; reading pace/heatmap; book/cover editing and favorites; procedural ambience; persistent rotating-vinyl music player.

Speech supports system voices, OpenAI-compatible endpoints, ElevenLabs, custom HTTP/base64 responses, and Fish Audio `s2.1-pro-free`. Generated audio is persistently cached in IndexedDB. AI-directed page/range/full-book conversion uses the saved chat/Define AI, checks source excerpts, adds bracket delivery cues, saves plans and audio, and supports resume/offline playback/export.

## Latest troubleshooting

The user reported an audiobook planning error. Planning now requests structured JSON, retries malformed plans, accepts typography-only excerpt differences, rejects invented text, and displays safe profile-specific API errors. AI enable/fallback toggles preserve unsaved editor fields. Real provider credentials remain in the user's browser; tests use local fixtures.

## Verification

24 automated browser checks passed: 10 in `tests/reader.browser.cjs` and 14 in `tests/phase2.browser.cjs`. Tests cover phone/tablet selection, page flips, media, offline lookup/caching, crop, scheduler, metadata, AI-directed conversion, pause/resume, error handling, JSON recovery, and source validation.

The current environment has Playwright at `/tmp/opencode/reader-browser/node_modules`, browser binaries at `/tmp/opencode/reader-browsers`, and extracted browser libraries at `/tmp/opencode/browser-libs/usr/lib/aarch64-linux-gnu`. Example:

```bash
NODE_PATH=/tmp/opencode/reader-browser/node_modules LD_LIBRARY_PATH=/tmp/opencode/browser-libs/usr/lib/aarch64-linux-gnu PLAYWRIGHT_HOST_PLATFORM_OVERRIDE=ubuntu24.04-arm64 PLAYWRIGHT_BROWSERS_PATH=/tmp/opencode/reader-browsers node tests/phase2.browser.cjs
```
