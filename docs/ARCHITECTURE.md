# Architecture

```
com.kartikkamboj.arena
├── ArenaApplication          creates AppContainer, applies screenshot protection, low-memory hook
├── MainActivity              single Activity: edge-to-edge, Compose root, Custom Tabs hand-off,
│                             file picker result, lifecycle → session resume/capture/shutdown
├── core/                     Clock (+ FakeClock), AppLog (redacting, debug-only), NetworkMonitor
├── data/
│   ├── model/                ArenaAccount, AccountStatus, SessionState, AuthProbeResult,
│   │                         CookieEntry/Snapshot, ArenaMode, ThreadRecord, ModelEntry/Catalog,
│   │                         ErrorKind/AppError  — all @Serializable, no Android imports
│   ├── prefs/                SettingsRepository (+ the SettingsSource read interface)
│   ├── secure/               BlobCipher (Keystore AES/GCM, software fallback), EncryptedBlobStore
│   ├── store/                JsonFileStore — temp-file+rename JSON for non-secret state
│   └── repo/                 AccountRepository, HistoryRepository, ModelRepository,
│                             SessionIsolation (interface + capability types)
├── domain/                   SessionStateMachine, AccountPolicy, CookieJarCodec, ErrorMapper,
│                             MarkdownParser     ← pure Kotlin, this is where the rules live
├── web/                      ArenaUrls, ParsedUrl, NavigationPolicy, AuthProbe, PageExtractor,
│                             RateLimitMonitor, WebViewSessionIsolation, WebViewClients,
│                             SessionCoordinator, ComposerScript
├── di/                       AppContainer (hand-rolled graph), Diagnostics
└── ui/                       ArenaApp (nav shell), theme/, components/, screens/
```

## 1. The constraint everything else follows from

There is **no public Arena API**, so this app is a native client *over the authenticated web
session* — not a browser shortcut, and not an API client. That single fact dictates:

- the session question is answered by **observing a rendered page**, not by calling `/whoami`;
- "sending a prompt" means handing the user a real Arena surface, with the app adding the parts the
  web page does badly on a phone (account slots, history ledger, markdown rendering of local notes,
  offline-tolerant status, secure storage);
- models cannot be toggled by the app, so the Models screen records **what was observed** and shows
  the selected label as something the user applies in Arena's own composer.

Pretending otherwise would mean inventing endpoints — see [AUTHENTICATION.md](AUTHENTICATION.md) §2.

## 2. No ViewModels, and why

State lives in app-scope stores in `AppContainer`, exposed as `StateFlow` and collected with
`collectAsStateWithLifecycle()` inside screens. Reasons, in order of weight:

1. **The requirement "switching tabs must not lose the conversation" is a lifecycle problem.** A
   `ViewModel` scoped to a nav backstack entry dies with that entry; a WebView that dies with it drops
   the loaded page. The only way to keep a live `WebView` across Chat → Accounts → Chat is to own it
   *above* the navigation graph. Once one object must be app-scoped, splitting its neighbours into
   per-screen ViewModels buys nothing.
2. **The state is genuinely shared.** `accounts.selection` is read by Chat (header, banner), Accounts
   (the list), History (per-slot filter) and Settings (diagnostics). Five ViewModels would need five
   delegations to the same repository.
3. **Testability is not lost.** The repositories are plain classes with injected `Clock`,
   `SettingsSource`, `JsonFileStore` and `SessionIsolation`, so the rules are covered by JVM tests
   without any Android at all. That is where the complexity actually is; UI is a projection.

`SessionCoordinator` is the one object that touches a `View`, and it is deliberately *not*
`DisposableEffect`-shaped: the host container is attached and detached, and nothing in the UI layer
calls `destroy()` — leaving the Chat tab must not end the session. Destruction
happens in `MainActivity.onDestroy()` → `session.shutdown()`.

## 3. One WebView, five slots

`SessionCoordinator` owns `webView: WebView?`, created on demand by `ensureView()` with an
**application** context wrapped in `ContextThemeWrapper` (never the Activity: a view that outlives the
composition must not be tied to its lifecycle). The Chat screen hosts it through an `AndroidView`
whose factory is a plain `FrameLayout`; `update` calls `session.attach(frame)` and `onRelease` calls
`session.detach(frame)` — detaching only reparents, it never destroys. `switchTo(slot)` captures,
wipes, restores and reloads.

`WebViewSessionIsolation` (implements `data/repo/SessionIsolation`) is the swap:

