package com.kartikkamboj.arena.web

import com.kartikkamboj.arena.core.WebFailure
import com.kartikkamboj.arena.ui.Problems
import com.kartikkamboj.arena.web.NavigationPolicy.HandoffReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the shell's promises are made of. Each test is written against the sentence a user would
 * see, because that is the contract: a background must not reload, a page that loaded must not be
 * discarded by a status change, and no button press may ever look like a successful load.
 */
class BrowserReducerTest {

    private val home = ArenaSite.HOME

    private fun reduce(state: BrowserState, event: BrowserEvent) = BrowserReducer.reduce(state, event)

    @Test
    fun `starting a load moves to loading and clears an earlier failure`() {
        val failed = reduce(reduce(BrowserState(), BrowserEvent.LoadStarted(home)).state, BrowserEvent.LoadFailed(home, "boom"))
        assertTrue(failed.state.phase == PagePhase.FAILED)
        val again = reduce(failed.state, BrowserEvent.LoadStarted(home))
        assertEquals(PagePhase.LOADING, again.state.phase)
        assertNull(again.state.detail)
    }

    @Test
    fun `progress is clamped and never goes backwards`() {
        var state = reduce(BrowserState(), BrowserEvent.LoadStarted(home)).state
        state = reduce(state, BrowserEvent.Progress(150)).state
        assertEquals(100, state.progress)
        state = reduce(state, BrowserEvent.Progress(20)).state
        assertEquals(100, state.progress)
        state = reduce(state, BrowserEvent.PageFinished(home)).state
        assertEquals(100, state.progress)
        // A finished page reporting progress again starts a fresh measurement, not a stuck bar.
        state = reduce(state, BrowserEvent.Progress(5)).state
        assertEquals(5, state.progress)
    }

    @Test
    fun `only the page finishing marks it ready`() {
        val loading = reduce(BrowserState(), BrowserEvent.LoadStarted(home))
        assertTrue(loading.effects.isEmpty())
        assertFalse(loading.state.firstPaint)
        val ready = reduce(loading.state, BrowserEvent.PageFinished(home))
        assertTrue(ready.state.firstPaint)
        assertEquals(PagePhase.READY, ready.state.phase)
        assertTrue(ready.state.usable)
    }

    @Test
    fun `going to the background and back changes nothing at all`() {
        val ready = reduce(reduce(BrowserState(), BrowserEvent.LoadStarted(home)).state, BrowserEvent.PageFinished(home)).state
        val backgrounded = reduce(ready, BrowserEvent.Backgrounded)
        assertEquals(ready, backgrounded.state)
        assertTrue(backgrounded.effects.isEmpty())
        val foreground = reduce(backgrounded.state, BrowserEvent.Foregrounded)
        assertEquals(ready, foreground.state)
        assertTrue("a foreground return must never queue a reload", foreground.effects.isEmpty())
    }

    @Test
    fun `losing the network keeps the page and says so once`() {
        val ready = reduce(reduce(BrowserState(), BrowserEvent.LoadStarted(home)).state, BrowserEvent.PageFinished(home)).state
        val offline = reduce(ready, BrowserEvent.NetworkChanged(false))
        assertEquals(PagePhase.READY, offline.state.phase)
        assertTrue(offline.state.offline)
        assertTrue(offline.state.usable)
        assertTrue(offline.state.notice!!.contains("offline"))
    }

    @Test
    fun `a failed load is retried on its own only once the network is back`() {
        var state = reduce(BrowserState(), BrowserEvent.LoadStarted(home)).state
        state = reduce(state, BrowserEvent.NetworkChanged(false)).state
        state = reduce(state, BrowserEvent.LoadFailed(home, "no network")).state
        val retryWhileDown = reduce(state, BrowserEvent.NetworkChanged(true))
        assertEquals(1, retryWhileDown.effects.size)
        assertEquals(BrowserEffect.Reload(home), retryWhileDown.effects.first())
        // Once it has loaded again, a subsequent network blip must not trigger another reload.
        val loaded = reduce(retryWhileDown.state, BrowserEvent.PageFinished(home)).state
        val later = reduce(loaded, BrowserEvent.NetworkChanged(false))
        assertTrue(later.effects.isEmpty())
    }

    @Test
    fun `a failure on a loaded page is a card and not a claim that the site is broken`() {
        val ready = reduce(reduce(BrowserState(), BrowserEvent.LoadStarted(home)).state, BrowserEvent.PageFinished(home)).state
        val failed = reduce(ready, BrowserEvent.LoadFailed(home, "The connection dropped part-way through loading."))
        assertEquals(PagePhase.FAILED, failed.state.phase)
        assertTrue(failed.state.usable)
        assertEquals(BrowserEffect.Say("This page did not load."), failed.effects.first())
    }

    @Test
    fun `retry asks for one reload of the page already shown`() {
        val failed = reduce(reduce(BrowserState(), BrowserEvent.LoadFailed(home, "boom")).state, BrowserEvent.RetryRequested)
        assertEquals(listOf<BrowserEffect>(BrowserEffect.Reload(home)), failed.effects)
        assertEquals(PagePhase.LOADING, failed.state.phase)
    }

