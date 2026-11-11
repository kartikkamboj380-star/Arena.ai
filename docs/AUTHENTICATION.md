# Authentication

Signed-in state belongs to arena.ai. This document says what the app does for the site's login, what it
refuses to do, and what it will never claim. It also records a real bug this file's earlier version
helped hide: a stateless trust rule strands a sign-in mid-flight, and a stranded sign-in does not look
like a failure — it looks like nothing happened.

## The one fact everything else follows from

An app's `WebView` and the user's browser have **separate, unreachable-from-each-other cookie stores**.
Android exposes no API to move a session between them: `CookieManager` is per-app, `CustomTabsService`
has no import, and `androidx.webkit.Profile` is read-only. So:

> A sign-in that completes in a browser tab can never make this app's window signed in. The only session
> this app can show is one that was created **inside its own WebView**.

That is not a limitation this app works around; it decides the whole design. Everything below is either
"keep the chain inside this window" or "say plainly when that was not possible".

## Why a browser hand-off cannot be repaired on the way back

Two candidate return paths, both checked on 2026-11-10, both closed to this app:

| Path | What was checked | Result |
| --- | --- | --- |
| Android App Links (the browser navigates back into the app) | `https://arena.ai/.well-known/assetlinks.json` → `{}`; `https://auth.arena.ai/.well-known/assetlinks.json` → `{"error":"requested path is invalid"}` | No Digital Asset Links statement exists for either host, so **no** Android package is verified for `arena.ai` links. The `<data>` filter in this app's manifest is a *willingness* to receive `ACTION_VIEW` links; verification is the site's to publish, and it publishes none. |
| A custom scheme (`arena://`) registered as the OAuth redirect | not attempted | Google accepts a redirect URI only for the OAuth client the **site** owns. This app is not that client, so any scheme it invented would be refused by Google, and inventing one would also mean the app, not the site, deciding where a code lands. |

`{"error":"Route not allowed"}` from `https://arena.ai/api/auth/providers`, `/session` and `/error` shows
the site does have an `/api/auth/*` surface, guarded against callers outside its own pages — which is
also why no endpoint here can be called to exchange or confirm anything. **There is no API for this app to
use, and none is invented.**

## What could not be verified, and how the design accounts for that

The exact redirect sequence cannot be enumerated from outside a browser: the sign-in control is created by
the site's client-side code, and every path probed on `auth.arena.ai` answers `{"error":"requested path is
invalid"}` (a strict path allowlist). So this file does **not** contain a claim like
"`arena.ai` → `accounts.google.com` → `arena.ai/callback` → `arena.ai`", because nobody has observed that
sequence end to end from this repository.

What *is* published, and what the app therefore keys on, is Google's own discovery document
(`https://accounts.google.com/.well-known/openid-configuration`, fetched 2026-11-10):

| Field | Value |
| --- | --- |
| `issuer` | `https://accounts.google.com` |
| `authorization_endpoint` | `https://accounts.google.com/o/oauth2/v2/auth` |
| `device_authorization_endpoint` | `https://oauth2.googleapis.com/device/code` |
| `token_endpoint`, `revocation_endpoint` | `https://oauth2.googleapis.com/…` |
| `userinfo_endpoint` | `https://openidconnect.googleapis.com/v1/userinfo` |
| `jwks_uri` | `https://www.googleapis.com/oauth2/v3/certs` |

Those hosts live under `google.com` and `googleapis.com`. The app's rule is written against **that**
structure rather than against a guessed host list, because a chain that leaves the window on an
unanticipated hop is indistinguishable, from the user's side, from a broken app.

## The bug this file documents, and its root cause

**Reported:** tap Sign in → Google's page opens in the external browser → pick an account → Continue → the
app never comes back and is still signed out.

**Root cause, in the app, not in the site:** `NavigationPolicy` decided each navigation in isolation
against an exact-host list of the two entry hosts (`arena.ai`, `www.arena.ai`). Arena's auth service is on
**`auth.arena.ai`**, which is not a member. So the *first* hop of the flow — before Google was even involved — was classified
`HandOffToBrowser` and launched a Chrome Custom Tab (`MainActivity.openExternally`), and the Google leg
ran inside that tab. Nothing in the code could bring it back: there is no verified App Link (above), no
registered redirect URI, no cookie import. `onHostStart()` then re-attached the same, still-unauthenticated
page, which is why the symptom was "no return" rather than "an error".

Two smaller defects compounded it:

- The policy had no notion of "a chain is in progress", so every hop needed to be on the list; one
  omission broke the flow.
