# Render Terminals with xterm.js in a WebView

Terminals are rendered by xterm.js inside a WebView, not by a native Android terminal emulator. The Troupe Server's desktop client already uses xterm.js, so escape-sequence handling, colors and replay snapshots behave identically on the phone; a native emulator would either take months to write or (Termux's `terminal-emulator`) force the app under GPLv3. The renderer sits behind a `TerminalRenderer` interface so it can be swapped later.

## Considered Options

- Termux `terminal-emulator`: native and fast, but GPLv3 conflicts with the MIT license.
- Custom VT parser in Compose: full control, far too much work for the MVP.