    @Test
    fun `dismissing a message does not mark the page loaded when it never painted`() {
        val failed = reduce(BrowserState(), BrowserEvent.LoadFailed(home, "boom"))
        val dismissed = reduce(failed.state, BrowserEvent.DetailDismissed)
        assertNull(dismissed.state.detail)
        assertEquals(PagePhase.FAILED, dismissed.state.phase)

        val ready = reduce(reduce(BrowserState(), BrowserEvent.LoadStarted(home)).state, BrowserEvent.PageFinished(home)).state
        val failedOnPage = reduce(ready, BrowserEvent.LoadFailed(home, "boom")).state
        val backToPage = reduce(failedOnPage, BrowserEvent.DetailDismissed)
        assertEquals(PagePhase.READY, backToPage.state.phase)
        assertTrue(backToPage.state.usable)
    }

    @Test
    fun `a dead renderer recovers by itself once and then asks`() {
        val loaded = reduce(reduce(BrowserState(), BrowserEvent.LoadStarted(home)).state, BrowserEvent.PageFinished(home)).state
        val first = reduce(loaded, BrowserEvent.RendererGone("crashed"))
        assertEquals(PagePhase.CRASHED, first.state.phase)
        assertTrue(first.state.crashRecovered)
        assertEquals(
            listOf(BrowserEffect.Say("The page stopped responding, so it is being reloaded."), BrowserEffect.Reload(home)),
            first.effects,
        )
        // Second crash without a successful load in between: no automatic reload loop.
        val second = reduce(first.state, BrowserEvent.RendererGone("crashed again"))
        assertTrue(second.effects.none { it is BrowserEffect.Reload })
        assertFalse(second.state.usable)
    }

    @Test
    fun `a provider that answers with an error is reported as a refusal`() {
        val refused = reduce(BrowserState(), BrowserEvent.AuthPageRejected(400))
        assertEquals(PagePhase.AUTH_REFUSED, refused.state.phase)
        assertTrue(refused.state.detail!!.contains("HTTP 400"))
        // Refusing is not "ready", and it is not an excuse to claim the sign-in worked.
        assertFalse(refused.state.usable)
        assertEquals(100, refused.state.progress)
    }

    @Test
    fun `back affordance follows the view rather than a guess`() {
        val state = reduce(BrowserState(), BrowserEvent.CanGoBackChanged(true)).state
        assertTrue(state.canGoBack)
        assertFalse(reduce(state, BrowserEvent.CanGoBackChanged(false)).state.canGoBack)
    }

    @Test
    fun `notices are replaced and can be dismissed`() {
        val with = reduce(BrowserState(), BrowserEvent.Notice("Saved to Downloads")).state
        assertEquals("Saved to Downloads", with.notice)
        val replaced = reduce(with, BrowserEvent.Notice("Opened in your browser")).state
        assertEquals("Opened in your browser", replaced.notice)
        assertNull(reduce(replaced, BrowserEvent.NoticeDismissed).state.notice)
        // Any new navigation clears a stale notice so it cannot outlive the thing it described.
        assertNull(reduce(replaced, BrowserEvent.LoadStarted(home)).state.notice)
    }

    @Test
    fun `the retry button is gated on what actually failed`() {
        // A malformed address will not start working because it was pressed again; a timeout might.
        val badUrl = reduce(BrowserState(), BrowserEvent.LoadFailed(home, "malformed", WebFailure.BAD_URL))
        assertFalse(Problems.retryMakesSense(badUrl.state))
        val timeout = reduce(BrowserState(), BrowserEvent.LoadFailed(home, "slow", WebFailure.TIMEOUT))
        assertTrue(Problems.retryMakesSense(timeout.state))
        // With no network at all, retry only fails again — except after a dead renderer, where the
        // reload is what rebuilds the view.
        val offline = reduce(timeout.state, BrowserEvent.NetworkChanged(false)).state
        assertFalse(Problems.retryMakesSense(offline))
        val crashedOffline = reduce(
            offline.copy(phase = PagePhase.CRASHED, detail = "gone"),
            BrowserEvent.NetworkChanged(false),
        )
        assertTrue(Problems.retryMakesSense(crashedOffline.state))
    }

    @Test
    fun `the sign-in page is recognised as the provider's and not as the site`() {
        val onProvider = BrowserState(url = "https://accounts.google.com/signin", firstPaint = true)
        assertTrue(onProvider.onProviderPage)
        val onSite = BrowserState(url = home, firstPaint = true)
        assertFalse(onSite.onProviderPage)
        assertTrue(onSite.usable)
    }

    @Test
    fun `an external hand-off is stated with the host it went to`() {
        val text = NavigationPolicy.handoffExplanation(HandoffReason.EXTERNAL_LINK, "example.org")
        assertTrue(text.startsWith("example.org"))
    }

