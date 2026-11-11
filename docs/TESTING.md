# Testing

Three statuses are kept apart on purpose. **Executed** means a machine ran it and it passed. **Compiled**
means the code builds and nothing more. **Not performed** means nobody has done it. The badge on the
README covers only the first group.

## 1. What CI runs

`.github/workflows/android.yml`, on every push to `main`, every pull request and on demand:

| Step | What it proves |
| --- | --- |
| Validate workflow syntax | the pipeline itself parses (a broken YAML cannot silently stop testing things) |
| Refuse to build with secrets in the tree | no keystore, `local.properties`, service credentials or key-shaped literal is committed |
| **Refuse the capabilities the docs deny** | the promises in README/docs are mechanical: no `addJavascriptInterface`, no `evaluateJavascript`, no cookie read/clear, no `pauseTimers()`, no HTTP client, no telemetry, and **exactly one** `WebView` construction in `app/src/main` |
| Run JVM unit tests | `:app:testDebugUnitTest` — 75 tests, must pass |
| Assemble debug APK, instrumentation APK and lint | `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug` |
| Report the failure on the pull request | on red, `tools/ci_explain.py` posts a parsed diagnosis as a PR comment; on green, a stale failure report gets a resolution beside it |
| Artifacts | `arena-ai-debug.apk`, `unit-test-results`, `lint-report` |

Nothing in the pipeline is `continue-on-error`, and no step is `|| true` except the two that post
comments, where a failure to comment must not turn a green build red. `lint.abortOnError = false` is a
deliberate exception, recorded in `app/build.gradle.kts`: lint findings are published as an artifact
rather than promoted to correctness.

## 2. JVM unit tests — executed on every run

75 tests in 5 files. Each row is the promise the file pins, not a topic.

| File | Tests | Pins |
| --- | --- | --- |
| `web/NavigationPolicyTest.kt` | 23 | the trust boundary: the site's whole domain family renders (including `auth.arena.ai`), provider endpoints render, the provider's other domains render **only** mid-chain, everyone else leaves, a lookalike host is refused, `javascript:`/`data:`/`file:`/`intent:` are refused; `javascript:`/`data:`/`file:`/`intent:` are refused; cleartext for the site is upgraded, never downgraded; relative refs resolve like a browser; a space in a path is not a refusal; every decision has wording attached |
| `web/BrowserReducerTest.kt` | 24 | the claims the shell may make: background and foreground change **nothing** (no reload path exists); only `PageFinished` sets `firstPaint`; progress clamps and never regresses; a network loss keeps a loaded page; a failed load auto-retries exactly once when connectivity returns; a failure over a usable page is a card, not a blank screen; a dead renderer recovers once and then asks; a provider's 4xx is a refusal and not a success; dismissing a message does not mark a never-painted page ready; the retry button is gated on the failure type; a provider hand-off is remembered and ends on any navigation; the acknowledgement states the platform limit and claims nothing about a session; the provider strip says only what a navigation can prove; `PagePhase` has no value that could report a login; a popup opens and closes as a window and its events cannot rewrite the address, the phase, a problem panel or a notice; a late popup callback cannot reopen a closed window; what the sheet says about a window names the host and promises nothing |
| `web/ArenaSiteTest.kt` | 16 | origin recognition over the whole domain family and the provider's domains, with the suffix traps (`arena.ai.evil.net`, `notarena.ai`) refused; `forLog` drops the query so an OAuth `code` cannot reach a log line; `displayHost` refuses to print a non-host; `Content-Disposition` naming, including `filename*=UTF-8''…` decoding and a `../../etc/passwd` name reduced to its leaf; the OAuth trail reports allow-listed parameter **names** only, so a secret in a value cannot reach a log line |
| `core/WebErrorsTest.kt` | 6 | no network outranks every error code; every failure sentence names a next step and never mentions a cookie or token; the platform code mapping; the copy of `WebViewClient`'s constant values is a tripwire against drift; only plausibly-transient failures are retryable; a long platform description is dropped, not appended |
| `core/AppLogTest.kt` | 6 | cookie headers, bearer/basic values, JWT-shaped runs, e-mail addresses and JSON `refresh_token`/`password`/`otp` shapes never survive `redact()` — including one crafted to defeat a naive `refresh` pattern — while ordinary sentences pass through untouched |

