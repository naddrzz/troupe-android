# Troupe Plan 1 — Pairing, Projects and Terminals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Troupe APK that pairs with a termul-server, lists its Projects, and lets the user attach to, spawn, type into and kill Terminals.

**Architecture:** One `:app` module, packages split by feature (`pairing`, `servers`, `projects`, `terminal`) over a small `core` (network policy, protocol envelopes, theme). HTTP (`/health`, `/projects`) via OkHttp; Terminals over the `/terminal/ws` WebSocket. Agent Chat and the `/ws` socket are Plan 2. Rendering is xterm.js in a WebView behind `TerminalRenderer`.

**Tech Stack:** Kotlin, Compose + Material 3, Hilt, OkHttp + MockWebServer, kotlinx.serialization, DataStore + Tink, CameraX + ML Kit barcode (bundled model), xterm.js (`@xterm/xterm` + `@xterm/addon-fit`), JUnit 4, Turbine, Robolectric (only where Android classes are unavoidable), Compose UI test.

**Spec:** `docs/superpowers/specs/2026-11-01-troupe-android-mvp.md` (+ `GLOSSARY.md`, `docs/adr/0001-0003`)

**Roadmap:** Plan 2 = `/ws` client, Agent Chat, Attention, background service + notifications. Plan 3 = app lock, release signing, GitHub Releases. Each is written after the previous one lands.

## Global Constraints

- applicationId and root package `dev.troupe.android`; app name "Troupe"; the string "termul" never appears in user-visible text.
- minSdk 26, compileSdk 36, targetSdk 36. JDK at `D:\tools\jdk21`, Android SDK at `D:\tools\android-sdk` (`local.properties`: `sdk.dir=D\:\\tools\\android-sdk`, git-ignored).
- Library versions: latest stable at execution time, pinned in `gradle/libs.versions.toml`; no alpha/beta.
- Protocol: `protocolVersion` = 7. Any other value → refuse with "This server version isn't supported. Troupe needs protocol 7."
- Every `/terminal/ws` request is `{id, type, payload, commandId?}`; `spawn`, `write`, `kill` must carry a `commandId` matching `[A-Za-z0-9_-]{1,128}` (a UUID string).
- Server HTTP replies are `{success:true,data}` / `{success:false,error,code}`; terminal replies are `{id,success:true,data}` / `{id,success:false,error,code}`.
- Tokens and terminal claims never appear in logs, `toString()`, crash reports or backups.
- All UI strings in `res/values/strings.xml`, English.
- Emulator: LDPlayer via `D:\LDPlayer\LDPlayer9\adb.exe` (check `adb devices`; was `emulator-5556`).
- Commit after every task; conventional commit messages.

## Review Focus

1. **Pasted links with noise** — surrounding whitespace/newline, URL-encoded token, a reverse-proxy path prefix (`https://h/troupe/#token=…`). Expect the base URL to keep the path and the token to be decoded. Test in Task 2.
2. **Wrong token / unreachable host / protocol mismatch / view-only** — each produces its own message, never a crash or an endless spinner. Tests in Tasks 5–7.
3. **UTF-8 split across output chunks** — an `é` or emoji whose bytes span two `data` frames must render correctly. Bytes go to xterm raw, never decoded in Kotlin. Test in Task 9.
4. **Rotation / process death while a Terminal is open** — re-attach with the stored claim and cursor; never spawn a new Terminal or duplicate output. Test in Task 10.
5. **Network drop then return** — reconnect with backoff and resume by `(logEpoch, lastSeq)`; close code 1001 shows "Server offline" and polls `/health` every 15 s. Test in Task 6.

---

