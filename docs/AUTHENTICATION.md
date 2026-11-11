# Authentication

Signed-in state belongs to arena.ai. This document says what the app does for the site's login, what it
refuses to do, and what it will never claim.

## The design in one paragraph

The site is loaded in the app's one `WebView`, with its own cookies, its own local storage and its own
login form. If you sign in there, the session is the WebView's, in the profile the `WebView` uses, and it
survives an app restart because cookies and web storage are on disk. The app never sees a password, never
sees a token, and has no idea whether you are signed in — it does not look.

## What the app does

| Step | Implementation |
| --- | --- |
| Reach the sign-in form | nothing special: the site's own "Get started"/account control is a normal link in the page |
| Follow the provider's redirect | `NavigationPolicy` allows `accounts.google.com`, `accounts.gmail.com`, `oauth2.googleapis.com` to **render in this window**, and only those hosts |
| Let the redirect chain work | `CookieManager.setAcceptThirdPartyCookies(view, true)` — scoped to this one view, needed because the provider's bounce between two origins is third-party from the WebView's point of view. The global default is not touched |
| Keep the page usable if a popup is used | `setSupportMultipleWindows(false)`, so a `target="_blank"` provider window continues in the same view instead of needing a second `WebView` |
| React to a refusal | a 4xx on the main frame while on a provider host → `PagePhase.AUTH_REFUSED` → the panel that explains it and offers the browser |
| Not lose a session to a memory kill | `CookieManager.flush()` on every `onStop`, before the process becomes a reclamation candidate |
| Leave the flow alone | no interception of `onReceivedLoginRequest`, no form filling, no DOM reading, no `WebMessage` handler |

## What the app refuses to do

These were all ways the previous version of this project worked, or was tempted to work. They are
recorded here so a future contributor does not reintroduce them as a "fix".

- **No user-agent spoofing.** Pretending to be Chrome desktop to get past an embedded-view check is
  defeating an access control, not a compatibility shim. The UA is Android WebView's own.
- **No scraping or automating the provider.** No clicking a consent button, no filling a field, no
  `evaluateJavascript` that inspects the sign-in DOM. Reading the page to decide whether you are
  "really" signed in is exactly the private-API work this project rules out.
- **No cookie extraction, import or export.** No `CookieManager.getCookie()` reads, no Keystore-encrypted
  cookie jar, no transfer between profiles or slots. The previous release had all of that for its
  multi-account isolation; the isolation layer is deleted, so there is nothing to extract with.
- **No fabricated success.** Nothing sets a "signed in" state. There is no such state in this app,
  because the only honest indicator would be the page itself, which you are looking at.
- **No session borrowing from the browser.** Android gives an app no supported way to read Chrome's
  cookies; `CustomTabsService` has no session-import API. See below.

## The fallback, and its honest limit

If the provider refuses the embedded view — identity providers commonly refuse OAuth in embedded
web views, Google included, though nothing here has been confirmed against arena.ai specifically —
the panel offers **"Open arena.ai in your browser"**, which launches a Chrome Custom Tab.

What that does and does not achieve:

- It works: the tab is a real browser with your real profile, so the provider's own checks pass there.
- It does **not** sign this app in. The Custom Tab uses the browser's cookie jar; the `WebView` uses
  the app's. They are separate storage by design, and no API moves a session between them.
- So the panel says: use the site's e-mail form in this window, **or** keep using arena.ai in the
  browser. It does not promise that signing in over there fixes anything over here, and there is no
  "detect the browser session and adopt it" path — that is the claim this project will not make.

If Arena's own flow later supports a proper Custom Tab round-trip (e.g. a redirect back into the app via
an app link with a code the *site* exchanges), the app would follow it as an ordinary page navigation.
Nothing would be added here, because the site would be doing the work.

## Password managers and autofill

The app blocks nothing and enables nothing: the `WebView` is left with the platform's autofill
behaviour, so whether your password manager can fill the site's form inside an app is up to the
provider, the WebView build and the site's own attributes. It is not a place this app intervenes, and
it never reads what comes back.

## Errors you may see, and what each means

| What the shell says | What actually happened | What to do |
| --- | --- | --- |
| "Your sign-in provider refused this window" | a 4xx arrived while the frame was on a provider host | use the site's e-mail form here, or open arena.ai in the browser and keep working there |
| "accounts.google.com is not arena.ai, so it opened in your browser" | a provider host outside the small allow-list was reached | finish in the browser; return to the app and sign in there if you want it in-app |
| "That page asked for the camera/microphone…" | the site requested a device capability this app does not declare | use those features in a browser |
| "arena.ai did not load" + a reason | the load itself failed — DNS, timeout, TLS, HTTP 5xx | the card names which; retry only when the reason is retryable |

## Testing status

Nothing in this file has been verified against a live sign-in on a device: this repository's environment
has no phone and no emulator, and CI does not log in to anything. The steps to verify it are
[TESTING.md](TESTING.md) §Authentication (items 3–5), and they are recorded as **not performed**.
