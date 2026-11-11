package com.kartikkamboj.arena.web

import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.kartikkamboj.arena.BuildConfig
import com.kartikkamboj.arena.R
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.core.NetworkMonitor
import com.kartikkamboj.arena.core.WebErrors
import com.kartikkamboj.arena.core.WebFailure
import com.kartikkamboj.arena.core.WebFailures
import com.kartikkamboj.arena.web.NavigationPolicy.Decision
import com.kartikkamboj.arena.web.NavigationPolicy.HandoffReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owner of the one WebView this app will ever create.
 *
 * The view lives here rather than in the Activity for two reasons, both about not losing the user's
 * work: a configuration change (rotation, dark-mode flip, split-screen resize) must not reload the
 * site, and keeping the process's web stack alive while the app is backgrounded is what lets a
 * response that is already streaming finish arriving. What the shell displays is derived from
 * [BrowserReducer], so the words in the UI and the state of the view cannot drift apart.
 *
 * There is no JavaScript bridge, no injected script, no cookie extraction and no user-agent override in
 * this class. It constructs every `WebView` in the app — the one persistent page, plus at most one
 * transient popup window that lives only while the page that opened it keeps it open — and both go
 * through the same [configure], so no second window can be quietly less restricted than the first.
 * `android.webkit` is used exactly as documented and no more, which is also what keeps the whole app small.
 */
