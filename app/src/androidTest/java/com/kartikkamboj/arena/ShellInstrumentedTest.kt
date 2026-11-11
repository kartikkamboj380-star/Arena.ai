package com.kartikkamboj.arena

import android.net.Uri
import android.os.Message
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kartikkamboj.arena.ArenaApplication
import com.kartikkamboj.arena.web.ArenaSite
import com.kartikkamboj.arena.web.ArenaWebChromeClient
import com.kartikkamboj.arena.web.ArenaWebViewClient
import com.kartikkamboj.arena.web.ParsedUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only checks: the two places where the JVM cannot tell the truth.
 *
 * `android.net.Uri` is a stub in unit tests, so the parser the navigation policy relies on is compared
 * against the real framework class here; the window-request rules need a live `WebView`, because the
 * transport the framework hands back only exists on a device; and the "one persistent view, at most one
 * window" rule is a property of a real view hierarchy, not of a data class. These tests **have never been
 * executed** — this repository has no emulator, only a build. See docs/TESTING.md for what that means for
 * trusting them.
 */
@RunWith(AndroidJUnit4::class)
class ShellInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val urls = listOf(
        "https://arena.ai/",
        "https://www.arena.ai/leaderboard/text?sort=elo",
        "https://arena.ai/some page",
        "https://accounts.google.com/v3/signin/identifier?flow=1",
        "https://arena.ai.example.com/",
        "http://arena.ai/history",
    )

    @Test
    fun ourUrlParserAgreesWithTheFrameworkAboutHosts() {
        for (raw in urls) {
            val expected = runCatching { Uri.parse(raw).host?.lowercase() }.getOrNull()
            val actual = ParsedUrl.of(raw)?.host ?: ParsedUrl.lenient(raw)?.host
            assertEquals("host mismatch for $raw", expected, actual)
        }
    }

    @Test
    fun theFrameworkAndThePolicyTrustTheSameOrigins() {
        for (raw in urls) {
            val host = Uri.parse(raw).host?.lowercase()
            // The same rule as the policy's, expressed through the framework's own parser: an https
            // URL on arena.ai or any subdomain of it, and nothing else. This is the device-side check
            // that the app's parser and the platform's agree about where a sign-in may render — the
            // bug that motivated it was exactly a disagreement about `auth.arena.ai`.
            val trustedByFramework = Uri.parse(raw).scheme == "https" &&
                (host == "arena.ai" || host?.endsWith(".arena.ai") == true)
            assertEquals("trust mismatch for $raw", trustedByFramework, ArenaSite.isSiteFamily(raw))
        }
    }

    @Test
    fun thePageAsksForAViewTwiceAndGetTheSameOne() {
        val engine = (context.applicationContext as ArenaApplication).container.engine
        var first: android.webkit.WebView? = null
        var second: android.webkit.WebView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            first = engine.ensureView()
            second = engine.ensureView()
        }
        assertSame("the shell must reuse its one persistent view", first, second)
        // The name this test used to carry was "the app never creates a second WebView", which stopped
        // being true when a sign-in popup got a real window: the honest rule is one *persistent* view
        // plus at most one transient window, which is what the CI promise step counts.
        assertNotNull(engine.currentView)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.shutdown() }
    }

    @Test
    fun aReplacedViewIsANewInstanceAndNeverTheDeadOne() {
        val engine = (context.applicationContext as ArenaApplication).container.engine
        var before: android.webkit.WebView? = null
        var after: android.webkit.WebView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            before = engine.ensureView()
            engine.destroyView()
            after = engine.ensureView()
        }
        assertNotSame(before, after)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.shutdown() }
    }

    @Test
    fun backOnAFreshPageIsRefusedRatherThanInvented() {
        val engine = (context.applicationContext as ArenaApplication).container.engine
        var handled = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.ensureView()
            handled = engine.goBack()
            engine.shutdown()
        }
        assertFalse("a view with no history must not claim it went back", handled)
    }

    @Test
    fun theProgressCallbackCannotLieAboutPainting() {
        val engine = (context.applicationContext as ArenaApplication).container.engine
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.onLoadProgress(100)
            assertFalse(engine.state.value.firstPaint)
            engine.onPageFinished(ArenaSite.HOME)
            assertTrue(engine.state.value.firstPaint)
        }
    }
    /**
     * The window rules, as a device assertion. A JVM test cannot reach any of this, because `Message`,
     * the hit test a gesture-less request would fall back to, and the view identity checks all need a
     * live framework `WebView`.
     *
     * The granted path is deliberately **not** tested here: `WebView.WebViewTransport` — the object the
     * framework hands a host to receive the new view through — is an inner class of `WebView` that only
     * the framework constructs, so a test cannot fabricate one. That half is covered where it can be:
     * the CI promise step requires the transport to be wired and the window destroyed on every teardown
     * path, and docs/TESTING.md §4 item 4c is the device check for the sheet appearing.
     */
    @Test
    fun aWindowRequestIsGrantedOnlyForAGestureItCanActuallyWire() {
        val engine = (context.applicationContext as ArenaApplication).container.engine
        var refusedWithoutGesture = true
        var refusedWithoutTransport = true
        var nullMessageHandled = true
        var pageIsNeverThePopup = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val page = engine.ensureView()
            // No touch happened, so there is no hit-test target and no fallback: the refusal must be total.
            refusedWithoutGesture = engine.requestPopup(requester = page, isUserGesture = false, resultMsg = Message.obtain())
            // A gesture, but the framework gave us nothing usable to hand a view to. Returning true here
            // would be undefined behaviour, and leaving a sheet up would be a promise with nothing behind
            // it — which is the one case this app can construct, by putting something else on the message.
            refusedWithoutTransport = engine.requestPopup(
                requester = page,
                isUserGesture = true,
                resultMsg = Message.obtain().apply { obj = "not a transport" },
            )
            nullMessageHandled = !ArenaWebChromeClient(engine).onCreateWindow(page, false, true, null)
            pageIsNeverThePopup = !engine.isPopup(page) && !engine.isPopup(null)
        }
        assertFalse("a window.open() without a user gesture must not get a window", refusedWithoutGesture)
        assertFalse("a window the framework cannot be told about must not be granted", refusedWithoutTransport)
        assertTrue("a callback with no message must be refused, not crashed", nullMessageHandled)
        assertTrue("the page's own view must never be treated as the popup", pageIsNeverThePopup)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertNull("and nothing may be left above the page", engine.state.value.popup)
            engine.shutdown()
        }
    }

    /**
     * Which view is governed by what, checked on the one view a test can legitimately build. The popup
     * cannot be fabricated from here (see the test above), so the promise this pins is the one the popup
     * inherits: `configure()` is what attaches the app's policy, and it is the same call for both views.
     */
    @Test
    fun thePageViewCarriesTheAppsOwnClients() {
        val engine = (context.applicationContext as ArenaApplication).container.engine
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val page = engine.ensureView()
            assertTrue("the page must be governed by the app's navigation policy", page.webViewClient is ArenaWebViewClient)
            assertTrue("and by the app's window and file handling", page.webChromeClient is ArenaWebChromeClient)
            val stranger = WebView(context)
            assertFalse("a WebView that is not ours is not the popup either", engine.isPopup(stranger))
            stranger.destroy()
            engine.shutdown()
        }
    }
}
