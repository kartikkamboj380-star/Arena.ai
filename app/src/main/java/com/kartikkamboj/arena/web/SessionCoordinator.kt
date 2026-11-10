package com.kartikkamboj.arena.web

import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.view.ContextThemeWrapper
import com.kartikkamboj.arena.R
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.core.Clock
import com.kartikkamboj.arena.core.NetworkMonitor
import com.kartikkamboj.arena.data.model.AppError
import com.kartikkamboj.arena.data.model.ArenaMode
import com.kartikkamboj.arena.data.model.AuthProbeResult
import com.kartikkamboj.arena.data.model.ErrorKind
import com.kartikkamboj.arena.data.model.ProbeVerdict
import com.kartikkamboj.arena.data.model.SessionState
import com.kartikkamboj.arena.data.prefs.AppSettings
import com.kartikkamboj.arena.data.prefs.SettingsSource
import com.kartikkamboj.arena.data.repo.AccountRepository
import com.kartikkamboj.arena.data.repo.HistoryRepository
import com.kartikkamboj.arena.data.repo.ModelRepository
import com.kartikkamboj.arena.data.repo.RestoreOutcome
import com.kartikkamboj.arena.data.repo.SessionIsolation
import com.kartikkamboj.arena.domain.ErrorMapper
import com.kartikkamboj.arena.domain.SessionStateMachine.Effect
import com.kartikkamboj.arena.domain.SessionStateMachine.SessionEvent
import com.kartikkamboj.arena.web.NavigationPolicy.BlockReason
import com.kartikkamboj.arena.web.NavigationPolicy.HandoffReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Transient, user-facing message with an optional single action. */
data class UiNotice(
    val text: String,
    val kind: Kind,
    val actionLabel: String? = null,
    val action: UiAction? = null,
) {
    enum class Kind { INFO, SUCCESS, WARN, ERROR }
}

enum class UiAction { RECONNECT, OPEN_ACCOUNTS, OPEN_LEADERBOARD, RETRY, BROWSER }

/**
 * Owns the single live web session for the selected slot, and nothing else.
 *
 * Deliberate properties, because they are the ones that decide whether this behaves like an app or
 * like a browser shortcut:
 *
 *  * The WebView is created against the **application** context and survives navigation and
 *    configuration changes. Leaving the Chat tab detaches it from the Compose hierarchy without
 *    destroying it, so scrolling back never restarts a running answer.
 *  * Exactly one slot is bound at a time ([boundSlot]); switching goes through
 *    [AccountRepository.select], which captures and restores cookie jars. There is no path that
 *    loads a page for an unbound slot.
 *  * No `@JavascriptInterface` exists. Communication with the page is one-way and read-only:
 *    `evaluateJavascript` with the fixed scripts in [AuthProbe] and [PageExtractor].
 *  * The user agent is never modified: rewriting it to slip past an identity provider's
 *    embedded-view check is out of the question, so those flows are handed to the browser instead.
 */