- `shouldOverrideUrlLoading` was consulted for sub-frames as well as the main frame, so a frame inside a
  page could change the window's trust context.

**Fixed by:**

| Change | File |
| --- | --- |
| Trust the site's **domain family** (`arena.ai` and any `*.arena.ai`, matched on label boundaries so `arena.ai.evil.net` and `notarena.ai` fail) | `web/ArenaSite.kt`, `web/ParsedUrl.kt` |
| Allow the provider's own domains **only while the window is already showing provider UI** — sticky for the chain, not a licence for `google.com` in general | `web/NavigationPolicy.kt` |
| Decide the main frame only; leave sub-frames to the WebView's own origin rules | `web/ArenaWebClients.kt` |
| Never strand a hand-off: a provider leg that still leaves the app sets `awaitingProviderReturn`, and the strip over the page gains **Finished there**, which returns the window to the site and states the limit above | `web/BrowserState.kt`, `ui/ArenaApp.kt` |
| Log every navigation as `host/path` **with the query dropped**, plus the decision, the intent that started a load, and which browser route was taken | `web/ArenaSite.forLog`, `core/AppLog.kt` |

The reducer has no state that could represent a login (`PagePhase` has no `AUTHENTICATED`/`SIGNED_IN`
value), and a `BrowserReducerTest` case asserts that, so "report success because a redirect looked right"
is not a change that can be made by accident.

## The comparison: the same site signs in when packaged, and 400s here

Reported: packaging `arena.ai` with a WebView-wrapper tool signs in with Google successfully, while this
app stopped at `Error 400` from `accounts.google.com`. The wrapper's own source was **not** available, so
nothing below is a claim about what that tool does internally; it is a claim about what *this* app was
missing, checked against the platform's own documentation and against the published guidance for the same
class of wrapper. The eight places a WebView host can differ, dimension by dimension:

| # | Dimension | This app, before the fix | Needed for a popup sign-in to complete | How that was established |
| --- | --- | --- | --- | --- |
| 1 | WebView configuration | one `configure()` for the single view | the same settings must apply to *every* view a flow touches | read from `BrowserEngine.configure` |
| 2 | Third-party cookies | on, per view, root view only | on for the view that actually receives the provider's reply — the popup, not the page | `setAcceptThirdPartyCookies` is per-view by API design |
| 3 | User-Agent | untouched, so it carries the `; wv)` marker | a wrapper world that "works" often rewrites this; **rewriting it is a policy violation** and is not what this app does | our code; Google's embedded-user-agent rule |
| 4 | JavaScript / DOM storage | both already on | both on | our code — so neither could be the difference |
| 5 | Popup / `window.open` | **`setSupportMultipleWindows(false)`, no `onCreateWindow`, no `onCloseWindow`** | a real second window, handed back through `WebView.WebViewTransport`, so `window.opener` exists | our code; `WebChromeClient` reference; the reports below |
| 6 | External browser / Custom Tabs | used only for a hand-off | a Custom Tab has no `window.opener` to post into, so it cannot carry a *popup* flow either | `CustomTabsService` contract; §why a hand-off cannot be repaired |
| 7 | Redirect / callback handling | no registered redirect URI, by design | unchanged: a same-site callback already renders in-app | `NavigationPolicy`, and the missing `assetlinks.json` probes |
| 8 | Lifecycle / navigation policy | `shouldOverrideUrlLoading` main-frame only; Back = site history | the child view must be governed too, and Back must not trap the user behind a sheet | our code; `onCreateWindow` guidance |

**The exact difference is row 5.** Google Identity Services signs in through a popup: the page calls
`window.open()`, the provider page posts the credential back to `window.opener`, and the popup closes
itself. With multiple windows refused there is no second window, so nothing receives that post — and the
provider's own fallback path, a top-frame navigation to `accounts.google.com/gis_transform`, is what
answers `400 malformed request`. That failure shape is documented outside this repository in exactly the
situation we were in: a popup sign-in opened inside a single-tab in-app browser ends on `gis_transform`
with a 400, and the reported remedy is either a redirect flow or a host that supports the window. The same
pair of settings — `setSupportMultipleWindows(true)` plus an `onCreateWindow` that hands back a `WebView` —
is what the published integration guides for other embedded-SDK sign-ins name as "the most critical
configuration", and what the long-standing open-source WebView wrapper threads about "Google Login issue"
land on.

