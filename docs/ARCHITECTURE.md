# Architecture

Three rules shape everything below.

1. **The website is the source of truth.** Chat, models, generation, uploads, history, image tools and
   login all belong to arena.ai. This app renders them. It has no parallel implementation of any of
   them, because a second version of a feature is a second version that can be wrong.
2. **The app may only claim what it can see.** Every status on screen comes from a WebView callback —
   `onPageStarted`, `onProgressChanged`, `onPageFinished`, `onReceivedError`. Nothing is marked loaded,
   signed-in or successful because a button was pressed.
3. **One WebView, always.** No second, hidden or throwaway browser instance, ever; not for a popup, a
   preview, a scrape or a warm-up. A second `WebView` on a 3 GB phone is the difference between keeping
   your conversation and being killed.

## Layers

```
MainActivity (thin: splash, insets, Back, picker, Custom Tab)
   └── ui/ArenaApp.kt            the shell: host, progress line, panels, strips
          ▲ reads
   AppContainer ──► BrowserEngine ──► WebView  (the only one, created lazily)
                        │        ▲
                        │        └── ArenaWebViewClient / ArenaWebChromeClient  (translate, decide nothing)
                        ▼
                    BrowserState + BrowserReducer   ← pure, JVM-tested, owns all wording decisions
   NetworkMonitor ──► offline signal
   AppLog        ──── redacting single log funnel
```

### `di/AppContainer`

Two fields. It exists so the WebView and the connectivity callback outlive the Activity, which is what
keeps a rotation from reloading the site. No DI framework, no service locator, no graph — with two
objects, a constructor is the clearest container there is.

### `web/BrowserEngine`

Owns the one `WebView`: builds it on an application context plus a theme wrapper, wires the two clients,
applies the settings, and drives loads, Back, downloads, the file chooser, lifecycle and memory. Nothing
else may hold a `WebView`, and nothing else calls `loadUrl`.

Its public surface is deliberately small — `ensureView`, `attachInto`/`detachFrom`, `load`, `goBack`,
`retry`, the notice dismissers, the lifecycle and memory hooks, the two Activity callbacks
(`onOpenExternal`, `onPickFiles`) and `deliverPickResult`. The Activity supplies UI-owning capability
(a window to launch a picker in, a tab to open a link in); the engine supplies state.

### `web/BrowserState` + `BrowserReducer`

A data class and a pure function. This is where the app's promises are actually made:

- `Backgrounded` / `Foregrounded` return the state unchanged with no effects — a foreground return can
  not reload the page, because the reducer has no branch that would.
- `PageFinished` is the only transition that sets `firstPaint` / `READY`.
- A load failure never overwrites a page that already painted: the reducer switches to a card, not a
  blank screen.
- A renderer death is recovered automatically once, then it asks — an automatic-reload loop on a phone
  that is out of memory is worse than a card with a button.
- A network returning retries a *failed* load only; a page that loaded is left alone.

Effects (`Reload`, `Say`) are returned rather than executed, which is what keeps the decision logic free
of Android and therefore testable.

### `web/NavigationPolicy`, `ArenaSite`, `ParsedUrl`

The security boundary, in three small pure pieces: which origins the app renders, which leave for the
browser, which are refused outright. `ParsedUrl` exists because `android.net.Uri` is a stub on the JVM,
and origin rules are the last place where "it compiles" should be mistaken for "it's correct" — so they
are asserted on every CI run instead. See [SECURITY.md](SECURITY.md).

### `web/ArenaWebClients`

`WebViewClient` and `WebChromeClient`, translated only: each override either forwards to the engine or
cancels. They contain no policy and no state, so there is nowhere for a decision to hide.

### `ui/`

One composable, four panels, two strips, and a palette. `AndroidView` hosts a `FrameLayout` that the
engine re-parents its view into; `key(webView)` means a *replaced* view (after a renderer death)
re-attaches cleanly while an ordinary recomposition just re-attaches the same one.

## What is deliberately absent

| Absent | Why |
| --- | --- |
| ViewModels / a navigation graph | one destination; state that must outlive the Activity belongs in the container |
| Room, DataStore, SharedPreferences, files | the app stores nothing; the site stores its own state in web storage |
| Retrofit/OkHttp/Ktor | no API is called; a HTTP client here would only tempt scraping |
| `addJavascriptInterface`, injected JavaScript | the bridge is the attack; there is no bridge |
| A settings screen | one toggle worth having (theme) is already the system's |
| Bottom navigation, tabs, drawer, FAB | all of them would be a worse copy of what the page already is |
| WorkManager, foreground service, `JobIntentService` | nothing here should run when you are not looking |
| Coil, Glide, an image pipeline | the page renders its own images |
| Analytics, crash reporting, ad SDKs | no reason, and a shell has no business phoning home |

## Data flow for "I sent a message"

1. You tap the site's own send button. The app sees none of this: no listener, no bridge, no interception.
2. The site streams its answer over its own connection, rendered by the one WebView.
3. The app's involvement: keep the view alive, keep drawing (or, when hidden, keep *not* pausing
   JavaScript timers), and update `BrowserState` only if the page tells it something changed.
4. If the renderer dies mid-answer, `onRenderProcessGone` → `destroyView()` → reducer → a fresh view
   loads the same URL, and the site's own thread view brings the answer back when it is finished
   server-side. The app cannot know whether the answer completed, and says so rather than guessing:
   see [BACKGROUND.md](BACKGROUND.md).

## Files, with reasons

| File | One-line reason it exists |
| --- | --- |
| `core/AppLog.kt` | one funnel, one redactor; debug-only verbosity |
| `core/NetworkMonitor.kt` | "no network" must be a fact, not an inference from a failed load |
| `core/WebErrors.kt` | error codes → sentences; `WebFailures` keeps the platform's constant values in one tested place |
| `di/AppContainer.kt` | the seam that lets the view outlive the Activity |
| `ui/theme/Color.kt`, `Theme.kt` | two modes, one accent, no gradient — see [DESIGN.md](DESIGN.md) |
| `ArenaApplication.kt` | `onTrimMemory` has to be caught at process scope |
