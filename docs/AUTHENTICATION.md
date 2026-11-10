# Authentication

**Short version:** this app does not have an Arena password field, and it never will. Sign-in happens
on Arena's own page, inside a `WebView`, for one account slot at a time. The app only ever *observes*
whether the page looks signed-in.

If you are looking for the reason there is no "Log in with Arena" button that works offline, or no
API key field, this file is the answer. If Arena ever ships a documented OAuth client-credentials
flow or a public chat API, the design in [ARCHITECTURE.md](ARCHITECTURE.md) leaves a clean seam to
swap this layer out.

---

## 1. What Arena actually offers

Investigated on 2026-11-10 against `https://arena.ai/`:

| Fact | Consequence for this app |
| --- | --- |
| The site's own sign-in is **Google** or **e-mail** (a magic-link / password flow served by the site). | Those are the only identity paths the app supports. |
| There is **no documented public REST API** for chat, model listing or account usage, and no developer console for issuing tokens. | The app cannot "call the API". Everything goes through the authenticated web session. |
| `arena.ai` pages are HTTPS-only, with a bot/abuse challenge in front of some paths. | No cleartext requests, no automation of the challenge, no scraping shortcuts. |

**There is no supported programmatic login.** So the app does not attempt one. It uses the one
method that *is* available to a third-party client: the user authenticates the real website with
their real credentials, and the app keeps the resulting session separate per slot.

## 2. The supported flows this app implements

1. **User-initiated WebView sign-in (primary).** The user taps *Log in* on an account slot. The app
   loads `https://arena.ai/` in that slot's WebView. The user completes Arena's own e-mail flow in
   the page. The app stores nothing about the credentials; the session cookie stays in the platform
   cookie jar, which the app snapshots into an encrypted per-slot blob (see
   [ACCOUNTS.md](ACCOUNTS.md)).
2. **Hand-off to the system browser (Google sign-in).** Google's OAuth does not permit
   authentication inside embedded WebView flows and rejects them (the site's own logs would show
   `disallowed_useragent`). Rather than spoof a desktop browser, the app detects
   `accounts.google.com` / `oauth2.googleapis.com` / `www.google.com` navigations and opens them with
   Custom Tabs, then tells the user to finish in the browser and return.
   `NavigationPolicy.handoffExplanation()` is the copy for exactly that banner.
3. **Nothing else.** No device-code flow (Arena does not publish one), no password grant (it does
   not exist), no cookie theft from Chrome, no reading of another app's token, no scraping of
   undocumented private endpoints.

### Explicitly rejected, with reasons

| Temptation | Why it is not in this app |
| --- | --- |
| A login screen that collects an Arena password and posts it to a private endpoint | It would be a credential-harvesting UI even if the code meant well. Any input the app can read, the user's own browser can also supply. |
| Spoofing the Chrome user agent so Google accepts the WebView | Circumvention of a documented restriction on that identity provider. |
| Solving or bypassing the site's CAPTCHA / bot challenge | That is the site's protection, not an inconvenience to route around. |
| Injecting JS that reads `localStorage`/cookies and replays them against an internal API | Token extraction from another origin's storage. Never done, and `AuthProbe` is deliberately read-only with no `addJavascriptInterface` anywhere. |
| "Private API" chat calls reverse-engineered from the web app | Undocumented endpoints change without notice, and using them as a stable product is a ToS risk the user cannot consent to on our behalf. |

## 3. How the app decides "Connected"

The status is produced by a state machine (`domain/SessionStateMachine.kt`), and the only event that
yields `CONNECTED` is a *positive observation*.

```
load started        -> CONNECTING          (progress only, no claim)
page settled        -> unchanged + REPROBE (the probe decides, not the load)
probe: signed-in chrome, or a user label  -> CONNECTED      + SNAPSHOT_JAR
probe: site shows its own "Log In"        -> LOGIN_REQUIRED, or SESSION_EXPIRED if a jar existed
HTTP 401 / 403 on the main frame          -> SESSION_EXPIRED + CLEAR_JAR + PROMPT_RECONNECT
HTTP 429                                  -> RATE_LIMITED   (never treated as logout)
DNS / TLS / timeout / renderer death      -> CONNECTION_ERROR (never clears a stored jar)
cold start with a stored jar              -> NOT_CONNECTED "stored session, not verified yet"
                                            (SESSION_EXPIRED past the local TTL)
```

The probe (`web/AuthProbe.kt`) is a single read-only `evaluateJavascript` call that returns
`{ok, url, title, loginPrompt, userChrome, label, rateLimited, sessionExpired, textLength}`. It
reads the DOM the user is already looking at; it does not click anything, submit forms, issue extra
network requests or read input fields. Its result is discarded unless `location.href` is still on
`arena.ai`, so a redirect away cannot answer on the site's behalf.

**Why "page finished" is not a verdict:** an unauthenticated `arena.ai/` renders perfectly well and
returns 200. Treating `onPageFinished` as success is how clients end up falsely reporting "logged
in". The unit test `a page that settles is not a verdict` in
`SessionStateMachineTest.kt` locks that in.

## 4. Session expiry and re-authentication

- Android's `CookieManager` only exposes `name=value`, so `Expires`/`Max-Age` from the server are
  **not observable**. The app therefore stamps its own capture time and honours a locally configured
  TTL (Settings → *Session validity*, default 12 h, 1–72 h). Past that, the slot is shown as
  **Session expired** and the encrypted jar is dropped on the next interaction.
- A 401/403 on a main-frame response is the strongest signal available and expires the session
  immediately, clearing the jar.
- Re-authentication is one tap (*Reconnect*), which reloads the slot's page with the user still in
  front of Arena's own form. Nothing is replayed automatically.
- The conversation is never the casualty: prompts and drafts live in the app's local store and Arena's
  own history page, so a reconnect returns to the same thread.

## 5. Logout

`AccountRepository.logout(slot)` → state machine `LoggedOut` → `CLEAR_JAR` + `RECREATE_VIEW`:

1. `WebViewSessionIsolation.release(slot)` deletes that slot's encrypted jar blob and, where the
   platform exposes it, the profile's data (`deleteProfileData`),
2. if that slot owns the live jar, the shared jar is wiped (`removeAllCookies` + `flush`) and the
   binding is dropped — with `WebStorage.deleteAllData()` when *Wipe site storage on switch* is on,
3. the `RECREATE_VIEW` effect makes `SessionCoordinator` tear the view down and build a fresh one, so
   no in-memory page state survives,
4. the account row is reset to `cookieCount = 0`, `snapshotCreatedAtMillis = 0`, `observedLabel = null`.

Settings → *Erase cookies on logout* (on by default) is what forces step 1-2 even if a transition
would otherwise have kept the jar; turning it off deliberately leaves the blob on disk until it ages
out, which some people want when they sign out of one of five slots by accident.

There is no "log out of all slots" shortcut that skips this sequence; `wipeAll()` does the same thing
for every slot and is what Settings → *Clear local data* calls.

## 6. If you want to test it yourself

The only way to prove the auth path works is a real device with a real Arena account:

```bash
./gradlew :app:installDebug
adb logcat -s ArenaAI:V   # status transitions; AppLog is redaction-gated and debug-only
```

Expected behaviour, step by step, is in [TESTING.md](TESTING.md) §"Manual: authentication". That run
has **not** been performed by the current CI workflow — CI compiles the instrumentation tests and runs
the JVM unit tests, but it does not sign in to arena.ai, and it must not.
