package com.kartikkamboj.arena.web

/**
 * The shell's view of what the WebView is doing, as a pure reducer.
 *
 * This exists so the promises the UI makes are the ones the code actually keeps. Two of them are
 * structural rather than incidental:
 *
 *  * [BrowserEvent.Backgrounded] and [BrowserEvent.Foregrounded] change nothing. There is no reload
 *    on returning to the app, because a reload would throw away the conversation the user was reading
 *    and interrupt a response already on its way.
 *  * A status only ever improves through a real signal from the WebView — [BrowserEvent.PageFinished]
 *    or a load that started. No code path here can mark a page loaded because a button was pressed,
 *    so the app cannot claim a sign-in or a page it has not seen.
 *
 * Everything that needs a device (cookies, the view itself, Custom Tabs) is a [BrowserEffect] the
 * caller executes; the decision logic stays here where unit tests can reach it.
 */
data class BrowserState(
    /** The page the view is showing, or is on its way to showing. */
    val url: String = ArenaSite.HOME,
    val phase: PagePhase = PagePhase.IDLE,
    /** 0..100 from the WebView's own progress callback. */
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    /** OS-level connectivity, not a claim about reaching the site. */
    val offline: Boolean = false,
    /** Why the page failed or was refused; already safe to show. Never a header, cookie or token. */
    val detail: String? = null,
    /**
     * The failure behind [detail], kept as a value so the Retry button can consult
     * [com.kartikkamboj.arena.core.WebFailure.retryable] instead of offering a press that cannot help.
     */
    val failure: com.kartikkamboj.arena.core.WebFailure? = null,
    /** Transient one-line message (a hand-off, a download). Dismissed by the user or the next event. */
    val notice: String? = null,
    /** True once the site has painted: the splash screen may go away, and an offline load is a real outage. */
    val firstPaint: Boolean = false,
    /** Whether the app has already spent its one automatic recovery after a dead renderer. */
    val crashRecovered: Boolean = false,
    /**
     * A sign-in leg left this window for the browser, so the flow is now running somewhere the app
     * cannot see. Nothing can be concluded from that except what the user can decide with it, which is
     * why the shell offers an acknowledgement rather than guessing an outcome.
     */
    val awaitingProviderReturn: Boolean = false,
    /** The transient window `window.open()` asked for, or null when nothing is above the page. */
    val popup: Popup? = null,
) {
    /**
     * Whether this window is currently showing an identity provider's page rather than the site. The
     * shell says so out loud: the user should never have to guess whose page they are typing a
     * password into, and the app has no way to know about a sign-in other than what it can see here.
     */
    val onProviderPage: Boolean get() = ArenaSite.isAuthPage(url)

    /** Whether the site is actually displayed, which is the only state where sending works. */
    val usable: Boolean get() = firstPaint && phase != PagePhase.CRASHED
}

enum class PagePhase {
    /** Nothing loaded yet. */
    IDLE,

    LOADING,

    /** `onPageFinished` fired for [BrowserState.url]. */
    READY,

    /** A load failed; the reason is in [BrowserState.detail]. */
    FAILED,

    /** The provider answered this window with an error status; see docs/AUTHENTICATION.md. */
    AUTH_REFUSED,

    /** The renderer died; the view is being or has been replaced. */
    CRASHED,
    ;
}

sealed interface BrowserEvent {
    data class LoadStarted(val url: String) : BrowserEvent
    data class Progress(val percent: Int) : BrowserEvent
    data class PageFinished(val url: String) : BrowserEvent

    /**
     * `onReceivedError` for the main frame. The sentence and the [com.kartikkamboj.arena.core.WebFailure]
     * travel together so the shell can word the card and gate the button from the same fact.
     */
    data class LoadFailed(
        val url: String,
        val detail: String,
        val failure: com.kartikkamboj.arena.core.WebFailure? = null,
    ) : BrowserEvent

    /** A 4xx/5xx on the main frame while on an identity-provider page. */
    data class AuthPageRejected(val status: Int) : BrowserEvent

    data class NetworkChanged(val available: Boolean) : BrowserEvent
    data class CanGoBackChanged(val canGoBack: Boolean) : BrowserEvent
    data class RendererGone(val detail: String) : BrowserEvent
    data class Notice(val text: String) : BrowserEvent

    object NoticeDismissed : BrowserEvent

    /** The user has read the failure and wants the page back, even if it is still broken. */
    object DetailDismissed : BrowserEvent

    /**
     * A provider page was handed to the browser. Deliberately carries nothing: the only fact worth
     * keeping is that a leg left, and a URL would put an OAuth `code` in state that reaches the screen.
     */
    object ProviderHandoff : BrowserEvent

    /** The user said they finished the sign-in in the browser. Clears the flag; asserts nothing else. */
    object ProviderReturnAck : BrowserEvent

    /** The page opened a popup window; [host] is unknown until its first page starts loading. */
    object PopupOpened : BrowserEvent

    /** The popup is now showing [host]. */
    data class PopupShown(val host: String?) : BrowserEvent

    /** The popup is gone — closed by the page, by the user, or by a dead renderer. */
    object PopupClosed : BrowserEvent
    object RetryRequested : BrowserEvent
    object Backgrounded : BrowserEvent
    object Foregrounded : BrowserEvent
}

sealed interface BrowserEffect {
    /** Re-point the view at [url]. The only way a load starts that is not a user's own action. */
    data class Reload(val url: String) : BrowserEffect

    /** Tell the user something in one line, in the app's own words. */
    data class Say(val text: String) : BrowserEffect
}

