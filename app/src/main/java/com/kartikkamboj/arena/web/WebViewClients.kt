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
        return when (val decision = NavigationPolicy.classify(url)) {
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
     * `window.open()` — Arena's identity providers use it. The stub view exists only long enough to
     * learn the URL it was opened for, which is then handed to the browser, because the provider
     * will refuse the embedded view anyway and spoofing our way past that is not on the table.
     */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        if (!isUserGesture || view == null) return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val stub = WebView(view.context).apply {
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(target: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    when (val decision = NavigationPolicy.classify(url)) {
                        is NavigationPolicy.Decision.LoadInSession -> {
                            // Same-site popups belong in the slot's own session.
                            view.loadUrl(url)
                        }

                        is NavigationPolicy.Decision.HandOffToBrowser ->
                            listener.onExternalNavigation(decision.url, decision.reason)

                        is NavigationPolicy.Decision.Block ->
                            listener.onBlockedNavigation(decision.url, decision.reason)
                    }
                    // Detach the stub only after the hand-off decision has been made; destroying a
                    // WebView inside its own client callback is a crash waiting to happen.
                    target?.post { runCatching { target.destroy() } }
                    return true
                }
            }
        }
        transport.webView = stub
        resultMsg?.sendToTarget()
        return true
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
