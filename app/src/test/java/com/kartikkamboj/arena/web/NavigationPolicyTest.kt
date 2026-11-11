package com.kartikkamboj.arena.web

import com.kartikkamboj.arena.web.NavigationPolicy.BlockReason
import com.kartikkamboj.arena.web.NavigationPolicy.Decision
import com.kartikkamboj.arena.web.NavigationPolicy.HandoffReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust boundary of the whole app, asserted on the JVM. These are the rules that decide what the
 * signed-in view is allowed to render, so they are tested rather than described.
 */
class NavigationPolicyTest {

    @Test
    fun `the site itself renders in app`() {
        for (url in listOf("https://arena.ai/", "https://arena.ai/leaderboard/text", "https://www.arena.ai/x?y=1")) {
            assertEquals(Decision.LoadInApp(url), NavigationPolicy.classify(url))
        }
    }

    @Test
    fun `cleartext for the site is upgraded rather than followed`() {
        assertEquals(
            Decision.LoadInApp("https://arena.ai/history"),
            NavigationPolicy.classify("http://arena.ai/history"),
        )
    }

    @Test
    fun `identity provider pages may render so the site's own sign-in can complete`() {
        assertTrue(
            NavigationPolicy.classify("https://accounts.google.com/v3/signin/identifier") is Decision.LoadInApp,
        )
        assertTrue(NavigationPolicy.classify("https://oauth2.googleapis.com/o/oauth2/auth") is Decision.LoadInApp)
    }

    @Test
    fun `any other https site leaves for the browser`() {
        val decision = NavigationPolicy.classify("https://example.com/pricing")
        assertEquals(Decision.HandOffToBrowser("https://example.com/pricing", HandoffReason.EXTERNAL_LINK), decision)
    }

    @Test
    fun `someone else's cleartext page is not loaded over an unencrypted connection`() {
        assertEquals(
            Decision.HandOffToBrowser("http://example.com", HandoffReason.INSECURE_SCHEME),
            NavigationPolicy.classify("http://example.com"),
        )
    }

    @Test
    fun `script inline and file schemes never reach the view`() {
        assertEquals(BlockReason.JAVASCRIPT_SCHEME, (NavigationPolicy.classify("javascript:alert(1)") as Decision.Block).reason)
        assertEquals(BlockReason.DATA_SCHEME, (NavigationPolicy.classify("data:text/html,<script>a</script>") as Decision.Block).reason)
        assertEquals(BlockReason.FILE_SCHEME, (NavigationPolicy.classify("file:///sdcard/x.html") as Decision.Block).reason)
        // intent: and friends must not become a way to launch another app's component from a page.
        assertEquals(BlockReason.MALFORMED_URL, (NavigationPolicy.classify("intent://scan/#Intent") as Decision.Block).reason)
    }

    @Test
    fun `a lookalike host is not the site`() {
        for (url in listOf("https://arena.ai.example.com/", "https://notarena.ai/", "https://arena.ai.evil.co/x")) {
            val decision = NavigationPolicy.classify(url)
            assertTrue("expected a hand-off for $url but got $decision", decision is Decision.HandOffToBrowser)
        }
    }

    @Test
    fun `an arena host in a path or query is not an arena origin`() {
        val decision = NavigationPolicy.classify("https://example.com/?next=https://arena.ai/")
        assertTrue(decision is Decision.HandOffToBrowser)
    }

    @Test
    fun `mailto and tel go to the system handler not to a browser tab`() {
        assertEquals(
            Decision.HandOffToBrowser("mailto:someone@example.com", HandoffReason.APP_HANDLER),
            NavigationPolicy.classify("mailto:someone@example.com"),
        )
        assertEquals(
            Decision.HandOffToBrowser("tel:+911234567890", HandoffReason.APP_HANDLER),
            NavigationPolicy.classify("tel:+911234567890"),
        )
    }

    @Test
    fun `blank is accepted and no other about target is`() {
        assertEquals(Decision.LoadInApp("about:blank"), NavigationPolicy.classify("about:blank"))
        assertEquals(
            BlockReason.ABOUT_BLANK_ONLY,
            (NavigationPolicy.classify("about:config") as Decision.Block).reason,
        )
    }

    @Test
    fun `nothing is invented for an empty address`() {
        assertEquals(BlockReason.MALFORMED_URL, (NavigationPolicy.classify(null) as Decision.Block).reason)
        assertEquals(BlockReason.MALFORMED_URL, (NavigationPolicy.classify("   ") as Decision.Block).reason)
    }

    @Test
    fun `a space in a path is not a reason to refuse a page`() {
        // URI() rejects this; the lenient fallback keeps the origin rules working like a browser's.
        assertTrue(NavigationPolicy.classify("https://arena.ai/some page") is Decision.LoadInApp)
    }

