package com.kartikkamboj.arena.ui

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kartikkamboj.arena.di.AppContainer
import com.kartikkamboj.arena.ui.theme.AppPalette
import com.kartikkamboj.arena.ui.theme.ArenaPalette
import com.kartikkamboj.arena.web.ArenaSite
import com.kartikkamboj.arena.web.BrowserState
import com.kartikkamboj.arena.web.NavigationPolicy.HandoffReason
import com.kartikkamboj.arena.web.PagePhase

/**
 * The entire native interface: one page, a few states of it, nothing else.
 *
 * No bottom navigation, no tabs, no drawer, no native composer — each of those would be a second
 * implementation of something the site already does properly. What is here exists only for what a
 * WebView cannot tell the user about itself: a load is in flight, a load failed and why, something
 * left for the browser, and the page you are looking at is not arena.ai.
 */
@Composable
fun ArenaApp(container: AppContainer) {
    val engine = container.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val webView by engine.view.collectAsStateWithLifecycle()
    val palette = AppPalette

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        // The single view. Keyed on the instance so a replaced view re-attaches; an ordinary
        // recomposition only re-attaches, which is what keeps a rotation from reloading the page.
        key(webView) {
            AndroidView(
                factory = { context -> FrameLayout(context) },
                update = { frame -> engine.attachInto(frame) },
                onRelease = { frame -> engine.detachFrom(frame) },
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            )
        }

        LoadBar(state.phase, state.progress, palette.accent)

        if (!state.firstPaint && state.phase == PagePhase.LOADING) {
            ColdStart(palette.inkMuted)
        }

        when {
            // Nothing usable on screen yet, so the failure is the whole screen.
            state.detail != null && !state.usable -> ProblemPanel(
                state = state,
                onRetry = engine::retry,
                onOpenInBrowser = { engine.openExternal(ArenaSite.HOME, HandoffReason.EXTERNAL_LINK) },
                onDismiss = engine::dismissDetail,
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.background)
                    .safeDrawingPadding(),
            )
            // A page is already displayed: say what happened without throwing it away.
            state.detail != null -> ProblemCard(
                state = state,
                onRetry = engine::retry,
                onDismiss = engine::dismissDetail,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }

        state.notice?.let { text ->
            NoticeStrip(
                text = text,
                onDismiss = engine::dismissNotice,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // Last in the z-order, on purpose: the page's own window belongs above the page, and the scrim
        // under it is what keeps it from being mistaken for the site.
        state.popup?.let { window ->
            PopupSheet(
                label = window.label,
                onClose = engine::closePopup,
                onHost = { frame -> engine.attachPopupInto(frame) },
                onRelease = { frame -> engine.detachPopupFrom(frame) },
            )
        }

        if (state.onProviderPage && state.phase == PagePhase.READY && state.detail == null) {
            // Present only when a leg of the flow left this window: the user gets to say what happened
            // there, because nothing in this app can find out.
            val acknowledge: (() -> Unit)? = if (state.awaitingProviderReturn) {
                { engine.acknowledgeProviderReturn() }
            } else {
                null
            }
            ForeignPageStrip(
                text = Problems.providerStripText(ArenaSite.displayHost(state.url), state.awaitingProviderReturn),
                onReturn = { if (!engine.goBack()) engine.openHome() },
                onFinishedInBrowser = acknowledge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * The page's `window.open()` window, shown as a labelled sheet rather than quietly pasted over the site.
 *
 * This exists because Android's guidance for `onCreateWindow` is that a new window must not simply be
 * overlaid: the user has to be able to tell which page they are signing in to. The label is the host the
 * popup is actually on — read from the popup itself, never assumed — and Close is the only affordance,
 * because nothing else in this app is allowed to act inside somebody else's window.
 */
@Composable
private fun PopupSheet(
    label: String,
    onClose: () -> Unit,
    onHost: (FrameLayout) -> Unit,
    onRelease: (FrameLayout) -> Unit,
) {
    val palette = AppPalette
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(ArenaPalette.Scrim),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .padding(top = 44.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clip(shape)
                .background(palette.surface),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(palette.surfaceHigh)
                    .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.inkMuted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose, shape = RoundedCornerShape(8.dp)) {
                    Text("Close", color = palette.accent, fontSize = 13.sp)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close this window", tint = palette.inkMuted)
                }
            }
            AndroidView(
                factory = { context -> FrameLayout(context) },
                update = { frame -> onHost(frame) },
                onRelease = { frame -> onRelease(frame) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

/** 2 dp of progress, straight from the WebView's own callback; it vanishes the moment loading does. */
@Composable
private fun LoadBar(phase: PagePhase, progress: Int, accent: Color) {
    if (phase != PagePhase.LOADING && phase != PagePhase.IDLE) return
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(accent.copy(alpha = 0.16f)),
    ) {
        if (progress > 0) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxSize(),
                color = accent,
                trackColor = Color.Transparent,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = accent,
                trackColor = Color.Transparent,
            )
        }
    }
}

/** The first few hundred milliseconds of a cold start: a name, not a logo reel. */
@Composable
private fun ColdStart(inkMuted: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "ARENA",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 8.sp,
            color = inkMuted,
        )
    }
}

@Composable
private fun ProblemPanel(
    state: BrowserState,
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppPalette
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .background(palette.surface, RoundedCornerShape(20.dp))
                .border(1.dp, palette.outline, RoundedCornerShape(20.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = Problems.titleOf(state),
                style = MaterialTheme.typography.titleMedium,
                color = palette.ink,
            )
            Text(
                text = state.detail.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkMuted,
            )
            Text(
                text = Problems.recoveryOf(state),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkFaint,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onRetry,
                    enabled = Problems.retryMakesSense(state),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent,
                    ),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.height(16.dp))
                    Text("Try again", Modifier.padding(start = 6.dp), fontSize = 14.sp)
                }
                if (state.phase == PagePhase.AUTH_REFUSED) {
                    TextButton(onClick = onOpenInBrowser, shape = RoundedCornerShape(10.dp)) {
                        Text("Open arena.ai in your browser", color = palette.accent, fontSize = 14.sp)
                    }
                } else {
                    TextButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                        Text("Leave this page", color = palette.inkMuted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** A failure on top of a page that is still readable: one card, dismissible, never a blocking dialog. */
@Composable
private fun ProblemCard(
    state: BrowserState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppPalette
    Row(
        modifier
            .fillMaxWidth()
            .background(palette.surfaceHigh, RoundedCornerShape(14.dp))
            .border(1.dp, palette.outline, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                Problems.titleOf(state),
                style = MaterialTheme.typography.labelLarge,
                color = palette.error,
                fontWeight = FontWeight.Medium,
            )
            Text(state.detail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
            TextButton(
                onClick = onRetry,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Try again", color = palette.accent, fontSize = 13.sp)
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = palette.inkFaint,
                modifier = Modifier.height(16.dp),
            )
        }
    }
}

@Composable
private fun NoticeStrip(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val palette = AppPalette
    Row(
        modifier
            .fillMaxWidth()
            .background(palette.surface, RoundedCornerShape(12.dp))
            .border(1.dp, palette.outlineSoft, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkMuted,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = palette.inkFaint,
                modifier = Modifier.height(14.dp),
            )
        }
    }
}

/**
 * The one piece of chrome about trust rather than status: while this window is showing an identity
 * provider's page, the user is told whose page it is, and how to get back. When a leg of the flow went
 * out to the browser, the same strip is where the user can say so — and where the platform's limit gets
 * stated instead of hidden.
 */
@Composable
private fun ForeignPageStrip(
    text: String,
    onReturn: () -> Unit,
    onFinishedInBrowser: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = AppPalette
    Row(
        modifier
            .fillMaxWidth()
            .background(palette.surfaceHigh, RoundedCornerShape(12.dp))
            .border(1.dp, palette.outline, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkMuted,
            modifier = Modifier.weight(1f),
        )
        if (onFinishedInBrowser != null) {
            TextButton(onClick = onFinishedInBrowser, shape = RoundedCornerShape(8.dp)) {
                Text("Finished there", color = palette.accent, fontSize = 13.sp)
            }
        }
        TextButton(onClick = onReturn, shape = RoundedCornerShape(8.dp)) {
            Text("Back to arena.ai", color = palette.accent, fontSize = 13.sp)
        }
    }
}

/**
 * Titles and recovery lines in one place, so the sentences the app says about itself can be read and
 * argued with without a device. Each is checked by `BrowserReducerTest`.
 */
object Problems {

    /**
     * What the strip over a provider page says. Two facts it may state, both verifiable from a
     * navigation: which host is on screen, and whether a leg of the flow left the app. Never whether a
     * session exists — the app cannot see one, in either window.
     */
    fun providerStripText(host: String?, awaitingReturn: Boolean): String = when {
        awaitingReturn ->
            "Sign-in continued in your browser, on ${host ?: "another host"}. A session made there stays " +
                "there: this window has its own, and Android has no way to copy one into the other."

        else ->
            "You are on ${host ?: "another host"} — the site's own sign-in page, not a copy of it."
    }

    fun titleOf(state: BrowserState): String = when {
        state.offline -> "No network"
        state.phase == PagePhase.CRASHED -> "The page stopped responding"
        state.phase == PagePhase.AUTH_REFUSED -> "Your sign-in provider refused this window"
        state.phase == PagePhase.FAILED -> "arena.ai did not load"
        else -> "Something went wrong"
    }

    fun recoveryOf(state: BrowserState): String = when {
        state.phase == PagePhase.AUTH_REFUSED ->
            "Whether sign-in works inside another app is the provider's decision, and this app will " +
                "not spoof its way past that check. A sign-in completed in your browser stays in your " +
                "browser — no session is copied into this window. Use the site's own e-mail form here, " +
                "or keep using arena.ai in the browser."

        state.offline ->
            "Nothing you had open was discarded and the site's own session was left alone. This screen " +
                "reloads itself as soon as a network returns."

        state.phase == PagePhase.CRASHED ->
            "The page's renderer was replaced. Your conversation lives on the site, so reloading brings " +
                "the thread back."

        else ->
            "Nothing in this app can sign you in, send a message, or change how the site is answering."
    }

    /**
     * Whether pressing Retry can plausibly change anything. The gate is the platform's own failure
     * classification (`WebFailure.retryable`) plus the two states the shell knows about: with no
     * network a retry only fails again, and after a dead renderer the reload is what rebuilds the view.
     */
    fun retryMakesSense(state: BrowserState): Boolean {
        if (state.offline && state.phase != PagePhase.CRASHED) return false
        return state.failure?.retryable ?: true
    }
}
