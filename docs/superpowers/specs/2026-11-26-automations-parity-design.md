# Automations: 1:1 with Troupe

Foundation and slice 1 (monitor and control) are designed in full here. Slices 2 (set up) and 3 (edit the
graph) are named, ordered and constrained here, and each gets its own brainstorm, design and plan when its
turn comes.

Source of truth: `naddrzz/troupe` branch `dev`, read on 2026-11-26. The repo is public: read any file with
`gh api -H "Accept: application/vnd.github.raw" repos/naddrzz/troupe/contents/<path>`. The checkout in
`D:\termul` is stale (2026-10-09) and has no Automations at all; do not use it. Files that act as the spec:

- Contract: `src/shared/types/automation.types.ts`, `src/shared/types/__fixtures__/automation.json`,
  `automation-ops.json`
- Server routes: `src-tauri/src/web/automation_api.rs`, event names in `src-tauri/src/automation/model.rs`,
  delta shape in `src-tauri/src/web/watch/sources.rs`
- Web client: `src/renderer/lib/automation-api.ts`, `automation-wire.ts`,
  `components/automations/use-automations.ts`
- Web UI: `components/automations/{AutomationsPanel,AutomationList,AutomationDetail,AutomationRunDetail,
  AutomationApprovalCard,RunPolicyCard,automation-format}.*`, and `canvas/run/*` for the run overlay

## Why

The phone is supposed to control Automations as fully as the web does. Today it cannot. The Android
Automations pane (`snapshots/SnapshotsAndAutomations.kt`) is a list with a Run button, and it is written
against a guessed contract, not the real one:

- `AutomationSummaryDto` has `status` and `description`. The server sends neither. It sends `trigger`,
  `next_run_at`, `last_run`, `unseen_failures`, `revision`, `approved_revision`, `consecutive_errors` and more.
- `setEnabled` exists in `AutomationsApi` and no screen calls it.
- Run ignores its result, shows no running state, and reloads the whole list behind a spinner.
- All failures collapse into one message; nothing refreshes live.
- `ServerApisContractTest` passes on hand-made JSON, so it proves nothing about the real server.

## Goals

- The phone can do everything the web can do with an Automation, delivered in three slices (below).
- State is the server's. The app adopts the web's model: same wire types, same strict parsing, same
  "event, then re-read" refresh rule, same last-good-data-on-failure behaviour.
- The wire contract is verified against the web's own fixtures, not invented JSON.
- Screens mirror the web's structure and wording (Troupe's Mobile V2 look, as the rest of the app does).

## Non-goals

- No server change and no protocol change (ADR-0002, ADR-0003). Everything uses `/api/automation*` over HTTP
  and the existing `/ws` `agents` watch.
- No pan/zoom node canvas on the phone (slice 3 uses an outline editor).
- No offline editing or local caching of Automations beyond the in-memory last-good copy.
- Desktop-only surfaces (Tauri commands, `automation_*` IPC) are out of scope.

## Anti-goals

- A parser that returns a half-typed object. A reply that does not match the contract is an error.
- A phone that silently shows stale state. A missed event must end in a re-read (see Live state).
- Secret values ever read back, logged or kept in state after sending (slice 2; the rule is set now).

## Slices

1. **Monitor and control** (designed below): list, detail, enable/disable, run now, run history, run
   steps, cancel a run, approve/reject a parked approval, mark failures seen, live updates.
2. **Set up** (own cycle): secrets, approve a revision, suggestions (list/match/accept/dismiss), create,
   rename, delete, trigger and schedule editor with `preview-schedule`.
3. **Edit the graph** (own cycle): forms for the 10 node kinds (`http`, `agent`, `mcp_tool`, `transform`,
   `condition`, `for_each`, `merge`, `wait`, `approval`, `fail`) through `ops` and `ops/preview`, shown
   `issues`, node order and edges as an outline editor.

Order is fixed: 1, then 2, then 3. Each later slice reuses the foundation.

## Approaches

- **A. Port the web model into a new `automations/` layer (chosen).** Kotlin wire models mirroring
  `automation.types.ts`, strict boundary parsers, a repository that owns state and refreshes on server
  events, a thin ViewModel and Compose screens. Matches how the app already treats other server contracts
  (hand-written serializable classes plus fixtures, ADR-0003) and gives slices 2 and 3 a place to land.
- **B. Patch the existing pane in place.** Cheapest for slice 1, but keeps logic inside composables with no
  ViewModel and no unit tests, and slices 2 and 3 would pile into the same file. Rejected.
- **C. Keep the graph as raw JSON and render it generically.** Less code for node forms, but loses
  validation and typed editing. Rejected.

The user chose A.

## Foundation (applies to all slices)