    @Test
    fun `relative references resolve against the page they came from`() {
        assertEquals(
            Decision.LoadInApp("https://arena.ai/history"),
            NavigationPolicy.classifyReference("/history", "https://arena.ai/leaderboard/text"),
        )
        // A bare relative reference is resolved against the *directory* of the page, exactly as a
        // browser would: from /leaderboard/text, "blog" is /leaderboard/blog, not /blog.
        assertEquals(
            Decision.LoadInApp("https://arena.ai/leaderboard/blog"),
            NavigationPolicy.classifyReference("blog", "https://arena.ai/leaderboard/text"),
        )
        assertEquals(
            Decision.LoadInApp("https://arena.ai/x"),
            NavigationPolicy.classifyReference("//arena.ai/x", "https://arena.ai/"),
        )
        // An off-site absolute reference from an arena page still leaves the app.
        assertTrue(
            NavigationPolicy.classifyReference("https://example.com/x", "https://arena.ai/") is Decision.HandOffToBrowser,
        )
    }

    @Test
    fun `a new window is only allowed for site content and never invents one`() {
        assertEquals("https://arena.ai/x", NavigationPolicy.allowsInAppWindow("https://arena.ai/x"))
        assertNull(NavigationPolicy.allowsInAppWindow("https://example.com/x"))
        assertNull(NavigationPolicy.allowsInAppWindow("javascript:alert(1)"))
        assertNull(NavigationPolicy.allowsInAppWindow(null))
    }

    @Test
    fun `every decision the user can see has a sentence attached`() {
        for (reason in HandoffReason.entries) {
            val text = NavigationPolicy.handoffExplanation(reason, "example.com")
            assertTrue(reason.name, text.contains("example.com"))
            assertTrue(reason.name, text.length > 30)
        }
        for (reason in BlockReason.entries) {
            assertTrue(reason.name, NavigationPolicy.blockExplanation(reason).length > 20)
        }
    }

    // --- the sign-in chain, which is the reason the rules are contextual -----------------------

    @Test
    fun `the site's own auth subdomain stays in the app`() {
        // This is the bug this rule exists to prevent: auth lives on auth.arena.ai, and an exact-host
        // list sent it to a browser tab where its session could never come back.
        val decision = NavigationPolicy.classify("https://auth.arena.ai/login?redirect=%2F")
        assertTrue(decision is NavigationPolicy.Decision.LoadInApp)
    }

    @Test
    fun `a host that merely contains the domain is not the site`() {
        for (url in listOf("https://arena.ai.evil.net/x", "https://notarena.ai/x", "https://arena.aievil.net/x")) {
            val decision = NavigationPolicy.classify(url)
            assertTrue("expected a hand-off for $url", decision is NavigationPolicy.Decision.HandOffToBrowser)
        }
    }

    @Test
    fun `the callback leg back to the site is loaded in app`() {
        val decision = NavigationPolicy.classify(
            "https://auth.arena.ai/api/auth/callback/google?code=secret&state=xyz",
            fromProviderPage = true,
        )
        assertEquals(
            "https://auth.arena.ai/api/auth/callback/google?code=secret&state=xyz",
            (decision as NavigationPolicy.Decision.LoadInApp).url,
        )
    }

    @Test
    fun `the provider's other domains continue a chain already in progress and start none`() {
        val leg = "https://myaccount.google.com/permissions"
        assertTrue(
            "a stray google page must not become app content",
            NavigationPolicy.classify(leg) is NavigationPolicy.Decision.HandOffToBrowser,
        )
        assertTrue(
            "mid-chain it is the provider's own next screen",
            NavigationPolicy.classify(leg, fromProviderPage = true) is NavigationPolicy.Decision.LoadInApp,
        )
    }

    @Test
    fun `mid-chain is not unconditional because someone else's site still leaves`() {
        val decision = NavigationPolicy.classify("https://medium.com/@arena/notes", fromProviderPage = true)
        assertTrue(decision is NavigationPolicy.Decision.HandOffToBrowser)
    }

    @Test
    fun `a cleartext request for a subdomain host is upgraded rather than handed off`() {
        val decision = NavigationPolicy.classify("http://auth.arena.ai/start")
        assertEquals("https://auth.arena.ai/start", (decision as NavigationPolicy.Decision.LoadInApp).url)
    }

    @Test
    fun `the log summary is a rule, not a url`() {
        assertEquals(
            "in-app",
            NavigationPolicy.logSummary(NavigationPolicy.classify(ArenaSite.HOME)),
        )
        assertEquals(
            "hand-off(external_link)",
            NavigationPolicy.logSummary(NavigationPolicy.classify("https://example.com/x")),
        )
        assertEquals(
            "block(javascript_scheme)",
            NavigationPolicy.logSummary(NavigationPolicy.classify("javascript:alert(1)")),
        )
    }

}
