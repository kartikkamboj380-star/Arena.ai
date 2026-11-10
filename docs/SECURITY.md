# Security

What this app protects: the user's Arena session (cookies), the fact that they use five of them, and
the boundary between the site's origin and the Android platform.

## 1. Transport

- `res/xml/network_security_config.xml` sets `cleartextTrafficPermitted="false"` in `base-config`, and
  its only trust anchor is `system` — **there is no per-domain exception rule anywhere in the file**,
  and `android:usesCleartextTraffic` is not set. Consequence worth stating: a user-installed CA is not
  trusted, so a proxy with a locally installed root cannot sit in the middle of a session cookie in a
  release build. `<debug-overrides>` does trust user CAs, for the `debug` variant only; that is the
  documented escape hatch for inspecting your own traffic and it cannot apply to a release APK.
- `NavigationPolicy` re-checks at navigation time: `http://arena.ai/…` is upgraded to HTTPS, and any
  other cleartext URL is blocked rather than upgraded (silently rewriting a third party's URL would
  be its own failure mode).
- `WebSettings.setMixedContentMode(MIXED_CONTENT_NEVER_ALLOW)` — no passive mixed content either.
- `onReceivedSslError` always calls `handler.cancel()` and reports `ErrorKind.TLS`. There is no
  "proceed anyway" path anywhere in the codebase, on any build type. This is the single most
  important line in this file: a WebView client that offers to ignore a certificate error is a
  downgrade attack with a button on it.

## 2. Storage

| Data | Where | Encrypted | Backed up |
| --- | --- | --- | --- |
| Cookie jars (per slot) | `noBackupFilesDir/secure/arena_account_<n>.bin` | **yes** — AES/GCM 256, key in Android Keystore (`arena_ai_session_blob_key`, never extractable, `setRandomizedEncryptionRequired(true)`) | excluded by both `backup_rules.xml` and `data_extraction_rules.xml` |
| Account registry (labels, statuses, counters) | `noBackupFilesDir/arena/accounts.json` | no — contains no secrets by construction | excluded |
| History ledger, model catalog | same directory | no | excluded |
| Settings | `SettingsRepository` over `JsonFileStore` | no | excluded |
| Anything else | — | — | `android:allowBackup="false"` |

- **No password is ever collected, so none can be stored.** There is no field for one, and
  `SharedPreferences` holds no credentials (the setting store is a JSON file of UI preferences).
- **No hardcoded keys.** The Keystore alias is a constant; there is no key material in the repo. CI
  greps for key-shaped literals (`AIza…`, `sk-…`, `-----BEGIN PRIVATE KEY-----`) and fails.
- The blob format is `[4-byte tag][IV(12)][ciphertext+GCM tag]`. A blob that fails authentication, or
  carries the wrong tag, is **deleted** — never half-trusted. `SecureBlobStoreTest` covers tamper,
  truncation, foreign key and mislabelled file.
- `CookieJarCodec` refuses to *build* an entry whose name looks like a credential
  (`password`, `passwd`, `secret`, `otp`, `pin`) even when encrypted, and refuses values containing
  `;`, `,`, quotes, backslash, CR or LF, so a hostile page cannot smuggle a second cookie through the
  `Set-Cookie` line the app re-injects.

## 3. The origin boundary

- **No `addJavascriptInterface`.** Verified by grep, not by promise. There is no Java object for page
  script to reach, so a compromised or malicious frame on the device has nothing to call.
- `AuthProbe` and `PageExtractor.LIST_SCRIPT` are read-only `evaluateJavascript` snippets returning
  JSON. They do not read form fields or `input.value`, do not click or submit, do not touch
  `localStorage`, and do not issue `fetch`/`XHR` (a test asserts the absence of each of those strings
  in the scripts, so an edit that adds one fails CI).
- `allowFileAccess = false`, `allowContentAccess = false` — a page cannot read the app's own files or
  `content://` URIs. `file://` navigations are also blocked by policy, so this is two independent
  gates rather than one.
- Downloads are never opened inside the app: `DownloadListener` hands the URL to
  `DownloadManager` into `getExternalFilesDir(null)` with a notification, and the file is opened by
  whatever the user chooses, not by this app.
- Multi-window is **not** enabled (`setSupportMultipleWindows` is never called), so `window.open()`
  cannot create a second frame outside the policy. `ArenaWebChromeClient.onCreateWindow` resolves the
  link the gesture hit (`WebView.HitTestResult.SRC_ANCHOR_TYPE` → `getExtra()`) and follows it *in the
  slot's own view*, which the policy already gates; if the target cannot be read, the user is told
  rather than left with a dead button. No second `WebView` is ever constructed — not a stub, not a
  hidden one. That is both the security answer (one view, one policy, one jar) and the low-RAM answer
  (a second renderer is the most expensive object in this app).