```
capture(slot)   CookieManager.getCookie(COOKIE_URL) → CookieJarCodec.parse → CookieJarSnapshot
                → EncryptedBlobStore.write("arena_account_<slot>.bin")
restore(slot)   read → TTL check → prune → removeAllCookies → setCookie(toSetCookie(entry)) → flush
release(slot)   delete blob, wipe live jar if bound, unbind, deleteProfileData (feature-guarded)
```

The invariant — and the reason the fake in the test source set is strict — is that the live jar belongs
to exactly one slot (`activeSlot`), `capture` is ignored for any other slot, and `restore` binds. Full
reasoning, including what the platform cannot do, is in [ACCOUNTS.md](ACCOUNTS.md) §2.

## 4. Data flow for one prompt

```
user types in the WebView's own composer (Arena's page)                       ─┐
                                                                               │ real answer renders
ChatScreen header  ◄── SessionCoordinator._state: StateFlow<SessionState>  ◄───┤ onProgress/onContentChanged
      │                                                                        │ onMainFrameHttpError → ErrorMapper
      └── banner: rate limit (RateLimitMonitor), expiry, offline              │ onReceivedError → AppError
                                                                               └ AuthProbe (420ms after settle)
                                                                                      └ accounts.applyProbe → state machine
```

Local persistence on top of that:

- **History** is a ledger of *pointers* (`ThreadRecord`: url, title, slot, mode, model, timestamps,
  note) — never a transcript mirror, because there is no API to read transcripts and scraping the
  user's history page into a local copy would be a private-data store with no benefit.
  `importVisibleHistory` records only what the user can see and only when they tap the button.
- **Drafts** are local-only: `ComposerScript` can place a saved draft into the page's own contenteditable,
  and never submits it. The toggle is off by default and the row says so.
- **Markdown** (`domain/MarkdownParser` → `ui/components/MarkdownText`) renders notes and any text the
  user copies out of the page, with code blocks, copy buttons and no HTML injection path.

## 5. Errors and copy

Every user-visible failure is an `ErrorKind` with `title` / `userMessage` / `actionLabel` /
`retryable` — that enum is the whole error vocabulary, and `AppError(kind, detail)` is what the UI
renders. `ErrorMapper` translates HTTP statuses, `WebViewClient` error codes and exception types into
it. Stack traces are never rendered; at most a redacted one-line `AppLog.diagnose(error)` appears in
debug logs. Adding a new failure means adding an enum entry, which the test
`no ErrorKind has empty user-facing copy` will complain about if the copy is missing.

## 6. Threads and lifetime

- `AppContainer.scope` = `SupervisorJob() + Dispatchers.Default`; repositories serialise mutations
  with a `Mutex` (no actor, no shared mutable collections).
- Disk I/O: `Dispatchers.IO` inside `JsonFileStore` (synchronous, called from the mutex) and
  `EncryptedBlobStore` (its own dispatcher); `WebView` calls hop to main via `withContext(main)`.
- `MainActivity` maps lifecycle to session: `ON_START` → `session.onHostResume()`, `ON_STOP` →
  `session.onHostPause()` (which captures the jar), `ON_DESTROY` → `session.shutdown()`.
  `onRenderProcessGone` rebuilds the view for the slot and reports `RENDER_CRASH` instead of dying.

## 7. Deliberate omissions

- **No Room/SQLDelight.** Three small lists and one catalog, all `@Serializable`, persisted as JSON
  with temp-file+rename and a `.corrupt` quarantine. A schema + KSP would add build complexity and a
  migration surface for data that is 200 rows of pointers.
- **No Hilt/Koin.** Ten objects, one construction order.
- **No WorkManager.** Session verification is driven by the user opening a page, not by a background
  poll — a background probe would be extra traffic to Arena that the user did not ask for.
- **No `addJavascriptInterface` anywhere.** The probe and extractor are read-only
  `evaluateJavascript` calls whose results are parsed as JSON in Kotlin. Grepping for
  `addJavascriptInterface` returning nothing is part of the security review in
  [SECURITY.md](SECURITY.md).
- **No release signing.** `assembleRelease` runs with minify on and no signing config, so CI cannot
  hand out an unsigned-as-if-official artifact by accident.

## 8. Where to start reading

`web/SessionCoordinator.kt` (the interesting and the risky parts meet here), then
`domain/SessionStateMachine.kt` (what may be claimed), then `di/AppContainer.kt` (how it is wired),
then `ui/ArenaApp.kt` (how little the UI owns).
