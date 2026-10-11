# UH Companion

Android shell for the Universal Harvester userscript.

Loads the userscript at document-start inside a WebView, supplies
`GM_*` polyfills, and adds captures the browser sandbox cannot reach:
wire-level headers, WebAssembly module dumps, `crypto.subtle` call
traces, `atob` / `TextDecoder` I/O.

Output is a single JSON bundle per capture. Same shape as the
userscript's own bundle, plus three extra top-level keys.

## Setup

1. Push to `main`. GitHub Actions builds `app-debug.apk`.
2. Download the APK from the Actions run artifacts.
3. Install on device. Allow unknown sources.
4. Open app → Settings → paste your userscript raw URL (gist raw).
5. Tap **Check for update now** → script downloads.
6. Set **Auto update on launch** if desired.

## Usage

- Browse. The userscript runs passively.
- Tap the FAB → **Capture Now** to write a bundle.
- Bundles land in `Downloads/uh_YYYYMMDD_HHMMSS.json`.
- **Clear All State** wipes cookies, storage, cached script,
  and all GM key-value pairs.

## Requirements

- Android 8.0+
- WebView updated via Play Store
- Userscript hosted at a raw HTTP URL (gist works)