### Task 1: Project scaffold, theme and license

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle wrapper files, `.gitignore`, `LICENSE` (MIT, holder "Troupe contributors"), `README.md` (one paragraph + links to spec, glossary, ADRs + "Third-party assets" section)
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/{data_extraction_rules.xml,backup_rules.xml}`, `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/dev/troupe/android/{TroupeApp.kt,MainActivity.kt}`
- Create: `app/src/main/java/dev/troupe/android/core/theme/{Color.kt,Type.kt,Theme.kt}`, fonts in `app/src/main/res/font/`
- Test: `app/src/test/java/dev/troupe/android/core/theme/TroupeColorsTest.kt`

**Interfaces:**
- Produces: `@Composable fun TroupeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`; `data class TroupePalette(background, card, elevated, border, foreground, secondary, muted, brand, brandText, success, warning, error, info: Color)`; `object TroupeColors { val Dark: TroupePalette; val Light: TroupePalette }`; `val LocalTroupePalette`; `FontFamily`s `InterFamily`, `MonoFamily`; placeholder `@Composable fun TroupeNavHost()` showing "Troupe" (replaced in Task 8). `TroupeApp` is `@HiltAndroidApp`, `MainActivity` is `@AndroidEntryPoint`.

- [ ] **Step 1: Bootstrap the Gradle wrapper.** Download the latest stable Gradle `-bin.zip` into `D:\tools\gradle\`, run its `bin\gradle.bat wrapper` in the repo root. Expected: `gradlew.bat` and `gradle/wrapper/` exist.
- [ ] **Step 2: License check.** Read the license of Paper Mono (find the bundled font file and its license in termul, `D:\termul`) and of `@hugeicons/core-free-icons`. Write the outcome in README "Third-party assets". Paper Mono not redistributable → use JetBrains Mono (OFL). Hugeicons free not usable in an Android app → use Material Symbols. Never bundle an asset whose license you have not read.
- [ ] **Step 3: Write the failing test** `TroupeColorsTest`:

```kotlin
@Test fun darkPaletteMatchesTermul() {
    val p = TroupeColors.Dark
    assertEquals(Color(0xFF08090A), p.background)
    assertEquals(Color(0xFF0F1011), p.card)
    assertEquals(Color(0xFF161718), p.elevated)
    assertEquals(Color(0xFF23252A), p.border)
    assertEquals(Color(0xFFE5E5E6), p.foreground)
    assertEquals(Color(0xFFD0D6E0), p.secondary)
    assertEquals(Color(0xFF8A8F98), p.muted)
    assertEquals(Color(0xFF0D74CE), p.brand)
    assertEquals(Color(0xFF3B9EFF), p.brandText)
    assertEquals(Color(0xFF22C55E), p.success)
    assertEquals(Color(0xFFEAB308), p.warning)
    assertEquals(Color(0xFFEF4444), p.error)
    assertEquals(Color(0xFF38BDF8), p.info)
}
@Test fun lightPaletteMatchesTermul() {
    val p = TroupeColors.Light
    assertEquals(Color(0xFFFCFCFC), p.background)
    assertEquals(Color(0xFFFFFFFF), p.card)
    assertEquals(Color(0xFFEDEDED), p.border)
    assertEquals(Color(0xFF202020), p.foreground)
}
```

- [ ] **Step 4: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*TroupeColorsTest"` — Expected: FAIL (unresolved `TroupeColors`).
- [ ] **Step 5: Implement.** Light fields not pinned above come from termul `src/renderer/lib/themes/termul-light-chrome.ts`. `TroupeTheme` maps the palette onto a Material 3 `ColorScheme` (`primary = brand`, `background`, `surface = card`, `outline = border`, …) and provides `LocalTroupePalette`. Body text 14sp Inter; nothing below 12sp. Manifest: `allowBackup="false"`, `dataExtractionRules` and `fullBackupContent` exclude everything, permissions `INTERNET` and `CAMERA`, theme `Theme.Material3.DayNight.NoActionBar`.
- [ ] **Step 6: Run** the test — Expected: PASS. Then `.\gradlew.bat :app:assembleDebug` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 7: Smoke on the emulator.** `D:\LDPlayer\LDPlayer9\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk`, then `adb shell am start -n dev.troupe.android/.MainActivity`. Expected: dark screen with "Troupe".
- [ ] **Step 8: Commit** `chore: scaffold Troupe Android app with termul theme`.

---

### Task 2: Pairing input parsing and cleartext policy

**Files:**
- Create: `app/src/main/java/dev/troupe/android/pairing/PairingLink.kt`, `app/src/main/java/dev/troupe/android/core/net/TransportPolicy.kt`, `app/src/main/res/xml/network_security_config.xml`
- Test: `app/src/test/java/dev/troupe/android/pairing/PairingLinkTest.kt`, `app/src/test/java/dev/troupe/android/core/net/TransportPolicyTest.kt`

**Interfaces:**
- Produces: `data class ServerAddress(val baseUrl: HttpUrl)` with `fun httpUrl(path: String): HttpUrl` and `fun wsUrl(path: String): String` (http→ws, https→wss, keeps the base path prefix); `data class PairingInput(val address: ServerAddress, val token: String)` whose `toString()` omits the token; `object PairingLink { fun parse(raw: String): Result<PairingInput>; fun fromManual(url: String, token: String): Result<PairingInput> }`; `sealed class PairingError : Exception()` with `InvalidUrl`, `MissingToken`, `CleartextNotAllowed(val host: String)`; `object TransportPolicy { fun isCleartextAllowed(host: String): Boolean }`.

- [ ] **Step 1: Write failing tests.**

```kotlin
// PairingLinkTest
@Test fun parsesDesktopQrLink() {
    val r = PairingLink.parse("https://abc.trycloudflare.com/#token=" + "a".repeat(64)).getOrThrow()
    assertEquals("https://abc.trycloudflare.com/", r.address.baseUrl.toString())
    assertEquals("a".repeat(64), r.token)
}
@Test fun trimsWhitespaceAndKeepsProxyPath() {
    val r = PairingLink.parse("  https://h.example.com/troupe/#token=abc%2Fd \n").getOrThrow()
    assertEquals("https://h.example.com/troupe/", r.address.baseUrl.toString())
    assertEquals("abc/d", r.token)
    assertEquals("wss://h.example.com/troupe/terminal/ws", r.address.wsUrl("terminal/ws"))
}
@Test fun linkWithoutTokenFails() =
    assertTrue(PairingLink.parse("https://h.example.com/").exceptionOrNull() is PairingError.MissingToken)
@Test fun publicHttpRejected() =
    assertTrue(PairingLink.parse("http://8.8.8.8:8080/#token=x").exceptionOrNull() is PairingError.CleartextNotAllowed)
@Test fun garbageRejected() =
    assertTrue(PairingLink.parse("not a url").exceptionOrNull() is PairingError.InvalidUrl)
@Test fun manualAddsSchemeAndSlash() =
    assertEquals("http://192.168.1.5:8080/", PairingLink.fromManual("192.168.1.5:8080", "t").getOrThrow().address.baseUrl.toString())
@Test fun toStringHidesToken() =
    assertFalse(PairingLink.fromManual("http://10.0.0.2", "secret123").getOrThrow().toString().contains("secret123"))

// TransportPolicyTest
@Test fun allowsPrivateAndLocal() = listOf(
    "127.0.0.1", "localhost", "::1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.10",
    "100.64.0.1", "100.127.255.255", "box.local", "box.tail1234.ts.net"
).forEach { assertTrue(it, TransportPolicy.isCleartextAllowed(it)) }
@Test fun rejectsPublic() = listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "example.com", "abc.trycloudflare.com")
    .forEach { assertFalse(it, TransportPolicy.isCleartextAllowed(it)) }
```

- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*PairingLinkTest" --tests "*TransportPolicyTest"` — Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement.** Parse with OkHttp `HttpUrl`. Token = URL-decoded value of `token=` in the fragment; base URL = URL without fragment/query, ending in `/`. Manual input without a scheme gets `http://`. IP checks apply to literal IPs only (no DNS lookups).
- [ ] **Step 4: Run** the tests — Expected: PASS.
- [ ] **Step 5: Network security config.** `cleartextTrafficPermitted="true"` at base level (Android cannot express IP ranges), with an XML comment that `TransportPolicy` is the enforcement point; reference it from the manifest.
- [ ] **Step 6: Commit** `feat(pairing): parse pairing links and enforce cleartext policy`.

---

### Task 3: Paired Server storage

**Files:**
- Create: `app/src/main/java/dev/troupe/android/servers/{PairedServer.kt,PairedServerRepository.kt,TokenCipher.kt,ServersModule.kt}`
- Test: `app/src/test/java/dev/troupe/android/servers/PairedServerRepositoryTest.kt`

**Interfaces:**
- Consumes: `ServerAddress`, `PairingInput` (Task 2).
- Produces: `data class PairedServer(val id: String, val name: String, val address: ServerAddress, val stayConnected: Boolean = false)` (no token); `interface TokenCipher { fun encrypt(plain: String, associated: String): ByteArray; fun decrypt(cipher: ByteArray, associated: String): String }` with `TinkTokenCipher` (AndroidKeysetManager, master key `android-keystore://troupe_master_key`, AES256_GCM); `class PairedServerRepository(dataStore: DataStore<Preferences>, cipher: TokenCipher)` with `val servers: Flow<List<PairedServer>>`, `suspend fun add(name: String, input: PairingInput): PairedServer`, `suspend fun token(serverId: String): String?`, `suspend fun rename(id: String, name: String)`, `suspend fun setStayConnected(id: String, value: Boolean)`, `suspend fun remove(id: String)`. Adding an already-paired base URL replaces the token and keeps the id.

- [ ] **Step 1: Write failing tests** with a file-backed `PreferenceDataStoreFactory` in a `TemporaryFolder` and a fake `TokenCipher` (XOR with a constant):

```kotlin
@Test fun addThenReadBack() = runTest {
    val s = repo.add("MacBook", PairingLink.fromManual("http://10.0.0.2:8080", "tok").getOrThrow())
    assertEquals(listOf(s), repo.servers.first()); assertEquals("tok", repo.token(s.id))
}
@Test fun tokenIsNotStoredInPlaintext() = runTest {
    repo.add("A", PairingLink.fromManual("http://10.0.0.2", "plain-token-xyz").getOrThrow())
    assertFalse(prefsFile.readText(Charsets.ISO_8859_1).contains("plain-token-xyz"))
}
@Test fun sameUrlReplacesToken() = runTest {
    val a = repo.add("A", PairingLink.fromManual("http://10.0.0.2", "one").getOrThrow())
    val b = repo.add("B", PairingLink.fromManual("http://10.0.0.2/", "two").getOrThrow())
    assertEquals(a.id, b.id); assertEquals("two", repo.token(a.id)); assertEquals(1, repo.servers.first().size)
}
@Test fun removeDeletesToken() = runTest {
    val a = repo.add("A", PairingLink.fromManual("http://10.0.0.2", "one").getOrThrow())
    repo.remove(a.id); assertNull(repo.token(a.id)); assertTrue(repo.servers.first().isEmpty())
}
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.** Server list = one JSON string key (kotlinx.serialization); each token = Base64 ciphertext under `token_<id>`, server id as associated data. Ids are random UUIDs.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(servers): store paired servers with encrypted tokens`.

---

### Task 4: Protocol models

**Files:**
- Create: `app/src/main/java/dev/troupe/android/core/protocol/{Json.kt,HttpEnvelope.kt,Health.kt,Project.kt}`, `app/src/main/java/dev/troupe/android/terminal/protocol/TerminalProtocol.kt`
- Create: fixtures in `app/src/test/resources/fixtures/`: `health.json`, `projects.json`, `terminal_data.json`, `terminal_replay.json`, `terminal_gap.json`, `terminal_event_exit.json`, `terminal_event_cwd.json`, `terminal_reply_spawn.json`, `terminal_reply_err.json`, `terminal_list_preserved.json`
- Test: `app/src/test/java/dev/troupe/android/core/protocol/HttpModelsTest.kt`, `app/src/test/java/dev/troupe/android/terminal/protocol/TerminalProtocolTest.kt`

