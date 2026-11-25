# Fresh-server usability: onboarding, agent setup and quick chat

Phase 1 of bringing Troupe Android to 1:1 parity with Troupe (`gnoviawan/troupe` at `1ff16c8`).
Phase 2 is a visual parity pass of the shell; phase 3 covers Files, Git, Settings and Snapshots.

## Why

On a freshly installed Troupe Server the app connects and then nothing works. Reproduced on an
emulator against a real server:

- With no Project the composer is disabled and "New terminal" silently does nothing.
- `WorkspaceShellState.bannerMessage` is set in several places and rendered nowhere.
- A new chat sends `create_session` with `agentId = "claude"`. The server only accepts the id of a
  process started by `spawn_agent`, so a new chat fails on every real server, with or without a
  Project. The `agents` watch the app reads lists running processes, not launchable agents.
- The hero line uses `typography.displayLarge`, which the theme never defines, so it renders at
  Material's default 57sp instead of 22sp.

Troupe handles the same server with a first-run flow, a quick chat that needs no Project, and
per-agent setup states. This phase ports those.

## Goals

- A user who pastes a link to a fresh server can install an agent, add a Project and send a first
  message without touching a computer.
- New chats start the way Troupe's web client starts them.
- Every dead end says why and offers the next step.

## Non-goals (this phase)

- Agent sign-in when an agent offers several sign-in methods: the app says to sign in from
  Troupe on a computer. A single method is tried automatically.
- "Create a new project" (new folder, template, `git init`). Only "Open a folder" is offered.
- Warming an agent before Send, model/effort/mode pickers, MCP servers, worktree changes.
- Resuming a closed chat with a new agent process.

## Anti-goals

- No server changes (ADR-0002) and no protocol version change (ADR-0003).
- Never write a shared store key the app has not read first, and never drop fields it does not
  know when writing `settings/app` or `acp/agents`.

## Approaches

1. **Port Troupe's flow natively (chosen).** Catalog, install, saved configs, spawn, quick chat and
   onboarding as Compose screens over the existing `/ws` connection. Matches ADR-0004.
2. **Show the web onboarding in a WebView.** Least code, but the web client keeps its own token,
   device id and sockets, so state would split in two and the result would not be the native app
   ADR-0004 decided on.
3. **Only explain the dead end** ("add a project and install an agent on your computer"). Small,
   but chat would still fail because of the `agentId` bug, and the user asked for the phone to
   handle a fresh server.

## Wire contract used

All on `/ws` unless noted. Envelope fields are snake_case, payloads camelCase.

- `list_acp_catalog {}` -> `{host:{os,arch,runtimes}, agents:[{id,name,description,status,
  distribution,installed?:{command,args,version},runtimeRequirements}]}`. `status` is `ready`,
  `install-required`, `needs-runtime`, `manual-install` or `unavailable`.
- `install_acp_agent {agentId}` with `command_id` -> `{command,args}` when the install ends.
  Timeout 330 s.
- Store key `acp/agents` (shared LWW): a list of configs
  `{id,templateId,configId,name,command,args,env,allowTerminal}` where
  `id == configId == "acp-registry:<catalogId>"`.
- Store key `agents/last-selected` (device): `{agentId:<configId>, mode:"acp"}`.
- Store key `settings/app` (shared LWW): `onboardingCompletedAt` is an ISO-8601 string or null.
- `spawn_agent {config:{configId,name,command,args,env,allowTerminal}, projectId, sessionId:null}`
  with `command_id` -> `{agentId, authMethods, hostAuthReady}`. `config` rejects unknown fields.
  `projectId` is the Project's id, or `null` for a quick chat.
- `create_session` with `command_id`:
  - Project chat: `{agentId,cwd,mcpServers:[],ephemeral:false,projectId}`.
  - Quick chat: `{agentId,kind:"quick",mcpServers:[],ephemeral:false}`.
  - `err.code == "agent_auth_required"`: with exactly one auth method send
    `authenticate_agent {agentId,methodId}` and retry once.
- `send_prompt {agentId,sessionId,content,turnId}` with `command_id == turnId` (unchanged).

Config for a catalog agent: the `installed` block when present (`command`, `args`); otherwise for a
`ready` agent `npx` -> `npx -y <package> <args>`, `uvx` -> `uvx <package> <args>`; otherwise none.

## Design

**`agents/` (new package)**

- `AgentCatalog.kt`: `CatalogAgent`, `AgentConfig`, parsing, `deriveConfig`, the featured order
  (`codex-acp`, `claude-acp`, `gemini`, `cursor`, `opencode`, `pi-acp`) and `pickDefault`.
- `AgentSetup.kt`: a singleton over the active connection and store. Holds `AgentSetupState`
  (catalog, saved configs, last selected, installing ids, error) and offers `refresh()`,
  `install(agentId)`, `select(configId)`. Installing saves the resulting config to `acp/agents`.
- `ChatLauncher.kt`: `launch(connection, config, project, cwd, prompt)` runs save-if-needed ->
  `spawn_agent` -> `create_session` (with the one auth retry) -> `send_prompt`, and returns the
  session id or a message for the user.

**Onboarding (`onboarding/`)**

Shown instead of the shell when the server has no Projects, `settings/app` was read (found or
absent), `onboardingCompletedAt` is null and the server is not View-only. Three steps with
Troupe's wording:

1. "Welcome to Troupe", the three-line outline, Appearance Dark/Light, "Get started".
2. "Check your agents": featured agents first, "Show all N agents", one status word per row and
   its action (Install, or the reason it cannot run). "Set up later" always continues.
3. "Where should agents work?": "Open a folder" opens the existing server folder browser. "Skip,
   chat without a project" continues.

Finishing saves the picked agent as last selected, writes `onboardingCompletedAt`, and lands in
the new-chat launcher (in the Project, or as a quick chat).

**Shell**

- No Project and no open tab: "No Projects Yet" with "Create Your First Project" (opens the Add
  project sheet). "New chat" opens the launcher as a quick chat.
- Header: title "Troupe" with no tab, subtitle "No project" with no Project. "New chat" is
  enabled without a Project.
- Launcher: agent pill lists launchable agents and a "Set up agents" row that opens the same
  list as onboarding step 2. With no launchable agent, a notice above the composer says so and
  offers "Set up agents".
- `bannerMessage` is rendered as a dismissible banner. "New terminal" without a Project sets it.
- `openChat` never selects the reserved Project id `__unassigned__`.
- Hero line uses 22/28; placeholder is "Ask anything…".
- A launcher icon made from the Troupe mark replaces the default Android icon.

**Networking**

`ChatSocket.request` takes a timeout (default 15 s): 120 s for each step of a launch
(`spawn_agent`, `create_session`, `authenticate_agent`), 330 s for
`install_acp_agent`.

## Testing

- Unit: catalog parsing and `deriveConfig` against a recorded catalog; the onboarding gate;
  `ChatLauncher` against `FakeChatServer` for project chat, quick chat and the auth retry.
- Real: on the MuMu emulator against the user's server, install OpenCode, add a Project, start a
  quick chat and a Project chat, open a terminal.

## Left to the builder

Exact Compose layout of the onboarding steps, taken from Troupe's components
(`src/renderer/components/onboarding/`), and the banner's look.
