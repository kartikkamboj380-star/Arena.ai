# Testing

Honest taxonomy first, because "tests pass" means different things depending on which of these you are
reading:

| Layer | What it proves | Where it runs | Status |
| --- | --- | --- | --- |
| **JVM unit tests** (`app/src/test`) | the rules: state machine, slot policy, cookie codec, URL policy, probe classification, redaction, markdown, error mapping, encryption format, repository behaviour against fakes | `./gradlew :app:testDebugUnitTest`, and CI | **written; executed by CI** |
| **Build verification** | everything compiles, resources link, lint reports | `:app:assembleDebug`, `:app:lintDebug`, CI | **CI** |
| **Instrumentation compile** | the androidTest sources compile against the real framework | `:app:assembleDebugAndroidTest`, CI | **CI compiles it** |
| **Emulator run** | WebView lifecycle on a device: attach/detach, page load, back nav, file picker, renderer death | `connectedAndroidTest` | **not run in CI; needs a device — see §4** |
| **Real-service integration** | signing in to arena.ai, five live sessions, real rate limits | manual, on a phone | **not run — deliberately excluded from automation** |

**Nothing in this repository claims that on-device authentication against arena.ai has been tested.**
It has not been, and an automated suite must not do it (credentials, bot challenge, and traffic to a
service that has no API for it). §4 is the script for the human who wants to verify it.

## 1. Unit tests

```bash
./gradlew :app:testDebugUnitTest            # all
./gradlew :app:testDebugUnitTest --tests '*SessionStateMachineTest*'
open app/build/reports/tests/testDebugUnitTest/index.html
```

`app/src/test` holds 17 test classes across 12 files — 199 `@Test` methods, all of them pure JVM, no
Robolectric, no emulator. Grouped by what they pin down:

| Test class | The claim it protects |
| --- | --- |
| `domain/SessionStateMachineTest` | `Loaded ≠ Connected`; only a positive probe yields `CONNECTED`; `ProbeAnonymous` + a stored jar is **expiry**, not "logged out"; 401/403 expire, 429 does not; a transport failure never clears a jar; cold start never upgrades a stored jar; `bootStatus` can only say "not verified yet" |
| `domain/AccountPolicyTest` | five slots, refuse a sixth rather than evict; stable slot numbering; label validation (length + `< >` newline tab); removing the selected slot **needs user choice**; first add may auto-select, second may not; only verified slots are "usable" |
| `domain/CookieJarCodecTest` | only `name=value` pairs are parseable, attribute names are skipped; values with `;` `,` quotes backslash CR LF are refused; credential-shaped names are never stored; caps (64 entries / 4096 value / 32 000 total); re-injection mints no `Expires`; summaries are `name(length)` only; `overlap()` proves jars are not interchangeable |
| `web/AuthProbeTest` | an answer from off `arena.ai` is discarded rather than believed; an ambiguous surface ⇒ `UNKNOWN`; `ok:false`/non-JSON ⇒ `UNKNOWN`; the probe script contains no `fetch`/`XHR`/`submit`/`click`/`setItem` |
| `domain/ParsingAndErrorsTest` | markdown blocks/inline styles/links, `javascript:` targets dropped, clipping; `ErrorMapper` tables; **every `ErrorKind` has non-empty, jargon-free copy**; `AppLog.redact` masks cookies/bearer/JWT/e-mail/quoted secrets *and leaves ordinary text alone* |
| `web/NavigationPolicyTest` | HTTPS-only for `arena.ai`; `http://arena.ai` upgraded, `http://evil` blocked; identity providers handed off (never blocked); `javascript:`/`file:`/`content:`/`intent:`/`data:` blocked; `about:blank` allowed (window.open); garbage blocked, not guessed |
| `web/ArenaUrlsTest` (in the same file) | relative/protocol-relative resolution stays on-origin; assets, `_next`, `cdn-cgi` and public chrome are not "content" pages |
| `web/PageExtractorTest` | an unparseable page yields **nothing**, not a guess; `name · vendor` rows become informational entries with a stable slug id; nav labels never become models |
| `web/RateLimitMonitorTest` | a limit belongs to one slot; 429 outranks a page notice and is never downgraded; alternatives are only the user's own verified slots; `automaticallyRotatesAccounts == false`; banner copy never invents a reset time or a quota number |
| `data/AccountRepositoryTest` | create/select/logout/remove/notes round-tripped through a **real** JSON file store, with a fake isolation layer that *throws* if a capture crosses slots |
| `data/HistoryRepositoryTest` + `data/ModelRepositoryTest` (one file) | ledger dedupe by url — and by mode when no url ever settled, so a half-loaded page is not lost — pin ordering holds the moment a pin is set rather than at the next open, cap, import refreshes and re-attributes a row instead of duplicating it; model census demotes missing rows to `UNAVAILABLE` instead of deleting them, never treats an empty read as a census, clears the selection when the selected row is removed |
| `domain/ProjectPolicyTest` | the continuity promises as arithmetic: a project exists only if named; a duplicate name returns the existing project instead of splitting the workspace; the ceiling is refused, never evicted; **an account switch changes the annotation and nothing else** (id, name, instructions, files, model and the linked conversation are asserted equal); context depends on which account is looking; a slug is read from the url or not at all; file lists are capped/trimmed/deduped; deleting the active project does not pick a replacement behind the user's back |
| `data/ProjectRepositoryTest` | round-trip through a real JSON file store: creation is explicit, a rejected name leaves the registry untouched, selecting an unknown id is reported, `onAccountChanged` cannot rewrite a project, a restart restores identity + linked conversation + slot, deleting unfiles history rows instead of destroying them, a corrupt store yields an empty workspace rather than a crash |
| `web/NavigationPolicyTest` (sign-in cases) | while a sign-in is in flight the provider stays in the slot's jar; the flag widens nothing else — a suffix-trick host, `javascript:`, and cleartext to the provider are all still refused |
| `data/SecureBlobStoreTest` | AES/GCM round trip, fresh IV per write, tamper/truncation/foreign-key rejection, corrupt file deleted not trusted, path-traversal-safe keys, and the plaintext-never-on-disk check |

