package com.kartikkamboj.arena.web

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.web.NavigationPolicy.BlockReason
import com.kartikkamboj.arena.web.NavigationPolicy.HandoffReason

/**
 * Everything the WebView reports upward. Implemented by [SessionCoordinator]; nothing here may
 * assume it is called on the main thread except that it is — WebView callbacks always are.
 */
interface SessionListener {
    fun onLoadingChanged(loading: Boolean, url: String)
    fun onProgress(progress: Int)
    fun onContentChanged(url: String, title: String)
    fun onMainFrameHttpError(status: Int, url: String)
    fun onSubResourceHttpError(status: Int)
    fun onTransportError(code: Int, description: String?, url: String, isMainFrame: Boolean)
    fun onRenderProcessGone(didCrash: Boolean)
    fun onSslError(host: String?)
    fun onExternalNavigation(url: String, reason: HandoffReason)
    fun onBlockedNavigation(url: String, reason: BlockReason)
    fun onFileChooserRequested(callback: ValueCallback<Array<Uri>>, acceptTypes: Array<String>, allowMultiple: Boolean)
    fun onDownloadRequested(url: String, userAgent: String, mimeType: String, contentLength: Long)
    fun onFirstPaint()

    /**
     * Whether an identity provider may load inside the app right now. True only while a sign-in is
     * in flight — see [NavigationPolicy.classify].
     */
    fun shouldAllowIdentityHost(): Boolean = false

    /** A popup was requested for a target this client cannot read; the user must be told, not stalled. */
    fun onPopupUnresolvable() {}
}

/**
 * Navigation gate + failure reporter for one account slot.
 *
 * Security-relevant choices live here: every URL is passed through [NavigationPolicy] before the
 * WebView may render it, TLS errors are always cancelled (never `proceed()`), and no error body is
 * ever logged or surfaced verbatim — only the status code and the mapped [com.kartikkamboj.arena.data.model.ErrorKind].
 */
class ArenaWebViewClient(
    private val slot: Int,
    private val listener: SessionListener,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return true
        return when (val decision = NavigationPolicy.classify(url, listener.shouldAllowIdentityHost())) {
            is NavigationPolicy.Decision.LoadInSession -> false

            is NavigationPolicy.Decision.HandOffToBrowser -> {
                AppLog.d("slot $slot handing off to browser (${decision.reason})")
                listener.onExternalNavigation(decision.url, decision.reason)
                true
            }

            is NavigationPolicy.Decision.Block -> {
                AppLog.w("slot $slot refused navigation: ${decision.reason}")
                listener.onBlockedNavigation(decision.url, decision.reason)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        listener.onLoadingChanged(loading = true, url = url.orEmpty())
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        listener.onLoadingChanged(loading = false, url = url.orEmpty())
        listener.onProgress(100)
        view?.title?.let { title -> listener.onContentChanged(url.orEmpty(), title) }
        // Persist whatever the page just set, so the next switch of this slot carries the freshest
        // session material instead of a snapshot from login time.
        runCatching { CookieManager.getInstance().flush() }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        // WebResourceResponse exposes getStatusCode(); the combined status-and-reason string that
        // HttpURLConnection uses is not part of this API, so there is nothing to parse here.
        val status = errorResponse?.statusCode ?: 0
        if (status <= 0) return
        if (request?.isForMainFrame == true) {
            listener.onMainFrameHttpError(status, request.url?.toString().orEmpty())
        } else {
            listener.onSubResourceHttpError(status)
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        val code = error?.errorCode ?: -1
        val description = error?.description?.toString()
        val isMain = request?.isForMainFrame ?: true
        listener.onTransportError(
            code = code,
            description = description,
            url = request?.url?.toString().orEmpty(),
            isMainFrame = isMain,
        )
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
        // Never proceed. A cancelled handler leaves the page unloaded and the user sees why.
        // SslError lives in android.net.http, and its primary error is an int constant; the int is
        // translated here rather than passed on, so no layer below has to know that class at all.
        AppLog.w("slot $slot refused a broken TLS certificate (primary error ${error?.primaryError})")
        listener.onSslError(ParsedUrl.of(error?.url)?.host)
        handler?.cancel()
    }

    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
        AppLog.w("slot $slot lost its renderer (didCrash=${detail?.didCrash()})")
        listener.onRenderProcessGone(didCrash = detail?.didCrash() ?: false)
        // Returning true tells the framework the app handled it; without this the process dies.
        return true
    }

    private fun parseStatus(statusCodeAndReasonPhrase: String?): Int =
        statusCodeAndReasonPhrase?.trim()?.substringBefore(' ')?.toIntOrNull() ?: 0
}

/**
 * Chrome-side callbacks: progress, title, downloads, file chooser, and the answers the page asks
 * the app for. Every answer that could grant the page more power is a refusal.
 */
class ArenaWebChromeClient(
    private val slot: Int,
    private val listener: SessionListener,
) : WebChromeClient() {

    private var painted = false

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        listener.onProgress(newProgress.coerceIn(0, 100))
        if (newProgress >= 95 && !painted) {
            painted = true
            listener.onFirstPaint()
        }
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        listener.onContentChanged(view?.url.orEmpty(), title.orEmpty())
    }

    /**
     * `window.open()` — the site uses it for sign-in popups, and a popup the app cannot host is the
     * reason a sign-in used to end back on the logged-out homepage.
     *
     * This client keeps exactly one WebView per device (see docs/LOW_RAM.md), so no second view is
     * created here, not even a short-lived stub. Instead the link the gesture hit is resolved
     * through [NavigationPolicy] and followed *in this view*: the popup's cookies then land in the
     * slot's own jar, which is what makes the session usable afterwards. A URL that policy will not
     * load in-session is handed to the browser, and a gesture whose target cannot be determined is
     * reported rather than swallowed — a silently dead button is worse than an honest refusal.
     */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        if (!isUserGesture || view == null) return false
        val hit = runCatching { view.hitTestResult }.getOrNull()
        // HitTestResult exposes exactly two things: a type and, for the anchor types, the url behind
        // it. Anything else (an image, a phone number, an edit field) has no popup target to follow.
        val isAnchor = hit?.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
            hit?.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val target = if (isAnchor) hit?.extra else null
        if (target.isNullOrBlank()) {
            listener.onPopupUnresolvable()
            return false
        }
        val absolute = ArenaUrls.absolute(target, view.url ?: ArenaUrls.CHAT) ?: target
        when (val decision = NavigationPolicy.classify(absolute, listener.shouldAllowIdentityHost())) {
            is NavigationPolicy.Decision.LoadInSession -> view.loadUrl(decision.url)
            is NavigationPolicy.Decision.HandOffToBrowser ->
                listener.onExternalNavigation(decision.url, decision.reason)

            is NavigationPolicy.Decision.Block ->
                listener.onBlockedNavigation(decision.url, decision.reason)
        }
        // Returning false keeps the framework from building the window we just served ourselves.
        return false
    }

    override fun onCloseWindow(window: WebView?) {
        runCatching { window?.destroy() }
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        val callback = filePathCallback ?: return false
        val types = fileChooserParams?.acceptTypes?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
        val multiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        listener.onFileChooserRequested(callback, types, multiple)
        return true
    }

    /** Camera/microphone capture is not offered by this client; uploads go through the chooser. */
    override fun onPermissionRequest(request: PermissionRequest?) {
        AppLog.d("slot $slot denied a ${request?.resources?.joinToString()} permission request")
        request?.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }

    /** Full-screen video needs a host view the chat surface does not provide: decline cleanly. */
    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        callback?.onCustomViewHidden()
    }
}
