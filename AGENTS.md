# Troupe Android

Native Android (Kotlin, Jetpack Compose) client for a Troupe Server (formerly termul-server). Product decisions: `docs/superpowers/specs/`; vocabulary: `GLOSSARY.md`; architectural decisions: `docs/adr/`.

## Build, test & check policy

**Compile, build, test, and lint on Google Colab through the `colab` CLI whenever it is available; otherwise run the same Gradle commands locally.** GitHub Actions still verifies every push (see "CI" below), but do not wait for CI to find out whether a change compiles.

### Gradle commands

Use `./gradlew` on Colab and in Git Bash, `.\gradlew.bat` in PowerShell. JDK 21 is required.

| Purpose | Command |
| --- | --- |
| Everything CI checks | `./gradlew --continue testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug` |
| Compile only | `./gradlew compileDebugKotlin` |
| Unit tests | `./gradlew testDebugUnitTest` |
| One test class | `./gradlew testDebugUnitTest --tests "dev.troupe.android.core.net.BackoffTest"` |
| One test method | `./gradlew testDebugUnitTest --tests "dev.troupe.android.core.net.BackoffTest.<method>"` |
| Lint | `./gradlew lintDebug` |
| Debug APK | `./gradlew assembleDebug` (output: `app/build/outputs/apk/debug/app-debug.apk`) |

### Is Colab available?

Colab is available when `colab sessions` exits 0 without asking for anything. If the command is missing, fails, or prints an authorization URL and waits for a code, Colab is **not** available: run Gradle locally, and tell the user Colab was skipped and why. Never try to complete the OAuth prompt, and never run `colab repl`, `colab auth`, `colab drivemount`, or `colab ssh` without `-- CMD` (they wait for a human). `colab skill` prints the CLI's full guide (set `PYTHONIOENCODING=utf-8` on Windows, or it crashes on a Unicode character).

### Running on Colab

A session is a billable VM whose files and kernel state persist until it is stopped, so set it up once and reuse it for the whole task.

1. Create the session (CPU is enough): `colab new -s troupe`
2. Set up the VM once per session. Colab does not ship JDK 21 or the Android SDK; Gradle downloads the SDK platform and build-tools itself once the licenses are accepted:

   ```bash
   colab exec -s troupe --timeout 900 <<'EOF'
   %%bash
   set -euo pipefail
   apt-get -yq install openjdk-21-jdk-headless unzip >/dev/null
   mkdir -p /opt/android-sdk/cmdline-tools
   cd /opt/android-sdk/cmdline-tools
   curl -fsSLo tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
   unzip -q tools.zip && mv cmdline-tools latest && rm tools.zip
   yes | latest/bin/sdkmanager --licenses >/dev/null || true
   EOF
   ```

3. Send the working tree, including uncommitted changes, so Colab tests exactly what is on disk. Write the archive to a temp directory outside the repo:

   ```bash
   src="${TMPDIR:-/tmp}/troupe-src.tgz"
   git ls-files -co --exclude-standard -z | tar --null -czf "$src" -T -
   colab upload -s troupe "$src" content/troupe-src.tgz
   ```

   Give `colab upload`, `download`, `ls` and `rm` the remote path without a leading slash (`content/...`): Git Bash on Windows rewrites `/content/...` into a Windows path and the call fails with a 500.

4. Run Gradle. `--timeout` is seconds without output, and the default 30 is too short for a build:

   ```bash
   colab exec -s troupe --timeout 900 <<'EOF'
   %%bash
   set -euo pipefail
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk
   export JAVA_TOOL_OPTIONS=-XX:-UseContainerSupport
   rm -rf /content/troupe/app/src && mkdir -p /content/troupe && cd /content/troupe
   tar -xzf /content/troupe-src.tgz
   sed -i 's/\r$//' gradlew && chmod +x gradlew
   ./gradlew --continue --console=plain testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug \
     > /content/gradle.log 2>&1 && echo GRADLE_OK || echo GRADLE_FAILED
   grep -E "^BUILD|FAILED|What went wrong" -A6 /content/gradle.log | cut -c1-300 | tail -60
   EOF
   ```

   - **Judge the run by `GRADLE_OK` / `GRADLE_FAILED`, not by the exit code**: `colab exec` exits 0 even when the `%%bash` cell fails. The full log is `/content/gradle.log` on the VM.
   - `JAVA_TOOL_OPTIONS=-XX:-UseContainerSupport` is required. Without it the JVM prints a cgroup warning on Colab that breaks AGP's `jlink` version check (`JdkImageTransform` fails with "Could not determine Java version").
   - The `sed` strips CRLF from `gradlew`, which a Windows checkout adds and Linux cannot execute.
   - A first run takes about 10 minutes on the 2-core CPU runtime (it downloads Gradle, dependencies and the SDK platform). Run it in the background rather than blocking on it.

   Repeat steps 3 and 4 after each change; `build/` and the Gradle cache stay on the VM, so later runs are incremental.
5. Fetch what you need, for example `colab download -s troupe content/troupe/app/build/outputs/apk/debug/app-debug.apk <local path>`. Reports are under `/content/troupe/app/build/reports/`.
6. **Always** `colab stop -s troupe` when the task is done, also after a failure. An idle session keeps spending compute units.

The archive has no `.git`, so a Colab build gets `versionName` `0.0.0-dev` and `versionCode` 1. Release builds are made by CI, not on Colab.

### Local only

These cannot run on Colab (no emulator, no Docker):

