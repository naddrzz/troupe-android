# Troupe Android MVP — Spec

Decisions agreed on 2026-11-01. Vocabulary: [GLOSSARY.md](../../../GLOSSARY.md). Decisions with trade-offs: [docs/adr](../../adr).

## Product

Troupe is a native Android companion to a Troupe Server (today: termul-server, protocol v7), in the spirit of the Orca mobile app. It looks like termul's dark UI. "Termul" never appears in the UI.

## MVP scope

1. Pair with a server: scan the desktop QR (`https://…#token=…`), paste such a link, or enter URL + token + name manually. Pairing = `GET /health` then `authenticate` succeeds.
2. Servers list with live connection status; several Paired Servers, switchable.
3. Per server: Project list with Attention rows on top; per Project, tabs Chats | Terminals.
4. Terminal: attach to running Terminals (via `list_preserved` claims) and spawn new ones; type, resize, key bar (Paste, Esc, Tab, Ctrl+C, arrows, PgUp, PgDn). Kill always asks for confirmation.
5. Agent Chat: Session list, transcript (messages, compact tool calls), send prompt, answer permission/question, cancel. Model/mode/config/attachments deferred.
6. Attention notifications. No push service exists: notifications arrive only while connected. Optional per-server "Stay connected in background" runs a foreground service with an ongoing notification.
7. Optional biometric/device-credential app lock (default off).

Deferred: usage tracking, GitHub/Linear tasks, diff review, other themes, real pairing/push (needs server work).

## Rules

- No changes to the server (ADR-0002). Protocol models hand-written, `protocolVersion` must equal 7 (ADR-0003).
- View-only when `/health.allowRemoteWrites == false`: all write actions hidden/disabled, "View only" badge with a hint to run the server with `--allow-remote-writes`.
- `http://` only for loopback, private, CGNAT/Tailscale (100.64.0.0/10), `.local`, `.ts.net` hosts; public hosts need `https://`.
- Tokens: encrypted at rest (Keystore + Tink AEAD), excluded from backup, never logged.
- Reconnect automatically with backoff, resuming by `(logEpoch, lastSeq)`; close code 1001 = host offline, poll `/health` every 15 s instead. Connection status in the header.
- Theme: termul dark palette default, light variant follows system, termul ANSI palette in the terminal. Inter (UI) + Paper Mono (terminal) if licenses allow; Hugeicons free set if license allows, else Material Symbols.
- UI copy in English, all strings in `strings.xml`.

## Stack

Kotlin, Jetpack Compose + Material 3 (custom theme), single activity + Compose Navigation, MVVM + Coroutines/Flow, Hilt, OkHttp (HTTP + WebSocket), kotlinx.serialization, DataStore + Tink, xterm.js in a WebView behind `TerminalRenderer` (ADR-0001). minSdk 26. Single `:app` module. applicationId `dev.troupe.android`. MIT license. Distributed as signed APK on GitHub Releases.

## Testing

Unit tests (JUnit + Turbine) for protocol/serialization and ViewModels, with JSON fixtures copied from termul's `docs/api-contracts.md`; one integration test against a real termul-server in Docker (pair → list projects → spawn terminal → write/read); Compose UI test for the pairing flow only.
