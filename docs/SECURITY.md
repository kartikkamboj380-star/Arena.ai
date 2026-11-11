# Security

The threat this app has to think about is narrow but real: it displays an authenticated page for a
service, on a device that holds the user's files, camera and other apps. Everything below is either a
line in the source or a claim CI checks; nothing here is a promise about intent.

## 1. Who may render

`web/NavigationPolicy.kt` decides every main-frame navigation, and it is the only place that does.

| Target | Result | Code path |
| --- | --- | --- |
| `https://arena.ai`, `https://www.arena.ai`, **and any `*.arena.ai`** | rendered in app | `Decision.LoadInApp`, via `hostUnder(ArenaSite.SITE_DOMAIN)` |
| `http://arena.ai…`, `http://auth.arena.ai…` | upgraded to https, then rendered | the http branch refuses to display a session page over cleartext |
| `accounts.google.com`, `accounts.gmail.com`, `oauth2.googleapis.com` | rendered in app, so the site's own sign-in can finish | the published endpoints, `ArenaSite.AUTH_HOSTS` |
| anything else under `google.com`, `googleapis.com`, `gmail.com` | rendered **only while the window is already showing a provider page**; otherwise an external link like any other | `fromProviderPage` + `ArenaSite.PROVIDER_DOMAINS` |
| any other `https` site | opened in a Chrome Custom Tab, with the host named in the notice | `HandoffReason.EXTERNAL_LINK` |
| any other `http` site | handed to the browser rather than loaded unencrypted here | `HandoffReason.INSECURE_SCHEME` |
| `mailto:`, `tel:`, `sms:`, `geo:`… | given to the system handler | `HandoffReason.APP_HANDLER` |
| `javascript:`, `data:`, `file:`, `intent:`, unparseable | refused, with a sentence explaining it | `Decision.Block` |

A lookalike host (`arena.ai.example.com`, `notarena.ai`, `arena.ai.evil.net`) is *not* the site:
`ParsedUrl.hostUnder` compares the parsed host on a **label boundary** (`h == d || h.endsWith(".d")`),
never a substring, so nothing about a path, a query or a hyphen can earn trust.

Two consequences are accepted deliberately, and both are load-bearing:

- Subdomains are included because Arena's auth service is one (`auth.arena.ai` answers a gateway error of
  its own; the entry hosts do not serve sign-in). An exact-host list strands sign-in on the first hop —
  that was the reported bug — and a session made in the browser it fell back to cannot return.
- What that trades away is stated plainly: the app trusts whatever DNS for `arena.ai` says, so a hostile
  subdomain of Arena would be rendered as the app's own content. That is Arena's boundary, not this app's;
  a browser behaves the same way about cookies scoped to a parent domain. If this app ever gains a reason
  to care (a second tenant on the domain, say), the fix is an explicit list plus a site-published
  `assetlinks.json` — not a heuristic on the URL.
These cases are unit tests, run on every push (`NavigationPolicyTest`).

Nothing is *added* to that list at runtime: there is no remote config, no dynamic allow-list, no way for
a page to ask to be trusted.

## 2. No bridge, by construction

- `addJavascriptInterface` appears nowhere in `app/src/main`, so there is no object for a page to reach
  and no `@JavascriptInterface` surface to exploit.
- No `evaluateJavascript`, no `injectJavascriptObj`, no `postWebMessage`/`createWebMessageChannel`, no
  `shouldInterceptRequest`. The app cannot run script in the page and cannot rewrite what the page loads;
  the deleted v1 code did all three, and deleting them removed the attack surface rather than hardening it.
- `allowFileAccess = false`, `setGeolocationEnabled(false)`, and `javaScriptCanOpenWindowsAutomatically =
  false`, which is the platform's popup blocker: as Android's own documentation of that setting puts it,
  "attempts without a user gesture will fail and do nothing", and "this is not affected by the
  `setSupportMultipleWindows(boolean)` setting". Gesture-gated script, no gesture-free popups.
- `allowContentAccess` is left at the platform default (`true`): `content://` reads are what a working
  browser does, they are gated by the URI permission a picker grants, and `file://` — the scheme with a
  real attack surface — is closed above.
- `setSupportMultipleWindows(true)` is on *only* so a sign-in popup has a window to reply into, and what
  that window may do is decided here rather than by the page:
  - it is granted for a user gesture, and never a second at a time;
  - it is built through the same `configure()` as the page, so it gets the same settings, the same
    `ArenaWebViewClient` policy (a `target="_blank"` that wants to leave the app still leaves it — and
    takes the sheet down with it, because an empty sheet is worse than no sheet) and the same per-view
    third-party cookies;
  - it never rewrites the page's state: an error inside it is reported inside it;
  - the shell says which host it is on, which is what Android asks for instead of overlaying a window
    silently;
  - `onCreateWindow` carries no URL and Android says the requesting frame cannot be identified, so no
    policy is claimed over a URL this app was never given.
- `WebView.setWebContentsDebuggingEnabled(true)` only when `BuildConfig.DEBUG`, so a release APK cannot
  be inspected over DevTools.

## 3. Transport

- `res/xml/network_security_config.xml`: `cleartextTrafficPermitted="false"`; only `system` trust anchors
  in release, with `user` anchors confined to `debug-overrides`. A user-installed CA cannot read the
  session on a release build.
- TLS errors go to `handler.cancel()`. There is deliberately no "continue anyway" affordance — Android's
  own guidance is that a user cannot make that decision meaningfully, and a shell that offers it becomes
  the weak link.
- **No certificate pinning.** Pinning a service this app does not operate means pinning a key the
  project cannot rotate; a wrong pin bricks the app for every user. Stated here so it is not mistaken for
  an oversight.
