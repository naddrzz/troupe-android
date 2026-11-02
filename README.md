# Troupe for Android

Troupe is a native Android client (Kotlin, Jetpack Compose) for a Troupe Server, the program that runs coding agents and terminals on your laptop, desktop or VPS (formerly termul-server). You pair the app with a server by its address and token, then browse its Projects; Terminals and Agent Chats follow. Product decisions live in the spec (GitHub issue #1 and [`docs/superpowers/specs/`](docs/superpowers/specs/)), vocabulary in [`GLOSSARY.md`](GLOSSARY.md), and architectural decisions in [`docs/adr/`](docs/adr/).

## Build and test

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Unit tests run against a scripted fake Troupe Server (`app/src/test/java/dev/troupe/android/testing/FakeTroupeServer.kt`).

The xterm.js renderer has an instrumented test that runs on a device or emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

The integration test runs against a real Troupe Server in Docker and skips when that server is not running:

```powershell
.\scripts\test-server.ps1 up        # termul-server on http://127.0.0.1:18080 with a fixed test token
.\gradlew.bat :app:integrationTest
.\scripts\test-server.ps1 reverse   # optional: lets the emulator reach it at http://127.0.0.1:18080
.\scripts\test-server.ps1 down
```

## Releasing

Signed release APKs are published on [GitHub Releases](https://github.com/naddrzz/troupe-android/releases). The `Build` workflow (`.github/workflows/build.yml`) runs the unit tests and a debug build on every push and pull request. When a `v*` tag is pushed, it also builds the signed release APK and attaches it, with its R8 mapping file, to a GitHub Release for that tag.

The release build runs R8 (code and resource shrinking). Keep rules for the xterm.js JavaScript bridge and the kotlinx.serialization models are in `app/proguard-rules.pro`.

### Version

- **versionName** is the tag on the built commit without its `v`: `v1.2.3` builds `1.2.3`. An untagged build is `0.0.0-dev`.
- **versionCode** is the number of commits in the history of the built commit (`git rev-list --count HEAD`). It grows with every commit, gives the same number locally and in CI, and does not depend on CI run numbers, which reset if the workflow is renamed or moved. Tag releases from the same line of history (`main`), so a later release always has more commits. CI checks out the full history for this.

### Create the keystore (once)

Make an upload keystore with `keytool` from the JDK, and keep it and its passwords outside the repository, in a password manager. Losing it means users cannot update to newer APKs.

```powershell
keytool -genkeypair -v -keystore troupe-release.jks -alias troupe -keyalg RSA -keysize 4096 -validity 10000
```

### Repository secrets

In the repository's **Settings > Secrets and variables > Actions**, add:

| Secret | Value |
| --- | --- |
| `TROUPE_KEYSTORE_BASE64` | The keystore file, Base64-encoded: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("troupe-release.jks"))` in PowerShell, or `base64 -w0 troupe-release.jks` on Linux. |
| `TROUPE_KEYSTORE_PASSWORD` | The keystore password. |
| `TROUPE_KEY_ALIAS` | The key alias (`troupe` above). |
| `TROUPE_KEY_PASSWORD` | The key password. |

### Sign locally

Gradle reads `TROUPE_KEYSTORE_FILE`, `TROUPE_KEYSTORE_PASSWORD`, `TROUPE_KEY_ALIAS` and `TROUPE_KEY_PASSWORD` from the environment, or else a `keystore.properties` file at the repository root (git-ignored, like `*.jks` and `*.keystore`):

```properties
storeFile=C:/path/to/troupe-release.jks
storePassword=...
keyAlias=troupe
keyPassword=...
```

Without them, `.\gradlew.bat assembleRelease` still works and builds `app-release-unsigned.apk`.

### Cut a release

```powershell
git checkout main
git pull
git tag v1.2.3
git push origin v1.2.3
```

The workflow then publishes `troupe-1.2.3.apk` on the `v1.2.3` release with generated notes. If a signing secret is missing, the release job fails instead of publishing an unsigned APK.

## License

MIT, see [`LICENSE`](LICENSE).

## Third-party assets

Each license below was read before the asset was bundled. Full texts are in [`licenses/`](licenses/).

| Asset | Used for | License | Decision |
| --- | --- | --- | --- |
| [Inter](https://github.com/rsms/inter) variable font (`Inter[opsz,wght].ttf` from google/fonts) | UI text | SIL Open Font License 1.1 ([`licenses/OFL-Inter.txt`](licenses/OFL-Inter.txt)) | Bundled as `app/src/main/res/font/inter_variable.ttf`. |
| [Paper Mono](https://github.com/paper-design/paper-mono) (`PaperMono[wght].woff2`, from termul's `src/renderer/assets/fonts/paper-mono/`) | Terminal text | SIL Open Font License 1.1 ([`licenses/OFL-PaperMono.txt`](licenses/OFL-PaperMono.txt)) | Redistribution allowed, so Paper Mono is the terminal font (not JetBrains Mono). Bundled as `app/src/main/assets/terminal/PaperMono.woff2` and loaded by the terminal page with `@font-face`. |
| [xterm.js](https://github.com/xtermjs/xterm.js) `@xterm/xterm` 6.0.0 (`lib/xterm.js`, `css/xterm.css`) | Terminal rendering (ADR-0001) | MIT ([`licenses/MIT-xterm.txt`](licenses/MIT-xterm.txt)) | Built files from the npm package, bundled unchanged in `app/src/main/assets/terminal/`. |
| `@xterm/addon-fit` 0.11.0 (`lib/addon-fit.js`) | Fitting the terminal to the screen | MIT ([`licenses/MIT-xterm.txt`](licenses/MIT-xterm.txt)) | Built file from the npm package, bundled unchanged in `app/src/main/assets/terminal/`. |
| [Hugeicons free set](https://hugeicons.com) (`@hugeicons/core-free-icons` 4.4.0) | Icons | MIT ([`licenses/MIT-Hugeicons.md`](licenses/MIT-Hugeicons.md)) | Usable in an Android app, so Hugeicons is the icon set (not Material Symbols). Icons are converted by hand to vector drawables (`app/src/main/res/drawable/ic_*.xml`). |
