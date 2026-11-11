# Background operation

Requirement: keep using the phone while Arena keeps working, and come back to the same conversation.
Below is what the app does to make that happen, and — more importantly — what it cannot promise, because
a `WebView` lives inside a process Android is free to freeze or kill.

## What the app does

| Mechanism | Effect |
| --- | --- |
| `android:configChanges` for orientation, screen size/layout, smallest screen size, density, keyboard, navigation, UI mode, locale, layout direction, font scale | rotating or folding the phone does not recreate the Activity, so there is no reload to survive |
| The `WebView` is owned by `AppContainer` (application scope), not the Activity | even a real recreation re-attaches the same live view instead of building a new page |
| `onStop` → `pause()`, never `pauseTimers()` | drawing and layout stop for a hidden page; JavaScript and the open connection keep running while the process is allowed to |
| No reload in `onStart` | returning to the app shows what you left, not a spinner. The reducer has no branch that reloads on foregrounding — see `BrowserReducerTest` |
| `CookieManager.flush()` on stop | the site's session is on disk before the process is a candidate for reclamation |
| No `stopWithTask` tricks, no `excludeFromRecents`, `singleTask` + `onNewIntent` | the app behaves like the one task it is; a tapped arena.ai link continues in the same view |
| `onTrimMemory` graduated: caches → `freeMemory()` → view | pressure is answered before the low-memory killer decides for us |

## What is *not* done, and why

- **No foreground service.** A service whose notification exists only to keep a `WebView` alive has no
  legitimate declared type (`mediaPlayback`, `dataSync`, …) that describes it, it needs
  `POST_NOTIFICATIONS` on 13+, it costs the user a persistent notification, and Android 14 tightens
  start-while-in-background rules for exactly this pattern. The gain would be unreliable anyway: the
  process is not what streams — the site's own connection is, and that is governed by the renderer and
  the network stack.
- **No wake lock.** Holding the CPU so a page keeps receiving a stream you are not looking at is a
  battery charge you did not agree to and a policy violation waiting to happen.
- **No hidden `WebView`, no second process, no headless fetch.** Streaming a response into a background
  view and rendering it later would need a second browser instance — the one thing the memory budget
  rules out — and a private, unsupported re-implementation of the site's transport.
- **No notification that an answer "finished".** The app cannot know that without the site telling it,
  and the site offers no such signal to a client that does not read its DOM or its traffic. A
  fabricated "Done" badge is the kind of lie this project refuses.
- **No battery-optimisation prompt or exemption request.** Asking the user to disable Doze for a chat
  client is a bad trade, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is not declared.

## What Android guarantees, and where each case lands

| Situation | Result | Whose decision |
| --- | --- | --- |
| App switch, quick | page intact, no reload; the stream usually keeps arriving because the process is still runnable | app + platform |
| Screen off briefly, device not idle | process cached; the network stack may keep receiving, and on returning you see whatever arrived | platform |
| Screen off long / Doze | the process is frozen: no JavaScript, no socket reads. Anything in flight is suspended | platform |
| User swipes the app from Recents | the task is finished; the process is expected to end. Next launch loads the site again — signed in, because the cookies and web storage stayed on disk | platform |
| Low memory, `TRIM_MEMORY_COMPLETE` | the app gives up the view deliberately, keeping cookies and storage; the next foreground reloads the last URL | app |
| Renderer killed by the system | `onRenderProcessGone` → view discarded, a new one reloads the same URL once; if it happens again the app asks instead of looping | app |
| Process killed while backgrounded | on relaunch the site starts from the URL the shell remembered. The page's mid-flight request is gone from the client's point of view | platform |

**Nothing here is a guarantee about the answer arriving.** A response the site is still generating is
delivered over the connection the renderer holds; if the OS freezes that process, delivery pauses with
it. Whether the site finishes generating on its own servers, keeps the finished answer in your thread,
or discards a partial one is a property of Arena, and this app does not observe it — see the next
section. What the app *does* guarantee is that it never makes the situation worse: no self-inflicted
reload, no timer suspension, no destroyed view except under real memory pressure.

## Was server-side continuation verified? No.

The open question in the requirement is whether arena.ai keeps generating after the client stops
following the stream. What was checked, from this environment:

- The public site's own pages (`https://arena.ai/`) — no documentation of stream or job lifetime, no
  "runs in the background" claim, no published API for collecting a finished answer later.
- Web searches for Arena/LMArena documentation on background generation — nothing authoritative found;
  what came back was other projects discussing their *own* generation-on-disconnect behaviour, which is
  not evidence about arena.ai.

So this project states the honest conclusion: **unknown, and designed not to depend on it.** The app
neither pretends an answer will be waiting (no "generation continues in the background" text, no badge,
no notification) nor cancels anything of its own accord. If Arena does keep the run, you find it in the
site's own history — which the app leaves you in. If a device test (items 12, 13 in
[TESTING.md](TESTING.md)) shows it, that is worth recording there; it is not something to write into the
UI before it has been seen.

## Verifying it on a device

The full checklist is [TESTING.md](TESTING.md) §Background (items 12–14). In short, with
`adb` available:

```
# stay on a long generation, then lock the screen for 2 minutes and return:
adb shell dumpsys activity activities | grep -A3 "Arena"     # task still there, no relaunch
adb shell dumpsys meminfo com.kartikkamboj.arena.debug       # one renderer, PSS as expected
adb shell am kill com.kartikkamboj.arena.debug               # simulate a kill while backgrounded
# then reopen: expected = the site reloads its entry point, still signed in, no login form
```

To exercise the memory paths on a device that is not actually short of memory, use the real command and
its level names:

```
adb shell am send-trim-memory com.kartikkamboj.arena.debug BACKGROUND   # caches given back
adb shell am send-trim-memory com.kartikkamboj.arena.debug COMPLETE     # the view is released
```

The first must leave the page on screen and readable; the second must leave the notice strip saying the
page is being reloaded, and the next foreground must land back on the same URL with no login form.
