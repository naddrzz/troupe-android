# Automations Foundation and Slice 1 (Monitor and Control) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the guessed Automations pane with a typed, live, 1:1 port of the web's monitor-and-control surface: list, detail, enable/disable, Run now, run history and steps, cancel, approve/reject, mark seen.

**Architecture:** New package `dev.troupe.android.automations` (`protocol/`, `net/`, `state/`, `ui/`). Kotlin wire models mirror `automation.types.ts`; `AutomationsApi` talks to `/api/automation*`; a `HostQuery` ports the web's `useHostQuery` (event, then re-read; latest reply wins; last good data kept); an `AutomationsController` holds UI state and actions; thin Compose screens render it. Live events come from the existing `WatchHub` `agents` watch.

**Tech Stack:** Kotlin 2.4, Jetpack Compose + Material3, Hilt, kotlinx.serialization 1.12, OkHttp, JUnit4 + `TestGraph`/`FakeTroupeServer`/`FakeChatServer`.

**Spec:** `docs/superpowers/specs/2026-11-26-automations-parity-design.md` (read it first; it names the web files that act as the contract).

## Global Constraints

- No server change and no protocol-version change (ADR-0002, ADR-0003). Only `/api/automation*` over HTTP and the `agents` watch on `/ws`.
- Build, test and lint through Colab when available, else local Gradle (AGENTS.md "Build, test & check policy"). Every "Run:" below is a Gradle command from that table. JDK 21.
- Work on branch `feat/automations-slice1` cut from `dev`; PR targets `dev`. Never `main`.
- User-facing text only in `app/src/main/res/values/strings.xml`; the word "Termul" never appears (GLOSSARY.md).
- Mutating calls are sent once and never auto-retried. Run steps poll every 2 000 ms only while the run is `running` and not parked, and never while a request is in flight. Runs are fetched with `limit=25`.
- After a successful mutation, wait for the server's event; if none reloads the query within 1 500 ms, re-read once.
- Run now is disabled when `approved_revision != revision`, with the text "Approve this revision first. Approving is done from Troupe on a computer until a later update."; server code `not_approved` maps to the same text. The enable switch stays available.
- 404 on the list route is a distinct "This server does not support Automations" state; menu entries stay visible.
- `state.viewOnly` hides every mutating control.
- New `@Serializable` classes must survive R8: review `app/proguard-rules.pro` (Task 12).
- Fixtures come from `naddrzz/troupe` (public; `gh api -H "Accept: application/vnd.github.raw" repos/naddrzz/troupe/contents/<path>`), never invented. Each fixture file starts with a `"_source"` note naming the file it came from.

## Review Focus

1. An `automation_changed` event for another project arrives while the list is shown: the list must not reload (Task 5).
2. A slow reply for an older request lands after a newer one: it must be dropped (Task 5).
3. A refresh fails after data was shown: last good data stays, with the error shown beside it (Task 5).
4. `decide` answers `false` (run already moved on): say so and re-read; no crash, no stuck spinner (Task 8).
5. A newer server sends a node kind or enum value this build does not know: the whole parse fails and the screen says "update the app", not a blank screen (Tasks 2, 3, 6).
6. An Automation with a manual trigger, `next_run_at = null`, `last_run = null` and zero nodes renders without crashing (Task 9).
7. The connection drops mid-mutation: the control shows the failure and becomes usable again; nothing is resent (Task 8).

---

### Task 1: Branch, fixtures and server probe

**Files:**
- Create: `app/src/test/resources/fixtures/automations/automation.json`, `automation-detail.json`, `runs.json`, `steps.json`, `agents-delta-changed.json`, `agents-delta-resync.json`, `automation-kitchen-sink.json` (an Automation whose graph holds all 10 node kinds)
- Create: `app/src/test/java/dev/troupe/android/automations/AutomationFixturesTest.kt`
- Create: `app/src/test/java/dev/troupe/android/automations/AutomationFixtures.kt` (the `load` helper below)
- Commit: `docs/superpowers/specs/2026-11-26-automations-parity-design.md` (still untracked) and this plan

**Interfaces:**
- Produces: `AutomationFixtures.load(name: String): String` in `AutomationFixturesTest.kt`'s sibling `app/src/test/java/dev/troupe/android/automations/AutomationFixtures.kt`, reading `/fixtures/automations/<name>` (same mechanism as `FakeTroupeServer.fixture`).