/**
 * The page's transient second window — `window.open()`, which is how Google's sign-in button works.
 *
 * [host] is the only thing the shell may say about it, and saying even that is a promise the platform
 * asks for: Android's guidance on `onCreateWindow` is that a new window must not simply be overlaid on
 * the page, because the user could otherwise mistake which site they are looking at. The host is what
 * makes the difference visible; nothing here knows or guesses more than that.
 */
data class Popup(val host: String? = null) {
    /** What the strip says when the window has not reported a page yet. */
    val label: String
        get() = host?.let { "You are looking at a window the page opened — $it, not arena.ai." }
            ?: "A window the page just opened is on screen. It is not part of arena.ai."
}

data class Reduction(val state: BrowserState, val effects: List<BrowserEffect> = emptyList())

object BrowserReducer {

    /**
     * Clamped so a page that reports 120% (older WebViews do) cannot produce a broken progress bar.
     */
    fun reduce(state: BrowserState, event: BrowserEvent): Reduction = when (event) {

        is BrowserEvent.LoadStarted -> Reduction(
            state.copy(
                awaitingProviderReturn = false,

                url = event.url,
                phase = PagePhase.LOADING,
                progress = if (state.firstPaint) state.progress else LOADING_START,
                detail = null,
                notice = null,
            ),
        )

        is BrowserEvent.Progress -> {
            val percent = event.percent.coerceIn(0, 100)
            Reduction(
                state.copy(
                    progress = maxOf(if (state.phase == PagePhase.READY) 0 else state.progress, percent),
                    phase = if (state.phase == PagePhase.IDLE) PagePhase.LOADING else state.phase,
                ),
            )
        }

        is BrowserEvent.PageFinished -> Reduction(
            state.copy(
                url = event.url,
                phase = PagePhase.READY,
                progress = 100,
                detail = null,
                failure = null,
                firstPaint = true,
                crashRecovered = if (state.phase == PagePhase.CRASHED) state.crashRecovered else false,
            ),
        )

        is BrowserEvent.LoadFailed -> Reduction(
            state.copy(
                phase = PagePhase.FAILED,
                detail = event.detail,
                failure = event.failure,
                progress = 0,
            ),
            effects = if (state.offline) emptyList() else listOf(BrowserEffect.Say("This page did not load.")),
        )

        is BrowserEvent.AuthPageRejected -> Reduction(
            state.copy(
                phase = PagePhase.AUTH_REFUSED,
                progress = 100,
                detail = "Your sign-in provider answered this window with HTTP ${event.status}. Providers " +
                    "sometimes refuse to work inside another app, and this app will not pretend otherwise.",
            ),
        )

        BrowserEvent.PopupOpened -> Reduction(
            state.copy(popup = Popup()),
        )

        is BrowserEvent.PopupShown ->
            // Only ever a label for a window that is open: a late callback from a popup that already
            // closed must not put an empty sheet back on the screen.
            if (state.popup == null) Reduction(state) else Reduction(
                state.copy(popup = Popup(host = event.host)),
            )

        BrowserEvent.PopupClosed -> Reduction(
            state.copy(popup = null),
        )

        BrowserEvent.ProviderHandoff -> Reduction(
            state.copy(awaitingProviderReturn = true),
        )

        // The acknowledgement is a statement about the platform, never about whether a session exists:
        // the app cannot read either browser's cookies and does not pretend to.
        BrowserEvent.ProviderReturnAck -> Reduction(
            state.copy(awaitingProviderReturn = false),
            listOf(
                BrowserEffect.Say(
                    "Your browser kept the sign-in it finished — this window holds its own copy of the " +
                        "site's cookies, and Android has no way to copy one into the other. If the " +
                        "page below still offers Sign in, use the site's own form here.",
                ),
            ),
        )

        is BrowserEvent.NetworkChanged -> {
            val next = state.copy(offline = !event.available)
            when {
                // A page already on screen is never thrown away because a network came or went.
                event.available && state.phase != PagePhase.FAILED -> Reduction(next)
                event.available -> Reduction(
                    next,
                    effects = listOf(BrowserEffect.Reload(next.url)),
                )
                state.usable -> Reduction(
                    next.copy(notice = "You are offline. The page stays as it loaded; nothing will send " +
                        "until a network returns."),
                )

                else -> Reduction(next)
            }
        }

        is BrowserEvent.CanGoBackChanged -> Reduction(state.copy(canGoBack = event.canGoBack))

        is BrowserEvent.RendererGone -> {
            val next = state.copy(phase = PagePhase.CRASHED, detail = event.detail, progress = 0)
            if (state.crashRecovered) {
                Reduction(next)
            } else {
                Reduction(
                    next.copy(crashRecovered = true),
                    effects = listOf(BrowserEffect.Say("The page stopped responding, so it is being reloaded."),
                        BrowserEffect.Reload(state.url)),
                )
            }
        }

        is BrowserEvent.Notice -> Reduction(state.copy(notice = event.text))

        BrowserEvent.NoticeDismissed -> Reduction(state.copy(notice = null))

        // Dismissing a message never claims the page loaded: a page that was on screen stays on
        // screen, and a page that never painted keeps its failure.
        BrowserEvent.DetailDismissed -> Reduction(
            state.copy(
                detail = null,
                phase = if (state.firstPaint && state.phase != PagePhase.CRASHED) {
                    PagePhase.READY
                } else {
                    state.phase
                },
            ),
        )

        BrowserEvent.RetryRequested -> Reduction(
            state.copy(phase = PagePhase.LOADING, progress = LOADING_START, detail = null),
            effects = listOf(BrowserEffect.Reload(state.url)),
        )

        // The rules the background/foreground requirement is built on: nothing changes, nothing reloads.
        BrowserEvent.Backgrounded -> Reduction(state)
        BrowserEvent.Foregrounded -> Reduction(state)
    }

    private const val LOADING_START = 12
}
