# Troupe for Android

Troupe is a native Android client (Kotlin, Jetpack Compose) for a Troupe Server, the program that runs coding agents and terminals on your laptop, desktop or VPS (formerly termul-server). You pair the app with a server by its address and token, then browse its Projects; Terminals and Agent Chats follow. Product decisions live in the spec (GitHub issue #1 and [`docs/superpowers/specs/`](docs/superpowers/specs/)), vocabulary in [`GLOSSARY.md`](GLOSSARY.md), and architectural decisions in [`docs/adr/`](docs/adr/).

## Build and test

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Unit tests run against a scripted fake Troupe Server (`app/src/test/java/dev/troupe/android/testing/FakeTroupeServer.kt`).

The integration test runs against a real Troupe Server in Docker and skips when that server is not running:

```powershell
.\scripts\test-server.ps1 up        # termul-server on http://127.0.0.1:18080 with a fixed test token
.\gradlew.bat :app:integrationTest
.\scripts\test-server.ps1 reverse   # optional: lets the emulator reach it at http://127.0.0.1:18080
.\scripts\test-server.ps1 down
```

## License

MIT, see [`LICENSE`](LICENSE).

## Third-party assets

Each license below was read before the asset was bundled. Full texts are in [`licenses/`](licenses/).

| Asset | Used for | License | Decision |
| --- | --- | --- | --- |
| [Inter](https://github.com/rsms/inter) variable font (`Inter[opsz,wght].ttf` from google/fonts) | UI text | SIL Open Font License 1.1 ([`licenses/OFL-Inter.txt`](licenses/OFL-Inter.txt)) | Bundled as `app/src/main/res/font/inter_variable.ttf`. |
| [Paper Mono](https://github.com/paper-design/paper-mono) | Terminal text | SIL Open Font License 1.1 (read from termul's `src/renderer/assets/fonts/paper-mono/OFL.txt`) | Redistribution allowed, so Paper Mono is the terminal font (not JetBrains Mono). It ships with the terminal renderer in a later slice; nothing is bundled yet. |
| [Hugeicons free set](https://hugeicons.com) (`@hugeicons/core-free-icons` 4.4.0) | Icons | MIT ([`licenses/MIT-Hugeicons.md`](licenses/MIT-Hugeicons.md)) | Usable in an Android app, so Hugeicons is the icon set (not Material Symbols). Icons are converted by hand to vector drawables (`app/src/main/res/drawable/ic_*.xml`). |