class SessionCoordinator(
    private val application: Context,
    private val accounts: AccountRepository,
    private val models: ModelRepository,
    private val history: HistoryRepository,
    private val isolation: SessionIsolation,
    private val settings: SettingsSource,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val rateLimits: RateLimitMonitor,
    private val scope: CoroutineScope,
) : SessionListener {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<UiNotice?>(null)
    val notice: StateFlow<UiNotice?> = _notice.asStateFlow()

    private val _extractionRunning = MutableStateFlow(false)
    val extractionRunning: StateFlow<Boolean> = _extractionRunning.asStateFlow()

    /** Set by the Activity: opens a URL in the user's browser (Custom Tabs). */
    var onOpenExternal: ((String) -> Unit)? = null

    /** Set by the Activity: shows the system document picker. */
    var onPickFiles: ((acceptTypes: Array<String>, allowMultiple: Boolean) -> Unit)? = null

    private var webView: WebView? = null
    private var boundSlot: Int? = null
    private var pendingChooser: ValueCallback<Array<Uri>>? = null
    private var probeToken = 0

    val selectedSlot: Int? get() = accounts.registry.value.selectedSlot

    /** Whether a live web view exists for the current slot; the UI gates actions on this. */
    val hasSessionView: Boolean get() = webView != null

    /** Whether the app may currently talk to the service on the user's behalf. */
    val canInteract: Boolean
        get() {
            val account = accounts.selected() ?: return false
            val state = _state.value
            return account.status.allowsInteraction && !state.loading && !state.rateLimited &&
                network.available.value
        }

    // --- view lifecycle ---------------------------------------------------------------------

    /** Called from the Compose `AndroidView` factory, always on the main thread. */
    fun ensureView(): WebView {
        webView?.let { return it }
        // Application context on purpose: this view outlives the Activity across configuration
        // changes, and holding an Activity context would leak it.
        val themed = ContextThemeWrapper(application, R.style.Theme_Arena)
        val view = WebView(themed)
        configure(view.settings)
        view.isVerticalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setDownloadListener { url, _, _, mimeType, length ->
            onDownloadRequested(url, "", mimeType, length)
        }
        webView = view
        bindClients(view)
        return view
    }

    private fun configure(target: WebSettings) = with(target) {
        val current: AppSettings = settings.settings.value
        javaScriptEnabled = true // Arena's chat is a JS app; there is nothing to load without it.
        domStorageEnabled = current.domStorageEnabled
        databaseEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        loadWithOverviewMode = true
        useWideViewPort = true
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        mediaPlaybackRequiresUserGesture = true
        setGeolocationEnabled(false)
        safeBrowsingEnabled = true
        offscreenPreRaster = true
        loadsImagesAutomatically = true
        textZoom = 100
        // Deliberately untouched: no custom user agent, no UA trimming, no platform masquerading.
    }

    private fun bindClients(view: WebView) {
        val slot = boundSlot ?: selectedSlot ?: 0
        // Slot 0 means "no slot yet": the clients still refuse off-origin navigation, they just
        // cannot attribute a probe to an account.
        view.webViewClient = ArenaWebViewClient(slot, this)
        view.webChromeClient = ArenaWebChromeClient(slot, this)
    }

    /** Attaches the live view to [container]; safe to call on every recomposition. */
    fun attach(container: ViewGroup) {
        val view = ensureView()
        if (view.parent === container) return
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Detaches without destroying: the conversation keeps streaming behind other tabs. */
    fun detach(container: ViewGroup) {
        val view = webView ?: return
        if (view.parent === container) container.removeView(view)
    }

    fun destroyView() {
        val view = webView ?: return
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.stopLoading() }
        runCatching { view.loadUrl("about:blank") }
        runCatching { view.destroy() }
        webView = null
        boundSlot = null
    }

    fun onHostPause() {
        val slot = boundSlot
        if (slot != null) scope.launch { accounts.refreshJar(slot) }
        runCatching { webView?.onPause() }
    }

    fun onHostResume() {
        runCatching { webView?.onResume() }
        val view = webView ?: return
        view.post {
            if (view.url == null) openSelected()
        }
    }

    /**
     * Tears the view down when the Activity is finishing. The jar itself is captured at [onHostPause]
     * and after every page settle, which is the reliable moment: a coroutine started here could be
     * cut off by process death, and pretending otherwise would be a fake guarantee.
     */
    fun shutdown() {
        destroyView()
        _state.value = SessionState()
    }

    // --- navigation -------------------------------------------------------------------------

    /**
     * Makes [slot] the live one: installs its jar if needed, points the clients at it and loads
     * [startUrl] (or the resume point of the current page).
     */
    fun openFor(slot: Int, startUrl: String? = null, force: Boolean = false) {
        val view = webView ?: return
        if (boundSlot != slot) {
            boundSlot = slot
            bindClients(view)
            scope.launch {
                if (isolation.activeSlot != slot) {
                    when (val outcome = isolation.restore(slot)) {
                        is RestoreOutcome.Expired -> notifyExpired(slot, outcome)
                        is RestoreOutcome.Failed -> _state.update {
                            it.copy(error = AppError(ErrorKind.PARSE, "stored session could not be read"))
                        }

                        else -> Unit
                    }
                }
                load(view, startUrl ?: view.url ?: ArenaUrls.CHAT, force)
            }
            return
        }
        load(view, startUrl ?: view.url ?: ArenaUrls.CHAT, force)
    }

    fun openSelected(force: Boolean = false) {
        val slot = selectedSlot
        if (slot == null) {
            _notice.value = UiNotice(
                "No account slot is selected. Pick one in Accounts — the app never loads Arena " +
                    "into a slot you have not chosen.",
                UiNotice.Kind.INFO,
                "Open Accounts",
                UiAction.OPEN_ACCOUNTS,
            )
            return
        }
        openFor(slot, startUrl = _state.value.url.takeIf { ArenaUrls.isArenaUrl(it) } ?: ArenaUrls.CHAT, force = force)
    }

    private fun load(view: WebView, url: String, force: Boolean) {
        val decision = NavigationPolicy.classify(url)
        when (decision) {
            is NavigationPolicy.Decision.LoadInSession -> {
                if (!network.available.value) {
                    _state.update { it.copy(error = AppError(ErrorKind.NO_NETWORK)) }
                    scope.launch {
                        boundSlot?.let { accounts.dispatch(it, SessionEvent.TransportFailure(ErrorKind.NO_NETWORK)) }
                    }
                    _notice.value = UiNotice(
                        "No network. Nothing was sent to Arena; the page you can still read is the " +
                            "last one that loaded.",
                        UiNotice.Kind.ERROR,
                        "Retry",
                        UiAction.RETRY,
                    )
                    return
                }
                if (force || view.url != decision.url) {
                    AppLog.d("slot ${boundSlot ?: 0} loading ${decision.url}")
                    _state.update { it.copy(error = null, loading = true, url = decision.url, progress = 8) }
                    view.loadUrl(decision.url)
                }
            }

            is NavigationPolicy.Decision.HandOffToBrowser ->
                onExternalNavigation(decision.url, decision.reason)

            is NavigationPolicy.Decision.Block ->
                onBlockedNavigation(decision.url, decision.reason)
        }
    }

    fun loadMode(mode: ArenaMode) {
        val view = webView ?: return ensureNoView(mode)
        load(view, mode.entryUrl, force = true)
        _notice.value = UiNotice("${mode.label} opened in the chat surface.", UiNotice.Kind.INFO)
    }

    private fun ensureNoView(mode: ArenaMode) {
        _notice.value = UiNotice(
            "Select an account slot first, then ${mode.label} will load into it.",
            UiNotice.Kind.WARN,
            "Open Accounts",
            UiAction.OPEN_ACCOUNTS,
        )
    }

    fun loadUrlInSession(url: String) {
        val view = webView ?: run {
            _notice.value = UiNotice("Select an account slot first.", UiNotice.Kind.WARN, "Open Accounts", UiAction.OPEN_ACCOUNTS)
            return
        }
        load(view, url, force = true)
    }

    fun reload() {
        val view = webView ?: return
        if (!network.available.value) {
            _state.update { it.copy(error = AppError(ErrorKind.NO_NETWORK)) }
            return
        }
        view.reload()
    }

    /** Real stop: cancels in-flight requests. It is not a fake "stop generating". */
    fun stopLoading() {
        val view = webView ?: return
        view.stopLoading()
        _state.update { it.copy(loading = false, progress = 100) }
        _notice.value = UiNotice(
            "Loading stopped. Arena keeps answering inside its own page if the request already " +
                "reached the model — there is no public cancel API this client can call.",
            UiNotice.Kind.WARN,
        )
    }

    fun goBack(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    /**
     * Hands Arena's sign-in to the real browser. Kept separate from the in-app path because the
     * two produce different outcomes: only the in-app slot's jar can be reused afterwards.
     */
    /** Opens an arena.ai page in the user's browser (used by About: terms, privacy). */
    fun handOffTo(url: String) {
        val handler = onOpenExternal
        if (handler == null) {
            reportProblem("No browser is available on this device.")
            return
        }
        handler(url)
    }

    fun handOffLoginToBrowser() {
        val opener = onOpenExternal
        if (opener == null) {
            _notice.value = UiNotice(
                "No browser is available on this device, so sign-in cannot be handed off. Use the " +
                    "in-app sign-in instead.",
                UiNotice.Kind.WARN,
            )
            return
        }
        opener(ArenaUrls.CHAT)
        _notice.value = UiNotice(
            "Arena opened in your browser. A browser session stays in the browser: this app cannot " +
                "read its cookies, by design. To give this slot its own session, sign in inside the " +
                "in-app view.",
            UiNotice.Kind.WARN,
        )
    }

    /** Stores a pointer to whatever arena.ai page is on screen, in this slot's history. */
    fun trackCurrentPage() {
        val view = webView ?: run { reportProblem("Nothing is loaded yet."); return }
        val url = view.url.orEmpty()
        val slot = boundSlot ?: selectedSlot
        if (slot == null || !ArenaUrls.isArenaUrl(url)) {
            reportProblem("Only arena.ai pages with a selected slot can be tracked.")
            return
        }
        scope.launch {
            history.trackOpen(
                slot = slot,
                mode = modeFor(url),
                url = url,
                model = models.activeSelection(),
                titleHint = view.title?.takeIf { it.isNotBlank() },
            )
            _notice.value = UiNotice(
                "Tracked this page in Account $slot's local history. Arena still owns the transcript.",
                UiNotice.Kind.SUCCESS,
            )
        }
    }

    fun scrollToBottom() {
        webView?.evaluateJavascript(AuthProbe.SCROLL_TO_BOTTOM_SCRIPT, null)
    }

    /** Copies whatever the user actually selected in the page — no invented transcript. */
    fun copySelection() {
        val view = webView ?: return
        view.evaluateJavascript(AuthProbe.SELECTION_SCRIPT) { raw ->
            val text = AuthProbe.unwrap(raw)
            _state.update { it.copy(selectionText = text) }
            if (text.isBlank()) {
                _notice.value = UiNotice(
                    "Nothing is selected in the page. Long-press an answer in the chat surface to " +
                        "select text, then copy it.",
                    UiNotice.Kind.INFO,
                )
            }
        }
    }

    // --- account operations -------------------------------------------------------------------

    fun switchTo(slot: Int) {
        scope.launch {
            val outcome = accounts.select(slot)
            when (outcome) {
                is AccountRepository.SelectOutcome.UnknownSlot -> _notice.value = UiNotice(
                    "Slot ${outcome.slot} does not exist.", UiNotice.Kind.ERROR,
                )

                is AccountRepository.SelectOutcome.AlreadySelected -> openFor(slot, force = false)
                is AccountRepository.SelectOutcome.Success -> {
                    val restore = outcome.restore
                    val verb = when (restore) {
                        is RestoreOutcome.Applied -> "restored ${restore.entries} cookie(s)"
                        RestoreOutcome.NothingStored -> "no stored session — sign in to use this slot"
                        is RestoreOutcome.Expired -> "stored session expired; sign in again"
                        is RestoreOutcome.Failed -> "jar could not be restored (${restore.reason})"
                    }
                    _notice.value = UiNotice(
                        "Now using ${accounts.find(slot)?.shortName ?: "slot $slot"} · $verb.",
                        if (restore is RestoreOutcome.Applied) UiNotice.Kind.SUCCESS else UiNotice.Kind.WARN,
                        if (restore is RestoreOutcome.Applied) null else "Sign in",
                        if (restore is RestoreOutcome.Applied) null else UiAction.RECONNECT,
                    )
                    openFor(slot, force = true)
                }
            }
        }
    }

    fun logout(slot: Int = selectedSlot ?: 0) {
        if (slot <= 0) return
        scope.launch {
            accounts.logout(slot)
            rateLimits.clear(slot)
            destroyView()
            _state.value = SessionState()
            _notice.value = UiNotice(
                "Slot $slot signed out: its cookies were wiped and the web view was destroyed, so " +
                    "nothing is left in memory. The chat itself stays in Arena's own history.",
                UiNotice.Kind.SUCCESS,
                "Choose another slot",
                UiAction.OPEN_ACCOUNTS,
            )
        }
    }

    /**
     * "Reconnect" — loads the real arena.ai page in this slot's jar so the site's own sign-in UI
     * handles credentials. The app never renders a login form and never reads one.
     */
    fun reconnect(slot: Int = selectedSlot ?: 0) {
        if (slot <= 0) {
            _notice.value = UiNotice("Create and select a slot first.", UiNotice.Kind.WARN, "Open Accounts", UiAction.OPEN_ACCOUNTS)
            return
        }
        scope.launch { accounts.dispatch(slot, SessionEvent.LoadStarted) }
        val view = webView ?: run {
            _notice.value = UiNotice(
                "Open the Chat tab to sign in — that is where this slot's session lives.",
                UiNotice.Kind.INFO,
                "Go to Chat",
                UiAction.RECONNECT,
            )
            return
        }
        boundSlot = slot
        bindClients(view)
        load(view, ArenaUrls.CHAT, force = true)
        _notice.value = UiNotice(
            "Use Arena's own sign-in on the page. Do not type credentials into this app — it has no " +
                "login form, by design.",
            UiNotice.Kind.INFO,
        )
    }

    // --- SessionListener ----------------------------------------------------------------------

    override fun onLoadingChanged(loading: Boolean, url: String) {
        _state.update {
            it.copy(
                loading = loading,
                url = url.ifBlank { it.url },
                progress = if (loading) maxOf(it.progress, 12) else 100,
                canGoBack = webView?.canGoBack() ?: false,
                slot = boundSlot ?: it.slot,
            )
        }
        if (!loading) {
            val slot = boundSlot ?: return
            scope.launch {
                val account = accounts.find(slot) ?: return@launch
                _state.update { state -> state.copy(authenticated = account.isAuthenticated) }
                if (account.isAuthenticated) {
                    history.trackOpen(
                        slot = slot,
                        mode = modeFor(url),
                        url = url.takeIf { ArenaUrls.isContentPath(it) },
                        model = models.activeSelection(),
                        titleHint = _state.value.title.takeIf { it.isNotBlank() },
                    )
                }
                scheduleProbe(420L)
            }
        }
    }

    private fun modeFor(url: String): ArenaMode = when {
        url.contains("/agent") -> ArenaMode.AGENT
        else -> settings.settings.value.defaultMode
    }

    override fun onProgress(progress: Int) {
        _state.update { it.copy(progress = progress) }
    }

    override fun onContentChanged(url: String, title: String) {
        _state.update { it.copy(title = title, url = url.ifBlank { it.url }) }
        if (title.isNotBlank()) scope.launch {
            val slot = boundSlot ?: return@launch
            history.trackOpen(slot, modeFor(url), url.takeIf { ArenaUrls.isContentPath(it) }, models.activeSelection(), title)
        }
    }

    override fun onMainFrameHttpError(status: Int, url: String) {
        val slot = boundSlot ?: return
        val kind = ErrorMapper.fromHttpStatus(status)
        scope.launch {
            rateLimits.observeHttpStatus(slot, status)
            val transition = accounts.dispatch(slot, SessionEvent.HttpFailure(kind, status))
            applyEffects(slot, transition.effects)
            _state.update {
                it.copy(
                    error = AppError(kind, "HTTP $status"),
                    rateLimited = kind == ErrorKind.RATE_LIMITED,
                )
            }
            if (kind == ErrorKind.RATE_LIMITED) {
                _notice.value = UiNotice(
                    rateLimits.messageFor(accounts.find(slot), models.activeSelection()?.displayName),
                    UiNotice.Kind.WARN,
                    "Switch account",
                    UiAction.OPEN_ACCOUNTS,
                )
            }
        }
    }

    override fun onSubResourceHttpError(status: Int) {
        if (status != ErrorMapper.RATE_LIMIT_STATUS) return
        val slot = boundSlot ?: return
        scope.launch {
            rateLimits.observeHttpStatus(slot, status)
            val transition = accounts.dispatch(slot, SessionEvent.RateLimited)
            applyEffects(slot, transition.effects)
            _state.update { it.copy(rateLimited = true) }
        }
    }

    override fun onTransportError(code: Int, description: String?, url: String, isMainFrame: Boolean) {
        if (!isMainFrame) return
        val kind = ErrorMapper.fromWebViewErrorCode(code, description)
        val slot = boundSlot ?: return
        scope.launch {
            val transition = accounts.dispatch(slot, SessionEvent.TransportFailure(kind))
            applyEffects(slot, transition.effects)
            _state.update { it.copy(error = AppError(kind)) }
            AppLog.w("slot $slot transport failure: code=$code kind=$kind")
        }
    }

    override fun onRenderProcessGone(didCrash: Boolean) {
        val slot = boundSlot ?: return
        scope.launch {
            val transition = accounts.dispatch(
                slot,
                SessionEvent.TransportFailure(ErrorKind.RENDER_CRASH),
            )
            applyEffects(slot, transition.effects)
            destroyView()
            _state.update { it.copy(error = AppError(ErrorKind.RENDER_CRASH), loading = false) }
            _notice.value = UiNotice(
                "Android ${if (didCrash) "crashed" else "ended"} the web renderer for this slot. " +
                    "Its session material is still stored; reloading reattaches it.",
                UiNotice.Kind.ERROR,
                "Reload",
                UiAction.RETRY,
            )
        }
    }

    override fun onSslError(host: String?) {
        _state.update { it.copy(error = AppError(ErrorKind.TLS, "host=$host")) }
        _notice.value = UiNotice(
            "The certificate for ${host ?: "that host"} could not be verified, so the page was not " +
                "loaded. This client never proceeds on a broken TLS connection.",
            UiNotice.Kind.ERROR,
            "Retry",
            UiAction.RETRY,
        )
    }

    override fun onExternalNavigation(url: String, reason: HandoffReason) {
        _notice.value = UiNotice(
            NavigationPolicy.handoffExplanation(reason),
            if (reason == HandoffReason.IDENTITY_PROVIDER) UiNotice.Kind.WARN else UiNotice.Kind.INFO,
            "Open in browser",
            UiAction.BROWSER,
        )
        // Handing off is only useful if the user can see the page, so do it right away too.
        onOpenExternal?.invoke(url) ?: AppLog.w("no browser handler installed for hand-off")
    }

    override fun onBlockedNavigation(url: String, reason: BlockReason) {
        _state.update { it.copy(error = AppError(ErrorKind.BLOCKED_ORIGIN, reason.name)) }
        AppLog.w("blocked navigation (${reason.name})")
    }

    override fun onFileChooserRequested(
        callback: ValueCallback<Array<Uri>>,
        acceptTypes: Array<String>,
        allowMultiple: Boolean,
    ) {
        pendingChooser?.onReceiveValue(null)
        pendingChooser = callback
        val handler = onPickFiles
        if (handler == null) {
            AppLog.w("no file picker installed; cancelling chooser")
            deliverPickResult(null)
            return
        }
        handler(acceptTypes.map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray(), allowMultiple)
    }

    /** Result of the system picker. Null means cancelled. */
    fun deliverPickResult(uris: Array<Uri>?) {
        val callback = pendingChooser ?: return
        pendingChooser = null
        if (uris == null || uris.isEmpty()) {
            callback.onReceiveValue(null)
            return
        }
        // Only content: URIs are handed to the page; file: paths never are.
        val safe = uris.filter { it.scheme == "content" }.toTypedArray()
        if (safe.isEmpty()) {
            callback.onReceiveValue(null)
            _notice.value = UiNotice("That file could not be shared with the page.", UiNotice.Kind.WARN)
            return
        }
        callback.onReceiveValue(safe)
    }

    override fun onDownloadRequested(url: String, userAgent: String, mimeType: String, contentLength: Long) {
        if (!ArenaUrls.isArenaUrl(url)) {
            _notice.value = UiNotice(
                "That file is not on arena.ai, so this app will not fetch it.",
                UiNotice.Kind.WARN,
                "Open in browser",
                UiAction.BROWSER,
            )
            onOpenExternal?.invoke(url)
            return
        }
        val manager = application.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (manager == null) {
            onOpenExternal?.invoke(url)
            return
        }
        val result = runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType.ifBlank { "application/octet-stream" })
                setTitle("Arena AI download")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(application, Environment.DIRECTORY_DOWNLOADS, fileNameFor(url))
            }
            manager.enqueue(request)
        }
        _notice.value = if (result.isSuccess) {
            val size = if (contentLength > 0L) " · ${(contentLength / 1024L).coerceAtLeast(1L)} KB" else ""
            UiNotice(
                "Downloading ${fileNameFor(url)}$size into this app's Downloads folder.",
                UiNotice.Kind.SUCCESS,
            )
        } else {
            onOpenExternal?.invoke(url)
            UiNotice("The system download manager refused this file; it opened in your browser instead.", UiNotice.Kind.WARN)
        }
    }

    override fun onFirstPaint() {
        _state.update { it.copy(progress = 100) }
    }

    private fun fileNameFor(url: String): String {
        val raw = runCatching { Uri.parse(url).lastPathSegment }.getOrNull().orEmpty()
        val cleaned = raw.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.take(64)
        return cleaned.ifBlank { "arena-download" }
    }

    // --- probing & extraction -----------------------------------------------------------------

    private fun scheduleProbe(delayMillis: Long) {
        val token = ++probeToken
        scope.launch {
            delay(delayMillis)
            if (token != probeToken) return@launch
            runProbe()
        }
    }

    fun probeNow() = scheduleProbe(0L)

    private fun runProbe() {
        val view = webView ?: return
        val slot = boundSlot ?: return
        if (!ArenaUrls.isArenaUrl(view.url)) return
        runCatching {
            view.evaluateJavascript(AuthProbe.SCRIPT) { raw ->
                val result = AuthProbe.probe(raw, clock.nowMillis())
                scope.launch { applyProbe(slot, result) }
            }
        }.onFailure { AppLog.w("probe skipped", it) }
    }

    private suspend fun applyProbe(slot: Int, result: AuthProbeResult) {
        val transition = accounts.applyProbe(slot, result)
        applyEffects(slot, transition.effects)
        rateLimits.observePageNotice(slot, result.seesRateLimitNotice)
        _state.update {
            it.copy(
                lastProbe = result,
                authenticated = result.verdict == ProbeVerdict.AUTHENTICATED ||
                    accounts.find(slot)?.isAuthenticated == true,
            )
        }
        when (result.verdict) {
            ProbeVerdict.ANONYMOUS -> _notice.value = UiNotice(
                "Arena is showing a signed-out page for ${accounts.find(slot)?.shortName ?: "this slot"}. " +
                    "Your conversation stays in Arena's history; sign in to continue there.",
                UiNotice.Kind.WARN,
                "Sign in",
                UiAction.RECONNECT,
            )

            ProbeVerdict.AUTHENTICATED -> Unit
            ProbeVerdict.UNKNOWN -> AppLog.d("probe inconclusive for slot $slot (${transition.reason})")
        }
    }

    private fun applyEffects(slot: Int, effects: Set<Effect>) {
        scope.launch {
            effects.forEach { effect ->
                when (effect) {
                    Effect.SNAPSHOT_JAR -> accounts.refreshJar(slot)
                    Effect.CLEAR_JAR -> isolation.release(slot)
                    Effect.RECREATE_VIEW -> destroyView()
                    Effect.REPROBE -> scheduleProbe(600L)
                    Effect.SHOW_RATE_LIMIT -> _state.update { it.copy(rateLimited = true) }
                    Effect.PROMPT_RECONNECT, Effect.BLOCK_INTERACTION, Effect.NONE -> Unit
                }
            }
        }
    }

    private fun notifyExpired(slot: Int, outcome: RestoreOutcome.Expired) {
        val ageHours = ((clock.nowMillis() - outcome.capturedAtMillis).coerceAtLeast(0L)) / 3_600_000L
        _notice.value = UiNotice(
            "${accounts.find(slot)?.shortName ?: "Slot $slot"} held a session from ${ageHours} h ago, " +
                "past this app's ${outcome.ttlMillis / 3_600_000L} h retention limit, so it was dropped " +
                "rather than reused. Sign in again to continue.",
            UiNotice.Kind.WARN,
            "Sign in",
            UiAction.RECONNECT,
        )
    }

    /** Reads ranking rows off whatever Arena page is currently displayed. */
    fun refreshModelList(onDone: ((Int) -> Unit)? = null) {
        val view = webView ?: run {
            _notice.value = UiNotice("Open a chat or leaderboard page first.", UiNotice.Kind.WARN)
            onDone?.invoke(-1)
            return
        }
        if (!ArenaUrls.isArenaUrl(view.url)) {
            _notice.value = UiNotice(
                "Model data can only be read while an arena.ai page is on screen.",
                UiNotice.Kind.WARN,
                "Open leaderboard",
                UiAction.OPEN_LEADERBOARD,
            )
            onDone?.invoke(-1)
            return
        }
        _extractionRunning.value = true
        runCatching {
            view.evaluateJavascript(PageExtractor.LIST_SCRIPT) { raw ->
                val (items, baseUrl) = PageExtractor.parseItems(raw)
                val entries = PageExtractor.models(items, clock.nowMillis())
                scope.launch {
                    if (entries.isEmpty()) {
                        _notice.value = UiNotice(
                            "No ranking rows could be read from ${baseUrl.ifBlank { "this page" }} — " +
                                "Arena may have changed its layout, so nothing was invented.",
                            UiNotice.Kind.INFO,
                        )
                    } else {
                        val result = models.applySnapshot(entries)
                        _notice.value = UiNotice(
                            "Read ${result.live} model row(s); ${result.demoted} pinned entry/entries " +
                                "were missing from this snapshot and are marked stale.",
                            UiNotice.Kind.SUCCESS,
                        )
                    }
                    _extractionRunning.value = false
                    onDone?.invoke(entries.size)
                }
            }
        }.onFailure {
            _extractionRunning.value = false
            _notice.value = UiNotice("The page refused to answer the read request.", UiNotice.Kind.ERROR)
            onDone?.invoke(-1)
        }
    }

    /** Imports the links visible on the user's own history page. */
    fun importVisibleHistory(onDone: ((Int) -> Unit)? = null) {
        val view = webView ?: run {
            _notice.value = UiNotice("Open arena.ai/history in the chat surface first.", UiNotice.Kind.WARN)
            onDone?.invoke(-1)
            return
        }
        val url = view.url.orEmpty()
        if (!ArenaUrls.isArenaUrl(url)) {
            _notice.value = UiNotice(
                "This reads the page you are looking at. Open arena.ai/history, then import.",
                UiNotice.Kind.WARN,
            )
            onDone?.invoke(-1)
            return
        }
        _extractionRunning.value = true
        val slot = boundSlot ?: 0
        runCatching {
            view.evaluateJavascript(PageExtractor.LIST_SCRIPT) { raw ->
                val (items, baseUrl) = PageExtractor.parseItems(raw)
                val links = PageExtractor.links(items, baseUrl.ifBlank { url })
                scope.launch {
                    if (links.isEmpty()) {
                        _notice.value = UiNotice(
                            "No conversation links were found on this page. Nothing was stored.",
                            UiNotice.Kind.INFO,
                        )
                    } else {
                        val summary = history.importFromArena(slot, links)
                        _notice.value = UiNotice(
                            "Imported ${summary.added} title(s), refreshed ${summary.refreshed}. " +
                                "Only what was on your screen was recorded.",
                            UiNotice.Kind.SUCCESS,
                        )
                    }
                    _extractionRunning.value = false
                    onDone?.invoke(links.size)
                }
            }
        }.onFailure {
            _extractionRunning.value = false
            onDone?.invoke(-1)
        }
    }

    /**
     * Places the native draft into the page's own composer. Never submits it, and never runs when
     * the setting is off — see [ComposerScript] for why.
     */
    fun insertDraft(text: String, onResult: ((String?) -> Unit)? = null) {
        val view = webView
        if (view == null || text.isBlank()) {
            onResult?.invoke("no-session")
            return
        }
        if (!settings.settings.value.nativeComposerEnabled) {
            onResult?.invoke("disabled")
            return
        }
        if (!ArenaUrls.isArenaUrl(view.url)) {
            onResult?.invoke("off-origin")
            return
        }
        val script = ComposerScript.build(text)
        runCatching {
            view.evaluateJavascript(script) { raw ->
                val reason = ComposerScript.interpret(raw)
                scope.launch {
                    boundSlot?.let { accounts.recordInteraction(it) }
                    onResult?.invoke(reason)
                }
            }
        }.onFailure {
            onResult?.invoke("script-error")
        }
    }

    // --- notices ------------------------------------------------------------------------------

    fun consumeNotice() {
        _notice.value = null
    }

    fun reportProblem(text: String) {
        _notice.value = UiNotice(text, UiNotice.Kind.ERROR)
    }

    fun describeIsolation(): String = buildString {
        append(isolation.capability.summary)
        append(" · bound slot: ").append(boundSlot?.toString() ?: "none")
        append(" · active jar: ").append(isolation.activeSlot?.toString() ?: "none")
    }
}
