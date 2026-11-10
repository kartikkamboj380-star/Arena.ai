# Low-RAM design

The target hardware is a 2 GB phone with an OLED screen, slow storage and a vendor build that kills
background processes early. Every decision below is a trade that was actually made in the code, with
what it costs named next to it. Where a measure is *not* implemented, this document says so instead
of claiming it.

## 1. One WebView, owned outside the composition

`SessionCoordinator` creates the single `WebView` lazily, on the first time the Chat surface attaches,
with the application context wrapped in a `ContextThemeWrapper`. Screens only attach and detach it:

```kotlin
AndroidView(
    factory = { FrameLayout(it) },
    update = { session.attach(it) },
    onRelease = { session.detach(it) },
)
```

Consequences, all deliberate:

- Leaving the tab, opening Accounts or rotating the device does not destroy the page — a streaming
  answer keeps running and coming back shows it where the user left it.
- There is no pool of five WebViews, no "warm" hidden view for the other account slots, and no
  background view created for prefetching. Unselected slots cost *zero* renderer memory.
- The view is destroyed in exactly three places: a logout that must drop a session, `shutdown()` when
  the Activity is finishing, and memory pressure (§4).

## 2. Temporal isolation instead of five profiles

The obvious design for five accounts is five `Profile`s, one WebView each. androidx.webkit cannot do
that on a public API: `WebViewCompat.getProfile()` is read-only, and `Profile` support behind
`MULTI_PROFILE` requires a multi-process build. Keeping five live profiles would also mean five
renderers, which is precisely what a 2 GB device cannot afford.

So the app gives each slot the jar it captured, and nothing else: on switch, the live cookie jar is
snapshotted into an AES/GCM blob, cleared, and the incoming slot's jar is installed before the page
reloads. One renderer, one set of site-data structures, five identities in time rather than in
space. `SessionIsolation.activeSlot` guards the swap so a capture for the wrong slot throws instead
of crossing accounts.

## 3. Caps, because "unbounded" is what kills you

| What | Limit | Where |
| --- | --- | --- |
| Projects on device | 24 | `ProjectPolicy.MAX_PROJECTS` |
| Thread records | 200 | `HistoryRepository.MAX_RECORDS` |
| Imported rows per press | 50 | `HistoryRepository.MAX_IMPORT` |
| Model catalog entries | 120 | `ModelRepository(maxEntries = 120)` |
| Cookies per jar | 64 entries / 4 096 chars per value / 32 000 total | `CookieJarCodec` |
| DOM rows read per extraction | 300 rows, 180 chars each | `PageExtractor.LIST_SCRIPT` |
| Markdown per message | 400 lines, 80 code lines, 8 000 chars inline | `MarkdownParser` |
| Files listed per project | 40 | `ProjectPolicy.MAX_FILES` |

Nothing here is a soft suggestion: the caps are asserted in unit tests, including "the oldest project
is never dropped to make room" and "an oversized jar is pruned, not written anyway".

## 4. Memory pressure, graduated

`ArenaApplication.onTrimMemory` forwards the level to `AppContainer.onLowMemory`:

- **Any pressure** → `CookieManager.flush()`. Everything the app owns is on disk before anything is
  given back.
- **`TRIM_MEMORY_COMPLETE`** → `SessionCoordinator.releaseForMemoryPressure()`: capture the slot's
  jar, then `destroy()` the WebView. The renderer and its paint hierarchy are the largest objects in
  this process by a wide margin, and they are the only ones that are genuinely disposable. The next
  attach rebuilds the view and reloads the URL remembered in `SessionState`, so a reclaim does not
  look like a lost conversation.

Two rules that are *not* negotiable: no branch of this path clears cookies or site storage (auth
state is never a memory optimisation), and the app never calls `System.gc()` — there is no such call
anywhere in the sources, because it does not free a byte that the runtime was going to keep.

`android.webkit.WebView` has no public cache-trimming API (`trimMemory` is not in the SDK surface), so
the app does not pretend to call it; the framework already forwards `onTrimMemory` to the renderer.

## 5. Rendering choices inside the page

```kotlin
offscreenPreRaster = false   // a second full-page buffer per frame is the wrong trade here
setSupportZoom(false)        // no zoom machinery, no double-resolution caches
builtInZoomControls = false
domStorageEnabled = true     // the site's session handling needs it; not a performance choice
databaseEnabled = false
allowFileAccess = false
loadsImagesAutomatically = true
cacheMode = WebSettings.LOAD_DEFAULT
```

`offscreenPreRaster` is the one switch that saves real memory in a text-heavy page and buys back only
a slightly longer first paint after a fling. `loadsImagesAutomatically` stays on deliberately: Arena's
model avatars and answer images are part of the content, and a half-rendered page that must be
re-requested later is not a saving.

## 6. Compose cost

- Every screen collects from a `StateFlow` with `collectAsStateWithLifecycle()`, so a backgrounded
  tab stops recomposing entirely rather than holding a live subscription.
- `MarkdownText` parses inside `remember(source)`; the parser is pure and allocation-bounded, so a
  scrolling list re-parses nothing.
- Lists are keyed (`items(visible, key = { it.id })`) instead of declaring one `item { }` per record,
  which is what keeps 200 history rows from costing composition memory the user never scrolls to.
- No `ModalNavigationDrawer`, no `Snackbar` host, no nested `LazyColumn` inside a scrolling `Column`.
- The theme dropped its blurred ambient blob and gradient fills: `.blur()` costs a full-screen render
  pass per frame on the layer, and a `Brush` recomputes; a hairline border and a flat surface cost
  nothing. `ArenaBackdrop` is now a solid colour and one 1 dp line.
- Transitions are limited to the loading strip and expand/collapse on cards; nothing animates on
  scroll, and `AnimatedVisibility` was removed from the per-frame Chat overlay.

## 7. What is *not* done, and why

- **No image-loading library.** Coil/Glide would add ~2 MB of APK and their own memory caches for a
  surface where images are drawn by the WebView, not by Compose. The app renders exactly two
  decorations, both vector drawables.
- **No Room, no SQLDelight, no KSP.** The datasets are a few hundred small rows with no queries
  beyond "list, filter by slot, replace". A temp-file + rename JSON write does that in one pass, with
  a `.corrupt` fallback instead of a migration failure.
- **No `largeHeap`.** Asking for a bigger heap does not make a 2 GB device have one; it moves the OOM
  into a longer, uglier pause and makes the process a more attractive kill target.
- **No foreground service, no WorkManager, no periodic re-probing.** Background activity is the first
  thing a low-memory vendor build kills, and it would buy nothing the user can see. Probes run after
  a page settles, and only while the Activity is started.
- **No preloading of the other four accounts.** Not the jars, not the profiles, not the pages.

## 8. Measuring this, honestly

None of the numbers above are measurements; they are structural properties of the code, and
"optimized for low-RAM devices" cannot be verified from a build container. On a real 2 GB device:

```bash
adb shell am trim-memory <pid> COMPLETE     # exercise §4 without waiting for the LMK
adb shell dumpsys meminfo com.kartikkamboj.arena
adb shell am start -W -n com.kartikkamboj.arena/.MainActivity
```

`docs/TESTING.md` §4 lists the device checklist, including the memory-pressure cases and the fact
that none of them has been run yet.
