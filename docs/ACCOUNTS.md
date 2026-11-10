# Account management

Five slots, one shared WebView, and a rule set that decides what the app may and may not claim about
any of them. Everything below is implemented in
`data/repo/AccountRepository.kt`, `data/repo/SessionIsolation.kt`,
`web/WebViewSessionIsolation.kt` and `domain/AccountPolicy.kt`.

## 1. The model

A slot is `ArenaAccount(slot, label, status, observedLabel, emailHint, cookieCount, …)`. Slot numbers
are **1…5 and stable**: removing slot 2 never renumbers slots 3-5, and a new account takes the lowest
free number. That matters because the encrypted blob on disk is keyed by slot
(`arena_account_<n>.bin`) — renumbering would silently swap sessions.

Statuses, exactly as rendered in the Accounts screen:

| Status | Set when | Effect |
| --- | --- | --- |
| **Not connected** | slot is empty, or a cold start found nothing stored | — |
| **Connecting** | a load started and no verdict yet | progress UI only |
| **Connected** | the read-only probe saw signed-in chrome (or a user label) on `arena.ai` | jar snapshotted |
| **Login required** | the probe saw the site's own logged-out surface, and no jar existed | reconnect prompt |
| **Session expired** | the logged-out surface appeared *while a jar was stored*, or 401/403, or the local TTL elapsed | jar cleared, interaction blocked, reconnect prompt |
| **Rate limited** | HTTP 429 on the main frame, or a rate-limit notice in the page | banner naming **this** account and model |
| **Connection error** | DNS/TLS/timeout/render-process death | interaction blocked, jar kept |

The only function that changes a status is `SessionStateMachine.transition(...)`, reached through
`AccountRepository.dispatch(slot, event)`. Nothing else writes `status`, which is what keeps the
"we never claim more than we observed" rule enforceable.

## 2. What "separate sessions" means here — and what it cannot mean

**The finding.** Android *looks* like it offers per-WebView data partitions: `androidx.webkit`
1.12.1 ships `ProfileStore.getOrCreateProfile(name)` and `Profile.getCookieManager()`. Investigated
against the real API surface (`androidx.webkit` `api/current.txt`, and
`WebViewFeatureInternal.java`):

- `WebViewCompat.getProfile(WebView)` is **read-only**. No public API binds an app-created
  `WebView` to a named profile — the profile a view uses is chosen by the framework.
- `ProfileStore`/`Profile` are gated behind `WebViewFeature.MULTI_PROFILE`, and
  `WebViewFeatureInternal` only enables that when `MULTI_PROCESS` is *also* enabled, i.e.
  `WebViewCompat.isMultiProcessEnabled(context) == true` — opt-in, device- and
  WebView-build-dependent, and off by default.
- The platform `android.webkit` package has no `Profile` class at all.

So **N parallel, simultaneously-live sessions is not implementable** on Android without unsupported
hacks. Two tempting shortcuts were rejected:

- *Attach a second `WebView` per slot and swap profiles anyway* — the feature check fails on most
  devices, so five slots would silently share one jar. Silently sharing is the exact bug this whole
  file exists to prevent.
- *Spoof the user agent / re-inject cookies while both slots are live* — that is concurrent
  multi-session by trickery, and the residue (localStorage, IndexedDB, service workers, HTTP cache,
  in-memory site state) stays shared. It would report isolation it does not have.

**The design that follows:** *temporal* isolation. Exactly one slot owns the shared jar at any
moment, and switching is a state machine, not a reassignment.

```
select(other)                       open for slot N
  ├─ capture(active)   ───────────►  live jar → encrypted blob for the outgoing slot
  ├─ removeAllCookies + flush        wipe the shared jar (and site storage, if enabled)
  ├─ restore(N)        ───────────►  blob → prune → inject → flush
  ├─ status = f(RestoreOutcome)      Applied/NothingStored/Expired/Failed → see §3
  └─ bind(N)                          N now owns the live jar
```

`WebViewSessionIsolation` guards this with an explicit invariant: capture is only honoured while the
requesting slot is the bound one, and `restore()` binds the incoming slot on **every** non-fatal
outcome — including `NothingStored`, which is what makes capture-on-cold-start correct. The JVM test
double in `app/src/test/.../data/TestDoubles.kt` *enforces* that binding rule (`check(active == slot)`)
so a regression that crosses jars fails a test rather than shipping quietly.

## 2b. Accounts do not own projects

Nothing in this document is a reason to start a new piece of work. The project workspace
(`ProjectPolicy` / `ProjectRepository`, see [ARCHITECTURE.md](ARCHITECTURE.md) §2b) sits above the slot
machinery: switching slots annotates the active project with "this account touched it last" and changes
no other field — not the id, not the name, not the instructions, not the file list, not the pinned
model, not the linked Arena conversation, and not the local history rows filed under it. A project is
never created by a switch, never selected by one, and never deleted by a logout.