- [ ] **Step 1: Create the branch.** `git switch dev` then `git switch -c feat/automations-slice1`.
- [ ] **Step 2: Build the fixtures from the web repo.** Sources: `src/shared/types/__fixtures__/automation.json`, `automation-ops.json`, `src/renderer/lib/test-utils/automation-fixtures.ts` (convert TS objects to JSON), and the wire output asserted in `src-tauri/src/web/automation_api_tests.rs` and `src-tauri/src/automation/model_tests.rs`. `agents-delta-changed.json` is `{"_source":"<see below>","event":"automation_changed","payload":{"project_id":"p1","automation_id":"a1"}}`, its `_source` naming the event name in `src-tauri/src/automation/model.rs`, the payload type `AutomationChanged` in `src/shared/types/automation.types.ts` and `publish_agent_event` in `src-tauri/src/web/watch/sources.rs`; `agents-delta-resync.json` is `{"_source":"<see below>","event":"automation_resync","payload":{"missed":3}}`, its `_source` naming `src-tauri/src/automation/model.rs` and the lagged-forwarder test in `src-tauri/src/web/automation_api_tests.rs`. Wrap each response fixture exactly as the server does (`{"success":true,"data":...}`) where it is a route reply.
- [ ] **Step 3: Write the failing test** `AutomationFixturesTest.everyFixtureIsValidJsonWithASourceNote`: for each file, `Json.parseToJsonElement(load(name))` succeeds and the root object carries a non-blank `_source` key (route replies keep it beside `success` and `data`, so array payloads still have an object root). Run: `./gradlew testDebugUnitTest --tests "dev.troupe.android.automations.AutomationFixturesTest"`. Expected: FAIL (missing resources).
- [ ] **Step 4: Run the test to pass** after the files exist. Expected: PASS.
- [ ] **Step 5: Probe the test server.** `.\scripts\test-server.ps1 up`, then `Invoke-RestMethod "http://127.0.0.1:18080/api/automations?project_id=x" -Headers @{Authorization="Bearer $(.\scripts\test-server.ps1 token)"}`, then `.\scripts\test-server.ps1 down`. Record the outcome (200 with a list, 404, or "Docker unavailable") in the spec's "Open items" section, replacing the first bullet. If 404 or unavailable, `RealServerTest`-style integration coverage for Automations is skipped.
- [ ] **Step 6: Commit.** `git add docs app/src/test && git commit -m "docs+test: Automations design, plan and contract fixtures"`.

---

### Task 2: Wire models

**Files:**
- Create: `app/src/main/java/dev/troupe/android/automations/protocol/AutomationGraph.kt` (ids, `Trigger`, `Schedule`, `NodeSpec`, `Graph`, `Edge`)
- Create: `app/src/main/java/dev/troupe/android/automations/protocol/AutomationModels.kt` (everything else)
- Test: `app/src/test/java/dev/troupe/android/automations/protocol/AutomationModelsTest.kt`

**Interfaces:**
- Produces (all `@Serializable`, snake_case `@SerialName`, decoded with `TroupeJson`; Long for ms/ints beyond 32 bits):
  - `@JvmInline value class AutomationId(val value: String)`, `RunId`, `NodeId` (same shape).
  - `enum Isolation { SharedInRoot("shared_in_root"), WorktreePerRun("worktree_per_run") }`, `RunStatus { Running, Succeeded, Failed, Cancelled, Skipped }`, `StepStatus { Running, Succeeded, Failed, Skipped, Waiting }`, `RunTrigger { Scheduled, CatchUp("catch_up"), Manual }`, `Port { Out, Error, True, False, Item }`, `HttpMethod`, `ConditionOp { Eq, Ne, Contains, Gt, Lt, Exists }`, `AgentTool { Read, Search, Edit, Execute, Fetch, Other }`, `MergeMode { Any, All }`, `ApprovalDecision { Approved, Rejected, TimedOut("timed_out") }` (lowercase wire names).
  - `@JsonClassDiscriminator("kind") sealed interface Schedule { Cron(expr, timezone); Every(everyMs: Long, anchorMs: Long); At(at: Long) }`, `sealed interface Trigger { Manual; ScheduleTrigger(schedule: Schedule) }` (wire kinds `manual`, `schedule`).
  - `sealed interface NodeSpec` with kinds `http` (`method, url, headers: List<NamedTemplate>, body: String?, allowedHosts`), `agent` (`agentId, model: String?, prompt, outputSchema: JsonObject, timeoutMs: Long, maxAttempts: Int, tools: List<AgentTool>, mcpServers: List<String>?`), `transform` (`fields`), `condition` (`left, op, right: String?`), `fail` (`message`), `mcp_tool` (`serverId, tool, arguments: List<NamedTemplate>, timeoutMs`), `for_each` (`items, maxIterations: Int`), `merge` (`mode`), `wait` (`durationMs`), `approval` (`message, timeoutMs`). `NamedTemplate(name, value)`.
  - `AutomationNode(id: NodeId, label: String, spec: NodeSpec)`, `Edge(from: String /* "trigger" or a NodeId */, port: Port, to: NodeId)`, `Graph(nodes, edges)`, `NodePosition(x: Double, y: Double)`.
  - `LastRun(runId: RunId, status: RunStatus, finishedAt: Long)`; `Automation(id, projectId, name, revision: Long, approvedRevision: Long?, enabled, trigger, graph, layout: Map<String, NodePosition>, isolation, nextRunAt: Long?, consecutiveErrors: Int, scheduleErrorCount: Int, unseenFailures: Int, lastRun: LastRun?, createdAt: Long, updatedAt: Long, deletedAt: Long?)`.
  - `Parked(nodeId, iteration: Int?, until: Long)`; `AutomationRun(id: RunId, automationId, revision, graph, trigger: RunTrigger, scheduledFor, status, startedAt, finishedAt: Long?, stopReason: String?, summary: String?, conversationId: String?, worktreeId: String?, parked: Parked?)`; `AutomationStep(runId, nodeId, iteration: Int?, status, output: JsonElement?, error: String?, startedAt: Long?, finishedAt: Long?, waitUntil: Long?)`.
  - `Issue(nodeId: NodeId?, code: String, message: String)`; `RunPolicy(triggerSummary, nextRuns: List<Long>, agents: List<AgentPolicy>, secrets: List<NodeNames>, outboundHosts: List<NodeHosts>, mcpTools: List<McpToolPolicy>, untrustedFlow: Boolean, failurePolicy: String)` with `AgentPolicy(nodeId, agentId, model?, timeoutMs, tools, mcpServers: List<AgentMcpServer>)`, `AgentMcpServer(serverId, name)`, `McpToolPolicy(nodeId, serverId, serverName?, tool)`, `NodeNames(nodeId, names)`, `NodeHosts(nodeId, hosts)`; `AutomationDetail(automation, issues, policy)`; `AutomationChanged(projectId, automationId: String?, proposed: Boolean = false, proposedBy: String? = null)`.
  - `data class ApprovalStepOutput(val message: String, val decision: ApprovalDecision?, val decidedAt: Long?)` and `fun parseApprovalOutput(output: JsonElement?): ApprovalStepOutput?` (null on any mismatch).