    @Test
    fun `a provider hand-off is remembered and any navigation ends the waiting`() {
        val handed = reduce(BrowserState(), BrowserEvent.ProviderHandoff)
        assertTrue(handed.state.awaitingProviderReturn)
        assertTrue("a hand-off must not move the page or invent a load", handed.effects.isEmpty())
        val navigating = reduce(handed.state, BrowserEvent.LoadStarted(home))
        assertFalse(navigating.state.awaitingProviderReturn)
    }

    @Test
    fun `the acknowledgement states a platform limit and claims nothing about a session`() {
        val awaiting = reduce(BrowserState(), BrowserEvent.ProviderHandoff).state
        val ack = reduce(awaiting, BrowserEvent.ProviderReturnAck)
        assertFalse(ack.state.awaitingProviderReturn)
        val said = ack.effects.filterIsInstance<BrowserEffect.Say>().single().text
        assertTrue(said, said.contains("no way to copy"))
        assertFalse(said, said.contains("signed in"))
        assertFalse(said, said.contains("logged in"))
        assertTrue(said, said.contains("site's own form"))
    }

    @Test
    fun `the provider strip says only what a navigation can prove`() {
        val normal = Problems.providerStripText("accounts.google.com", awaitingReturn = false)
        assertTrue(normal, normal.contains("You are on accounts.google.com"))
        val stranded = Problems.providerStripText("accounts.google.com", awaitingReturn = true)
        assertTrue(stranded, stranded.contains("your browser"))
        assertTrue(stranded, stranded.contains("stays"))
        for (text in listOf(normal, stranded)) {
            assertFalse(text, text.contains("signed in"))
            assertFalse(text, text.contains("success"))
        }
        assertTrue(Problems.providerStripText(null, false).contains("another host"))
    }

    @Test
    fun `there is no state the app could use to report a login`() {
        // The bug this pins: an app that says "signed in" because a redirect looked right. The phase
        // list has no such value, so no branch could claim it even by accident.
        val names = PagePhase.values().map { it.name }
        for (forbidden in listOf("AUTHENTICATED", "SIGNED_IN", "LOGGED_IN", "AUTHORIZED")) {
            assertFalse(names.toString(), names.any { it.contains(forbidden) })
        }
    }
    // ---- the page's own window (window.open) ---------------------------------------------------

    @Test
    fun `a popup opens as a window and closes as a window`() {
        val opened = BrowserReducer.reduce(BrowserState(), BrowserEvent.PopupOpened).state
        assertNotNull("the sheet has to be on screen before the window has a page", opened.popup)
        assertNull("a window with no page yet has no host to name", opened.popup?.host)

        val labelled = BrowserReducer.reduce(opened, BrowserEvent.PopupShown("accounts.google.com")).state
        assertEquals("accounts.google.com", labelled.popup?.host)
        assertTrue(labelled.popup?.label?.contains("accounts.google.com") == true)

        val closed = BrowserReducer.reduce(labelled, BrowserEvent.PopupClosed).state
        assertNull("a closed window must leave nothing behind", closed.popup)
    }

    @Test
    fun `the popup's own page is never mistaken for the site's`() {
        // The bug this pins: the popup navigates, and the app reports that as if the site had
        // navigated — a Google page under the strip, then a "problem with arena.ai" panel when the
        // popup fails. Popup events move the popup and nothing else.
        val site = BrowserState(url = home, phase = PagePhase.READY, firstPaint = true)
        val events = listOf<BrowserEvent>(
            BrowserEvent.PopupOpened,
            BrowserEvent.PopupShown("accounts.google.com"),
            BrowserEvent.PopupShown(null),
            BrowserEvent.PopupClosed,
        )
        for (event in events) {
            val result = BrowserReducer.reduce(site, event)
            assertEquals("popup events cannot rewrite the address: $event", home, result.state.url)
            assertEquals("popup events cannot change the phase: $event", PagePhase.READY, result.state.phase)
            assertTrue("popup events produce no effects: $event", result.effects.isEmpty())
            assertNull("popup events cannot raise a problem panel: $event", result.state.detail)
            assertNull("popup events cannot raise a notice: $event", result.state.notice)
        }
    }

    @Test
    fun `a late popup callback cannot reopen a closed window`() {
        val closed = BrowserReducer.reduce(BrowserState(), BrowserEvent.PopupClosed).state
        val late = BrowserReducer.reduce(closed, BrowserEvent.PopupShown("accounts.google.com")).state
        assertNull("an empty sheet over the site is worse than no sheet", late.popup)
    }

    @Test
    fun `what the sheet says about a window names it and promises nothing`() {
        val withHost = Popup("accounts.google.com").label
        val withoutHost = Popup(null).label
        for (label in listOf(withHost, withoutHost)) {
            assertFalse(label, label.contains("signed in"))
            assertFalse(label, label.contains("logged in"))
            assertFalse(label, label.contains("success"))
            assertFalse(label, label.contains("Complete"))
        }
        assertTrue(withHost, withHost.contains("accounts.google.com"))
        assertTrue(withoutHost, withoutHost.contains("not part of arena.ai"))
    }

}
