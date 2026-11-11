# The reset, recorded

Date: 2026-11-10. Direction change: from a five-account management client to a shell for the site.

## What the repository looked like before

Checked directly, not from notes:

| Fact | Value |
| --- | --- |
| Branch | `arena/0f930c82-arena-ai`, PR #1 (open) |
| Remote tip | `d971c0427a509aaa0aae097d8ba0eadede5f4aea` |
| Commit | *"refactor: delete two constructor parameters that nothing read"* |
| History | 19 commits from `main` (`f551748`, a one-line README) |
| Tracked files at that tip | 96 |
| Volume | ~16.1k added lines, ~10.6k of them Kotlin in `app/src/main` (49 files) |
| Tests | 199 JVM tests in 11 files; 3 instrumentation tests (compiled, never run) |
| CI state | green: unit tests, `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`; artifacts `arena-ai-debug.apk` (17.9 MB), `unit-test-results`, `lint-report` |
| Build config | AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.11.1 wrapper · JDK 17 · `compileSdk 35` · `minSdk 26` · `targetSdk 35` · `applicationId com.kartikkamboj.arena` · versionName 1.0.0 |
| Notable config choices | no release `signingConfig` (CI builds unsigned release / debug-signed debug); `lint.abortOnError = false`; `unitTests.isReturnDefaultValues = true`; no Gradle configuration cache; 25 dependency declarations (17 `implementation`, 2 test, 5 androidTest, 1 debug) |

**Backup branch:** `backup/pre-reset-2026-11-10` → `d971c04`, **pushed to `origin`** as well as kept
locally, so the restore point survives anything happening to a working copy. Nothing was rebased, squashed or
force-pushed, so every old commit is also still an ancestor of the new work: `git log` on this branch
shows both halves of the project's life, and `git show d971c04:app/src/main/java/...` recovers any
individual old file.

## Deleted, not left dead

Removed outright, together with the tests and docs that only described them:

- five-slot account model, account switching/rotation, `AccountRepository`, `AccountPolicy`,
  `SessionStateMachine`, `AuthProbe`, per-slot status pills;
- session isolation: `SessionIsolation`, `WebViewSessionIsolation`, `CookieJarCodec`, `BlobCipher`,
  `EncryptedBlobStore` (Keystore-encrypted cookie capture — no longer applicable, and its absence is
  the point: the app never touches the jar);
- the project workspace: `Project`, `ProjectPolicy`, `ProjectRepository`, `ProjectsScreen`, thread
  filing in `HistoryRepository`;
- native draft management (`ComposerScript`, the draft map, the draft bar) and the rate-limit card with
  account alternatives (`RateLimitMonitor`);
- the native catalogue/mirror layer: `PageExtractor`, `ModelRepository`, `HistoryRepository`,
  `MarkdownParser`/`MarkdownText`, `JsonFileStore`, `SettingsRepository`/`SettingsSource`;
- screens and chrome: `AccountsScreen`, `ChatScreen`, `HistoryScreen`, `ModelsScreen`,
  `ProjectsScreen`, `SettingsScreen`, the navigation routes in `ArenaApp`, `ArenaComponents`,
  `AppContainer`'s repository graph, `Diagnostics`;
- resources nothing references: `xml/backup_rules.xml`, `xml/data_extraction_rules.xml`,
  `xml/locales_config.xml`, `docs/assets/app-icon-source.png`;
- dependencies that only served the deleted layers: `navigation-compose` (six routes to no longer
  navigate), `kotlinx-serialization-json` and its compiler plugin (nothing is serialised any more),
  `material-icons-extended` (the shell needs two glyphs, which `material-icons-core` already brings),
  `appcompat`, `androidx-core-ktx`, `lifecycle-process`, `lifecycle-runtime-ktx`, `espresso-core`,
  `kotlinx-coroutines-test`. `androidx.webkit` stayed — see below.

Nothing removed is referenced by anything that stayed. That is checked by the compiler on every push,
not by grep: an unused import or an unresolved reference in the shipped code turns CI red.

## Preserved on purpose

- the Gradle build: wrapper 8.11.1, `settings.gradle.kts`, `gradle.properties`, version catalog,
  `app/build.gradle.kts` structure (SDK levels, JDK 17, lint and unit-test options, no release
  signing), `app/proguard-rules.pro` (rewritten: no serializer keeps, only the WebView callback keeps);
- `.github/workflows/android.yml`, unchanged as a pipeline: license acceptance, workflow-syntax check,
  a secrets-in-the-tree refusal, read-only Gradle cache, unit tests → assemble+lint with `ci_explain.py`
  diagnostics, failure reports posted to the PR, a green run resolving a stale report, three uploaded
  artifacts, a test-count summary. It is the working cloud build the phone-only workflow depends on;
  artifact names unchanged (`arena-ai-debug.apk`, `unit-test-results`, `lint-report`) so any link to an
  earlier build still resolves;
- `tools/ci_explain.py`, `LICENSE` (MIT), `.gitignore`, the adaptive launcher icons (recoloured, still
  our own geometry), `res/xml/network_security_config.xml` (HTTPS-only, system trust anchors, user CAs
  in debug builds only), `core/AppLog.kt` (redaction is still needed: the platform hands error
  descriptions and URLs to the log), `core/NetworkMonitor.kt`;
- all Git history.

`androidx.webkit` survived for one call — `WebSettingsCompat.setAlgorithmicDarkeningAllowed`, the
supported way for web content to follow the app's theme — which is worth a dependency where the old
private `forceDark` and `ProfileStore` usage were not. `androidx.browser` survived for Custom Tabs, and
`androidx.core:core-splashscreen` was added so the splash is the platform's rather than a hand-drawn
screen to keep in sync.

## Documentation

Every file in `README.md` and `docs/` was rewritten for the new app. `docs/ACCOUNTS.md` and
`docs/LOW_RAM.md` were deleted rather than edited; their surviving subject matter moved to
`docs/BACKGROUND.md` and `docs/PERFORMANCE.md`.