- [ ] **Step 1: Write the failing tests** (decode with `TroupeJson.decodeFromString`):
  - `automationFixtureDecodes`: `Automation` from `automation.json` has `projectId`, `enabled`, non-null `trigger`.
  - `allTenNodeKindsDecode`: kitchen-sink graph yields exactly the 10 `NodeSpec` subclasses (`assertEquals(10, nodes.map { it.spec::class }.toSet().size)`).
  - `detailRunsAndStepsFixturesDecode`: `AutomationDetail`, `List<AutomationRun>`, `List<AutomationStep>` decode.
  - `unknownNodeKindFails`: replacing a node `"kind":"http"` with `"kind":"teleport"` throws `SerializationException`.
  - `unknownRunStatusFails`: `"status":"exploded"` throws.
  - `missingRequiredFieldFails`: removing `project_id` throws.
  - `unknownIssueCodeIsKept`: an `Issue` with `"code":"brand_new_code"` decodes with that string.
  - `unknownExtraKeysIgnored`: an extra top-level key does not fail.
  - `approvalOutputParses`: `{"message":"ok?"}` gives decision `null`; `{"message":"ok?","decision":"approved","decided_at":5}` gives `Approved`/5; `{"x":1}` and `JsonNull`/`null` give `null`.
  - `changedEventParses`: both delta fixtures' payloads; a payload with `automation_id: null` gives `automationId == null`.
- [ ] **Step 2: Run and see it fail.** Run: `./gradlew testDebugUnitTest --tests "dev.troupe.android.automations.protocol.AutomationModelsTest"`. Expected: FAIL to compile (types undefined).
- [ ] **Step 3: Implement the two files.** Use `@OptIn(ExperimentalSerializationApi::class)` with `@JsonClassDiscriminator("kind")` on the sealed types and `@SerialName("...")` per subclass. Missing nullable fields rely on `TroupeJson.explicitNulls = false`. Enums use `@SerialName` per entry.
- [ ] **Step 4: Run and see it pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(automations): wire models mirroring automation.types.ts"`.

---

### Task 3: `ServerError.status` and `AutomationsApi`

**Files:**
- Modify: `app/src/main/java/dev/troupe/android/core/net/ServerApi.kt` (`ApiResult.ServerError`, `failure`, the two catch blocks in `execute`)
- Create: `app/src/main/java/dev/troupe/android/automations/net/AutomationsApi.kt`
- Test: `app/src/test/java/dev/troupe/android/automations/net/AutomationsApiTest.kt`

**Interfaces:**
- Consumes: `ServerApi.getEnveloped/postEnveloped/postUnit` and models from Task 2.
- Produces:
  - `data class ServerError(val message: String, val code: String?, val status: Int? = null)` (existing positional callers keep compiling). `failure()` sets `status = response code`; the `SerializationException`/`IllegalArgumentException` catches set `status = response.code` (a 2xx status with a `ServerError` therefore means "reply did not match the contract").
  - `@Singleton class AutomationsApi @Inject constructor(private val api: ServerApi)` with, all `suspend`, all taking `(address: ServerAddress, token: String?, ...)` first: `list(projectId: String): ApiResult<List<Automation>>`, `get(id: AutomationId): ApiResult<AutomationDetail>`, `setEnabled(id, enabled: Boolean): ApiResult<Unit>`, `runNow(id): ApiResult<AutomationRun>`, `runs(id, limit: Int = 25): ApiResult<List<AutomationRun>>`, `steps(runId: RunId): ApiResult<List<AutomationStep>>`, `cancelRun(runId): ApiResult<Unit>`, `decideApproval(runId, nodeId: NodeId, approved: Boolean): ApiResult<Boolean>`, `markSeen(id): ApiResult<Unit>`. Paths and bodies exactly as the spec's table; path segments percent-encoded (spaces as `%20`).