**Interfaces:**
- Produces:
  - `val TroupeJson: Json` (`ignoreUnknownKeys = true`, `explicitNulls = false`).
  - `@Serializable data class Health(val status: String, val allowRemoteWrites: Boolean = false, val setupRequired: Boolean = false)`.
  - `@Serializable data class Project(val id: String, val name: String, val color: String? = null, val path: String? = null, val isDefault: Boolean = false, val isArchived: Boolean = false)`; `@Serializable data class ProjectsState(val projects: List<Project>, val defaultProjectId: String? = null)`.
  - `sealed interface HttpResult<out T> { data class Ok<T>(val data: T); data class Err(val error: String, val code: String?) }`; `fun <T> decodeEnvelope(json: String, ser: KSerializer<T>): HttpResult<T>`.
  - `const val PROTOCOL_VERSION = 7`.
  - `@Serializable data class TerminalRequest(val id: String, val type: String, val payload: JsonObject, val commandId: String? = null)`.
  - `sealed interface TerminalFrame`: `Reply(id: String, success: Boolean, data: JsonElement?, error: String?, code: String?)`, `Data(terminalId: String, seq: Long, data: ByteArray)`, `Replay(terminalId, chunks: List<Chunk(seq: Long, data: ByteArray)>, gap: Boolean, reset: Boolean, logEpoch: String, latestSeq: Long, snapshot: TerminalSnapshot)`, `Gap(terminalId, lastSeq: Long)`, `Event(payload: TerminalEvent)`, `Unknown(raw: String)`. `fun parseTerminalFrame(text: String): TerminalFrame` dispatches on `type` (`data`/`replay`/`gap`/`event`); a frame with `id` and `success` is a `Reply`; anything else, including invalid JSON, is `Unknown`. It never throws.
  - `@Serializable data class TerminalSnapshot(val cwd: String? = null, val gitBranch: String? = null, val exitCode: Int? = null, val exited: Boolean = false)`.
  - `sealed interface TerminalEvent`: `Exit(terminalId: String, exitCode: Int?, signal: Int?)`, `CwdChanged(terminalId, cwd)`, `GitBranchChanged(terminalId, branch: String?)`, `ExitCodeChanged(terminalId, exitCode: Int)`, `Other(type: String)`. Event payload keys are snake_case (`terminal_id`, `exit_code`).
  - `@Serializable data class SpawnedTerminal(val id: String, val shell: String, val cwd: String, val pid: Int, val cols: Int, val rows: Int, val claim: String)`; `@Serializable data class AttachResult(val id: String, val shell: String, val cwd: String, val pid: Int, val cols: Int, val rows: Int, val latestSeq: Long, val gap: Boolean, val reset: Boolean, val logEpoch: String, val snapshot: TerminalSnapshot)`; `@Serializable data class PreservedTerminal(val id: String, val shell: String, val cwd: String, val claim: String? = null, val hasLiveAttachment: Boolean = false)`; `@Serializable data class PreservedList(val projectId: String, val terminals: List<PreservedTerminal>)`. `toString()` of anything holding a claim omits it.
  - `object TerminalErrorCodes` with the string constants `UNAUTHORIZED`, `PROTOCOL_VERSION_MISMATCH`, `INVALID_CLIENT_IDENTITY`, `FORBIDDEN`, `OUTCOME_UNKNOWN`, `COMMAND_CONFLICT`, `INVALID_COMMAND_ID`, `TERMINAL_LIMIT`, `PROJECT_BUSY`.

- [ ] **Step 1: Write the fixtures** from termul `src/shared/types/web-terminal-protocol.types.ts`, `src/shared/types/ipc.types.ts`, `src-tauri/src/pty/manager/types.rs` and `docs/api-contracts.md`. Data bytes are JSON int arrays (`"data":[104,105]`); the replay fixture has two chunks and `reset:true`; each fixture carries one unknown extra field. For `health.json`, check `src-tauri/src/web/router` for whether `/health` answers bare or inside the `{success,data}` envelope, and make `Health` decoding match.
- [ ] **Step 2: Write failing tests:**

```kotlin
@Test fun dataFrameBytes() {
    val f = parseTerminalFrame(fixture("terminal_data.json")) as TerminalFrame.Data
    assertArrayEquals(byteArrayOf(104, 105), f.data); assertEquals(12L, f.seq)
}
@Test fun replayWithReset() {
    val f = parseTerminalFrame(fixture("terminal_replay.json")) as TerminalFrame.Replay
    assertTrue(f.reset); assertEquals(2, f.chunks.size); assertEquals("ep-1", f.logEpoch)
}
@Test fun errorReply() {
    val f = parseTerminalFrame(fixture("terminal_reply_err.json")) as TerminalFrame.Reply
    assertFalse(f.success); assertEquals("FORBIDDEN", f.code)
}
@Test fun exitEventSnakeCase() =
    assertEquals(TerminalEvent.Exit("t1", 0, null), (parseTerminalFrame(fixture("terminal_event_exit.json")) as TerminalFrame.Event).payload)
@Test fun unknownTypeIsUnknown() = assertTrue(parseTerminalFrame("""{"type":"nope"}""") is TerminalFrame.Unknown)
@Test fun notJsonIsUnknown() = assertTrue(parseTerminalFrame("<<<") is TerminalFrame.Unknown)
@Test fun spawnedHidesClaim() = assertFalse(SpawnedTerminal("i", "s", "c", 1, 80, 24, "CLAIMX").toString().contains("CLAIMX"))
@Test fun preservedListDecodes() { /* terminal_list_preserved.json → 1 terminal, claim present, hasLiveAttachment false */ }
// HttpModelsTest
@Test fun healthDecodes() { /* health.json → allowRemoteWrites == true */ }
@Test fun errEnvelope() = assertEquals(HttpResult.Err("nope", "UNAUTHORIZED"),
    decodeEnvelope("""{"success":false,"error":"nope","code":"UNAUTHORIZED"}""", ProjectsState.serializer()))
```