Design note: the fakes are strict on purpose. `TestDoubles.FakeIsolation.capture(slot)` fails the test
if `slot` is not the bound slot, which is the only way "cookies are never merged" can be a test rather
than a comment. `FakeClock` makes every TTL/expiry assertion deterministic.

## 2. Lint

```bash
./gradlew :app:lintDebug
open app/build/reports/lint-results-debug.html
```

`abortOnError = false` in `app/build.gradle.kts`, deliberately: lint findings on a Compose + WebView
app are advisory during construction, and a red CI must mean "your code does not work", not "a
disable-comment is missing". The report is uploaded by CI as `lint-report` so findings are still read,
and the ones that matter are addressed in code (`NetworkSecurityConfig`, `AllowBackup=false`,
`GifUsage`, `DuplicatePlatformClasses` are absent by construction).

## 3. CI

`.github/workflows/android.yml` on `ubuntu-latest` (which ships a JDK and an Android SDK; the pinned
SDK packages are installed by `android-actions/setup-android@v3`):

1. parse the workflow YAML — a malformed workflow cannot report a fake green;
2. refuse to run if the tree tracks a keystore/`.env`/`local.properties` or contains key-shaped
   literals;
3. `:app:testDebugUnitTest`;
4. `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --continue`;
5. print the APK identity with `aapt dump badging` (package, version, label — the thing that proves the
   artifact is the app and not a placeholder);
6. upload `arena-ai-debug.apk`, plus `unit-test-results` and `lint-report`;
7. write the parsed test counts into the run summary.

No secrets are read, echoed or needed; the job has `permissions: contents: read` only. A green run
means: it compiles, the unit tests pass, lint ran, an APK exists. It does **not** mean the auth flow
was exercised — see §4.

```bash
gh run list --workflow 'Android CI'
gh run view <id> --log-failed
gh run download <id> -n arena-ai-debug.apk
```

## 4. Emulator and device checks that CI does not perform

Run these before believing a release is usable. `./gradlew :app:connectedDebugAndroidTest` needs a
device (emulator images are far too large for this repo's CI budget, and a headless emulator is a poor
test of WebView sign-in anyway).

`WebViewLifecycleTest` (androidTest) covers what the JVM cannot: constructing a `WebView` with the
app's settings, installing `ArenaWebViewClient`/`ArenaWebChromeClient` against a recording
`SessionListener`, and destroying it.

### Manual: startup, navigation, accounts

1. Install the debug APK. Cold start must land on **Chat** with the site's own page loading, the
   bottom bar showing five destinations, and the account chip reading `Account 1 · Not connected`.
   Nothing may claim "Connected" before a probe.
2. Tap each destination and back. Returning to Chat must show the **same** loaded page — no reload, no
   lost scroll position, no blank frame. This is the single most important UX assertion in the app.
3. **Accounts → Add** five times: slot labels `Account 1…5` appear in order. A sixth add must be
   *refused* with a message, not replace anything.
4. Select slot 2, then remove slot 2. The app must ask which slot to use next — never silently pick.
5. Rename a slot, add a note, set an e-mail hint, force-stop, relaunch: all three survive; statuses
   must come back as `Not connected` / `Session expired`, never `Connected` (unless a probe re-earned
   it).

### Manual: chat, models, history