- [ ] **Step 1: Write the failing tests** with `FakeTroupeServer().use { server -> server.onHttpRoute = ...; TestGraph(temp.newFolder()).use { graph -> ... } }` (pattern in `ServerApisContractTest`), serving the Task 1 fixtures:
  - `listUsesProjectIdQueryAndDecodes`: request path `/api/automations`, `queryParameter("project_id") == "p1"`.
  - `getRunsStepsDecode`: `/api/automations/a1`, `/api/automations/a1/runs?limit=25`, `/api/automation-runs/r1/steps`.
  - `setEnabledBody`: POST `/api/automations/a1/enabled`, body `{"enabled":false}`.
  - `runNowReturnsRun`: POST `/api/automations/a1/run`, body `{}`, returns the run fixture.
  - `decideSendsNodeIdAndApproved`: POST `/api/automation-runs/r1/approval`, body has `node_id` `"n1"` and `approved` `true`; reply `data:false` gives `Ok(false)`.
  - `cancelAndMarkSeen`: POST `/api/automation-runs/r1/cancel` and `/api/automations/a1/seen`, both `{}`.
  - `notApprovedKeepsCodeAndStatus`: 409-style envelope `{"success":false,"error":"approve the current revision before running it","code":"not_approved"}` gives `ServerError(code="not_approved", status=<served code>)`.
  - `notFoundKeepsStatus404`: non-envelope 404 gives `ServerError(status=404)`.
  - `contractMismatchHasSuccessStatus`: 200 envelope with an unknown run status gives `ServerError` with `status == 200`.
  - `requestsAreSentOnce`: a mutation answered with a dropped socket yields `Unreachable` and exactly one recorded request.
- [ ] **Step 2: Run and see it fail.** Run: `./gradlew testDebugUnitTest --tests "dev.troupe.android.automations.net.AutomationsApiTest"`. Expected: FAIL (types undefined).
- [ ] **Step 3: Implement.** Add the `status` parameter and thread it through `failure()` and the catches; build `AutomationsApi` on `getEnveloped(address, path, token, ListSerializer(Automation.serializer()))` etc.; `decideApproval` uses `Boolean.serializer()`; bodies built with `buildJsonObject`.
- [ ] **Step 4: Run to pass**, then the whole suite for regressions: `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(automations): AutomationsApi and ServerError.status"`.

---

### Task 4: Live events from the `agents` watch

**Files:**
- Modify: `app/src/test/java/dev/troupe/android/testing/FakeChatServer.kt` (`watchFrame`, `watch`, `unwatch`, `historyChanged`, per-connection `watchN`)
- Create: `app/src/main/java/dev/troupe/android/automations/state/AutomationEvents.kt`
- Test: `app/src/test/java/dev/troupe/android/automations/state/AutomationEventsTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface AutomationEvent { data class Changed(val change: AutomationChanged) : AutomationEvent; data object Resync : AutomationEvent }`.
  - `class AutomationEvents(private val hub: WatchHub)` with `fun stream(): Flow<AutomationEvent>`. Each collection watches `WatchHub.AGENTS` (scope `""`). Per update: `delta` of shape `{event, payload}` with `event == "automation_changed"` and a payload that parses as `AutomationChanged` emits `Changed`; `"automation_resync"` emits `Resync`; every `reset` update after the first one of that collection emits `Resync` (reconnect); anything else (other agent events, malformed payload, the first reset) is ignored.
  - `FakeChatServer.agentEvent(event: String, payload: JsonObject)`: sends a `{event, payload}` delta to every connection watching `agents`; the `agents` watch answers `watch` with an empty `JsonObject` reset state. Per-connection counters become a `MutableMap<String, Int>` keyed by watch name, so `historyChanged` keeps its current behaviour.

