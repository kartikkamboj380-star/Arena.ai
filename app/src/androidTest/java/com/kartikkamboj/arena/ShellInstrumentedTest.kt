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
    fun theAppNeverCreatesASecondWebView() {
        val engine = (context.applicationContext as ArenaApplication).container.engine
        var first: android.webkit.WebView? = null
        var second: android.webkit.WebView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            first = engine.ensureView()
            second = engine.ensureView()
        }
        assertSame("the shell must reuse its one view", first, second)
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
     * The rules the sign-in fix is made of, as a device assertion. A JVM test cannot cover any of this:
     * `Message`, `WebViewTransport` and the hit test a gesture-less request would need all belong to a
     * live framework `WebView`.
     */
    @Test
    fun aWindowRequestIsGrantedOnlyForAGestureItCanActuallyWire() {
        val engine = (context.applicationContext as ArenaApplication).container.engine
        var refusedWithoutGesture = true
        var refusedWithoutTransport = true
        var nullMessageHandled = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val page = engine.ensureView()
            // No touch happened, so there is no hit-test target and no fallback: the refusal must be total.
            refusedWithoutGesture = engine.requestPopup(requester = page, isUserGesture = false, resultMsg = Message.obtain())
            // A gesture, but the framework gave us nothing to hand the window to. Returning true here
            // would be undefined behaviour, and leaving a sheet up would be a promise we cannot keep.
            refusedWithoutTransport = engine.requestPopup(requester = page, isUserGesture = true, resultMsg = Message.obtain())
            nullMessageHandled = !ArenaWebChromeClient(engine).onCreateWindow(page, false, true, null)
        }
        assertFalse("a window.open() without a user gesture must not get a window", refusedWithoutGesture)
        assertFalse("a window the framework cannot be told about must not be granted", refusedWithoutTransport)
        assertTrue("a callback with no message must be refused, not crashed", nullMessageHandled)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertNull("and nothing may be left above the page", engine.state.value.popup)
            engine.shutdown()
        }
    }

    /**
     * The granted path: a real second `WebView`, handed to the transport the framework supplied, tracked
     * in the state so the shell can label it, and gone without a trace when it closes.
     */
    @Test
    fun aGestureGrantedAWindowThatClosesCleanly() {
        val engine = (context.applicationContext as ArenaApplication).container.engine
        var given: WebView? = null
        var wired = false
        var labelled = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val page = engine.ensureView()
            var handed: WebView? = null
            val transport = object : WebView.WebViewTransport() {
                override fun setWebView(webView: WebView?) {
                    handed = webView
                }
            }
            val message = Message.obtain().apply { obj = transport }
            wired = engine.requestPopup(requester = page, isUserGesture = true, resultMsg = message)
            given = handed
            labelled = engine.state.value.popup != null
            // The one claim that matters about the window's contents and cannot be made from the JVM:
            // it is governed by this app's policy, not by the page that opened it. minSdk 26 is exactly
            // where WebView.getWebViewClient() exists, so this is readable here and nowhere else.
            assertTrue(
                "the popup must run under the app's own navigation policy",
                (handed?.webViewClient is ArenaWebViewClient) && handed !== page,
            )
            engine.closePopup()
            engine.shutdown()
        }
        assertTrue("a gesture-initiated window.open must be granted", wired)
        assertNotNull("the popup view must be the one handed to the transport", given)
        assertTrue("the shell must know a window is up", labelled)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertNull("a closed window must leave nothing above the page", engine.state.value.popup)
        }
    }
}
