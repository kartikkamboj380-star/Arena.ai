package com.kartikkamboj.arena

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kartikkamboj.arena.ArenaApplication
import com.kartikkamboj.arena.web.ArenaSite
import com.kartikkamboj.arena.web.ParsedUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only checks: the two places where the JVM cannot tell the truth.
 *
 * `android.net.Uri` is a stub in unit tests, so the parser the navigation policy relies on is compared
 * against the real framework class here; and the "one WebView" rule is a property of a live view
 * hierarchy, not of a data class. These tests **have never been executed** — this repository has no
 * emulator, only a build. See docs/TESTING.md for what that means for trusting them.
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
}