- [ ] **Step 1: Write the failing tests** (get the hub as `AttentionTest` does: `runBlocking { graph.chatConnections.connection(serverId) }.watchHub`; pair a server first as that test's setup does):
  - `changeEventIsEmitted`: after `chat.agentEvent("automation_changed", {"project_id":"p1","automation_id":"a1"})`, stream yields `Changed(AutomationChanged("p1","a1"))`.
  - `projectWideChangeHasNullAutomationId`.
  - `resyncEventIsEmitted`.
  - `otherAgentEventsAreIgnored`: `agentEvent("agent_started", ...)` then a change; only the change arrives.
  - `malformedPayloadIsDropped`: `agentEvent("automation_changed", {"x":1})` then a valid change; only the valid one arrives.
  - `reconnectEmitsResync`: `chat.closeAll(1011)`; after the socket re-handshakes, the stream yields `Resync` (and not before).
- [ ] **Step 2: Run and see it fail.** Run: `./gradlew testDebugUnitTest --tests "dev.troupe.android.automations.state.AutomationEventsTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the `FakeChatServer` extension and `AutomationEvents` (decode `delta` with `TroupeJson`, catching `SerializationException` per update).
- [ ] **Step 4: Run to pass**, then `./gradlew testDebugUnitTest --tests "dev.troupe.android.attention.*"` to prove the fake still serves `chat_history`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(automations): live automation events from the agents watch"`.

---

### Task 5: `HostQuery`

**Files:**
- Create: `app/src/main/java/dev/troupe/android/automations/state/HostQuery.kt`
- Test: `app/src/test/java/dev/troupe/android/automations/state/HostQueryTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface QueryError { Unauthorized; Unreachable; Unsupported; NotApproved; Invalid; data class Server(val message: String, val code: String?) }`, `sealed interface QueryResult<out T> { data class Ok<T>(val value: T); data class Failed(val error: QueryError) }` and `fun <T> ApiResult<T>.toQuery(unsupportedOn404: Boolean = false): QueryResult<T>` mapping: `Unauthorized`, `Unreachable`, `ServerError` with `code == "not_approved"` to `NotApproved`, `status == 404 && unsupportedOn404` to `Unsupported`, `status in 200..299` to `Invalid`, else `Server`.
  - `data class QueryState<T>(val data: T? = null, val error: QueryError? = null, val loaded: Boolean = false, val refreshing: Boolean = false)`.
  - `class Poll(val everyMs: Long, val active: () -> Boolean)`.
  - `class HostQuery<T>(scope: CoroutineScope, events: Flow<AutomationEvent>, load: suspend () -> QueryResult<T>, affects: (AutomationChanged) -> Boolean, poll: Poll? = null)` with `val state: StateFlow<QueryState<T>>`, `fun reload()`, `fun reloadUnlessEventWithin(windowMs: Long)`, `fun close()`. It loads once on construction; `reload()` on `Changed` when `affects` is true and on every `Resync`; latest request wins; a failed load keeps previous `data` and sets `error`; a successful load clears `error`; `refreshing` is true while a load runs after the first; the poll ticks every `everyMs` only when `poll.active()` is true and no load is in flight; `reloadUnlessEventWithin` reloads once after the window unless an event already reloaded the query in the meantime.

- [ ] **Step 1: Write the failing tests** with `runTest`, `StandardTestDispatcher` and a `MutableSharedFlow<AutomationEvent>` for events:
  - `loadsOnceOnStart`.
  - `matchingChangeReloads` / `otherProjectChangeDoesNotReload` (Review Focus 1): `affects = { it.projectId == "p1" }`; a `Changed` for `p2` leaves the load count at 1.
  - `resyncReloads`.
  - `slowOlderReplyIsDropped` (Review Focus 2): two overlapping loads, first completes last; state holds the second's data.
  - `failedRefreshKeepsLastGoodData` (Review Focus 3): second load fails; `data` is still the first value and `error` is set; a third success clears `error`.
  - `pollTicksOnlyWhileActive` and `pollSkipsWhileLoadInFlight`: advance virtual time; count loads.
  - `reloadUnlessEventWithinSkipsWhenEventArrives` and `...ReloadsWhenQuiet`.
  - `closeStopsEverything`: after `close()`, events and poll ticks cause no loads.
  - `toQueryMapping`: one assertion per `ApiResult` case incl. `status=404` with and without `unsupportedOn404`, `status=200` giving `Invalid`, code `not_approved` giving `NotApproved`.
- [ ] **Step 2: Run and see it fail.** Run: `./gradlew testDebugUnitTest --tests "dev.troupe.android.automations.state.HostQueryTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.** A request counter makes "latest wins"; a `pending` flag gates the poll (port of `useHostQuery` in `use-automations.ts`).
- [ ] **Step 4: Run to pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(automations): HostQuery (event-driven re-read, latest-wins)"`.

---

### Task 6: `AutomationsRepository`

**Files:**
- Create: `app/src/main/java/dev/troupe/android/automations/state/AutomationsRepository.kt`
- Test: `app/src/test/java/dev/troupe/android/automations/state/AutomationsRepositoryTest.kt`

**Interfaces:**
- Consumes: `AutomationsApi`, `AutomationEvents`, `HostQuery`, `QueryError`.
- Produces: `class AutomationsRepository(api: AutomationsApi, address: ServerAddress, token: suspend () -> String?, events: AutomationEvents, scope: CoroutineScope)` with
  - `fun list(projectId: String): HostQuery<List<Automation>>` (affects: same `projectId`; maps 404 to `Unsupported`)
  - `fun detail(id: AutomationId): HostQuery<AutomationDetail>`, `fun runs(id: AutomationId): HostQuery<List<AutomationRun>>` (both affect: same `automationId`, or a project-wide change when `automationId == null`)
  - `fun steps(automationId: AutomationId, runId: RunId, running: () -> Boolean): HostQuery<List<AutomationStep>>` (poll 2 000 ms while `running()`; the caller's `running` returns false for a parked run, `status == Running && parked == null`, so a parked run is never polled)
  - `sealed interface Mutation<out T> { data class Ok<T>(val value: T); data class Failed(val error: QueryError) }` and suspend `setEnabled(id, enabled)`, `runNow(id): Mutation<AutomationRun>`, `cancelRun(runId)`, `decideApproval(runId, nodeId, approved): Mutation<Boolean>`, `markSeen(id)`.
  - A change with `automationId == null` counts as affecting every automation query of that project (`affects` for detail/runs/steps returns true for it).

- [ ] **Step 1: Write the failing tests** (`TestGraph` + `FakeTroupeServer` HTTP routes, real `AutomationEvents` over `chat.agentEvent`):
  - `listReloadsOnChangeForItsProject`: serve two different list bodies in turn; after `agentEvent("automation_changed", {project_id:"p1", ...})` the state shows the second.
  - `projectWideChangeReloadsDetail`.
  - `listOn404IsUnsupported`: route returns 404; state `error == Unsupported`.
  - `contractMismatchIsInvalid` (Review Focus 5): a list reply with an unknown node kind gives `error == Invalid`.
  - `runNowNotApprovedMapsToNotApproved`: `Failed(NotApproved)`.
  - `mutationsUseTheCurrentToken`: the token lambda's latest value is the `Authorization` header.
- [ ] **Step 2: Run and see it fail.** Run: `./gradlew testDebugUnitTest --tests "dev.troupe.android.automations.state.AutomationsRepositoryTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** by delegating to `AutomationsApi` and `toQuery`.
- [ ] **Step 4: Run to pass.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(automations): AutomationsRepository"`.

---

### Task 7: Presentation rules

**Files:**
- Create: `app/src/main/java/dev/troupe/android/automations/ui/AutomationFormat.kt`
- Test: `app/src/test/java/dev/troupe/android/automations/ui/AutomationFormatTest.kt`

**Interfaces:**
- Produces: `sealed interface TriggerSummary { Manual; data class Every(val everyMs: Long); data class Cron(val expr: String, val timezone: String); data class At(val atMs: Long) }`; `fun triggerSummary(trigger: Trigger): TriggerSummary`; `fun needsApproval(a: Automation): Boolean` (`a.approvedRevision != a.revision`); `fun canRunNow(a: Automation, viewOnly: Boolean): Boolean`; `fun stepTimeLeftMs(step: AutomationStep, nowMs: Long): Long?` (only for `Waiting` steps with `waitUntil`; never negative); `fun orderForList(list: List<Automation>): List<Automation>` (port the ordering of `AutomationList.tsx`; read that file first and name each rule in a test).

- [ ] **Step 1: Write the failing tests:** `manualAndScheduleTriggersSummarize`; `needsApprovalWhenApprovedRevisionIsNull` and when behind; `canRunNowFalseWhenViewOnly` and when approval is needed; `stepTimeLeftClampsAtZero`; `orderForList` tests that pin the web's order on the kitchen-sink and list fixtures.
- [ ] **Step 2: Run and see it fail.** Run: `./gradlew testDebugUnitTest --tests "dev.troupe.android.automations.ui.AutomationFormatTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the functions (pure, no Android imports).
- [ ] **Step 4: Run to pass.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(automations): presentation rules"`.

---

### Task 8: `AutomationsController` and ViewModel

**Files:**
- Create: `app/src/main/java/dev/troupe/android/automations/state/AutomationsController.kt`
- Create: `app/src/main/java/dev/troupe/android/automations/ui/AutomationsViewModel.kt`
- Test: `app/src/test/java/dev/troupe/android/automations/state/AutomationsControllerTest.kt`

**Interfaces:**
- Consumes: `AutomationsRepository`, `canRunNow`.
- Produces:
  - `enum class ControlAction { Enable, Run, Cancel, Approve, Reject }`; `data class Control(val target: String, val action: ControlAction)`, where `target` is the id's string: `Control(id.value, ControlAction.Enable)` for an automation, `Control(runId.value, ControlAction.Cancel)` for a run.
  - `sealed interface Route { data object Overview; data class Detail(val id: AutomationId); data class Run(val id: AutomationId, val runId: RunId) }`.
  - `data class AutomationsUi(val route: Route, val list: QueryState<List<Automation>>, val detail: QueryState<AutomationDetail>?, val runs: QueryState<List<AutomationRun>>?, val steps: QueryState<List<AutomationStep>>?, val pending: Set<Control>, val failures: Map<Control, QueryError>, val enabledOverride: Map<AutomationId, Boolean>, val notices: List<Notice>)` and `enum class Notice { ApprovalAlreadyHandled }`.
  - `class AutomationsController(repo: AutomationsRepository, projectId: String, scope: CoroutineScope)` with `val ui: StateFlow<AutomationsUi>`; `fun open(id)`, `fun openRun(runId)`, `fun back(): Boolean` (false at the list), `fun refresh()`, `fun toggleEnabled(id, enabled)`, `fun runNow(id)`, `fun cancelRun(runId)`, `fun decide(runId, nodeId, approved)`, `fun dismissNotice(n: Notice)`, `fun close()`. Opening a detail whose `unseenFailures > 0` calls `markSeen` once per open. Closing/leaving a route closes that route's queries.
  - `@HiltViewModel class AutomationsViewModel @Inject constructor(session: WorkspaceSessionManager, api: AutomationsApi) : ViewModel()` exposing `fun controllerFor(projectId: String): StateFlow<AutomationsController?>`; it builds `AutomationsRepository(api, server.address, session::currentToken, AutomationEvents(hub), viewModelScope)` once `session.currentWatchHub()` and `session.state.value.activeServer` are non-null, rebuilds when the active server changes, and closes the controller in `onCleared`.

- [ ] **Step 1: Write the failing tests** (`TestGraph` + routes + `chat.agentEvent`, `runTest` where virtual time matters):
  - `toggleEnabledFlipsImmediatelyAndRollsBack`: route answers 500; `enabledOverride[id]` becomes the new value at once, is removed after failure, and `failures[Control(id.value, ControlAction.Enable)]` is set.
  - `toggleEnabledSuccessWaitsForEventThenSettles`: success; override stays until the list reload shows the server's value.
  - `runNowDisablesItsControlWhileInFlight`: `pending` contains `Control(id, Run)` during the call; a second `runNow` while pending sends no second request.
  - `runNowBlockedWhenApprovalNeeded`: no request is sent; failure is `NotApproved`.
  - `connectionDropDuringMutationShowsFailureAndReenables` (Review Focus 7): `Unreachable` recorded; `pending` cleared; one request only.
  - `decideFalseShowsNoticeAndRereads` (Review Focus 4): reply `data:false` adds `Notice.ApprovalAlreadyHandled` and triggers a steps reload.
  - `cancelRunAndDecideSendOnce`.
  - `openingDetailWithUnseenFailuresMarksSeenOnce`; `backFromDetailClosesDetailQueries`; `backAtOverviewReturnsFalse`.
  - `postMutationRereadsOnceWhenNoEventArrives`: advance 1 500 ms; exactly one extra list request.
- [ ] **Step 2: Run and see it fail.** Run: `./gradlew testDebugUnitTest --tests "dev.troupe.android.automations.state.AutomationsControllerTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the controller (combine query states into `ui`; per-control `pending`/`failures` maps) and the ViewModel wiring.
- [ ] **Step 4: Run to pass**, then `./gradlew compileDebugKotlin`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(automations): controller and ViewModel"`.

---

### Task 9: Screen host and list (replaces the old pane)

**Files:**
- Create: `app/src/main/java/dev/troupe/android/automations/ui/AutomationsScreen.kt` (host: route switching, `BackHandler`, states), `AutomationListScreen.kt`, `AutomationStateViews.kt` (loading, error, unsupported, empty)
- Modify: `app/src/main/java/dev/troupe/android/shell/MobileWorkspaceShellScreen.kt:324-331` (call `AutomationsScreen(projectId = activeTab.projectId, viewOnly = state.viewOnly)`), `WorkspaceShellViewModel.kt` (drop `automationsApi`), `snapshots/SnapshotsAndAutomations.kt` (delete `AutomationSummaryDto`, `AutomationsApi`, `AutomationsPane` and their imports; keep Snapshots)
- Modify: `app/src/main/res/values/strings.xml` (new `automations_*` strings in the "Snapshots & Automations" block)
- Modify: `app/src/test/java/dev/troupe/android/workspace/ServerApisContractTest.kt` (remove the old `AutomationsApi` assertions from `worktreeAndAutomationsMatchServerRoutes`; the worktree part stays)

**Interfaces:**
- Consumes: `AutomationsViewModel.controllerFor`, `AutomationsUi`, `orderForList`, `triggerSummary`, `canRunNow`, `needsApproval`.
- Produces: `@Composable fun AutomationsScreen(projectId: String, viewOnly: Boolean, modifier: Modifier = Modifier)`; the Detail/Run routes show a placeholder until Tasks 10 and 11.

- [ ] **Step 1: Implement the host:** obtains the controller via `hiltViewModel<AutomationsViewModel>()`; while it is `null` shows the loading view; `BackHandler(enabled = route != Route.Overview) { controller.back() }`.
- [ ] **Step 2: Implement the list** inside `PullToRefreshBox` (`controller.refresh()`), one card per Automation in `orderForList` order, using `TroupeTheme` palette/typography and `StatusDot` like the old pane: name; trigger summary line; next run time or "Manual"; last-run status with finished time (none when `last_run == null`); a failures badge when `unseenFailures > 0`; a `Switch` bound to `enabledOverride[id] ?: enabled`, with its failure text under it; a `TroupeButton` "Run" disabled by `canRunNow` and showing `loading` while `Control(id, Run)` is pending, with the approval hint when `needsApproval`. Both controls are hidden when `viewOnly`. The list keeps its data on refresh and errors (no full-screen spinner once data exists). Error views per `QueryError`: Unauthorized, Unreachable (Retry), Server (message + Retry), `Invalid` ("update the app", Review Focus 5), `Unsupported` ("This server does not support Automations"), empty (`automations_empty`).
- [ ] **Step 3: Rewire the call site, delete the old code, move the contract test.** Run: `./gradlew compileDebugKotlin`. Expected: success, no references to `snapshots.AutomationsApi`.
- [ ] **Step 4: Verify.** Run: `./gradlew testDebugUnitTest lintDebug`. Expected: PASS, no new lint errors. Manual: install the debug APK on the emulator (adb notes in memory), open Automations on a server that has some; the list shows live changes when one is toggled from the web.
- [ ] **Step 5: Commit.** `git commit -m "feat(automations): live list with enable and Run, replaces the old pane"`.

---

### Task 10: Detail screen

**Files:**
- Create: `app/src/main/java/dev/troupe/android/automations/ui/AutomationDetailScreen.kt`, `RunPolicyCard.kt`
- Modify: `AutomationsScreen.kt` (route `Detail`), `strings.xml`

**Interfaces:**
- Consumes: `AutomationsUi.detail`, `runs`, `controller.open/openRun/toggleEnabled/runNow/refresh`.
- Produces: `@Composable fun AutomationDetailScreen(ui: AutomationsUi, detail: AutomationDetail, controller: AutomationsController, viewOnly: Boolean, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Implement the screen** per the spec's "Detail" section, mirroring `AutomationDetail.tsx` and `RunPolicyCard.tsx`: header (name, enable switch, Run now with the approval gate and hint), trigger and next runs (`policy.nextRuns`, `triggerSummary`), run policy card (agents, secrets names, outbound hosts, MCP tools, untrusted-flow flag, failure policy), graph issues list (read-only; node label when `nodeId` matches), read-only graph outline (nodes in graph order: kind label and `label`), run history (last 25: trigger, status, start/finish, stop reason, summary; tap opens the run). Pull-to-refresh calls `controller.refresh()`.
- [ ] **Step 2: Empty and degenerate states** (Review Focus 6): no runs, no issues, zero nodes, `nextRunAt == null`, manual trigger each render their own empty text, no crash.
- [ ] **Step 3: Verify.** Run: `./gradlew compileDebugKotlin lintDebug`. Expected: success. Manual: open a detail with failures; the badge clears after opening (mark seen).
- [ ] **Step 4: Commit.** `git commit -m "feat(automations): detail screen with policy, issues, outline and run history"`.

---

### Task 11: Run detail, cancel and approval

**Files:**
- Create: `app/src/main/java/dev/troupe/android/automations/ui/RunDetailScreen.kt`, `ApprovalCard.kt`
- Modify: `AutomationsScreen.kt` (route `Run`), `strings.xml`

**Interfaces:**
- Consumes: `AutomationsUi.steps`, `controller.cancelRun/decide`, `parseApprovalOutput`, `stepTimeLeftMs`, `Notice.ApprovalAlreadyHandled`.
- Produces: `@Composable fun RunDetailScreen(ui: AutomationsUi, run: AutomationRun, controller: AutomationsController, viewOnly: Boolean, modifier: Modifier = Modifier)`; `@Composable fun ApprovalCard(message: String, pending: Boolean, onApprove: () -> Unit, onReject: () -> Unit)`.

- [ ] **Step 1: Implement the run screen** mirroring `AutomationRunDetail.tsx` and `canvas/run/step-view.tsx`: run header (trigger, status, times, stop reason, summary), a step list (node label looked up from `run.graph`, status, iteration, timings, error, collapsible JSON output), `Waiting` steps showing time left from `stepTimeLeftMs` ticking once per second, Cancel while the run is `running` (hidden when `viewOnly`), and the approval card on a step whose node is the run's `parked.nodeId` with a parseable approval output and no decision (Approve/Reject disabled while `Control` is pending). A `Notice.ApprovalAlreadyHandled` shows as a one-line message with dismiss.
- [ ] **Step 2: Verify.** Run: `./gradlew compileDebugKotlin lintDebug`. Expected: success. Manual: start a run with an `approval` node from the web, approve it on the phone, then cancel another running run.
- [ ] **Step 3: Commit.** `git commit -m "feat(automations): run detail, cancel and approve/reject"`.

---

### Task 12: Hardening and full verification

**Files:**
- Modify: `app/proguard-rules.pro`, `AGENTS.md` (Architecture section: one line on `automations/` and the `agents` watch), `strings.xml` (audit)
- Test: add a release-shaped check if the repo has one; otherwise manual

- [ ] **Step 1: R8.** Confirm the new `@Serializable` classes (sealed `NodeSpec`/`Trigger`/`Schedule` with `@SerialName`) are covered by the existing kotlinx.serialization keep rules; add a rule for the `automations.protocol` package only if the existing rules do not cover it. Run: `./gradlew assembleRelease` if a signing-free release variant exists, else `./gradlew assembleDebug` and state that R8 was not exercised.
- [ ] **Step 2: Copy audit.** `Grep` `strings.xml` and the `automations` package for "Termul" (expected: none) and for hard-coded UI literals in `automations/ui` (expected: none).
- [ ] **Step 3: Full check.** Run: `./gradlew --continue testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`. Expected: all green.
- [ ] **Step 4: Emulator pass** against a server with Automations: list live-updates; toggle; Run now (and the approval hint on an unapproved one); open a run; approve a parked run; cancel a running run; a view-only pairing hides the controls; stop the server and see Unreachable with Retry.
- [ ] **Step 5: Commit and push.** `git commit -m "chore(automations): R8 review, docs and verification"`, `git push -u origin feat/automations-slice1`, then verify CI with `gh run watch` per AGENTS.md and open the PR to `dev`.