- **Third-party cookies are enabled for the session `WebView` only**
  (`CookieManager.setAcceptThirdPartyCookies(view, true)`). This is required for an identity provider's
  redirect chain to work in-app; without it a sign-in silently returns the user anonymous, which is
  the bug this client used to have. It is scoped to this view — the global default is untouched — and it
  does not weaken slot isolation: cookies still live in the shared jar that is captured, encrypted and
  swapped per slot, so a third-party cookie set during Account 2's sign-in is stored *as Account 2's*
  material and is gone when the jar is cleared. The page still cannot reach the app (no
  `addJavascriptInterface`), and no third-party *navigation* is allowed outside the sign-in window.
- `onShowFileChooser` only ever satisfies the request with `content://` URIs the user picked through
  `ActivityResultContracts.OpenMultipleDocuments`; no file path is exposed to the page.
- Geolocation is refused (`onGeolocationPermissionsShowPrompt(origin, false, false)`), every
  `onPermissionRequest` is `deny()`-ed (camera, microphone, MIDI), and fullscreen is declined cleanly
  rather than left hanging. The `CAMERA` permission is declared with
  `<uses-feature android:required="false">` and no code path requests it at runtime; the upload picker
  is documents-only (`OpenMultipleDocuments`), so the manifest line is the seam for a future
  "attach a photo" flow, not a live capability. Diagnostics says which of these are on.

## 4. What leaves the device

Nothing that the app adds. There is no analytics SDK, no crash reporter, no telemetry endpoint, no
error ping. The only network traffic is the WebView loading `arena.ai` (and the identity provider the
user chose, in the browser). Settings → *Connection diagnostics* states this on screen, and
`Diagnostics` prints counts and labels only — it never renders a cookie value, a token, or a raw
error body.

## 5. Redaction

`AppLog` is a single funnel for logging, and every message passes through five regexes first:
`cookie`/`set-cookie` headers, `bearer|basic|token|api_key|authorization|session|sid` assignments,
JWT-shaped `eyJ…` runs, and e-mail addresses, plus quoted `"password"|"secret"|"code"|"otp"|…`
values. Throwable logging keeps the type and the redacted message, capped at 160 characters. On
release builds the whole object is a no-op before formatting.

`CookieJarCodec.summarise()` is the only representation of a jar that can reach a log or the
diagnostics screen: `name(length)`, never `name=value`.

`RedactionTest` asserts each pattern masks, and that ordinary diagnostic text
(`Loaded https://arena.ai/leaderboard in 812ms`) is *not* mangled — a redactor that eats everything is
useless, and a test that only checks the leak case would allow that.

## 6. Screenshot protection

`FLAG_SECURE` is applied from `MainActivity` while the setting is on (default): screen capture is
blocked and the recent-apps preview is blank. This is opt-out because a chat window can contain
someone else's prompt, and because the same flag also hides the loaded page from some accessibility
and automation surfaces — which is the trade-off the row in Settings explains.

## 7. WebView package and its limits

`IsolationCapability` records the actual WebView provider and version (`WebViewCompat
.getCurrentWebViewPackage()`), shown on the diagnostics screen. If the provider is outdated, that is
a device-level risk this client cannot fix; the app never patches or downgrades the engine, and never
disables a safety feature to work around one.

## 8. Known limits of *this* app's security model (no more, no less)

1. **The session lives in the platform cookie jar, not in an app-private one.** `CookieManager` is
   shared with other WebView users in this process; the app's mitigation is to make ownership explicit
   (`activeSlot`) and to wipe on switch, but a bug in that sequencing is the residual risk. The
   strict test double exists to make that sequencing fail loudly.
2. **A rooted or debuggable device can read memory** and therefore the live jar — as with every
   WebView-based client. Debug builds are `.debug`-suffixed and installable side by side; release
   builds are minified and, in CI, unsigned on purpose.
3. **Session validity is judged by visible page affordances.** If Arena changes the DOM, the probe
   degrades to "unknown" (status is held rather than guessed) — an availability bug, not a security
   one, and the conservative choice.
4. **No attestation.** The app cannot prove to Arena that a human is typing, and does not try to.
5. **Keystore fallback.** If the Keystore is unavailable, `AppContainer` falls back to a
   process-lifetime key: sessions then do not survive a restart, and the diagnostics screen prints
   `hardwareBacked=false` rather than pretending otherwise.