What account switching *does* change is reachability: an Arena thread belongs to the account that
created it, so after a switch the app says *"this conversation belongs to Account 2"* instead of
opening a page that would render empty. Arena publishes no API to move or import a conversation, so
the app does not offer a button that would fail. That distinction — local context always preserved,
remote context only where the service allows it — is the honest core of the feature, and both halves
are asserted in `ProjectPolicyTest` / `ProjectRepositoryTest`.

## 3. What switching never does

- **Never merges cookies.** Two slots may hold a cookie with the same *name* (every session cookie
  is called `sid` or similar); they never hold each other's *values*. `restore()` wipes before it
  injects, and `CookieJarCodec.overlap(...)` exists so the isolation tests can name the difference
  explicitly.
- **Never auto-selects.** `AccountPolicy.selectionAfterRemoval(...)` returns `NeedsUserChoice` when
  the selected slot is removed, and `AccountRepository.remove(...)` then sets `selectedSlot = null`.
  The Accounts screen must ask. There is no "pick the next one for me" path in the code.
- **Never creates-and-activates.** `add()` makes a slot; it stays `Not connected` and unselected
  until the user selects it. Five accounts you did not choose are worse than four you did.
- **Refuses a sixth slot** instead of evicting the oldest (`AddOutcome.Full(5)`).
- **Never re-labels a background slot.** `SwitchedAway` snapshots and keeps the status, because a
  slot you are not looking at has not stopped being logged in.

## 4. Residue the app cannot remove (read this before trusting isolation)

The jar is per-slot. Everything else about the origin `arena.ai` is per-app, because the platform
gives no per-view partition:

| Store | On switch | Residual risk |
| --- | --- | --- |
| Cookies | wiped, then the incoming slot's jar is injected | none for the app's own file, **but** a service worker still alive in the process could hold state in memory |
| `localStorage` / IndexedDB / Cache Storage | `WebStorage.deleteAllData()` when *Wipe site storage on switch* is on (default) | if you turn it off, slot B can read what slot A's page wrote — by design, with a warning in the row |
| HTTP cache | dropped with the same call | a cached authenticated HTML document could be reused if storage is preserved |
| In-memory page state | the WebView is destroyed and rebuilt for the slot | nothing survives an explicit rebuild; a page the user left open in *another* slot's session is never kept alive |
| Service workers | not started by this client beyond what the site does; `wipeLive` deletes profile data when MULTI_PROFILE is available | a worker installed while signed in as A outlives the switch on devices without profile support |
| Android account manager, other apps | untouched | out of scope; this app never reads them |

Settings → *Connection diagnostics* prints, per slot, the status, the stored cookie count and the age
of the last probe, plus the platform capability line — so the honest answer to "is this device doing
real per-profile isolation?" is visible on screen rather than buried here.

## 5. Operations

| Action | What happens |
| --- | --- |
| **Add slot** | `AccountPolicy.add` → validated label (2-24 chars, no `< > LF TAB`), lowest free slot, persisted. No selection change. |
| **Select** | sequence in §2; `SelectOutcome.Success(slot, restore)` |
| **Log out** | see [AUTHENTICATION.md](AUTHENTICATION.md) §5 |
| **Remove** | blob + live jar released, row deleted, selection cleared if it was the selected slot |
| **Rename / note / e-mail hint** | local labels only. `emailHint` accepts a shaped string and ignores junk, so a typo cannot erase the label; the app never verifies it against the service. |
| **Reconnect** | reload with the slot's jar, then re-probe |
| **Verify now** | runs the read-only probe against the live page |

`observedLabel` — the account label the probe could read off the page — is displayed when present and
is never used for anything but disambiguation. It is not an assertion about who owns the account.

## 6. Quota, and why five slots are not five quotas

`web/RateLimitMonitor.kt` records throttling per slot. It can offer `alternatives(...)` — the *other*
slots the user has themselves verified — but `automaticallyRotatesAccounts` is hard-coded `false`, and
no call site retries a prompt on another slot. Switching is a user action with a tap, and the banner
copy states that five profiles do not multiply the quota. Auto-rotation across accounts to spread a
rate limit would be abuse of the service, not a feature, so the capability is not in the app.

## 7. Test coverage

`app/src/test/java/com/kartikkamboj/arena/data/AccountRepositoryTest.kt` and
`domain/AccountPolicyTest.kt` cover: the five-slot ceiling (refusal, not eviction), stable slot
allocation, no auto-select on create or remove, capture-then-wipe-then-inject ordering, "inactive slot
cannot capture", jar non-merging across a round trip, logout removing the blob while leaving other
slots intact, `Loaded ≠ Connected`, 401 → `SESSION_EXPIRED`, 429 → `RATE_LIMITED` without a clear,
cold start never presenting a stored jar as a live login, and TTL-forced expiry. See
[TESTING.md](TESTING.md) for how to run them.
