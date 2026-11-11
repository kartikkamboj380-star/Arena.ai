# Arena for Android

A small, fast Android client for **[arena.ai](https://arena.ai)**. One `WebView`, dressed as a native
app: warm paper and charcoal instead of a browser tab, a splash that matches the theme, Back that
walks the site's own history, a file picker for uploads, downloads that the system finishes, and a
sentence for every state the page can be in.

The AI work is done by Arena, not by this app. There is no second chat implementation here, no
invented API, no scraped endpoint, and no model catalogue copied from the site. If arena.ai adds a
feature tonight, this app has it tomorrow without a new build.

> **Independent client.** Not affiliated with, endorsed by, or operated by Arena. It loads the public
> site with your real credentials typed into the site's own forms. It uses no Arena branding, logo or
> artwork; the launcher mark is a generic geometric "A".

## Why it is built this way

Arena publishes no public API for chat, models or authentication. Anything that claimed otherwise
would have to reverse-engineer the site — which is exactly what this project refuses to do. So the
app does the one thing that can be done honestly: it hosts the real site in a hardened `WebView`, and
owns everything *around* it (lifecycle, memory, navigation trust, error recovery, the look of the
first frame).

The consequences are deliberate and worth reading before you judge the app:

| It does | It does not |
| --- | --- |
| Render `https://arena.ai` inside the system bars, light or dark | Store your prompt, chat, model list or history anywhere |
| Keep the page alive through rotation and background | Sign you in, or read what the site's session is |
| Suspend nothing while hidden, so a response can keep arriving | Guarantee a streamed answer survives a screen lock |
| Say "the site did not load" and why | Claim a browser sign-in transferred into this app |
| Hand non-arena.ai links to your browser | Intercept, fill or inspect anything on the page |
| Send files the page asks for, via the system picker | Upload, transcode or look inside those files |
| Recover from a dead renderer | Add a bottom navigation bar, tabs or a drawer |

## What is on screen

* **Splash** — the system `core-splashscreen`, tinted to the current theme, held until the site paints
  its first frame (and never longer than 1.2 s, so a slow network still gets an interactive screen).
* **The page** — the site, edge-to-edge below the status bar, keyboard-resizing, scrollable, zooming as
  the site intends.
* **2 dp progress line** — driven only by the WebView's own `onProgressCallback`; it never fills
  because a button was pressed.
* **Failure panel / failure card** — full screen if nothing painted yet, a dismissible card if a usable
  page is already there. It names the reason (`DNS`, `timeout`, `HTTP 503`, `the renderer was replaced`)
  and what it cannot do about it.
* **Notice strip** — one line for the things worth saying but not worth blocking: "example.com opened
  in your browser", "Saving to Downloads".
* **Provider strip** — while the window is showing an identity provider's page, it says whose page that
  is and how to get back.

That is the whole interface. There is no native composer, no history list, no settings screen: the
site does all of those better and already has them.

## Behaviour that matters on a phone

**Background.** Returning to the app never reloads the page. `WebView.pauseTimers()` is never called
because it would freeze the response you are waiting for; only drawing is paused (`pause()`), and
cookies are flushed so a kill cannot lose a sign-in. What Android then does with a frozen or killed
process is not the app's choice — [docs/BACKGROUND.md](docs/BACKGROUND.md) states exactly which cases
work, which degrade, and what could not be verified.

**Memory.** One renderer, one persistent WebView (plus at most one transient popup window, destroyed the
moment it closes), `offscreenPreRaster` left off, and the HTTP cache given back at
`TRIM_MEMORY_BACKGROUND` — the only pressure level Android still delivers since API 34, which is also
why the app does not pretend to have a finer-grained plan. No `System.gc()`, no preload, no second
hidden browser instance. [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

**Sign-in.** The site's own form, in this window, with third-party cookies enabled for the views that
need them so the provider's redirect chain can complete — and a `window.open()` sign-in popup gets a real
labelled window, because a popup with nowhere to reply is what produced Google's `Error 400`. No
user-agent spoofing, no cookie extraction, no automation. If the provider refuses an embedded view, the app
says so and offers the browser. [docs/AUTHENTICATION.md](docs/AUTHENTICATION.md).

**Security.** In-app navigation is limited to the site's own domain family and the identity provider's
domains — the provider's wider domains only while a sign-in is mid-chain — plus
`javascript:`/`data:`/`file:` never reach the view; TLS errors cancel the load with no "proceed" button;
there is no `addJavascriptInterface` anywhere; logged URLs lose their query before anything else does,
and then pass through a redactor.
[docs/SECURITY.md](docs/SECURITY.md).

## Build it — no desktop required

CI builds a debug APK on every push and on demand; the APK is a downloadable artifact, installable
straight onto a phone.

1. Push to a branch (or run **Actions → Android CI → Run workflow**).
2. Open the run, download **`arena-ai-debug.apk`**.
3. Install it (`adb install`, or Files → tap the APK → allow unknown sources).

Locally: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
Details, artifacts, release signing: [docs/BUILD.md](docs/BUILD.md).

## Tests and their status — read this before trusting any green badge

| Kind | Count | Status on CI |
| --- | --- | --- |
| JVM unit tests (`:app:testDebugUnitTest`) | 75, in 5 files | **executed**, must pass |
| Instrumentation tests (`app/src/androidTest`) | 6 | **compiled only** — no emulator in CI, never executed |
| Lint (`:app:lintDebug`) | — | executed, `abortOnError = false`, findings published as an artifact |
| Manual device checklist (15 items: sign-in, uploads, back, backgrounding, lock-screen during generation, renderer recovery, low memory) | 15 | **not performed** — this repository has no device |

[docs/TESTING.md](docs/TESTING.md) lists each manual item with the exact steps and what it should show,
so whoever has a phone can run it and record the result. Nothing here reports a device test that did
not happen.

## Layout

```
app/src/main/java/com/kartikkamboj/arena/
├── ArenaApplication.kt          owns the container; routes onTrimMemory
├── MainActivity.kt              splash, edge-to-edge, Back, picker, Custom Tab hand-off
├── core/
│   ├── AppLog.kt                single log funnel, credential redaction
│   ├── NetworkMonitor.kt        connectivity signal for the offline states
│   └── WebErrors.kt             load-error codes → sentences a user can act on
├── di/AppContainer.kt           two fields: the network signal and the engine
├── ui/
│   ├── ArenaApp.kt              the whole shell: host, progress, panels, strips
│   └── theme/                   palette and Material3 wiring
└── web/
    ├── ArenaSite.kt             the one origin, its trust list, download naming
    ├── ParsedUrl.kt             URL reading without android.net.Uri (so it can be unit-tested)
    ├── NavigationPolicy.kt      render / hand off / refuse — the security boundary
    ├── BrowserState.kt          pure reducer: what the shell is allowed to claim
    ├── BrowserEngine.kt         every WebView the app owns, lifecycle, popups, downloads, chooser
    └── ArenaWebClients.kt       WebViewClient + WebChromeClient, thin on purpose
```

## Status of this repository

Version `2.0.0`, branch `arena/0f930c82-arena-ai`, [PR #1](https://github.com/kartikkamboj380-star/Arena.ai/pull/1).
Version 2 is a **complete change of direction**: the multi-account, project and model-catalogue client
that came before it was deleted rather than left in the tree. [docs/RESET.md](docs/RESET.md) records
what was removed, what was kept, and where the old code still exists in history.

Licensed under the MIT License — see [LICENSE](LICENSE).