- [ ] **Step 3: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*protocol*"` — Expected: FAIL.
- [ ] **Step 4: Implement** the models and parsers.
- [ ] **Step 5: Run** — Expected: PASS.
- [ ] **Step 6: Commit** `feat(protocol): terminal and HTTP protocol models with fixtures`.

---

### Task 5: Server HTTP API

**Files:**
- Create: `app/src/main/java/dev/troupe/android/core/net/{HttpModule.kt,ServerApi.kt}`
- Test: `app/src/test/java/dev/troupe/android/core/net/ServerApiTest.kt`

**Interfaces:**
- Consumes: `ServerAddress` (Task 2); `Health`, `ProjectsState`, `decodeEnvelope` (Task 4).
- Produces: `sealed interface ApiResult<out T> { data class Ok<T>(val value: T); data object Unauthorized; data class Unreachable(val cause: String); data class ServerError(val message: String, val code: String?) }`; `class ServerApi @Inject constructor(client: OkHttpClient)` with `suspend fun health(address: ServerAddress): ApiResult<Health>` and `suspend fun projects(address: ServerAddress, token: String): ApiResult<ProjectsState>`. Token only in the `Authorization: Bearer` header; no `Origin` header. Any logging interceptor redacts `Authorization`. Timeouts: connect 5 s, read 10 s.

- [ ] **Step 1: Write failing tests** with MockWebServer:

```kotlin
@Test fun healthOk() = runTest { server.enqueue(MockResponse().setBody(fixture("health.json")))
    val r = api.health(addr); assertTrue(r is ApiResult.Ok && r.value.allowRemoteWrites) }
@Test fun projectsSendsBearer() = runTest { server.enqueue(MockResponse().setBody(fixture("projects.json")))
    api.projects(addr, "tok"); assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization")) }
@Test fun projects401IsUnauthorized() = runTest { server.enqueue(MockResponse().setResponseCode(401))
    assertEquals(ApiResult.Unauthorized, api.projects(addr, "bad")) }
@Test fun connectionRefusedIsUnreachable() = runTest { server.shutdown()
    assertTrue(api.health(addr) is ApiResult.Unreachable) }
@Test fun envelopeErrorIsServerError() = runTest { server.enqueue(MockResponse().setBody("""{"success":false,"error":"boom","code":"X"}"""))
    assertEquals(ApiResult.ServerError("boom", "X"), api.projects(addr, "t")) }
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement** with a `Call.await()` extension over `suspendCancellableCoroutine`; `IOException` → `Unreachable`, 401/403 → `Unauthorized`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(net): server HTTP API for health and projects`.

---

### Task 6: Terminal socket connection

**Files:**
- Create: `app/src/main/java/dev/troupe/android/terminal/net/{TerminalSocket.kt,ClientIdentity.kt,Backoff.kt,ConnectionState.kt}`
- Test: `app/src/test/java/dev/troupe/android/terminal/net/{TerminalSocketTest.kt,BackoffTest.kt}`

**Interfaces:**
- Consumes: `ServerAddress` (Task 2); `ServerApi` (Task 5); frames and `PROTOCOL_VERSION` (Task 4).
- Produces:
  - `class ClientIdentity` with `val deviceId: String` (UUID persisted once per install) and `val clientInstanceId: String` (new UUID per process).
  - `class Backoff(base: Duration = 500.milliseconds, max: Duration = 30.seconds, jitter: Double = 0.2)` with `fun next(): Duration` and `fun reset()`.
  - `sealed interface ConnectionState { Connecting; Connected(val viewOnly: Boolean); Reconnecting(val attempt: Int, val inMillis: Long); ServerOffline; Failed(val reason: ConnectionFailure) }`; `enum class ConnectionFailure { Unauthorized, ProtocolMismatch }`.
  - `class TerminalSocket(client: OkHttpClient, api: ServerApi, address: ServerAddress, token: String, identity: ClientIdentity, viewOnly: Boolean, scope: CoroutineScope)` with `val state: StateFlow<ConnectionState>`, `val frames: SharedFlow<TerminalFrame>` (everything except `Reply`), `val reconnected: SharedFlow<Unit>` (after every successful re-authenticate), `fun start()`, `fun close()`, `suspend fun request(type: String, payload: JsonObject, ledgered: Boolean = false): TerminalFrame.Reply` (mints `id`, plus `commandId` when `ledgered`; throws `TerminalRequestTimeout` after 15 s and `TerminalDisconnected` when the socket drops).
  - Handshake: open `address.wsUrl("terminal/ws")`; first request is `authenticate {protocolVersion: 7, deviceId, clientInstanceId, token}`. Reply code `PROTOCOL_VERSION_MISMATCH` → `Failed(ProtocolMismatch)`, `UNAUTHORIZED` → `Failed(Unauthorized)`, no retry for either. Success → `Connected(viewOnly)`.
  - Close code 1001 → `ServerOffline`; poll `api.health` every 15 s and reconnect once it answers. Any other close or failure → `Reconnecting` with `Backoff`.

- [ ] **Step 1: Write failing tests** with MockWebServer `withWebSocketUpgrade` and a scripted server listener, on a `StandardTestDispatcher` (virtual time for backoff and polling):

