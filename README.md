# Universal Harvester Companion

A fast, lightweight Android browser with built-in userscript support and capture tools.

## Overview

Universal Harvester is a browser for Android built on the system WebView. It focuses on three things: fast browsing, full userscript compatibility, and the ability to inspect what pages and scripts are doing underneath.

## Features

**Browser**
- Multi-tab browsing
- Incognito tabs with isolated session, cleared when closed
- Tab groups
- Address bar at top or bottom
- Search engine picker — Brave, Google, Bing, Yahoo, DuckDuckGo, Yandex, plus custom engines
- Browsing history with inline suggestions
- Built-in downloads manager
- Light and dark theme that follows your device

**Userscripts**
- Install from URL, local file, or by pasting the script
- Full userscript API compatibility, including:
  - Value storage (get / set / delete / list)
  - Cross-origin HTTP requests
  - File downloads
  - Opening tabs
  - Menu commands (shown in a bottom sheet)
  - Notifications and clipboard access
  - Style and element injection
  - Cross-frame value change events
  - Cookie access
  - Access to the page's real window object
- Support for SystemJS-bundled userscripts
- Per-script enable / disable
- Built-in editor for modifying scripts
- Address bar icon shows the commands of the scripts running on the current page

**Capture Tools**
- Request headers
- WebAssembly module dumps
- Crypto calls
- Base64 and text decoding
- Session-based recording — all events saved to one JSON per session
- Automatic filtering of noise (empty payloads, duplicate entries, thumbnail maps)
- Raw mode available when you need every event

**Logs**
- Diagnostic log for crashes, navigation, and script errors
- Network log for API calls, POST data, and third-party domains
- Verbose logging toggle
- Save logs to Downloads

## Installation

1. Download the latest APK from Releases.
2. Install on your device. You may need to allow installation from unknown sources.
3. Open the app.

## Updating

Tap **Check for app update** in Settings → About. Updates install in place without losing your data.

## Usage

- **Browse** — type a URL or search term in the address bar.
- **Install a userscript** — tap the puzzle icon in the address bar, or open Scripts from the menu.
- **Run a script** — scripts run automatically on the pages they match. If a script registers menu commands, tap the puzzle icon to see them.
- **Capture data** — open Capture Tools from the menu, choose what to record, start a session, browse, then stop and save.
- **View logs** — Settings → About → Logs.

## Files

Session captures and logs are saved to:

Download/Harvester/sess_<timestamp>/
├── session.json    (all captured events)
├── log.txt         (diagnostic log)
└── net.txt         (network log)


## Requirements

- Android 8.0 or newer
- Updated Android System WebView (available from Play Store)

## Notes

- Play Protect may warn on first install. This is because the app is signed with a self-generated key. Tap "Install anyway" to proceed.
- Session files can grow large during heavy capture. Use the built-in filters or stop sessions regularly.