Rows 1–4 and 7–8 were already correct or already permissive, which is why they cannot explain a 400: a
setting that matches cannot be the difference. Row 3 is the one place a working packaged app may genuinely
differ from this one, and this app will not follow it: Google refuses authorization requests sent to an
embedded user agent, and stripping `; wv)` to hide that is itself prohibited. Row 3 is therefore handled
as a *stated limit* (`PagePhase.AUTH_REFUSED`, §the known hard limit), not as a bypass.

**What this fix does.** `onCreateWindow` is honoured when — and only when — `isUserGesture` is true, into
at most one transient window built by the same `configure()`, wired back with `WebView.WebViewTransport`
before the callback returns, labelled with the host it is really on, and destroyed by `onCloseWindow`,
Back, a refused or handed-off navigation, a dead renderer or view teardown. A gesture-free request stays
refused, which is the browser default and the platform's own advice. The window is a first-class citizen
of the navigation policy (its own child `ArenaWebViewClient`) and gets its own third-party-cookie grant —
that grant is what row 2 was always meant to cover and could not reach while there was only one view.

**Why the granted path is not a unit test.** `WebView.WebViewTransport` — the object the host fills with
the new view — is an *inner* class of `WebView` that only the framework constructs; a test cannot make one
("Constructor of the inner class can only be called with a receiver of the containing class"). So the
granted path is pinned where pinning is possible: the CI promise step requires the transport to be wired,
`configure(popup)` to be what builds the window, and `popup.destroy()` to exist; the refusal paths, which
need no transport, are instrumentation tests; and the visible result is device item 4c in
[TESTING.md](TESTING.md). That is a real limit of `android.webkit`, stated rather than worked around.

**The falsifiable part, for the device run.** If the logcat line `popup window created` appears when
pressing *Continue with Google* and the sign-in still fails, this app is not in the popup case and the
remaining candidate is row 3 (the redirect flow meeting the embedded-agent policy) — in which case no
WebView setting of ours will fix it and the answer is the site's own browser-based sign-in, which the strip
already offers. The line `popup answered 400 on accounts.google.com [oauth params: …]` is what tells
those two apart: `ux_mode`/`origin` present means a popup request, while a bare
`client_id,response_type,redirect_uri,scope,state` on a top-frame load means a redirect request, and a
`redirect_uri_mismatch` on it is the site's OAuth configuration, not this app.

## What the app does

| Step | Implementation |
| --- | --- |
| Reach the sign-in form | nothing special: the site's own account control is a normal link in the page |
| Stay on the site's auth service | any `*.arena.ai` over https is the site — including `auth.arena.ai` |
| Follow the provider's redirect | the hosts Google publishes, plus (mid-chain only) anything under `PROVIDER_DOMAINS` |
| Let the redirect chain work | `CookieManager.setAcceptThirdPartyCookies(view, true)` — per view, so it covers the popup window when a popup is the flow; the global default is untouched |
| Give a popup sign-in a window to reply into | `setSupportMultipleWindows(true)` + `onCreateWindow`, for a user gesture only, one at a time, same `configure()`, same policy, same per-view cookies |
| Tear that window down from every direction | `onCloseWindow`, Back, a refused/handed-off navigation, `onRenderProcessGone`, `destroyView()` and `shutdown()` all close it; `onLowMemory` clears its cache too |
| React to a refusal | a 4xx on the main frame while on a provider host → `PagePhase.AUTH_REFUSED` → the panel that explains it and names the alternative |
| Not lose a session to a memory kill | `CookieManager.flush()` on every `onStop`, before the process becomes a reclamation candidate |
| Leave the flow alone | no `onReceivedLoginRequest` interception, no form filling, no DOM reading, no `WebMessage` handler |

## What the app refuses to do

Recorded so a future contributor does not reintroduce any of it as a "fix".

- **No user-agent spoofing.** Pretending to be Chrome desktop to get past an embedded-view check is
  defeating an access control, not a compatibility shim. The UA is Android WebView's own.
- **No scraping or automating the provider.** No clicking a consent button, no filling a field, no
  `evaluateJavascript` that inspects the sign-in DOM.
- **No cookie extraction, import or export.** No `CookieManager.getCookie()` reads, no Keystore-encrypted
  jar, no transfer between profiles.
- **No fabricated success.** Nothing sets a "signed in" state, because the only honest indicator is the
  page itself, which the user is looking at.
- **No session borrowing from the browser.** See the first section.
- **No invented OAuth plumbing.** No redirect URI, no client ID, no `arena://` scheme, no token exchange.

## The known hard limit: Google's embedded-user-agent policy