```kotlin
@Test fun sendsAuthenticateFirst()          // first message: type=="authenticate", payload.protocolVersion==7, payload.token=="tok"
@Test fun connectedAfterOkReply()           // reply success → state Connected(false)
@Test fun protocolMismatchFailsWithoutRetry() // reply code PROTOCOL_VERSION_MISMATCH → Failed(ProtocolMismatch); server saw exactly 1 connection
@Test fun requestCorrelatesById()           // two concurrent requests answered in reverse order → each caller gets its own reply
@Test fun ledgeredRequestHasValidCommandId() // commandId matches Regex("[A-Za-z0-9_-]{1,128}")
@Test fun dataFramesGoToFrames()            // server sends a data frame → frames.test { awaitItem() is Data }
@Test fun reconnectsAfterAbnormalClose()    // server closes 1011 → Reconnecting, then Connected; reconnected emits once
@Test fun close1001IsServerOffline()        // server closes 1001 → ServerOffline; after health answers → Connected
// BackoffTest
@Test fun doublesAndCaps() { val b = Backoff(1.seconds, 4.seconds, jitter = 0.0)
    assertEquals(listOf(1, 2, 4, 4).map { it.seconds }, List(4) { b.next() }) }
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.** Pending requests in a `ConcurrentHashMap<String, CompletableDeferred<Reply>>`; a disconnect fails all of them with `TerminalDisconnected`. Never log the payload of `authenticate` or `write`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(terminal): /terminal/ws connection with handshake and reconnect`.

---

### Task 7: Pairing flow (scan, paste, manual)

**Files:**
- Create: `app/src/main/java/dev/troupe/android/pairing/{PairViewModel.kt,PairScreen.kt,QrScanner.kt}`
- Test: `app/src/test/java/dev/troupe/android/pairing/PairViewModelTest.kt`, `app/src/androidTest/java/dev/troupe/android/pairing/PairScreenTest.kt`

**Interfaces:**
- Consumes: `PairingLink` (Task 2), `PairedServerRepository` (Task 3), `ServerApi` (Task 5), a `TerminalSocket` factory (Task 6). A successful `authenticate` is the proof that the token works.
- Produces: `PairViewModel` with `val ui: StateFlow<PairUiState>` (`Idle`, `Checking`, `Error(@StringRes message: Int, arg: String? = null)`, `Paired(serverId: String, viewOnly: Boolean)`), `fun onLinkPasted(raw: String)`, `fun onQrScanned(raw: String)`, `fun onManual(url: String, token: String, name: String)`. Default name = host of the URL. `@Composable fun PairScreen(onPaired: (String) -> Unit, onBack: () -> Unit)` with tabs Scan / Paste / Manual. `@Composable fun QrScanner(onResult: (String) -> Unit)` = CameraX preview + ML Kit `BarcodeScanning` (QR only), asks for CAMERA with a rationale. A scanned QR goes through the same path as a pasted link.
- Strings (exact copy):
  - `pair_error_invalid_url`: "That doesn't look like a server address."
  - `pair_error_missing_token`: "The link has no token. Copy the full link from Troupe on your computer."
  - `pair_error_cleartext`: "Use https:// for %1$s. Plain http:// is only allowed on your local network or Tailscale."
  - `pair_error_unreachable`: "Can't reach the server. Check the address and that the server is running."
  - `pair_error_unauthorized`: "The token was rejected. Copy a fresh link from Troupe on your computer."
  - `pair_error_protocol`: "This server version isn't supported. Troupe needs protocol 7."

- [ ] **Step 1: Write failing ViewModel tests** with fakes: a link without a token → `Error(pair_error_missing_token)`; health unreachable → `Error(pair_error_unreachable)`; auth rejected → `Error(pair_error_unauthorized)` and the repository stays empty; protocol mismatch → `Error(pair_error_protocol)`; success with `allowRemoteWrites=false` → `Paired(id, viewOnly = true)` and the repository holds the server.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement** the ViewModel and screens.
- [ ] **Step 4: Run** the unit tests — Expected: PASS.
- [ ] **Step 5: Compose UI test** `PairScreenTest.manualEntryShowsCleartextError`: in Manual, type `http://8.8.8.8:8080` and token `x`, press Pair, assert a text containing "Use https:// for 8.8.8.8" is shown. Run `.\gradlew.bat :app:connectedDebugAndroidTest --tests "*PairScreenTest"` with the emulator running — Expected: PASS.
- [ ] **Step 6: Commit** `feat(pairing): pair screen with QR, paste and manual entry`.

---

### Task 8: Navigation, Servers list and Server home