- Safe Browsing is left at the platform default (the WebView shows its own interstitial); the app does
  not override `onSafeBrowsingHit` to hide or auto-accept it.

## 4. Permissions, and what each is for

| Permission | Why | Absence consequence |
| --- | --- | --- |
| `INTERNET` | load the site | nothing works |
| `ACCESS_NETWORK_STATE` | say "no network" instead of blaming the site for a radio that is off | error wording gets vaguer |
| `WRITE_EXTERNAL_STORAGE`, `maxSdkVersion="28"` | place a download in the public `Downloads` dir on API 26–28 | on 29+ the system download manager needs nothing, so most devices never hold it |

That is the whole list. No camera, no microphone, no notifications, no storage reads, no
`QUERY_ALL_PACKAGES`, no location, no foreground-service type. Consequences are stated rather than
hidden: a page asking for the camera or microphone is denied and told why
(`ArenaWebChromeClient.onPermissionRequest`), and no download progress notification belongs to this app
because the download belongs to the system.

## 5. Files, downloads, other apps

- **Uploads.** `<input type="file">` is answered with `ACTION_GET_CONTENT` restricted to the page's own
  `accept` list, through the system picker. The app receives the URIs and hands them straight to the
  WebView. It does not copy, cache, convert or read them, so the grant lifetime is the picker's, not a
  permission the app holds. A cancelled picker answers the callback with `null` — leaving a page's input
  hanging is both a bug and a way to keep a grant alive.
- **Downloads.** Sent to `DownloadManager` with the filename the *server* offered (`Content-Disposition`,
  including the `filename*=UTF-8''…` form), percent-decoded, reduced to its **last path segment** so a
  server cannot name `../../etc/passwd`, capped at 96 characters, extension from the mime type only when
  the name has none (`ArenaSite.suggestedFileName`, unit-tested both ways). No private `HttpURLConnection`
  downloader, so no second TLS stack and no destination the user cannot see; progress and completion are
  the system's notification, which keeps working when this app is gone.
- **Hand-offs.** Off-site links go to a Custom Tab; `intent:` and unknown schemes are refused rather
  than passed to `startActivity`, so a page cannot pick a component to launch.

## 6. What the app knows about you

Nothing it chose to keep. There is no database, no preferences file, no JSON store, no cache of
prompts, threads, models, accounts or history — the entire persistence layer of v1 was deleted, and the
app has no code path that writes a file of its own. What does exist lives in the WebView's own profile
(the site's cookies, local storage, HTTP cache) because a browser cannot work otherwise:

| Data | Where | Cleared by |
| --- | --- | --- |
| session cookies | the app's WebView cookie store, on disk (flushed on `onStop`) | the site's own expiry, or the user via system app storage |
| local/session storage, IndexedDB | the app's WebView data directory | same |
| HTTP cache | the WebView cache | the site's cache headers; given back first under memory pressure (`clearCache(true)`) |
| anything this app recorded | **nothing** | — |

`android:allowBackup="false"` and no `dataExtractionRules`/`fullBackupContent`: there is deliberately no
copy of this profile in cloud or device-to-device backups. **The cost of that, stated plainly:** a
restore onto a new phone gives you the app, not your sign-in, and the site's own "sign out everywhere"
cannot reach this profile. Wiping it yourself: system Settings → Apps → Arena → Storage → Clear storage
(or `adb shell pm clear com.kartikkamboj.arena.debug`). No "log out" button was added, because a native
button that clears storage would be a second, blunter logout than the site's own.

## 7. Logging

`core/AppLog.kt` is the only writer to `Log`. Verbose output is debug-only; warnings and errors survive
in release because they name failure types, and every string passes through `redact()` first:
cookie/`Set-Cookie` headers, `Authorization`/bearer/basic values, `*token|secret|password|code|otp|session|sid|key*`
in both quoted JSON and `key=value` shapes, JWT-looking runs, e-mail addresses, then truncated. Load
failures log the **host and path**, never the full URL, because a provider redirect URL is exactly where a
`?code=…` lives — `ArenaSite.forLog` does that truncation at the single place navigations are logged, so a
query never reaches the redactor to begin with. `AppLogTest` asserts a secret in each shape does not survive
the funnel — including
one crafted to defeat a weaker `refresh` pattern. There is no crash handler that uploads a stack trace,
no analytics, no telemetry endpoint.

## 8. Input the app parses

Small, and worth listing because each parser is a place a hostile page could reach:

- URLs — `ParsedUrl`, `java.net.URI` with a structural fallback; only scheme/host/path are read, never
  credentials (so `https://arena.ai@evil.example/` fails the host test rather than passing it).
- `Content-Disposition` — the two filename forms above.
- WebView progress, error codes, HTTP status, `RenderProcessGoneDetail` — numbers, mapped in
  `WebFailures`/`BrowserReducer`.
- No HTML, JSON, Markdown or cookie parsing at all. That sentence is the summary of this section: the
  app's parser surface is four rules, three of them about a host name.

## 9. Known gaps

- In-page camera and microphone do not work (by design, §4). *File* uploads do, because the picker needs
  no permission, and the site's own image tools work with files you attach that way.
- A provider that rejects embedded views outright leaves you with the site's e-mail form or the browser;
  the app cannot fix that and the panel says so.
- The WebView is the system component: its TLS and Safe Browsing behaviour is the device's, and a device
  with an outdated WebView provider is outside this app's control. Where a feature is optional the code
  asks for it before using it (`WebViewFeature.isFeatureSupported(ALGORITHMIC_DARKENING)` around the
  web-theme line) rather than assuming every device has it.
- Nothing in this file has been pen-tested or audited. What is verified: the trust rules and the
  download-name sanitising, by unit tests that run on every push.