Google's OAuth 2.0 policy states that a developer **must not direct a Google OAuth 2.0 authorization
request to an embedded user-agent under the developer's control**; secondary sources dated from 2023
onward report this enforced as a `403 disallowed_useragent` for requests from `WebView`/`WKWebView`,
effective 2023-07-24 for "Sign in with Google", and note that switching a popup flow to a redirect flow
does not help because both run in the same blocked view. Google's own mobile guidance points apps at
its native SDKs or the system browser instead
(`developers.google.com/identity/protocols/oauth2/native-app`, which links
`developer.android.com/identity/authorization` for Android).

Two consequences, stated without softening:

1. Even with the chain kept in this window, **Google may refuse it**, and no code in this app can or
   should change that. The refusal is surfaced as `AUTH_REFUSED` with the reason.
2. The supported alternative is the one that does not depend on Google: **the site's own e-mail/OTP form**
   in this window, which is a plain form post to the site and never touches Google's OAuth machinery. The
   refusal panel and the strip both say so. Making Google sign-in work *inside an app* properly needs
   something the site has to provide — a native client (Credential Manager / Sign In with Google for
   Android) whose ID token its own backend accepts, or App Links on `arena.ai` plus a site-owned
   one-time-code exchange. Neither is something a client can add unilaterally.

## Errors you may see, and what each means

| What the shell says | What actually happened | What to do |
| --- | --- | --- |
| "You are on accounts.google.com — the site's own sign-in page, not a copy of it." | the chain is running in this window, which is the intended path | finish it here |
| "Your sign-in provider refused this window" | a 4xx arrived while the frame was on a provider host (the `disallowed_useragent` case, among others) | use the site's e-mail form here, or keep using arena.ai in the browser |
| "Sign-in continued in your browser…" | a leg left the app because it was on neither the site's family nor the provider's domains | tap **Finished there** to return the window to the site; expect the site to still offer Sign in, and use its form here |
| "`x` is not arena.ai, so it opened in your browser" | an ordinary external link | nothing; that link belongs to someone else |
| "That page asked for the camera/microphone…" | the site requested a capability this app does not declare | use those features in a browser |
| "arena.ai did not load" + a reason | the load itself failed — DNS, timeout, TLS, HTTP 5xx | the card names which; retry only when the reason is retryable |

## Diagnostics

`adb logcat -s Arena` (tag from `core/AppLog`). Every line is host-and-path only:

```
nav arena.ai/battle -> auth.arena.ai/login [in-app]
nav auth.arena.ai/login -> accounts.google.com/o/oauth2/v2/auth [in-app]
hand-off medium.com/x [EXTERNAL_LINK]
browser hand-off via custom tab
intent android.intent.action.VIEW for arena.ai/battle
popup window created
popup request [oauth params: client_id,nonce,redirect_uri,response_type,scope,state,ux_mode]
popup answered 400 on accounts.google.com [oauth params: redirect_uri,response_type,client_id]
popup refused without a user gesture
popup window closed
```

The `[oauth params: …]` suffix is the *names* Google's request used, never their values — Google's failure
pages are indistinguishable from the outside and the parameter set is the only part of the request this app
may honestly report. `ArenaSite.oauthParameterNames` allow-lists the names and drops everything else, and
an `origin_mismatch`/`redirect_uri_mismatch`/`invalid_request` can be told apart from that line alone.

No query string is ever logged **in full**, because an OAuth callback carries `code`, `state` and
sometimes an `id_token` there: what is logged is the allow-listed set of parameter *names* on a provider
navigation, which cannot contain a value by construction (`ArenaSite.oauthParameterNames` takes the text
before each `=` and drops anything not on the list). `AppLog` additionally redacts
cookie/auth-header/JWT/email shapes as a second line of defence, and a JVM test asserts that a secret in a
value never reaches the reported string. Passwords, OTPs, cookies and tokens are never read, so there is
nothing of theirs to log.

## Testing status

Nothing in this file has been verified by completing a live sign-in on a device: this repository's
environment has no phone and no emulator, CI does not log in to anything, and the JVM tests assert the
*rules* (which host may render where, what may be logged, what the reducer may claim, that a popup's own
page cannot rewrite the site's state), not Google's behaviour. The popup hosting above is therefore
**root cause removed, awaiting a device run** — including the check that a popup window actually appears
for the site's Google button, which is the assumption the whole diagnosis rests on. The six-step sequence in the bug report is recorded as a device checklist item in
[TESTING.md](TESTING.md) §Authentication and remains **not performed**.