**Files:**
- Create: `app/src/main/java/dev/troupe/android/core/nav/TroupeNavHost.kt` (replaces the Task 1 placeholder)
- Create: `app/src/main/java/dev/troupe/android/servers/{ServersViewModel.kt,ServersScreen.kt}`
- Create: `app/src/main/java/dev/troupe/android/projects/{ServerHomeViewModel.kt,ServerHomeScreen.kt,ProjectScreen.kt}`
- Create: `app/src/main/java/dev/troupe/android/core/ui/{ConnectionBadge.kt,ViewOnlyBadge.kt}`
- Test: `app/src/test/java/dev/troupe/android/projects/ServerHomeViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 3, 5 and 7.
- Produces:
  - Routes: `servers` (start), `pair`, `server/{serverId}`, `server/{serverId}/project/{projectId}`, `server/{serverId}/project/{projectId}/terminal/{terminalId}`.
  - `ServerHomeViewModel` with `val ui: StateFlow<ServerHomeUi>` (`Loading`, `Loaded(projects: List<Project>, viewOnly: Boolean)`, `Error(@StringRes message: Int)`, reusing the Task 7 strings) and `fun refresh()`. Archived Projects are hidden; the default Project comes first, then the rest by name.
  - `ProjectScreen` has tabs "Chats" (placeholder text "Agent chats arrive in the next update.") and "Terminals" (filled in Task 10).
  - Servers list rows show name, host and `ConnectionBadge`; long-press opens Rename / Remove, and Remove asks for confirmation. Empty state: "No servers yet" + a "Pair a server" button.
  - `ViewOnlyBadge` reads "View only". Tapping it shows: "This server only lets other devices watch. Start it with --allow-remote-writes to type and run commands from your phone."

- [ ] **Step 1: Write failing tests:** the loaded list hides archived Projects and puts the default first; 401 → `Error(pair_error_unauthorized)`; `allowRemoteWrites=false` → `viewOnly = true`.
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement** ViewModels and screens: rows at least 44dp tall, and a Project color dot for termul's named colors (blue, purple, green, yellow, red, cyan, pink, orange, gray).
- [ ] **Step 4: Run** — Expected: PASS. Build, install, pair with the Docker server (Task 11 Step 1) and check the Projects list on the emulator.
- [ ] **Step 5: Commit** `feat: servers list, server home and project screen`.

---

### Task 9: Terminal renderer (xterm.js in WebView) and key bar

**Files:**
- Create: `app/src/main/assets/terminal/{index.html,terminal.js,xterm.js,xterm.css,addon-fit.js,LICENSE-xterm}`. Copy the built files of the latest stable `@xterm/xterm` and `@xterm/addon-fit` from npm, and record their versions in README "Third-party assets".
- Create: `app/src/main/java/dev/troupe/android/terminal/render/{TerminalRenderer.kt,XtermWebViewRenderer.kt,TerminalView.kt,KeyBar.kt,AnsiPalette.kt}`
- Test: `app/src/test/java/dev/troupe/android/terminal/render/KeyBarTest.kt`, `app/src/androidTest/java/dev/troupe/android/terminal/render/XtermWebViewRendererTest.kt`

**Interfaces:**
- Produces:
  - `interface TerminalRenderer { val input: Flow<String>; val size: StateFlow<TermSize?>; fun write(bytes: ByteArray); fun reset(); fun focus() }`; `data class TermSize(val cols: Int, val rows: Int)`.
  - `XtermWebViewRenderer(webView: WebView, palette: AnsiPalette)`:
    - Loads `index.html` through `WebViewAssetLoader`.
    - Output goes to JS as Base64 and is written with `term.write(Uint8Array)`; it is never decoded to a String in Kotlin.
    - Writes are coalesced and flushed at most every 16 ms.
    - JS → Kotlin goes through one `@JavascriptInterface` object for `onData(String)` and `onResize(cols, rows)`.
    - JavaScript on, file access off apart from the asset loader, no network loads.
  - `enum class TerminalKey(val sequence: String) { Esc("\u001b"), Tab("\t"), CtrlC("\u0003"), Up("\u001b[A"), Down("\u001b[B"), Right("\u001b[C"), Left("\u001b[D"), PageUp("\u001b[5~"), PageDown("\u001b[6~") }`; `@Composable fun KeyBar(enabled: Boolean, onKey: (TerminalKey) -> Unit, onPaste: () -> Unit)`.
  - `AnsiPalette.Termul` has 16 colors plus foreground, background and cursor, copied from termul `src/renderer/lib/themes/termul-terminal.ts`. The terminal font is the mono family chosen in Task 1, loaded with `@font-face`.
  - `@Composable fun TerminalView(renderer: XtermWebViewRenderer)` hosts the WebView via `AndroidView`.

- [ ] **Step 1: Write failing tests.** `KeyBarTest` asserts every `TerminalKey.sequence` above. `XtermWebViewRendererTest` (instrumented) has two cases:
  1. `write(byteArrayOf(0xC3.toByte()))` then `write(byteArrayOf(0xA9.toByte()))`; after the flush, JS that reads the active buffer's first line returns `"é"`.
  2. JS `term.input("ls")` makes `input` emit `"ls"`.
- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*KeyBarTest"` and `.\gradlew.bat :app:connectedDebugAndroidTest --tests "*XtermWebViewRendererTest"` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** both — Expected: PASS.
- [ ] **Step 5: Commit** `feat(terminal): xterm.js renderer and key bar`.

---

### Task 10: Terminals tab and Terminal screen

**Files:**
- Create: `app/src/main/java/dev/troupe/android/terminal/{TerminalSessionManager.kt,TerminalCursorStore.kt,TerminalsViewModel.kt,TerminalsTab.kt,TerminalViewModel.kt,TerminalScreen.kt}`
- Test: `app/src/test/java/dev/troupe/android/terminal/{TerminalViewModelTest.kt,TerminalsViewModelTest.kt}`