```bash
./gradlew :app:testDebugUnitTest                      # all of them
./gradlew :app:testDebugUnitTest --tests '*BrowserReducerTest*'
open app/build/reports/tests/testDebugUnitTest/index.html
```

## 3. Instrumentation tests — compiled, never executed

`app/src/androidTest/java/com/kartikkamboj/arena/ShellInstrumentedTest.kt`, 8 tests. CI assembles the
APK, which is the only guarantee made about them: **they have not been run**, because this repository has
no emulator. They cover the things the JVM cannot see — `android.net.Uri` agreeing with `ParsedUrl` about
hosts on a real framework, the site's trust list agreeing with the framework's own parse, the persistent
view being reused and a replaced view being a new instance, Back on a fresh view being refused rather than
invented, progress not being able to fake a first paint, and the window rules: a `window.open()` request
without a user gesture gets nothing, a gesture whose message carries no usable transport is refused
instead of returning true with nothing wired (the framework calls that undefined behaviour), a callback
with no message at all does not crash, the page's own view is never treated as the popup, and the view
that does get governed is governed by `ArenaWebViewClient`/`ArenaWebChromeClient` — which is the same
`configure()` call a popup window receives.

What these tests cannot cover, and why: `WebView.WebViewTransport` is an **inner** class of `WebView`
that only the framework constructs, so a test cannot fabricate the granted path's transport. The granted
path is therefore pinned by the CI promise step (transport wired, `popup.destroy()` on every teardown,
`configure(popup)`) and by item 4c below on a device.

```bash
./gradlew :app:connectedDebugAndroidTest              # needs a device or emulator
adb logcat -s TestRunner
```

## 4. Manual device checklist — not performed

**No item below has been executed.** There is no phone or emulator in this environment, so nobody has
installed the APK, signed in, sent a message or locked a screen. Each item is written so it can be run
and its result recorded honestly. Use the CI artifact — install `arena-ai-debug.apk` from the latest
green run.