- Instrumented tests need a device or emulator: `.\gradlew.bat connectedDebugAndroidTest`
- Integration tests need a real Troupe Server in Docker and skip when it is not running:

  ```powershell
  .\scripts\test-server.ps1 up        # server on http://127.0.0.1:18080 with a fixed test token
  .\gradlew.bat :app:integrationTest
  .\scripts\test-server.ps1 down
  ```

### CI

- Every push and pull request triggers the `Build` workflow in GitHub Actions (`.github/workflows/build.yml`), which runs:
  - `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`
  - Uploads the signed debug APK as workflow artifact **`troupe-android-debug-apk`** (`app-debug.apk`, 14-day retention).
  - Uploads HTML test and lint reports as **`test-and-lint-reports`** (7-day retention, even on failure).
- To verify changes after pushing to a feature branch, inspect the workflow via `gh`:
  - `gh run list -R naddrzz/troupe-android --branch <branch> --limit 5`
  - `gh run watch <run-id> -R naddrzz/troupe-android --exit-status`
  - `gh run view <run-id> -R naddrzz/troupe-android --log-failed`
- Fix CI failures by pushing follow-up commits; do not force-push merely to retrigger CI.

## Branch & PR policy

- `dev` is the default branch. Target all feature/fix PRs to **`dev`** (not `main`). `main` is reserved for production releases tagged `v*`; it is protected, so changes reach it only through a pull request, and release tags must point at a commit on `main`.
- Never use `"Termul"` in user-facing UI strings (`app/src/main/res/values/strings.xml`); follow canonical terms in `GLOSSARY.md`.

## Architecture

One Gradle module (`:app`, package `dev.troupe.android`), Kotlin + Jetpack Compose, Hilt for injection (`core/AppModule.kt`), dependency versions in `gradle/libs.versions.toml`. Packages are per feature; `core/` holds what they share.

- **No server changes (ADR-0002, ADR-0004).** The app works against the Troupe Server as shipped. A Paired Server is a URL plus an optional bearer token (`servers/PairedServerRepository.kt`), stored in DataStore and encrypted through the single `TokenCipher` (Tink). `core/net/TransportPolicy.kt` allows plain `http://` only for loopback, LAN, Tailscale, `.local` and `.ts.net` hosts.
- **Two WebSockets per Paired Server.** `/terminal/ws` (`terminal/net/`) carries Terminals; `/ws` (`chat/net/`) carries Agent Chats, watches and the store. Both are kept alive by `core/net/ConnectionLifecycle.kt`: backoff reconnect, heartbeat timeout, close code 1001 means the server is offline and `/health` is polled, a rejected token or protocol mismatch is final. Each successful handshake bumps `generation`; the server forgets attachments and subscriptions on reconnect, so anything subscribed must resubscribe when `generation` changes.
- **Protocol pinned to version 7 (ADR-0003).** Frames are hand-written kotlinx.serialization classes (`chat/protocol/`, `terminal/protocol/`, `core/protocol/`) mirroring the server's TypeScript types, checked against JSON fixtures in `app/src/test/resources/fixtures/`. The app refuses to connect to any other `protocolVersion`. Changing a model means updating its fixture test.
- **Live state over `/ws`.** `core/store/WatchHub.kt` multiplexes the seven watches (`projects`, `chat_history`, `agents`, `chat_activity`, `fs`, `terminals`, `store`), ref-counted per `(watch, scope)`, and resyncs on any `(hostEpoch, n)` gap. `core/store/WebStoreClient.kt` syncs the server's WebStore; key names are in `core/store/PersistenceKeys.kt`. Keys under `shared/` are shared with desktop and browser; phone layout and drafts go under `device/<deviceId>/` so the phone never overwrites desktop layouts.
- **One session manager drives the UI.** `workspace/WorkspaceSessionManager.kt` is the singleton that owns `WorkspaceShellState` for the active Paired Server (projects, sessions, terminals, attention, workspace tabs). `core/nav/TroupeNavHost.kt` boots into the connect screen when nothing is paired, otherwise into `shell/MobileWorkspaceShellScreen.kt`, which mirrors Troupe's Mobile Workspace Shell V2.
- **Automations are monitored and controlled from the phone (`automations/`).** `protocol/` holds hand-written models mirroring the web contract (sealed `Schedule`, `Trigger` and `NodeSpec` with `@SerialName` kinds), pinned by fixtures under `app/src/test/resources/fixtures/automations/` that are copied from the web repo; refresh the fixtures from there, not by hand. `net/AutomationsApi` talks to the server's REST endpoints. `state/` turns the `agents` watch's events into `HostQuery`-based queries (list, detail, runs, steps) that refetch on change, with `AutomationsController` exposing state and the enable, Run, cancel and approve/reject actions to `ui/AutomationsViewModel`, and the Compose screens (list, detail, run detail, approval) in `ui/`.
- **Terminals render in xterm.js (ADR-0001).** A WebView loads `app/src/main/assets/terminal/` behind the `TerminalRenderer` interface (`terminal/render/`). The JavaScript bridge and the serialization models need the keep rules in `app/proguard-rules.pro`, because release builds run R8.
- **Tests use the real object graph.** `app/src/test/.../testing/TestGraph.kt` wires the same objects Hilt does against scripted fakes (`FakeTroupeServer`, `FakeChatServer`), with on-disk DataStore and a deterministic cipher; connection lifecycles take a test dispatcher for virtual time. Tests under `integration/` are excluded from `testDebugUnitTest` and run only through `:app:integrationTest`.

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues of `naddrzz/troupe-android`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
