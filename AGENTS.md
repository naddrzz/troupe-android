# Troupe Android

Native Android (Kotlin, Jetpack Compose) client for a Troupe Server (formerly termul-server). Product decisions: `docs/superpowers/specs/`; vocabulary: `GLOSSARY.md`; architectural decisions: `docs/adr/`.

## Build, test & CI policy

- **Run builds, tests, lint, and debug APK generation in GitHub Actions CI only** (`.github/workflows/build.yml`). Do **not** run local Gradle builds or tests (`./gradlew ...`) in agent sessions — cloud VMs are slower, hit Maven Central rate limits, and duplicate CI work.
- Every push and pull request triggers the `Build` workflow in GitHub Actions, which runs:
  - `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug`
  - Uploads the signed debug APK as workflow artifact **`troupe-android-debug-apk`** (`app-debug.apk`, 14-day retention).
  - Uploads HTML test and lint reports as **`test-and-lint-reports`** (7-day retention, even on failure).
- To verify changes after pushing to a feature branch, inspect the workflow via `gh`:
  - `gh run list -R naddrzz/troupe-android --branch <branch> --limit 5`
  - `gh run watch <run-id> -R naddrzz/troupe-android --exit-status`
  - `gh run view <run-id> -R naddrzz/troupe-android --log-failed`
- Fix CI failures by pushing follow-up commits; do not force-push merely to retrigger CI.

## Branch & PR policy

- Target all feature/fix PRs to **`dev`** (not `main`). `main` is reserved for releases tagged `v*`.
- Never use `"Termul"` in user-facing UI strings (`app/src/main/res/values/strings.xml`); follow canonical terms in `GLOSSARY.md`.

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues of `naddrzz/troupe-android`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