| # | Check | Steps | Pass looks like | Status |
| --- | --- | --- | --- | --- |
| 1 | Fresh install | uninstall, install the debug APK, launch | system splash in the app's theme (paper in light, charcoal in dark), no white flash, then the site; no permission prompts at all | not performed |
| 2 | Website loading | watch the first load | the 2 dp line advances with the page and disappears; no app-drawn spinner outlives `onPageFinished`; cold start is on the site within ~1.2 s even on a slow network | not performed |
| 3 | E-mail/password login | use the site's own form in-app | the form works like it does in Chrome; nothing in this app reads or stores what you typed; after the site returns you to a signed-in view, force-stop and relaunch: still signed in | not performed |
| 4 | **The reported six-step sequence** | 1 launch, 2 tap the site's Sign in, 3 watch where the provider page appears, 4 pick the Google account, 5 tap Continue, 6 look at the app | steps 3–5 must happen **inside this window** (the log shows `nav … [in-app]` for every hop, with `auth.arena.ai` and then the provider host, never a `hand-off` line); on return the site renders signed-in, or shows its own Sign in again — and the app says neither "signed in" nor "failed", because it cannot see either. Failure modes to record separately: a hop leaving the app (policy gap, fixable), and a 4xx on a provider host (`AUTH_REFUSED`, Google's embedded-view policy, not fixable from here). With the popup fix in place, `adb logcat -s Arena` must show `popup window created` at step 4-5; if it does not, the site is not using a popup and the diagnosis in [AUTHENTICATION.md](AUTHENTICATION.md) must be revisited | not performed |
| 4c | **The popup window itself** | press the site's Google button, then: pick an account; press Back while the window is open; press **Close**; tap a `target="_blank"` link while a window is already open | the popup appears as a **labelled sheet** whose strip names the host it is really on (never silently over the site); the page underneath is never replaced and its progress line never moves for the popup; after the provider replies the sheet goes away on its own; Back closes the sheet and leaves the site where it was; a second simultaneous request is refused with the one-line notice, not a second window; a load failure **inside** the sheet shows one line and must not raise a problem panel over arena.ai. Record whether the site ends signed in — this app cannot tell you | not performed |
| 4b | Browser fallback and the strip | force a hand-off (e.g. a flow that reaches a host outside both families), then tap **Finished there** | the strip over the provider page explains that the browser's session stays in the browser; the tap returns the window to the site and says the same thing once; no claim of success appears | not performed |
| 5 | Session persistence after restart | sign in, swipe the app away, relaunch | the site reloads its entry point already authenticated (cookies and web storage are on disk); no re-login form; nothing in the app had to be restored because it kept nothing | not performed |
| 6 | Send a real message | type and send on the site | the app is not involved: no native composer, no interception, no echoed copy of your text anywhere in the UI | not performed |
| 7 | Receive a real AI response | send a long prompt | tokens render progressively; the shell adds nothing on top; a 2 dp line appears only if the page starts a real navigation | not performed |
| 8 | Change AI models | use the site's own selector | the site's control works unaided; there is no native model list, so there is nothing here to go stale | not performed |
| 9 | File uploads | attach a PDF and an image through an in-page input | the system picker opens filtered to what the page accepted; after choosing, the site shows the attachment; pressing Back out of the picker leaves the page usable (the input was answered with nothing, not left hanging) | not performed |
| 10 | Image-related site features | use an image tool; request a camera/mic feature | images behave; the camera/microphone request is denied **and** explained in one line pointing at the browser — not silently swallowed | not performed |
| 11 | Android Back navigation | deep in the site's history, press Back repeatedly | each press walks the site's own history; the app closes only once the page cannot go back; nothing reloads on the way | not performed |
| 12 | Background and foreground | start a long answer, switch apps for 30 s, return | the page is intact and no reload happened (`adb shell dumpsys activity activities` shows the same task); returning to the app shows whatever arrived | not performed |
| 13 | Screen locked mid-generation | lock for 2 min during a long answer, unlock | the view is still there; the answer either continued or stopped depending on what Android and the site did — **record which**; the app must not claim either way | not performed |
| 14 | WebView renderer recovery | `chrome://crash` in a debug build (the only supported way to reach this path: `send-trim-memory` no longer has a level the app acts on beyond cache release) | the dead view is discarded, a fresh one reloads the same URL once with a one-line notice; a second death shows the card with a button instead of looping | not performed |
| 15 | Low-memory behaviour | 3 GB device, many apps open, `dumpsys meminfo` before/after | one renderer process; after `am send-trim-memory com.kartikkamboj.arena.debug BACKGROUND` the page stays visible and only its HTTP cache is gone (the next navigation refetches); nothing is logged except redacted lines; the app is not killed with a notification or a service of ours running | not performed |

Extra checks worth recording while a device is attached: an arena.ai link tapped in another app opens
**in this app** on that page; a link to another domain opens in a Custom Tab and the notice names the
host; a download offered by the site lands in `Downloads` with the server's filename and the system's
notification; airplane-mode while reading a page keeps the page with the offline line, and turning
connectivity back on reloads the failed page once.

## 5. What this repository cannot check

- Anything needing a browser engine, a network, a provider, a file picker or a screen: everything in §4.
- Real memory behaviour on the 3 GB device the budget is aimed at (no profiling, no heap dump, no
  `gfxinfo` capture; docs/PERFORMANCE.md quotes no number for that reason).
- Whether the WebView on a given device supports `ALGORITHMIC_DARKENING` or Safe Browsing interstitials —
  the code asks at runtime instead of assuming, and the outcome is device-dependent.
- Whether arena.ai finishes a generation after the client stops following the stream. Searches found no
  documentation either way, so the app depends on neither answer; see
  [BACKGROUND.md](BACKGROUND.md).
- Accessibility of the *site's* UI: the shell's own surfaces (notice strips, cards, buttons) carry labels
  and `contentDescription`s, but TalkBack behaviour inside the page is the site's, not this app's.

## 6. Recording a result

Add a row to the table in §4 with the device, Android version, WebView provider version
(`adb shell dumpsys webviewupdate | head`) and what actually happened, in the run's own words. A check
that passed is worth one line; a check that failed is worth the log fragment. Do not move an item from
"not performed" to "passed" on the strength of CI — CI cannot see any of §4, and the distinction is the
point of this file.