**Package.** New `dev.troupe.android.automations` (`protocol/`, `net/`, `state/`, `ui/`). The old
`AutomationSummaryDto`, `AutomationsApi` and `AutomationsPane` leave `snapshots/SnapshotsAndAutomations.kt`;
that file keeps only Snapshots. Call sites (`WorkspaceShellViewModel`, `MobileWorkspaceShellScreen`) and
`ServerApisContractTest` move with them.

**Wire models and parsing.** Kotlin classes for every type in `automation.types.ts`: `Automation`, `Trigger`,
`Schedule`, `Graph`, `NodeSpec` (all 10 kinds), `Edge`, `AutomationRun`, `AutomationStep`, `LastRun`,
`Parked`, `RunPolicy`, `AutomationDetail`, `Issue`, `ApprovalStepOutput`, `AutomationChanged`, plus ids as
value classes. Parsing follows `automation-wire.ts`: unknown enum values and missing required fields fail
the whole parse with the field path; `Issue.code` stays a string (shown, never branched on); step `output`
stays free-form JSON; an approval step's output that does not match yields no approval detail instead of
failing the list. All 10 `NodeSpec` kinds are modelled in slice 1 even though slice 1 only displays them, so
slice 3 needs no model change.

**HTTP client.** `AutomationsApi` over the existing `ServerApi` (token, cleartext guard and envelope
handling stay as they are). Slice 1 routes:

| Operation | Request |
| --- | --- |
| list | `GET /api/automations?project_id=` |
| get | `GET /api/automations/{id}` (returns `AutomationDetail`) |
| set enabled | `POST /api/automations/{id}/enabled` `{enabled}` |
| run now | `POST /api/automations/{id}/run` `{}` (returns the new `AutomationRun`) |
| list runs | `GET /api/automations/{id}/runs?limit=25` |
| run steps | `GET /api/automation-runs/{run_id}/steps` |
| cancel run | `POST /api/automation-runs/{run_id}/cancel` `{}` |
| decide approval | `POST /api/automation-runs/{run_id}/approval` `{node_id, approved}` (returns bool: `false` = already moved on) |
| mark seen | `POST /api/automations/{id}/seen` `{}` |

Mutating calls are sent once, never auto-retried (the web uses `postJsonOnce`). Failures keep
`ApiResult`'s four cases (`Unauthorized`, `Unreachable`, `ServerError`, parse failure); the UI tells them
apart and offers Retry.

**Repository and live state.** `AutomationsRepository` (one per Paired Server session) exposes per-project
and per-automation state as flows and applies the web's `useHostQuery` rule:

- Load on first collection; reload when an `automation_changed` event matches (same `project_id` for the
  list, same `automation_id` for detail and runs) and on `automation_resync`.
- Only the latest request's reply is kept (a stale reply never overwrites a newer one).
- A failed refresh keeps the last good data and exposes the error next to it.
- Run steps poll every 2 s while the run is `running` and not parked; a parked run changes only through
  events. A poll never starts while a request is in flight.
- Events arrive on the `agents` watch of `WatchHub` as a delta
  `{"event": "automation_changed" | "automation_resync", "payload": {...}}`. The server publishes them with
  scope `""` (`publish_agent_event` in `web/watch/sources.rs`), so there is one subscription per Paired
  Server and it carries events for every project; the repository filters by `project_id`/`automation_id`.
  `payload` for a change is `{project_id, automation_id | null, proposed?, proposed_by?}`; a `null`
  `automation_id` is project-wide. Subscribers re-register when `ConnectionLifecycle.generation` changes
  (existing `WatchHub` behaviour); after a reconnect every shown query re-reads once.
- `WatchHub` already supports the `agents` watch but no feature in the app subscribes to it today, and
  `WatchHub` itself needs no change. The repository owns that single subscription and ignores deltas
  whose `event` is not an automation event (the same watch also carries other agent lifecycle events), so
  any later consumer of the watch is unaffected. The delta shape is pinned by a fixture test.

**Tests.** Real object graph (`TestGraph` with `FakeTroupeServer`), as the rest of the app. Fixtures for
`Automation`, `AutomationDetail`, run, steps and the `agents` delta come from the web repo, never from
invented JSON: `src/shared/types/__fixtures__/automation.json` and `automation-ops.json`,
`src/renderer/lib/test-utils/automation-fixtures.ts` (runs, steps, details) and the Rust tests next to
`automation_api.rs` (`automation_api_tests.rs`, `model_tests.rs`) for the exact wire output. They are
checked in under `app/src/test/resources/fixtures/automations/`, each with a note naming its source file.
Changing a model means updating its fixture test. The old hand-made JSON in `ServerApisContractTest` is
replaced. A recorded response from a real server with run history is a bonus check when one is available,
not a prerequisite. Connection-dependent logic uses a test dispatcher for virtual time (existing
convention).

## Slice 1 design: monitor and control

