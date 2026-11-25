# Full Troupe Mobile V2 UI, State, and `WebStore` Parity

The MVP ([ADR-0002](./0002-no-server-changes-for-mvp.md)) introduced a minimal hierarchical navigation (`Servers` -> `Projects` -> `Project` tabs -> `Chat`/`Terminal`) that used only `/health`, `/projects`, `/terminal/ws`, and a subset of `/ws`. Since then, Troupe's desktop and mobile web client have evolved to **Mobile Workspace Shell V2** (`MobileWorkspaceShell`, `MobileShellHeader`, `MobileShellDrawer`, `ComposerV2`, 20 OKLCH bundled themes, full `WebStore` state synchronization, and 7 `/ws` watch channels), while keeping `WS_PROTOCOL_VERSION = 7`.

## Decision

1. **100% Native Jetpack Compose UI matching Troupe Mobile V2 1:1** — Keep native Kotlin + Jetpack Compose for all chrome, sheets, Agent Chat, Git, Files/Editor, GitHub, Snapshots, Automations, and Settings, retaining `xterm.js` inside `WebView` behind `TerminalRenderer` ([ADR-0001](./0001-xterm-js-in-webview.md)).
2. **No server changes required** — Continue working directly against `troupe-server` (and desktop shared-live remote hosts) as shipped on protocol v7, now adopting the full `/ws`, `/terminal/ws`, `WebStore`, and REST surface (`/setup/*`, `/server/config`, `/fs/*`, `/git/*`, `/worktree/*`, `/github/*`, `/search/*`, `/skills*`, `/api/automations/*`).
3. **Paste-link-first connection** — Connect directly by pasting any link produced by `troupe-server` or Desktop Remote Access: `#token=...` links, ungated URLs (with automatic fallback to a `401 UNAUTHORIZED` token gate screen matching `WebTokenGateScreen`), multi-line console output containing a URL, `#/c/<sessionId>` deep links, and first-run `?setup=<token>` links (driving the `/setup/*` wizard). Once connected, the app lands straight into `MobileWorkspaceShell` for the active Paired Server, while multi-server switching and QR scanning stay one tap away in the switcher sheet.
4. **Official Troupe state partitioning** — Synchronize cross-device shared keys (`shared/lww/*` such as `settings/app`, `acp/agents`, `acp/mcp-servers`, and `shared/cas/*`) via `WebStore` over `/ws`, and isolate mobile workspace layout and drafts under `device/<deviceId>/*` (`active-project`, `workspace/<projectId>`, `shell-layout`, `chat-draft/*`) so phone navigation never overwrites desktop split-pane layouts.

## Consequences

- Themes, projects, chat history, live `chat_activity`, agents, terminals, and settings stay live-synced across desktop, browser, and Android.
- Plain `http://` remains restricted to loopback, RFC 1918 LAN, Tailscale/CGNAT (`100.64.0.0/10`), `.local`, and `.ts.net` hosts; public hosts require `https://`.
- Tokens remain optional only for ungated hosts and are still encrypted at rest via Keystore + Tink AEAD when present, excluded from backups, and never logged.