class BrowserEngine(
    context: Context,
    private val network: NetworkMonitor,
) {

    private val appContext = context.applicationContext

    private val _view = MutableStateFlow<WebView?>(null)

    /** The live view, or null when memory pressure gave it up. The host composable observes this. */
    val view: StateFlow<WebView?> = _view.asStateFlow()

    private val _browser = MutableStateFlow(BrowserState())

    /** Everything the shell renders comes from here: one reducer, one source of truth. */
    val state: StateFlow<BrowserState> = _browser.asStateFlow()

    private var chooser: ValueCallback<Array<Uri>?>? = null
    private var loadedOnce = false

    /**
     * The transient window a page asked for with `window.open()`. At most one exists, it is never
     * persisted, and it is destroyed the moment the page or the user closes it. It exists because
     * Google's sign-in button is a popup: with multiple windows unsupported, the popup *replaced* this
     * view's page, so the credential the popup posts back to its `window.opener` had nowhere to
     * arrive — the flow ended on Google's `gis_transform` 400. docs/AUTHENTICATION.md §"the popup is
     * the flow".
     */
    private var popupView: WebView? = null
    private var popupFrame: ViewGroup? = null

    /** Set by the Activity: opens a Custom Tab or a system intent, depending on the reason. */
    var onOpenExternal: ((url: String, reason: HandoffReason) -> Unit)? = null

    /** Set by the Activity: the document picker that answers a file input on the page. */
    var onPickFiles: ((acceptTypes: Array<String>, allowMultiple: Boolean) -> Unit)? = null

    val currentView: WebView? get() = _view.value

    // ------------------------------------------------------------------ view

    /**
     * The single view, created on first use. Application context plus a theme wrapper, so a view held
     * by a process-scoped object can never pin an Activity — the leak a "saved WebView" normally is.
     */
    fun ensureView(): WebView {
        _view.value?.let { return it }
        val themed = ContextThemeWrapper(appContext, R.style.Theme_Arena)
        val webView = WebView(themed)
        configure(webView)
        _view.value = webView
        AppLog.d("web view created")
        return webView
    }

    private fun configure(webView: WebView) {
        val settings: WebSettings = webView.settings
        with(settings) {
            // The site *is* a JavaScript app; there is no server-rendered version to fall back to.
            javaScriptEnabled = true
            // The site keeps its own state — including its session — in web storage. Web SQL, which
            // `databaseEnabled` used to switch on, is gone from Chromium: the setting has done nothing
            // since API 21 and is deprecated since 30, so it is not set rather than set and ignored.
            domStorageEnabled = true
            // Popups must be real windows: `window.open()` is how Google's sign-in button works, and a
            // popup that overwrites the page instead has no `window.opener` to post its credential to.
            // The window it asks for is granted only for a user gesture, and only through [requestPopup],
            // which shows the host of what opened so a second page never masquerades as the site.
            setSupportMultipleWindows(true)
            // As in a browser: a script may not open a window unless the user asked for it.
            javaScriptCanOpenWindowsAutomatically = false
            // Nothing on the page may reach the device's files, and there is no bridge to abuse.
            allowFileAccess = false
            // Left at the platform default (true) rather than switched off: `content://` reads are the
            // stock behaviour every working wrapper keeps, they are gated by the URI permission the
            // picker grants, and `file://` — the one with a real attack surface — stays closed above.
            allowContentAccess = true
            setGeolocationEnabled(false)
            // Audio the page plays waits for a tap on the page, which is what a chat UI does anyway.
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                // Lets the page use its own dark theme when the app is in dark mode. This is the
                // supported switch; nothing here force-recolours content that has no dark theme.
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
            }
        }
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.webViewClient = ArenaWebViewClient(this)
        webView.webChromeClient = ArenaWebChromeClient(this)
        webView.setDownloadListener { url, _, disposition, mime, length ->
            startDownload(url, disposition, mime, length)
        }
        // Third-party cookies are required for the site's own sign-in chain to complete in a WebView:
        // the reply comes back on a request whose first-party is `accounts.google.com`, so a cookie set
        // for the site is third-party to it. Per-view, because that is the only scope the API has — and
        // it therefore has to be applied to the popup window too, which is where that reply actually
        // lands when sign-in is a popup. The app never reads a cookie value anywhere.
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    /** Reparent the view into [frame]. Idempotent, so it is safe on every recomposition. */
    fun attachInto(frame: ViewGroup) {
        val webView = _view.value
        for (i in frame.childCount - 1 downTo 0) {
            if (frame.getChildAt(i) !== webView) frame.removeViewAt(i)
        }
        if (webView == null) return
        (webView.parent as? ViewGroup)?.removeView(webView)
        frame.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** Detach because the composition is going away. The view itself is deliberately kept alive. */
    fun detachFrom(frame: ViewGroup) {
        _view.value?.let { if (it.parent === frame) frame.removeView(it) }
    }

    // ------------------------------------------------------------------ loads

    fun openHome() = load(ArenaSite.HOME)

    /** The only entry point for "go somewhere": the same policy as a clicked link is applied. */
    fun load(url: String) {
        val target = url.trim()
        if (target.isEmpty()) return
        val fromProviderPage = ArenaSite.isProviderPage(_browser.value.url)
        when (val decision = NavigationPolicy.classify(target, fromProviderPage)) {
            is Decision.LoadInApp -> {
                loadedOnce = true
                AppLog.d("load ${ArenaSite.forLog(decision.url)}${providerTrail(decision.url)}")
                onLoadStarted(decision.url)
                ensureView().loadUrl(decision.url)
            }

            is Decision.HandOffToBrowser -> openExternal(decision.url, decision.reason)
            is Decision.Block -> {
                AppLog.w("load refused: ${decision.reason}")
                say(NavigationPolicy.blockExplanation(decision.reason))
            }
        }
    }

    fun stopLoading() {
        _view.value?.stopLoading()
    }

    /**
     * Android's Back button. True when the page went back through its own history, so the Activity
     * only finishes once the site's history is exhausted — browser behaviour, and the reason no
     * native navigation stack is needed on top of the page.
     */
    fun goBack(): Boolean {
        val webView = _view.value ?: return false
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    fun retry() = dispatch(BrowserEvent.RetryRequested)

    fun dismissNotice() = dispatch(BrowserEvent.NoticeDismissed)

    fun dismissDetail() = dispatch(BrowserEvent.DetailDismissed)

    // ------------------------------------------------------------------ state

    fun dispatch(event: BrowserEvent) {
        val reduction = BrowserReducer.reduce(_browser.value, event)
        _browser.value = reduction.state
        reduction.effects.forEach { effect ->
            when (effect) {
                is BrowserEffect.Reload -> load(effect.url)
                is BrowserEffect.Say -> _browser.value = _browser.value.copy(notice = effect.text)
            }
        }
    }

    fun onLoadStarted(url: String?) =
        dispatch(BrowserEvent.LoadStarted(url.orEmpty().ifEmpty { _browser.value.url }))

    fun onLoadProgress(percent: Int) = dispatch(BrowserEvent.Progress(percent))

    fun onPageFinished(url: String?) {
        dispatch(BrowserEvent.PageFinished(url.orEmpty().ifEmpty { _browser.value.url }))
        dispatch(BrowserEvent.CanGoBackChanged(_view.value?.canGoBack() == true))
    }

    /** `onReceivedError`. Sub-resource failures are ignored on purpose: they are not the page. */
    fun onLoadFailed(errorCode: Int, description: String?, forMainFrame: Boolean, failingUrl: String?) {
        if (!forMainFrame) return
        val failure = WebFailures.fromErrorCode(errorCode)
        AppLog.w("load failed: $failure on ${ArenaSite.displayHost(failingUrl ?: _browser.value.url) ?: "the page"}")
        dispatch(
            BrowserEvent.LoadFailed(
                url = failingUrl ?: _browser.value.url,
                detail = WebErrors.describe(failure, description, !network.isCurrentlyAvailable()),
                failure = failure,
            ),
        )
    }

    /**
     * `onReceivedHttpError`. A 4xx on an identity-provider page is the one signal this app treats as
     * information about sign-in — it is a status code, not a peek into the page — and it is reported
     * as a refusal rather than as a failure to load.
     */
    fun onHttpError(status: Int, forMainFrame: Boolean, url: String?) {
        if (!forMainFrame) return
        when {
            ArenaSite.isAuthPage(url) -> dispatch(BrowserEvent.AuthPageRejected(status))
            status >= 500 -> dispatch(
                BrowserEvent.LoadFailed(
                    url = url ?: _browser.value.url,
                    detail = "The site answered HTTP $status. Nothing in this app can change that; try " +
                        "again in a moment.",
                    failure = WebFailure.UNKNOWN,
                ),
            )
        }
    }

    /**
     * The renderer process died — on a 3 GB phone this is the failure most likely to happen mid-answer.
     * Android requires the dead view to be removed and discarded; a fresh one replaces it and the
     * reducer decides whether to reload automatically (once) or to ask the user first.
     */
    fun onRendererGone(detail: RenderProcessGoneDetail) {
        val text = if (detail.didCrash()) {
            "The page's rendering process crashed."
        } else {
            "The system reclaimed the renderer process, which happens under memory pressure."
        }
        destroyView()
        dispatch(BrowserEvent.RendererGone(text))
    }

    fun say(text: String) = dispatch(BrowserEvent.Notice(text))

    // ------------------------------------------------------------------ external

    fun openExternal(url: String, reason: HandoffReason) {
        val host = ArenaSite.displayHost(url)
        say(NavigationPolicy.handoffExplanation(reason, host))
        AppLog.d("hand-off ${ArenaSite.forLog(url)} [$reason]")
        // The case this app must never strand: a leg of a sign-in chain leaving for the browser, where
        // the browser's session is unreachable from here by design. Record it, so the shell can offer
        // the user an honest way back instead of leaving a provider page on screen forever.
        if (ArenaSite.isProviderPage(_browser.value.url) || ArenaSite.isAuthPage(url)) {
            dispatch(BrowserEvent.ProviderHandoff)
        }
        val handler = onOpenExternal
        if (handler == null) {
            AppLog.w("no window available to hand off to; nothing was opened")
            return
        }
        val launched = runCatching { handler(url, reason) }
        if (launched.isFailure) AppLog.w("hand-off failed", launched.exceptionOrNull())
    }

    /**
     * The user says the sign-in ended in their browser. Nothing here claims an outcome: the window goes
     * back to the site so the page itself can show what it knows, and the one sentence said is about
     * the platform limit. Load first, then speak — a new navigation clears the previous notice.
     */
    fun acknowledgeProviderReturn() {
        openHome()
        dispatch(BrowserEvent.ProviderReturnAck)
    }

    /**
     * For a provider navigation, the *names* of the OAuth parameters in its query — never the values.
     * Google's failure pages all look like `Error 400` from the outside, and the parameter set is what
     * tells `invalid_request` from `redirect_uri_mismatch` from a popup that fell back to a redirect.
     */
    private fun providerTrail(url: String?): String {
        if (!ArenaSite.isProviderPage(url)) return ""
        val names = ArenaSite.oauthParameterNames(url)
        return if (names.isEmpty()) " [oauth params: none]" else " [oauth params: $names]"
    }

    // ------------------------------------------------------------------ popups

    /**
     * Answer to `window.open()`. Honoured only for a request the user caused, and only while no other
     * popup is open — one window at a time keeps the memory story the rest of this class is built on.
     *
     * A request we refuse is not thrown away: pages that open a window without a gesture (rare, and the
     * kind of thing a browser blocks) still get their target loaded here if it passes the same policy,
     * which is what the previous single-window behaviour effectively did for everyone.
     *
     * @return true only when the transport was wired, because returning true without one is undefined.
     */
    fun requestPopup(requester: WebView, isUserGesture: Boolean, resultMsg: Message): Boolean {
        if (!isUserGesture) {
            val href = gesturelessHref(requester)
            AppLog.d("popup refused without a user gesture" + (href?.let { ", falling back to $it" } ?: ""))
            if (href != null) load(href)
            return false
        }
        if (popupView != null) {
            AppLog.w("a popup window is already open; this one was refused")
            say("Arena already has a sign-in window open. Close it before opening another.")
            return false
        }
        val themed = ContextThemeWrapper(appContext, R.style.Theme_Arena)
        val popup = WebView(themed)
        configure(popup)
        popupView = popup
        dispatch(BrowserEvent.PopupOpened)
        // The composable that shows the strip mounts the same frame; if one is already on screen, the
        // window is placed into it straight away rather than waiting for the next recomposition.
        attachPopupIntoExistingFrame()
        val transport = resultMsg.obj as? WebView.WebViewTransport
        if (transport == null) {
            AppLog.w("popup transport missing; the window was discarded")
            closePopup()
            return false
        }
        transport.setWebView(popup)
        resultMsg.sendToTarget()
        // The URL is not part of this callback — Android is explicit that no page identity can be
        // trusted here — so the host label appears when the window starts loading.
        AppLog.d("popup window created")
        return true
    }

    /**
     * One Back press: the page's window first, then the site's history — the order a browser uses, so a
     * popup can never trap the user behind it.
     */
    fun handleBack(): Boolean {
        if (popupView != null) {
            closePopup()
            return true
        }
        return goBack()
    }

    /** Whether [view] is the transient window rather than the app's page. */
    fun isPopup(view: WebView?): Boolean = view != null && view === popupView

    /** Called for every navigation the popup makes, so the strip can name the host it is really on. */
    fun notePopupUrl(view: WebView?, url: String?) {
        if (!isPopup(view)) return
        val href = view?.url ?: url
        val trail = providerTrail(href)
        if (trail.isNotEmpty()) AppLog.d("popup request$trail")
        dispatch(BrowserEvent.PopupShown(ArenaSite.displayHost(href)))
    }

    /**
     * A failed load *inside* the popup — reported, never promoted. The page behind the popup is still
     * loaded and healthy, so replacing it with a problem panel would describe something that did not
     * happen to it, and the sign-in would look like an arena.ai failure rather than a Google answer.
     */
    fun notePopupFailure(view: WebView?, detail: String?) {
        if (!isPopup(view)) return
        AppLog.w("popup load failed: ${detail ?: "no detail"}${providerTrail(view?.url)}")
        say("The window the page opened could not load. The page underneath it is untouched.")
    }

    /**
     * An HTTP status on the popup's own page. A 400 from `accounts.google.com` here is the interesting
     * case, which is why the OAuth parameter *names* travel with it: they are what separates
     * `invalid_request` from `origin_mismatch` from a popup that fell back to a redirect.
     */
    fun notePopupError(view: WebView?, url: String?, status: Int) {
        if (!isPopup(view)) return
        val host = ArenaSite.displayHost(url) ?: "a page"
        AppLog.w("popup answered $status on $host${providerTrail(url)}")
        if (ArenaSite.isProviderPage(url)) {
            say(
                "The sign-in window got $status from $host. That is the identity provider's answer, " +
                    "not a problem with this device or with your account.",
            )
        }
    }

    /** TLS inside the popup: the same rule as everywhere else in this app — never walked past. */
    fun notePopupTlsFailure(view: WebView?, url: String?) {
        if (!isPopup(view)) return
        AppLog.w("popup TLS error on ${ArenaSite.displayHost(url) ?: "a page"}; load cancelled")
        say("The window the page opened could not be loaded safely, so it was stopped.")
    }

    /** The page closed its own window (`window.close()`) — normal at the end of a sign-in. */
    fun onClosePopup(window: WebView?) {
        if (window == null || !isPopup(window)) return
        closePopup()
    }

    /** Detaches and destroys the popup. Idempotent, and safe to call from any teardown path. */
    fun closePopup() {
        val popup = popupView ?: return
        popupView = null
        runCatching { popupFrame?.removeView(popup) }
        runCatching { (popup.parent as? ViewGroup)?.removeView(popup) }
        runCatching { popup.destroy() }.onFailure { AppLog.w("popup destroy failed", it) }
        dispatch(BrowserEvent.PopupClosed)
        AppLog.d("popup window closed")
    }

    /** The composable that hosts the popup calls this; it re-attaches on every recomposition. */
    fun attachPopupInto(frame: ViewGroup) {
        popupFrame = frame
        val popup = popupView ?: return
        for (i in frame.childCount - 1 downTo 0) {
            if (frame.getChildAt(i) !== popup) frame.removeViewAt(i)
        }
        (popup.parent as? ViewGroup)?.removeView(popup)
        frame.addView(
            popup,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    fun detachPopupFrom(frame: ViewGroup) {
        popupView?.let { if (it.parent === frame) frame.removeView(it) }
        if (popupFrame === frame) popupFrame = null
    }

    private fun attachPopupIntoExistingFrame() {
        popupFrame?.let { attachPopupInto(it) }
    }

    /**
     * The only way to learn what a link was about to open: `onCreateWindow` carries no URL, and the
     * platform says the requesting frame cannot be identified either, so this reads the same hit-test a
     * long-press would. A miss is not chased — the gesture-less path simply stays a refusal.
     */
    private fun gesturelessHref(requester: WebView): String? {
        val result = runCatching { requester.hitTestResult }.getOrNull() ?: return null
        val type = runCatching { result.type }.getOrDefault(0)
        if (type != WebView.SRC_ANCHOR_TYPE && type != WebView.SRC_IMAGE_ANCHOR_TYPE) return null
        return result.extra?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ downloads

    /**
     * A file the site offers goes to the system [DownloadManager]. This app adds no notification of
     * its own and no foreground service: the download is the system's, its progress UI is the
     * platform's, and it keeps running if this app is closed.
     */
    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?, contentLength: Long) {
        val result = runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
            val name = ArenaSite.suggestedFileName(contentDisposition, url, mimeType)
            request.setTitle(name ?: "Download from arena.ai")
            if (contentLength > 0) {
                request.setDescription("${contentLength / 1024} KB")
            }
            mimeType?.let { request.setMimeType(it.substringBefore(';').trim()) }
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setAllowedOverRoaming(false)
            if (name != null) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return@runCatching false
            manager.enqueue(request)
            true
        }
        if (result.getOrDefault(false)) {
            say("Saving to Downloads. Progress and completion are in the system notification.")
        } else {
            AppLog.w("download refused for ${ArenaSite.displayHost(url)}")
            openExternal(url, HandoffReason.EXTERNAL_LINK)
            say("This app could not save the file, so your browser was asked to handle it.")
        }
    }

    // ------------------------------------------------------------------ file chooser

    internal fun takeChooser(
        callback: ValueCallback<Array<Uri>?>,
        acceptTypes: Array<String>,
        allowMultiple: Boolean,
    ) {
        // An unanswered earlier request must be cancelled, never left hanging on the page.
        finishChooser(null)
        chooser = callback
        val hook = onPickFiles
        if (hook == null) {
            finishChooser(null)
            say("No document picker is available from this window, so nothing was attached.")
            return
        }
        val types = acceptTypes.filter { it.isNotBlank() && it != "/" }.toTypedArray()
        runCatching { hook(types.ifEmpty { arrayOf("*/*") }, allowMultiple) }.onFailure {
            AppLog.w("picker could not start", it)
            finishChooser(null)
            say("The picker could not be opened, so nothing was attached.")
        }
    }

    fun deliverPickResult(uris: Array<Uri>?) = finishChooser(uris?.takeIf { it.isNotEmpty() })

    private fun finishChooser(uris: Array<Uri>?) {
        val callback = chooser ?: return
        chooser = null
        runCatching { callback.onReceiveValue(uris) }.onFailure { AppLog.w("chooser callback failed", it) }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * The window came back. Deliberately *not* a reload: a reload would throw away the conversation
     * and interrupt whatever the site is still sending. Nothing in this class destroys the view while
     * the app is hidden any more, so there is nothing to recover here; the one path that does destroy
     * it — a lost renderer — reloads through its own event, not through this one.
     */
    fun onHostStart() {
        network.start()
        dispatch(BrowserEvent.Foregrounded)
        if (!loadedOnce) openHome()
    }

    /**
     * The window went away. Nothing is paused and nothing is destroyed: `WebView.pauseTimers()` would
     * stop the streaming response the user asked us to protect, and `WebView.pause()` — the
     * drawing-only variant — is `protected` on the platform class, so calling it would mean subclassing
     * the view for a saving the visibility system already makes. Cookies are flushed because that is
     * the one thing that must survive being killed.
     */
    fun onHostStop() {
        dispatch(BrowserEvent.Backgrounded)
        runCatching { CookieManager.getInstance().flush() }
        network.stop()
    }

    /**
     * The system asking for memory back. One tier, because that is all the platform still offers:
     * `TRIM_MEMORY_COMPLETE`, `TRIM_MEMORY_MODERATE` and the `RUNNING_*` levels were deprecated in
     * API 35 with the note that apps are **not notified of them since API 34**. An earlier version of
     * this method released the WebView at `TRIM_MEMORY_COMPLETE`; that branch could never run on a
     * device this app ships to, and a branch that never runs is worse than no branch because it makes
     * the documentation a promise nobody keeps.
     *
     * So: at `TRIM_MEMORY_BACKGROUND` the HTTP cache goes back — the biggest single reclaimable block a
     * loaded SPA holds, and the page refetches it — and nothing else does. The view is destroyed only
     * when it is actually gone ([onRendererGone]) or when the host is finishing ([shutdown]). Under real
     * pressure Chromium's renderer is what the system reclaims anyway, and that path already rebuilds
     * the window once and says so.
     *
     * `WebView.freeMemory()` is not called: it is deprecated with no public replacement, and
     * `clearCache(true)` is the part that still describes something real. Cookies and web storage are
     * never touched, because they are what turns a recovery into a re-render rather than a sign-in.
     */
    fun onLowMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return
        runCatching { CookieManager.getInstance().flush() }
        val webView = _view.value ?: return
        runCatching { webView.clearCache(true) }
        popupView?.let { runCatching { it.clearCache(true) } }
        AppLog.d("released the HTTP cache at trim level $level")
    }

    fun destroyView() {
        // The popup's opener is this view; leaving it alive with no opener strands a half-finished
        // sign-in on screen, so it goes first.
        closePopup()
        val webView = _view.value ?: return
        _view.value = null
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }.onFailure { AppLog.w("web view destroy failed", it) }
    }

    /** Only when the Activity is finishing for real; the page is gone by definition at that point. */
    fun shutdown() {
        closePopup()
        destroyView()
        finishChooser(null)
    }

}