6. Type in Arena's composer; the header's mode chip must match the site's mode; switching mode reloads
   the right page without discarding the draft.
7. *Stop* must halt local loading (`WebView.stopLoading()`); the copy must say the site may keep
   generating, because that is what is actually true.
8. Long-press a reply → copy; paste elsewhere and confirm the markdown survived as text, code blocks
   included.
9. **Models → Refresh from the leaderboard** with a page on screen: rows appear with names *read from
   the page*. Kill the network and repeat: it must say it could not read the page — **not** show an
   empty list, and **not** mark every model unavailable.
10. Select a model, then open Chat: the header shows the label, and the row explains the selection is
    applied in Arena's composer (there is no API for the app to toggle it). Nothing may switch models
    by itself.
11. **History → Import visible**: with `arena.ai/history/search` open, the titles on screen become
    rows for *that slot*. With nothing open, the button must say so.
12. Airplane mode with a loaded page: `No connection`, and your typed draft is still in the box.

### Manual: authentication

13. Slot 1 → *Log in*: complete Arena's **e-mail** sign-in inside the page. Status must become
    `Connected` only after the probe sees signed-in chrome, and `observedLabel` may fill in.
14. Try **Sign in with Google** *inside* the app: while the sign-in window is open the provider must
    stay in the WebView and the account must become `Connected` without leaving. If Google refuses the
    embedded view, expect the provider's own refusal page, then use **Use browser** — the banner must
    say the browser session cannot be imported. Either way the status may only change after the probe
    agrees; a tap on *Log in* alone must never produce `Connected`.
14b. Regression check for the original bug: on a fresh install with cookies allowed, sign in and
    confirm the app does **not** land back on the logged-out homepage. `SessionCoordinator` returns the
    view to `ArenaUrls.CHAT` on a verified sign-in, so the entry point is where the user should end up.
15. Sign in as a second account in slot 2. Slot 1 must still read as its own identity; the jars must
    not share a value (Settings → Connection diagnostics shows each slot's cookie count).
16. Expire a session realistically: sign out of that account in a desktop browser session, or set
    Settings → *Local session retention* to 1 h and wait. Expect `Session expired` + a reconnect prompt,
    the conversation intact, and no automatic credential replay.
17. Trigger a rate limit only if the service does so naturally. Expect a banner naming **this** account
    and model, an offer to switch by hand, and no reset time invented.

### Manual: WebView surface

18. Back navigation: system back steps through the page history, and exits the app at the first page.
19. Tap a link to `example.com`: it opens in the browser, the session page stays put.
20. A file input on the page: the system document picker appears; cancelling it must not hang the page
    (the callback is answered with `null`).
21. A download link: notification appears, file lands in the app's external files dir, and the app
    never opens it itself.
22. Rotate, background/foreground, and pull-to-refresh: no reload storm, no lost session, no white
    screen. Force a renderer kill (`adb shell kill` the sandboxed process) and expect
    `Web view restarted` with a fresh view for the slot.
23. Memory pressure: `adb shell am trim-memory <pid> COMPLETE` while a page is open, then return to
    Chat. Expect the same page reloaded from the remembered URL, the session still `Connected`, and
    the stored-cookie count unchanged — releasing the WebView must never cost a cookie. See
    [LOW_RAM.md](LOW_RAM.md) §4.
24. Popup handling: tap a sign-in control that calls `window.open()`. The flow must continue in the
    same view (no second WebView exists), and a popup whose target cannot be resolved must show the
    explanation notice instead of doing nothing.
25. Project continuity, the whole point of the workspace: open a project, type instructions, add a
    file, link the current page, then switch to another account slot. The project must still be active
    with the same name, instructions, files and linked conversation; the strip should now say
    *"conversation belongs to Account N"* rather than pretending it moved. Switch back and it must say
    *"continues here"* again. Deleting the project must leave the history rows in place, unfiled.

## 5. Not covered, and what it would take

- **Concurrency of the five slots.** Temporal isolation means only one slot is live; a test that
  asserted "two slots loaded at once keep two jars" would assert a capability the platform does not
  give (see [ACCOUNTS.md](ACCOUNTS.md) §2).
- **Robolectric coverage of `WebViewSessionIsolation`** — the swap itself, against a real
  `CookieManager`. Deliberately left out: Robolectric's cookie jar is not the Chromium one, so a green
  result would prove less than the strict fake does.
- **Compose UI tests** for each screen. The screens are thin projections of flows the unit tests
  already pin down; adding `compose-ui-test` without an emulator run in CI would be a dependency
  without a signal. If you add a device matrix to CI, `ChatScreenTest` (attach/detach keeps the view)
  is the first one worth writing.
- **Long-conversation performance** on low-end hardware, and TalkBack pass.