**Interfaces:**
- Consumes: `TerminalSocket` (Task 6), models (Task 4), `TerminalRenderer`, `KeyBar` and `TerminalView` (Task 9), `PairedServerRepository` and `TokenCipher` (Task 3).
- Produces:
  - `TerminalSessionManager` (`@Singleton`): one `TerminalSocket` per Paired Server, created on demand and closed after 60 s with no user.
  - `TerminalCursorStore` (DataStore): per `(serverId, terminalId)` it keeps the `claim` (encrypted with `TokenCipher`), `logEpoch` and `lastSeq`. This is what makes re-attach work after rotation and process death.
  - `TerminalsViewModel(serverId, projectId)`:
    - Lists with `list_preserved {projectId}`. Each row shows cwd and shell, plus "Open elsewhere" when `hasLiveAttachment`. Each returned claim goes into the store.
    - `fun spawn()` sends a ledgered `spawn {projectId, cols: 80, rows: 24}` and stores the claim with cursor `("", 0)`.
    - `fun kill(terminalId)` sends a ledgered `kill {terminalId}`. The UI always confirms first: "Kill this terminal? Anything running in it will stop."
    - Spawn and kill are hidden when view-only.
  - `TerminalViewModel(serverId, terminalId)`:
    - **Attach.** On start and on every `reconnected`, send `attach {terminalId, claim, lastSeq, logEpoch}`. When `lastSeq > 0` and no epoch is known, send `logEpoch: ""`.
    - **Replay.** On `Replay`, call `renderer.reset()` first if `reset`, then write the chunks.
    - **Live output.** Write `Data` only when `seq == lastSeq + 1`. Any other seq, or a `Gap`, means re-attach from the stored cursor.
    - **Cursor.** Persist `(logEpoch, lastSeq)` at most once per second and in `onCleared`.
    - **Input and resize.** Renderer and key-bar input go out as a ledgered `write {terminalId, data}`. `size` changes go out as `resize {terminalId, cols, rows}`, debounced 150 ms.
    - **Errors.** `UNAUTHORIZED` on attach shows "This terminal is no longer available." and drops the stored claim. An `Exit` event shows the banner "Process exited (code N)". `FORBIDDEN` switches the screen to view-only.
    - **Paste.** The Paste button sends the clipboard text as one `write`.

- [ ] **Step 1: Write failing tests** with a fake socket and a recording renderer:

```kotlin
@Test fun attachUsesStoredCursor()      // store (claim "c", epoch "e1", seq 41) → first request attach{terminalId, claim:"c", lastSeq:41, logEpoch:"e1"}
@Test fun resetReplayClearsRenderer()   // Replay(reset=true, chunks [1],[2], latestSeq 2) → renderer: reset, write(1), write(2); cursor seq 2
@Test fun outOfOrderDataTriggersReattach() // lastSeq 5, Data(seq 7) → not written; new attach with lastSeq 5
@Test fun reconnectReattaches()         // reconnected emits → attach sent with the current cursor
@Test fun unauthorizedAttachDropsClaim() // attach reply UNAUTHORIZED → ui unavailable; store has no claim
@Test fun viewOnlyBlocksWrites()        // viewOnly → renderer input "x" sends no write
@Test fun spawnStoresClaim()            // TerminalsViewModel.spawn → store holds the returned claim; request carried a commandId
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Check on the emulator** against the Docker server (Task 11 Step 1):
  1. Spawn a terminal and run `ls`.
  2. Rotate: `adb shell settings put system accelerometer_rotation 0` then `adb shell settings put system user_rotation 1`. The output must still be there, once.
  3. Press Home, then `adb shell am kill dev.troupe.android`, and reopen the app. It must re-attach to the same terminal.
- [ ] **Step 6: Commit** `feat(terminal): terminals tab, attach/spawn/kill and terminal screen`.

---

### Task 11: Integration test against a real server

**Files:**
- Create: `integration/docker-compose.yml`, `scripts/test-server.ps1`, `.gitignore` entry `integration/workspace/`
- Create: `app/src/test/java/dev/troupe/android/integration/RealServerTest.kt`
- Modify: `app/build.gradle.kts` (exclude `**/integration/**` from `testDebugUnitTest`; add an `integrationTest` task that runs only that package)

**Interfaces:**
- Consumes: `ServerApi`, `TerminalSocket`, models.

- [ ] **Step 1: Server fixture.** `integration/docker-compose.yml` runs `ghcr.io/gnoviawan/termul-server:latest` the way termul's own compose file does, with these settings:
  - `TERMUL_WEB_AUTH_TOKEN`: a fixed, test-only 64-hex token defined in the script.
  - `TERMUL_SERVER_ALLOW_REMOTE_WRITES: "true"`.
  - Port `127.0.0.1:18080:8080`.
  - An empty `integration/workspace/` bind-mounted at `/workspace`.

  `scripts/test-server.ps1 up|down|reverse`, where `reverse` runs `D:\LDPlayer\LDPlayer9\adb.exe reverse tcp:18080 tcp:18080` so the emulator can use `http://127.0.0.1:18080`.

  Run `.\scripts\test-server.ps1 up`. Expected: `curl http://127.0.0.1:18080/health` answers.
- [ ] **Step 2: Write the test** `RealServerTest.pairListSpawnWriteRead`. It skips via `assumeTrue` when `/health` is unreachable. Steps, in order:
  1. Health answers with `allowRemoteWrites = true`.
  2. Setup only: `POST /projects` with `{id: <uuid>, name: "it", path: "/workspace", color: "blue"}`, sent directly with OkHttp and the bearer token.
  3. `ServerApi.projects` contains that Project.
  4. `TerminalSocket` connects.
  5. A ledgered `spawn {projectId, cols: 80, rows: 24}` returns a claim; then `attach`.
  6. A ledgered `write {data: "echo troupe-ok\r"}`. Within 10 s, the concatenated `Data` bytes contain `troupe-ok`.
  7. A ledgered `kill`.
  8. Cleanup: `DELETE /projects/{id}?cascade=true`.
- [ ] **Step 3: Run** `.\gradlew.bat :app:integrationTest` — Expected: PASS. If a contract detail differs, fix the model in Task 4's files and add a fixture of the real shape.
- [ ] **Step 4: Full suite.** `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` — Expected: all PASS, BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `test: integration test against a real Troupe Server`.
