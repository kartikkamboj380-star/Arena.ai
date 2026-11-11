package com.kartikkamboj.arena.web

import android.graphics.Bitmap
import android.os.Message
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
        if (!request.isForMainFrame) {
            // Sub-frame loads are not navigations the user chose, and the platform warns that this
            // callback fires for them too. Deciding them here would let a frame inside a page move the
            // whole window's trust context, so they are left to the WebView and its own origin rules.
            return false
        }
        // The one piece of context that changes the answer: whether the window is *already* showing
        // the identity provider's UI, in which case breaking the chain mid-sign-in is the worse error.
        val fromProviderPage = ArenaSite.isProviderPage(view.url)
        val decision = NavigationPolicy.classify(url, fromProviderPage)
        // Logged as host and path only. An OAuth callback carries `code`, `state` and sometimes an
        // `id_token` in its query string, and a query never belongs in logcat.
        AppLog.d(
            "nav ${ArenaSite.forLog(view.url)} -> ${ArenaSite.forLog(url)} " +
                "[${NavigationPolicy.logSummary(decision)}]",
        )
        val inPopup = engine.isPopup(view)
        if (inPopup) engine.notePopupUrl(view, url)
        return when (decision) {
            is Decision.LoadInApp -> false

            is Decision.HandOffToBrowser -> {
                engine.openExternal(decision.url, decision.reason)
                // A popup whose content wants to leave the app has nothing left to show, and an empty
                // sheet over the site is worse than the link having simply been handed off.
                if (inPopup) engine.closePopup()
                true
            }

            is Decision.Block -> {
                AppLog.w("refused navigation: ${decision.reason}")
                engine.say(NavigationPolicy.blockExplanation(decision.reason))
                if (inPopup) engine.closePopup()
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (engine.isPopup(view)) {
            engine.notePopupUrl(view, url)
            return
        }
        engine.onLoadStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (engine.isPopup(view)) return
        engine.onPageFinished(url)
    }

    /** An SPA pushing history changes what Back can do; the shell's affordance follows that, not a guess. */
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        // The popup's history is not the page's: the shell's Back affordance must not answer to a window
        // that may already be gone.
        if (engine.isPopup(view)) return
        engine.dispatch(BrowserEvent.CanGoBackChanged(view.canGoBack()))
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
        if (engine.isPopup(view)) {
            if (request?.isForMainFrame == true) engine.notePopupFailure(view, error?.description?.toString())
            return
        }
        engine.onLoadFailed(
            errorCode = error?.errorCode ?: WebViewClient.ERROR_UNKNOWN,
            description = error?.description?.toString(),
            forMainFrame = request?.isForMainFrame ?: true,
            failingUrl = request?.url?.toString(),
        )
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        if (engine.isPopup(view)) {
            if (request?.isForMainFrame == true) {
                engine.notePopupError(view, request?.url?.toString(), errorResponse?.statusCode ?: 0)
            }
            return
        }
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
        if (engine.isPopup(view)) {
            engine.notePopupTlsFailure(view, error?.url)
            return
        }
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
        if (engine.isPopup(view)) {
            // Only the popup's renderer went away; the page underneath survives and is not rebuilt.
            engine.closePopup()
            return true
        }
        detail?.let { engine.onRendererGone(it) } ?: engine.destroyView()
        return true
    }
}

class ArenaWebChromeClient(private val engine: BrowserEngine) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        // The strip at the top of the screen belongs to the page, not to a window floating above it.
        if (engine.isPopup(view)) return
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
    /**
     * `window.open()`. The engine decides whether to honour it, and nothing here pretends to know more
     * than the platform gives: this callback carries no URL, and Android says the requesting frame
     * cannot be identified (it could be a third-party iframe), so no policy is applied to a URL we were
     * never handed. The window that does get created runs this same [ArenaWebViewClient], which is what
     * keeps it inside the app's rules rather than beside them.
     */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        if (view == null || resultMsg == null) return false
        return engine.requestPopup(view, isUserGesture, resultMsg)
    }

    /** The page called `window.close()` — the normal end of a popup sign-in, once the reply lands. */
    override fun onCloseWindow(window: WebView?) {
        engine.onClosePopup(window)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        runCatching { request.deny() }
        // getOrigin() is a Uri on the device and the app has no reason to look inside it beyond the
        // host, which is all this line records.
        val host = request.origin?.host ?: "a page"
        AppLog.d("denied a device-permission request from $host")
        val asked = if (request.resources.any { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }) {
            "camera"
        } else {
            "microphone"
        }
        engine.say(
            "That page asked for the $asked, which this app does not request. " +
                "Open arena.ai in your browser to use it.",
        )
    }
}
