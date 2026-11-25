# Fresh-Server Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fresh Troupe Server is usable from the phone: install an agent, add a Project, chat (with or without a Project).

**Architecture:** A new `agents/` package holds the catalog model, the saved-config store and the launch sequence; `WorkspaceSessionManager` owns their lifetime per connection and exposes an onboarding gate. The shell renders onboarding, an empty state and banners from that state.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, OkHttp WebSocket, Hilt. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-25-fresh-server-onboarding-design.md`

## Global Constraints

- Protocol version stays 7; no server changes.
- `spawn_agent.config` carries exactly `configId,name,command,args,env,allowTerminal`.
- Saved config id is `acp-registry:<catalogId>`; `id == configId`.
- `onboardingCompletedAt` is an ISO-8601 string.
- Never write `acp/agents` or `settings/app` without reading it first; keep unknown fields.
- User-facing strings never contain "Termul"; wording follows Troupe's.
- Build and test on Colab (`AGENTS.md`).

## Review Focus

- Catalog entry with an unknown `status` or missing `distribution`: shown as not available, never a crash.
- `install_acp_agent` resent while running (`receipt: in_progress`): treated as still installing, not as success.
- Connection drops between `spawn_agent` and `create_session`: the launcher reports an error and Send works again.
- Another client finishes onboarding or adds a Project while the phone shows onboarding: the phone leaves onboarding.
- View-only server: no onboarding, no install buttons, composer disabled as before.

---

### Task 1: Catalog model

**Files:** Create `app/src/main/java/dev/troupe/android/agents/AgentCatalog.kt`; test `app/src/test/java/dev/troupe/android/agents/AgentCatalogTest.kt`; fixture `app/src/test/resources/fixtures/acp-catalog.json`.

**Produces:**
- `enum class AgentStatus { Ready, InstallRequired, NeedsRuntime, ManualInstall, Unavailable }`
- `data class CatalogAgent(id, name, description, status, installed: InstalledAgent?, npx: PackageDist?, uvx: PackageDist?, runtimeRequirements: List<String>)`
- `@Serializable data class AgentConfig(id, templateId, configId, name, command, args, env, allowTerminal)`
- `fun parseCatalog(payload: JsonElement?): List<CatalogAgent>`
- `fun CatalogAgent.deriveConfig(): AgentConfig?`
- `fun registryConfigId(catalogId: String): String`
- `fun featuredFirst(agents: List<CatalogAgent>): List<CatalogAgent>`
- `fun pickDefault(saved: List<AgentConfig>, lastSelected: String?): AgentConfig?`

- [ ] Tests: fixture parses to 4 agents; installed OpenCode derives `command=/home/u/.opencode/bin/opencode,args=[acp]`; ready npx agent derives `npx -y <pkg>`; `install-required` derives null; unknown status maps to `Unavailable`; `pickDefault` prefers last selected, then featured order.
- [ ] Implement; run `./gradlew testDebugUnitTest --tests "dev.troupe.android.agents.*"`.

### Task 2: Launch sequence

**Files:** Create `agents/ChatLauncher.kt`; modify `chat/net/ChatSocket.kt` (`request(..., timeout: Duration = 15.seconds)`); extend `testing/FakeChatServer.kt` with `spawn_agent`, `create_session`, `authenticate_agent`; test `agents/ChatLauncherTest.kt`.

**Produces:**
- `sealed interface LaunchResult { data class Started(sessionId, agentId); data class Failed(message) }`
- `suspend fun launchChat(socket: ChatSocket, config: AgentConfig, projectId: String?, cwd: String?, prompt: String): LaunchResult`

- [ ] Tests: project chat sends `spawn_agent` (6 config fields, `projectId`), `create_session` (`cwd`, `ephemeral=false`, `mcpServers=[]`), `send_prompt`; quick chat sends `kind=quick` without `cwd`/`projectId`; `agent_auth_required` with one method authenticates and retries once; spawn failure returns `Failed` with the server message.
- [ ] Implement; run the tests.

### Task 3: Agent setup state and onboarding gate

**Files:** Create `agents/AgentSetup.kt`; modify `workspace/WorkspaceSessionManager.kt`, `workspace/AppSettingsStore.kt` (`onboardingCompletedAt`); test `agents/OnboardingGateTest.kt`.

**Produces:**
- `data class AgentSetupState(loaded, catalog, saved, lastSelected, installing: Set<String>, error)` with `val launchable: List<AgentConfig>` and `val selected: AgentConfig?`
- `class AgentSetup(socket, store, scope)`: `state`, `refresh()`, `install(agent: CatalogAgent)`, `select(configId)`, `save(config)`
- `fun shouldShowOnboarding(projectsLoaded, projectCount, settingsLoaded, completedAt, viewOnly): Boolean`
- `WorkspaceShellState.agentSetup`, `.onboarding: Boolean`; `WorkspaceSessionManager.finishOnboarding()`, `.currentAgentSetup()`

- [ ] Tests for the gate: true only with zero projects, settings loaded, null timestamp, not view-only.
- [ ] Implement; wire into the session job; fix `openChat` for `__unassigned__`.

### Task 4: UI

**Files:** Create `onboarding/OnboardingScreen.kt`, `agents/AgentSetupList.kt`; modify `shell/MobileWorkspaceShellScreen.kt`, `shell/MobileShellHeader.kt`, `shell/ProjectAndServerSheet.kt` (expose `AddProjectContent`), `chat/ui/ChatStartView.kt`, `chat/composer/ComposerV2.kt`, `pairing/ConnectServerScreen.kt`, `setup/ServerSetupWizardScreen.kt`, `res/values/strings.xml`; add launcher icon resources and manifest entries.

- [ ] Onboarding steps, agent list, empty state, banner, quick launcher, hero style, placeholder, icon as in the spec.
- [ ] `./gradlew --continue testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug` reports `GRADLE_OK`.

### Task 5: Real test

- [ ] Install the APK on the emulator; on the user's server: onboarding appears, install OpenCode, add a Project, send a quick chat and a Project chat, open a terminal. Record what passed and what did not.