**Entry.** Unchanged: drawer footer, command palette and More actions open `WorkspaceTab.Automations(projectId)`.
The tab hosts a list; tapping an item opens its detail inside the same tab (a back button returns to the
list), so no new `WorkspaceTab` kind is required.

**List.** One row per Automation: name, trigger summary (manual, or the schedule in words), next run time,
last-run status and finished time, a badge for `unseen_failures`, and an enable/disable switch. Rows for
Automations that never ran show no last run. Sorting and grouping follow the web's `AutomationList`. Empty
and error states say why and offer the next step (Retry, or "create one from Troupe on a computer" until
slice 2).

**Detail.** Header with name, enable switch, Run now and a "needs approval" notice when
`approved_revision` is not the current `revision` (slice 1 shows it; approving a revision is slice 2).
Sections: trigger and next runs, run policy (`RunPolicy`: agents, secrets, outbound hosts, MCP tools,
untrusted-flow flag, failure policy, as `RunPolicyCard` shows), graph issues (`issues`, shown read-only),
a read-only outline of the graph (nodes in order with kind and label), and run history. Opening the detail
marks failures seen (`/seen`) when `unseen_failures > 0`.

**Run history and run detail.** Last 25 runs: trigger (scheduled, catch-up, manual), status, start/finish,
stop reason and summary. A run opens a step list: per step the node label, status, iteration, timings,
error and a collapsible output; steps waiting on `wait` show the time left. A running run shows Cancel.
A run parked on an `approval` node shows the approval card (the node's message and Approve/Reject); if the
server answers `false` ("already moved on") the screen says so and re-reads.

**Approval gate.** The server refuses Run now with `ServerError` code `not_approved` ("approve the current
revision before running it") whenever `approved_revision != revision`; Enable/disable has no such check
(`set_enabled` only saves and announces). So Run now is disabled in the UI in that state, with the hint
"Approve this revision first. Approving is done from Troupe on a computer until a later update.", and a
`not_approved` code from the server maps to the same text. The enable switch stays available.

**Server without Automations.** A server that predates Automations answers the list route with 404. That
maps to a distinct "This server does not support Automations" state (not a generic error, no Retry loop);
the drawer, palette and More entries stay visible and lead to it.

**Controls and feedback.** Every mutation shows an in-flight state on its own control, is disabled while
in flight (no double sends), and reports failure on that control. After a successful mutation the screen
waits for the server's event (or re-reads once if none arrives within a short window) instead of
optimistic local edits, except the enable switch, which flips immediately and rolls back on failure. View-only
sessions (`state.viewOnly`) hide every mutating control, as the rest of the app does.

**Refresh gestures.** Pull-to-refresh on the list and on the detail forces a re-read. The list never
replaces itself with a spinner once it has data.

**Copy.** All strings go to `strings.xml`; "Termul" never appears (GLOSSARY.md). Error text maps from
`ApiResult` cases; no raw server text in titles.

## Constraints

- Android: Kotlin, Compose, Hilt, one `:app` module; follow the existing package-per-feature layout.
- Build and tests run through Colab when available, else local Gradle (AGENTS.md); CI runs
  `testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`.
- Release builds use R8: new `@Serializable` classes need the existing keep rules reviewed
  (`app/proguard-rules.pro`).
- The server is the web's server: whatever `automation_api.rs` accepts and returns is final. Where the
  contract and a fixture disagree, the Rust route and a recorded real response win.

## Left to the builder

- Exact Compose layout, spacing and iconography (match the existing shell components and `TroupeTheme`).
- How the repository is scoped and injected (per Paired Server session vs singleton), and where
  `WatchHub`/`agents` is obtained, following how other features get it.
- The short wait before the single fallback re-read after a mutation.
- Time and relative-time formatting (reuse the app's existing formatters if present, else add one).
- Naming of internal classes and the split of ViewModels (one for list and one for detail is expected).
- Whether to record real server responses with the Docker test server for fixtures, or derive them from
  the web fixtures plus the Rust types.

## Open items to confirm while building

- Probed 2026-11-26: the integration test server (`ghcr.io/gnoviawan/termul-server:latest`, image created
  2026-11-23, see `integration/docker-compose.yml`) has the Automations routes. `GET
  /api/automations?project_id=x` answered 200 `{"success":true,"data":[]}`. A create, ops, approve and run
  sequence also returned the same shapes as the web fixtures for list, detail, runs and steps (the run
  failed with `stop_reason` `project_unavailable` because the project did not exist, so its steps list was
  empty). Integration coverage for Automations is therefore possible.
- Real response bodies for `GET /api/automations/{id}/runs` and `/steps` on a server with run history, as a
  cross-check of the web-derived fixtures.
- The `limit` upper bound the server accepts for runs (25 is what the web sends).
