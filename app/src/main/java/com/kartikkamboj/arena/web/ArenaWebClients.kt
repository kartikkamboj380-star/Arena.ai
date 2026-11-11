package com.kartikkamboj.arena.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.core.WebErrors
import com.kartikkamboj.arena.core.WebFailure
import com.kartikkamboj.arena.web.NavigationPolicy.Decision

/**
 * The WebView's two callbacks, in one file because together they are the whole interface between
 * "the page did something" and "the shell says something about it".
 *
 * Both classes are deliberately thin: they translate what Android reports into a [BrowserEvent] and
 * let [BrowserReducer] decide what the app claims. Nothing here reads the page's content, its forms,
 * its cookies or its storage — that is the line between a shell and a scraper.
 */
class ArenaWebViewClient(private val engine: BrowserEngine) : WebViewClient() {

    /**
     * Main-frame navigations are decided by [NavigationPolicy]. A page we render is never re-loaded
     * by hand: returning `false` lets the in-flight load continue, which is what the platform asks
     * for and what keeps the site's own client-side routing intact.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return true
        return when (val decision = NavigationPolicy.classify(url)) {
            is Decision.LoadInApp -> false

            is Decision.HandOffToBrowser -> {
                engine.openExternal(decision.url, decision.reason)
                true
            }

            is Decision.Block -> {
                AppLog.w("refused navigation: ${decision.reason}")
                engine.say(NavigationPolicy.blockExplanation(decision.reason))
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        engine.onLoadStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        engine.onPageFinished(url)
    }

    /** An SPA pushing history changes what Back can do; the shell's affordance follows that, not a guess. */
    override fun onDoUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        engine.dispatch(BrowserEvent.CanGoBackChanged(view.canGoBack()))
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
        engine.onLoadFailed(
            errorCode = error?.errorCode ?: WebViewClient.ERROR_UNKNOWN,
            description = error?.description?.toString(),
            forMainFrame = request?.isForMainFrame ?: true,
            failingUrl = request?.url?.toString(),
        )
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        engine.onHttpError(
            status = errorResponse?.statusCode ?: 0,
            forMainFrame = request?.isForMainFrame ?: false,
            url = request?.url?.toString(),
        )
    }

    /**
     * A TLS problem is never walked past, and never turned into a prompt either — Android's own advice
     * on both. The load is cancelled and the reason is stated in plain words.
     */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) {
        handler.cancel()
        AppLog.w("TLS error on ${error?.url?.let { ArenaSite.displayHost(it) } ?: "a resource"}; load cancelled")
        engine.dispatch(
            BrowserEvent.LoadFailed(
                url = view.url ?: ArenaSite.HOME,
                detail = WebErrors.describe(WebFailure.SSL, null, false),
                failure = WebFailure.SSL,
            ),
        )
    }

    /**
     * Android is explicit: after the renderer goes away the WebView is unusable and must be removed
     * and discarded. Returning true is what stops the system killing the app instead, and
     * [BrowserEngine.onRendererGone] owns the replacement view.
     */
    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
        detail?.let { engine.onRendererGone(it) } ?: engine.destroyView()
        return true
    }
}

class ArenaWebChromeClient(private val engine: BrowserEngine) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        engine.onLoadProgress(newProgress)
    }

    /**
     * `<input type="file">` on the page. The page's own accept list is passed through to the system
     * picker, so images, PDFs and anything else the site asks for work exactly as the site intends —
     * the app does not filter, convert or inspect the files on the way in.
     */
    override fun onShowFileChooser(
        view: WebView,
        filePathCallback: ValueCallback<Array<android.net.Uri>?>,
        params: FileChooserParams?,
    ): Boolean {
        engine.takeChooser(
            callback = filePathCallback,
            acceptTypes = params?.acceptTypes ?: emptyArray(),
            allowMultiple = params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
        )
        return true
    }

    /**
     * Device capabilities stay off-limits, on purpose: this app declares no microphone or camera
     * permission, so an in-page recorder cannot work here even if the site offers one. Denying and
     * saying so is the honest behaviour; the notice points at the way to use it (the browser).
     */
    override fun onPermissionRequest(request: PermissionRequest) {
        runCatching { request.deny() }
        AppLog.d("denied a device-permission request from ${ParsedUrl.of(request.origin)?.host ?: "a page"}")
        engine.say(
            "That page asked for the " +
                (if (request.resources.any { it == Permission.RESOURCE_VIDEO_CAPTURE }) "camera" else "microphone") +
                ", which this app does not request. Open arena.ai in your browser to use it.",
        )
    }
}
