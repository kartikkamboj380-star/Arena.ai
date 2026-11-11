# Performance and memory

Target: a 3 GB phone that is also running other things. Not a benchmark target — there is no
measurement in this repository (see §6) — but every decision below was made because the alternative
costs memory on exactly that device.

## 1. One browser, one process

- Exactly one `WebView` is ever constructed, in `BrowserEngine.ensureView()`, which returns the existing
  instance. There is no preview view, no warm-up view, no throwaway stub for a popup, no headless
  second instance. The rule is enforced structurally: `WebView(` appears once in `app/src/main`.
- `setSupportMultipleWindows(false)` means a `target="_blank"` link continues in the same view, so the
  app never has to create a window for it.
- No `Profile`/multi-profile APIs, no second process, no `RendererPriorityPolicy` games: one renderer is
  the cheapest correct configuration here.
- No Service of any kind, no WorkManager, no foreground service, no alarm, no polling: with the app closed
  there is nothing of ours running.

## 2. What the APK carries

| Removed in the reset | Why it was there |
| --- | --- |
| `material-icons-extended` | the shell needs two glyphs; `material-icons-core` (already a Material 3 dependency) has them |
| `kotlinx-serialization-json` + the compiler plugin | nothing is serialised any more; the plugin also keeps generated serializers in the dex |
| `navigation-compose` | one destination; a nav graph is 100% dead weight here |
| `appcompat`, `androidx-core-ktx` | the theme is a platform `Theme.Material.*` theme; no API 26 backport needed |
| `lifecycle-process`, `lifecycle-runtime-ktx` | the Activity's own callbacks are the lifecycle signal |
| `espresso-core`, `kotlinx-coroutines-test` | the instrumentation tests do not need them (see docs/TESTING.md) |
| `androidx.compose.ui:ui-tooling`, `:ui-tooling-preview` | removed after the first green build: the project has no `@Preview` function, so the debug-only tooling was being dexed into every debug APK for a feature nobody uses, and the preview annotation library was in the release classpath for the same reason |
| `data` layer + `res/xml/{backup,data_extraction,locales}` | no store, no rules about a store, no per-app locale list |
| never-shipped PNG asset in `docs/assets` | not referenced by anything (a docs asset costs no APK bytes, but it was noise) |

Nothing was added except `androidx.core:core-splashscreen`, so that the splash is drawn by the system
instead of by a Compose screen this app would have to keep alive.

`minifyEnabled = true` for release with resource shrinking off (a shrunk resource table is a poor trade
for a two-glyph app), and R8 keeps only `WebViewClient`/`WebChromeClient` subclasses for the platform's
own override resolution. Debug builds — the ones CI publishes — are unminified, so their size is a ceiling
rather than what a user installs from a release build.

## 3. Rendering and layout cost

- `offscreenPreRaster` is left at its default (`false`): pre-rasterising a full-screen page spends the
  exact memory this app is trying not to spend.
- No `RenderEffect`, no blur, no shader, no translucent scrim stacked over the page, no elevation
  shadows: the deleted v1 header blob forced a full-screen render pass per frame, which is why the
  panels in v2 are hairlines and flat fills.
- Scrollbars disabled (`isVerticalScrollBarEnabled = false`) — the page has its own; drawing ours costs
  a pass and nothing else.
- `windowSoftInputMode="adjustResize"` plus safe-area padding, so the keyboard resizes the window rather
  than the app relaying decorative layers on top of it.
- Progress UI is a 2 dp determinate bar driven by `onProgressChanged`; no indeterminate shimmer while a
  page sits idle, because an indeterminate bar animates at 60 fps for no information.

## 4. Lifecycle rules that save memory without losing state

| Event | Action | Not done |
| --- | --- | --- |
| `onStop` | `CookieManager.flush()`, unregister the network callback | `pauseTimers()` — it would freeze the response being streamed; `pause()` — it is `protected`, and drawing already stops when the view is not visible; `destroy()` |
| `onStart` | register the network callback; load the home URL only on the very first start | any reload — nothing destroys the view while hidden, so there is nothing to recover from |
| `TRIM_MEMORY_BACKGROUND` (40) | `clearCache(true)` — the HTTP cache is the biggest reclaimable chunk a loaded SPA holds and the page refetches it | dropping cookies or web storage; `freeMemory()`, deprecated with no replacement |
| `TRIM_MEMORY_UI_HIDDEN` (20) | nothing | clearing the cache on every app switch would make returning slower to save memory that was not asked for |
| the levels above `BACKGROUND` | nothing available to do — `TRIM_MEMORY_MODERATE` (60) and `TRIM_MEMORY_COMPLETE` (80) are deprecated in API 35 with "apps are not notified of this level since API level 34", so a branch keyed on them is dead code | pretending the app can outguess the killer |
| `onRenderProcessGone` | discard the dead view, reducer decides: reload once automatically, then ask | looping a reload on a device that is out of memory |
| Activity finishing | `shutdown()`, and the two Activity hooks are nulled in `onDestroy` so nothing holds a window | — |

The view is created on an application context wrapped in `ContextThemeWrapper`, so the singleton holding
it cannot pin an Activity — the standard leak in a "keep the WebView alive" design. `runCatching` guards
the destroy path because a dead renderer's `destroy()` can throw, and a crash while recovering from a
crash is the worst possible outcome on a low-memory device.

No `System.gc()`, `Runtime.gc()` or `runFinalizationOnExit` anywhere, and no `WebView.freeMemory()`
either: it is deprecated with no public replacement, so `clearCache(true)` is the only cache request this
app makes. A forced full GC stops the world to guess.

## 5. Startup

The container builds two objects and the WebView is created lazily on first use; there is no JSON to read,
no database to open, no migration to run, no settings to hydrate. The cold-start cost is the site's, which
is why the splash is held for at most 1.2 s and then yields to the page rather than to an app-drawn
spinner. `installSplashScreen()` is called before `super.onCreate`, so the system draws it, not Compose.

## 6. What was not measured

No number in this document is a measurement, and none is quoted anywhere else in the repository. This
environment has no JDK, no SDK and no device: nothing was profiled, no heap dump was taken, no
`Simpleperf`/GPU-profile capture exists, and no APK size is claimed here (the size CI printed is reported
in the PR, next to the run that produced it). To measure it for real:

```
./gradlew :app:assembleRelease                                  # size of what a user would install
adb shell dumpsys meminfo com.kartikkamboj.arena.debug          # PSS by category, one renderer expected
adb shell am send-trim-memory com.kartikkamboj.arena.debug COMPLETE
adb shell dumpsys gfxinfo com.kartikkamboj.arena.debug framestats
```

## 7. Deliberate non-goals

No image loading library (the page loads its own images), no persistence layer, no prefetch of the site,
no offline cache of rendered conversations, no "lite mode" that strips the page, no WebView provider
download prompt, no eager font or script preloading, no background keep-alive of any kind. Each would buy
speed for one user in exchange for memory or battery in someone else's pocket, which is the trade this
app is built to refuse.
