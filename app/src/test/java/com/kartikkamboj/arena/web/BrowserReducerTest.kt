package com.kartikkamboj.arena.web

import com.kartikkamboj.arena.core.WebFailure
import com.kartikkamboj.arena.ui.Problems
import com.kartikkamboj.arena.web.NavigationPolicy.HandoffReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val failed = reduce(reduce(BrowserState(), BrowserEvent.LoadStarted(home)), BrowserEvent.LoadFailed(home, "boom"))
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
        assertEquals("This page did not load.", failed.effects.first())
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
}
