# Arena AI

**AI Chat Experience** — a native Android client for [arena.ai](https://arena.ai), written in Kotlin
with Jetpack Compose, Material 3 and a lifecycle-managed `WebView`.

> **Independent client.** This app is not Arena's official app, is not affiliated with, endorsed by or
> audited by LMArena/Arena, and does not use any private API. It is a native shell that drives the real
> website on your behalf. Its icon and name are its own, and the app says so on screen (Settings →
> About) as well as here.

## Why this exists, and what it is honestly made of

Arena exposes **no public API** — no OAuth client registration, no token endpoint, no chat endpoint.
So "convert arena.ai into a real native Android client" cannot mean "call their API", and it must not
mean "fake a login screen". It means:

- a real Compose app with five **separate account slots**, session state, storage and diagnostics;
- the actual authenticated web session inside a `WebView` the app owns and hardens;
- a **state machine** that only reports `Connected` after *observing* a signed-in page, and never
  reports a quota number or a reset time it was not given;
- everything else a phone client should add: markdown rendering of local notes, an offline-tolerant
  status surface, per-slot history ledger, Keystore-encrypted session blobs, and no telemetry.

If a documented Arena API ever appears, `web/` is the seam that swaps out; the domain layer
(`domain/`) has no WebView in it at all.

| Document | Read it for |
| --- | --- |
| [docs/AUTHENTICATION.md](docs/AUTHENTICATION.md) | which sign-in methods are supported, which were rejected and why, expiry/re-auth |
| [docs/ACCOUNTS.md](docs/ACCOUNTS.md) | five slots, temporal isolation, what the platform cannot do, residue it cannot remove |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | package map, why there are no ViewModels, data flow for one prompt, why projects own continuity instead of accounts |
| [docs/SECURITY.md](docs/SECURITY.md) | HTTPS-only, Keystore, redaction, the origin boundary, known limits |
| [docs/TESTING.md](docs/TESTING.md) | 199 unit tests, what CI runs, and the device checklist CI deliberately does not run |
| [docs/LOW_RAM.md](docs/LOW_RAM.md) | the 2 GB design: one WebView, temporal isolation, every cap, what memory pressure releases, and what is deliberately *not* done |

## Screens

**Chat** — Arena's page in the app's own hardened WebView: mode switcher (Auto, Battle, Side by Side,
Direct, Agent, WebDev — the site's real selector), selected-account chip, selected-model label,
connection state, progress, error banners, scroll-to-bottom, stop-loading, copy-selection, stop-and-
reload, a draft bar that can place text into the page's composer without ever submitting it,
pull-to-refresh, keyboard-aware layout, and an import button for the thread you are looking at.

**Projects** — the workspace layer: a named project ("Android game development", "Q3 report") that
holds the standing instructions, the file index, the model you pinned, the local thread rows filed
under it, and the pointer back to the Arena conversation it continues. Switching account slots never
starts a new project, never resets one, and never files your work under the new account — the only
thing a switch may change is an annotation, and that rule is asserted in
[tests](app/src/test/java/com/kartikkamboj/arena/domain/ProjectPolicyTest.kt). Where the remote
conversation is reachable from the account on screen, the strip says *"continues here"*; where it is
not, it says *"conversation belongs to Account 2"*, because Arena has no API to move a thread between
accounts and the app will not pretend otherwise.

**Models** — the catalog the app *observed*, never a hardcoded list: names and vendors read from the
live leaderboard page plus rows you pinned yourself, each with an availability state
(`Automatic`, `Selectable in Arena`, `Informational`, `Stale`), a rank, and the last refresh time.
Selecting a model records your intent and shows it in the chat header; the app cannot toggle the
site's picker, and says so instead of pretending.

**History** — a local ledger of threads this client opened or imported, per slot, with notes and pins.
It is *not* a mirror of Arena's transcripts: there is no API to read them, and the row that explains
this is in the screen, not buried in a doc.

**Accounts** — the five slots, numbered `Account 1…5`, each with its label, the account name the probe
could read from the page, status (`Not connected`, `Connecting`, `Connected`, `Login required`,
`Session expired`, `Rate limited`, `Connection error`), last-active time, stored-cookie count, and
`Log in` / `Log out` / `Remove` / `Select` actions. The selected slot is marked. Adding a sixth is
refused, not accommodated.

**Settings** — theme, default mode, native-draft-bar toggle, screenshot protection, session retention
(TTL), wipe-site-storage-on-switch, erase-cookies-on-logout, verify-after-load, clear history, sign
out every slot, delete all app data, **connection diagnostics** (WebView provider/version, isolation
capability, per-slot status and jar size, key type, and what the app refuses to do), plus About and
links to Arena's terms and privacy pages.

## Build it

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35.0.0 (`ANDROID_HOME` or
`local.properties`), and the committed Gradle wrapper (8.11.1). No other setup, no plugins, no keys.

```bash
git clone https://github.com/kartikkamboj380-star/Arena.ai.git
cd Arena.ai
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 172 JVM tests
./gradlew :app:lintDebug              # report in app/build/reports/
./gradlew :app:installDebug           # to a connected device
```

`local.properties` (never committed) is just:

```properties
sdk.dir=/path/to/Android/sdk
```

There is no signing config and no `API_KEY` anywhere: the debug APK is signed with the standard debug
key by the Gradle plugin, and `assembleRelease` intentionally produces an **unsigned** minified APK so
nobody can mistake a CI artifact for an official release. To ship one, add your own
`signingConfig` and a real keystore outside the repository.

### Getting the APK without building

Every push and pull request runs [`.github/workflows/android.yml`](.github/workflows/android.yml),
which tests, builds and uploads the artifact. Download it from the run page:

1. Actions → **Android CI** → pick a run on `main`;
2. **Artifacts** → `arena-ai-debug.apk`;
3. `adb install -r arena-ai-debug.apk` (or transfer it to the phone).

The artifact exists only for runs that actually turned green — the workflow never uploads on a failed
build, and no download link in this README points at anything that might not be there. Release
assets are deliberately not published: an unsigned or debug-signed build does not belong on a
Releases page.

## Status of this branch

The repository was greenfield apart from a one-line README. Current state: full Kotlin source (main +
Compose UI), 199 JVM unit tests in 12 files (17 test classes), one instrumentation test class, resources, Gradle wrapper and CI.
**Compile-and-test verification happens in CI**: the sandbox this was written in has no JDK or Android
SDK, and the required hosts (`dl.google.com`, `services.gradle.org`, `repo.maven.apache.org`) are not
reachable from it, so nothing here was compiled locally. Whatever CI says is the real answer, and if a
check fails it gets fixed rather than papered over — see the latest run:
[![Android CI](https://github.com/kartikkamboj380-star/Arena.ai/actions/workflows/android.yml/badge.svg)](https://github.com/kartikkamboj380-star/Arena.ai/actions/workflows/android.yml)

The interface is deliberately not a blue-gradient AI template: obsidian and graphite surfaces, one
emerald accent for what the app is doing, warm amber for what needs you, monospace micro-labels,
1 dp hairlines, no blurred ambient layer and no gradient fills — each of which was also a per-frame
render cost. See [docs/LOW_RAM.md](docs/LOW_RAM.md).

On-device behaviour (auth, five live sessions, rate limits, WebView edge cases) requires a phone and a
real Arena account. The checklist is [docs/TESTING.md](docs/TESTING.md) §4. **It has not been executed
by the author of this client**, and no test in this repo signs in to arena.ai automatically.

## Known limitations (the honest list)

1. **No API, so no offline chat.** A prompt is only answered by the live site. Airplane mode gives you
   local history, notes and drafts — not answers.
2. **Five slots are not five sessions at once.** Android gives no API to bind a `WebView` to a named
   profile (`WebViewCompat.getProfile` is read-only; `MULTI_PROFILE` requires opt-in multi-process), so
   isolation is **temporal**: one slot owns the shared cookie jar at a time, with an encrypted snapshot
   swap. Details, and the residue it cannot remove: [docs/ACCOUNTS.md](docs/ACCOUNTS.md) §2 and §4.
3. **Google sign-in may still leave the app.** While a sign-in is in flight the identity provider is
   kept *inside* the slot's WebView (third-party cookies are enabled for that view, which is what the
   redirect chain needs), because a session created in the system browser belongs to the browser and
   can never be installed into this slot. If the provider refuses an embedded view anyway, the app
   hands off to your browser and says plainly that the result cannot come back. There is no user-agent
   spoofing. **This is the fix for "sign in redirects to the homepage"; it is reasoned from the code
   and the platform's rules, and it has not been verified on a device** — see
   [docs/AUTHENTICATION.md](docs/AUTHENTICATION.md) §2.
4. **Model selection is advisory.** The app can record and display your choice; only the site's own
   composer can apply it. There is no "the app switched model for you", on purpose.
5. **Session expiry is approximated.** `CookieManager` does not expose `Expires`/`Max-Age`, so expiry is
   judged by a locally configured TTL plus what the page actually says (401/403, or a logged-out
   surface). 429 responses are read from status codes; `Retry-After` is not observable, so the app
   never shows a reset time.
6. **Quota is never multiplied.** Five profiles do not mean five times the quota, and the app will not
   rotate accounts to dodge a limit — `automaticallyRotatesAccounts` is `false` and tested. When a slot
   is limited, the card names *this* account, lists only the other slots this app has verified as
   connected, and leaves the choice to a tap; your project and your unsent draft are untouched by the
   switch (the draft lives in memory, on purpose — see [docs/LOW_RAM.md](docs/LOW_RAM.md)).
7. **DOM coupling.** Signed-in detection depends on Arena's current markup (a "Log In" affordance when
   signed out). A redesign can degrade status to "unknown" — the app holds the last status instead of
   guessing, and Reports it as unknown on the diagnostics screen.
8. **Local history only.** Threads are pointers this client saw; a conversation started in your desktop
   browser appears only after you press *Import visible* while on Arena's own history page.
9. **No attachments from the app.** Uploads work through the page's own file input, using the system
   document picker (images and documents; no camera capture, no arbitrary path exposure).
10. **Markdown rendering is for local text.** Answers render on the site; the app's own renderer is used
    for notes, imports and copied text.
11. **Not compiled in this sandbox.** See §"Status of this branch" — CI is the first real build.

## Respectful use

This client automates nothing on the site beyond showing you the page, adds no load the user did not
ask for (no background polling, no proactive re-probes while backgrounded), and honours the service's
decisions: rate limits stop the account that hit them until *you* act. Using five identities to spread
abuse would breach Arena's terms; that is why the code cannot do it.

## License and trademarks

Source in this repository: MIT ([LICENSE](LICENSE)). "Arena", "LMArena" and the arena.ai artwork and marks belong to their owner and are used
here only to name the service this client connects to. Nothing in this repository may be presented as
an official Arena product.

## Security disclosures

Found a way to make this app leak a session, render a cleartext page, execute a `javascript:` URL,
accept a bad certificate, or report a status it did not observe? Open an issue. The invariants that
would be broken are named in the test titles listed in [docs/TESTING.md](docs/TESTING.md) §1.
