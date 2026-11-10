package com.kartikkamboj.arena.web

import com.kartikkamboj.arena.core.FakeClock
import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.model.ArenaAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what the WebView is allowed to do, plus the read-only probes that decide
 * what the app is allowed to *claim*. These are pure functions over strings and urls on purpose:
 * the security posture of the app is then testable without an emulator.
 */
class NavigationPolicyTest {

    @Test
    fun `arena ai https urls load in the session`() {
        val decision = NavigationPolicy.classify("https://arena.ai/leaderboard")
        assertTrue(decision is NavigationPolicy.Decision.LoadInSession)
        assertEquals("https://arena.ai/leaderboard", (decision as NavigationPolicy.Decision.LoadInSession).url)
    }

    @Test
    fun `www is treated as the same site`() {
        assertTrue(NavigationPolicy.classify("https://www.arena.ai/") is NavigationPolicy.Decision.LoadInSession)
        assertFalse("but not a suffix trick", ParsedUrl.of("https://arena.ai.evil.example")!!.hasArenaHost)
    }

    @Test
    fun `plain http is upgraded for arena ai only`() {
        val decision = NavigationPolicy.classify("http://arena.ai/leaderboard")
        assertTrue(decision is NavigationPolicy.Decision.LoadInSession)
        assertEquals("https://arena.ai/leaderboard", (decision as NavigationPolicy.Decision.LoadInSession).url)

        val other = NavigationPolicy.classify("http://example.com/")
        assertTrue("a third-party http link is never silently upgraded", other is NavigationPolicy.Decision.Block)
    }

    @Test
    fun `identity providers are handed to the browser, never blocked`() {
        listOf(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=x",
            "https://oauth2.googleapis.com/token",
            "https://www.google.com/account/login",
        ).forEach { url ->
            val decision = NavigationPolicy.classify(url)
            assertTrue("$url must be handed off", decision is NavigationPolicy.Decision.HandOffToBrowser)
            assertEquals(
                "the reason is shown to the user so the hand-off is not mysterious",
                NavigationPolicy.HandoffReason.IDENTITY_PROVIDER,
                (decision as NavigationPolicy.Decision.HandOffToBrowser).reason,
            )
        }
    }

    @Test
    fun `a sign-in in flight keeps the provider inside the slot's jar`() {
        // This is the login-redirect fix as a rule, not as a story: while the user is signing this
        // slot in, the provider must load *here*, because a session established in the system browser
        // lives in the browser's cookie jar and can never be installed into this slot.
        listOf(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=x",
            "https://www.google.com/account/login",
        ).forEach { url ->
            val decision = NavigationPolicy.classify(url, allowIdentityHosts = true)
            assertTrue("$url should stay in the session", decision is NavigationPolicy.Decision.LoadInSession)
            assertEquals(url, (decision as NavigationPolicy.Decision.LoadInSession).url)
        }
    }

    @Test
    fun `the sign-in window widens nothing but the provider`() {
        // Everything else is judged exactly as it would be outside a sign-in: no new origin becomes
        // loadable, no blocked scheme becomes allowed.
        val stranger = NavigationPolicy.classify("https://evil.example/callback", allowIdentityHosts = true)
        assertTrue(stranger is NavigationPolicy.Decision.HandOffToBrowser)
        assertEquals(
            NavigationPolicy.HandoffReason.EXTERNAL_LINK,
            (stranger as NavigationPolicy.Decision.HandOffToBrowser).reason,
        )
        assertTrue(
            "a javascript: url is refused even mid-sign-in",
            NavigationPolicy.classify("javascript:alert(document.cookie)", allowIdentityHosts = true)
                is NavigationPolicy.Decision.Block,
        )
        assertTrue(
            "cleartext to a provider is never upgraded into the session",
            NavigationPolicy.classify("http://accounts.google.com/x", allowIdentityHosts = true)
                is NavigationPolicy.Decision.Block,
        )
        assertTrue(
            "the arena origin is unaffected by the flag",
            NavigationPolicy.classify("https://arena.ai/", allowIdentityHosts = true)
                is NavigationPolicy.Decision.LoadInSession,
        )
        // A host that merely looks like a provider is not one.
        assertTrue(
            NavigationPolicy.classify("https://accounts.google.com.evil.example/x", allowIdentityHosts = true)
                is NavigationPolicy.Decision.HandOffToBrowser,
        )
    }

    @Test
    fun `other external links leave the app instead of hijacking the session`() {
        val decision = NavigationPolicy.classify("https://github.com/kartikkamboj380-star/Arena.ai")
        assertTrue(decision is NavigationPolicy.Decision.HandOffToBrowser)
        assertEquals(NavigationPolicy.HandoffReason.EXTERNAL_LINK, (decision as NavigationPolicy.Decision.HandOffToBrowser).reason)
    }

    @Test
    fun `dangerous schemes are blocked and explained`() {
        listOf(
            "javascript:alert(document.cookie)",
            "file:///sdcard/Download/x.html",
            "content://com.other.provider/secret",
            "intent://scan/#Intent",
            "data:text/html,<script>1</script>",
        ).forEach { url ->
            assertTrue("$url must not load", NavigationPolicy.classify(url) is NavigationPolicy.Decision.Block)
        }
    }

    @Test
    fun `about blank is allowed because window open starts there`() {
        assertTrue(NavigationPolicy.classify("about:blank") is NavigationPolicy.Decision.LoadInSession)
    }

    @Test
    fun `a hand-off is explained instead of leaving the user wondering`() {
        val text = NavigationPolicy.handoffExplanation(NavigationPolicy.HandoffReason.IDENTITY_PROVIDER)
        assertTrue(text.contains("browser"))
        assertFalse("the explanation must not read like an error code", text.contains("Exception"))
        assertTrue(NavigationPolicy.handoffExplanation(NavigationPolicy.HandoffReason.EXTERNAL_LINK).isNotBlank())
    }

    @Test
    fun `garbage is blocked rather than guessed at`() {
        listOf(null, "", "   ", "::::", "http://[bad uri").forEach { url ->
            assertTrue("$url must not load", NavigationPolicy.classify(url) is NavigationPolicy.Decision.Block)
        }
    }

    @Test
    fun `a scheme-less href from the site is resolved and re-checked`() {
        val decision = NavigationPolicy.classify("/leaderboard")
        assertTrue(decision is NavigationPolicy.Decision.LoadInSession)
        assertEquals("https://arena.ai/leaderboard", (decision as NavigationPolicy.Decision.LoadInSession).url)
    }

    @Test
    fun `new in-app windows are only accepted for arena ai`() {
        assertTrue(NavigationPolicy.allowsNewInAppWindow("https://arena.ai/thread/1"))
        assertFalse(NavigationPolicy.allowsNewInAppWindow("https://evil.example/"))
        assertFalse(NavigationPolicy.allowsNewInAppWindow("javascript:alert(1)"))
    }
}

class ArenaUrlsTest {

    @Test
    fun `relative hrefs resolve against the arena origin`() {
        assertEquals("https://arena.ai/leaderboard", ArenaUrls.absolute("/leaderboard", "https://arena.ai/thread/x"))
        assertEquals("https://arena.ai/x/y", ArenaUrls.absolute("x/y", "https://arena.ai/"))
        // A root-relative link on a thread page must not become a thread path.
        assertEquals("https://arena.ai/blog", ArenaUrls.absolute("/blog", "https://arena.ai/thread/x"))
    }

    @Test
    fun `protocol relative and scheme-less links still land on https`() {
        assertEquals("https://arena.ai/x", ArenaUrls.absolute("//arena.ai/x", "https://arena.ai/"))
        assertEquals("https://arena.ai/x", ArenaUrls.absolute("arena.ai/x", "https://arena.ai/"))
    }

    @Test
    fun `off-origin and script links are never turned into arena urls`() {
        assertNull(ArenaUrls.absolute("javascript:alert(1)", "https://arena.ai/"))
        assertNull(ArenaUrls.absolute("file:///etc/hosts", "https://arena.ai/"))
        assertNull(ArenaUrls.absolute("data:text/html,x", "https://arena.ai/"))
        assertNull(ArenaUrls.absolute("https://evil.example/", "https://arena.ai/"))
        assertNull(ArenaUrls.absolute(null, "https://arena.ai/"))
        assertNull(ArenaUrls.absolute("   ", "https://arena.ai/"))
    }

    @Test
    fun `only arena ai over https is an arena url`() {
        assertTrue(ArenaUrls.isArenaUrl("https://arena.ai/"))
        assertFalse("http is never acceptable", ArenaUrls.isArenaUrl("http://arena.ai/"))
        assertFalse(ArenaUrls.isArenaUrl("https://arena.ai.evil.example/"))
        assertFalse(ArenaUrls.isArenaUrl("https://evil.example/?next=arena.ai"))
        assertFalse(ArenaUrls.isArenaUrl(null))
    }

    @Test
    fun `a scheme-less host-shaped href stays on the arena origin`() {
        assertTrue(ArenaUrls.absolute("arena.ai/x", "https://arena.ai/")!!.startsWith("https://arena.ai/"))
    }

    @Test
    fun `conversation paths count as content while public chrome does not`() {
        assertTrue(ArenaUrls.isContentPath("https://arena.ai/thread/abc"))
        assertTrue(ArenaUrls.isContentPath("https://arena.ai/history/search"))
        // The public pages are deliberately excluded: the history importer must not record
        // "Leaderboard" as one of somebody's conversations.
        assertFalse(ArenaUrls.isContentPath("https://arena.ai/leaderboard"))
        assertFalse(ArenaUrls.isContentPath("https://arena.ai/leaderboard/text"))
        assertFalse(ArenaUrls.isContentPath("https://arena.ai/blog/post"))
        assertFalse(ArenaUrls.isContentPath("https://arena.ai/"))
        assertFalse(ArenaUrls.isContentPath("https://arena.ai/_next/static/chunk.js"))
        assertFalse(ArenaUrls.isContentPath("https://arena.ai/cdn-cgi/challenge-platform/x"))
        assertFalse(ArenaUrls.isContentPath("https://evil.example/thread/1"))
        assertFalse(ArenaUrls.isContentPath(null))
    }

    @Test
    fun `the cookie url is the host-only origin the jar is stamped with`() {
        assertEquals("https://arena.ai", ArenaUrls.COOKIE_URL)
        assertEquals(setOf("arena.ai", "www.arena.ai"), ArenaUrls.ARENA_HOSTS)
    }

    @Test
    fun `the documented entry points are exactly the ones that exist`() {
        assertEquals("https://arena.ai/", ArenaUrls.CHAT)
        assertEquals("https://arena.ai/agent", ArenaUrls.AGENT)
        assertEquals("https://arena.ai/leaderboard", ArenaUrls.LEADERBOARD)
        assertEquals("https://arena.ai/history/search", ArenaUrls.HISTORY_SEARCH)
        // The paths the site actually serves; nothing is invented here.
        assertTrue(ArenaUrls.NON_CONTENT_PREFIXES.isNotEmpty())
    }
}

class AuthProbeTest {

    private fun json(
        loginPrompt: Boolean = false,
        userChrome: Boolean = false,
        label: String? = null,
        rateLimited: Boolean = false,
        url: String = "https://arena.ai/",
        title: String = "Arena",
        ok: Boolean = true,
    ): String = buildString {
        append("{\"ok\":").append(ok)
        append(",\"url\":\"").append(url).append('"')
        append(",\"title\":\"").append(title).append('"')
        append(",\"loginPrompt\":").append(loginPrompt)
        append(",\"userChrome\":").append(userChrome)
        append(",\"label\":").append(label?.let { "\"$it\"" } ?: "null")
        append(",\"rateLimited\":").append(rateLimited)
        append(",\"sessionExpired\":false")
        append(",\"textLength\":1200}")
    }

    @Test
    fun `evaluateJavascript unwrapping handles quoted and empty payloads`() {
        assertEquals("", AuthProbe.unwrap(null))
        assertEquals("", AuthProbe.unwrap(""))
        assertEquals("", AuthProbe.unwrap("null"))
        assertEquals("hello", AuthProbe.unwrap("\"hello\""))
        assertEquals("say \"hi\"", AuthProbe.unwrap("\"say \\\"hi\\\"\""))
        assertEquals("{\"a\":1}", AuthProbe.unwrap("{\"a\":1}"))
    }

    @Test
    fun `a signed-in surface is the only thing that yields Authenticated`() {
        val signedIn = AuthProbe.probe(json(userChrome = true, label = "me@corp.example"), now = 1_000L)
        assertEquals(com.kartikkamboj.arena.data.model.ProbeVerdict.AUTHENTICATED, signedIn.verdict)
        assertEquals("me@corp.example", signedIn.accountLabel)
        assertEquals(1_000L, signedIn.observedAtMillis)

        val signedOut = AuthProbe.probe(json(loginPrompt = true), now = 2_000L)
        assertEquals(com.kartikkamboj.arena.data.model.ProbeVerdict.ANONYMOUS, signedOut.verdict)
        assertTrue(signedOut.seesLoginPrompt)

        val empty = AuthProbe.probe(null, now = 3_000L)
        assertEquals(com.kartikkamboj.arena.data.model.ProbeVerdict.UNKNOWN, empty.verdict)
    }

    @Test
    fun `both signals at once is not a verdict`() {
        val both = AuthProbe.probe(json(loginPrompt = true, userChrome = true), now = 1L)
        assertEquals(
            "an ambiguous surface must not be reported as either state",
            com.kartikkamboj.arena.data.model.ProbeVerdict.UNKNOWN,
            both.verdict,
        )
    }

    @Test
    fun `a failed probe never invents a verdict`() {
        assertEquals(
            com.kartikkamboj.arena.data.model.ProbeVerdict.UNKNOWN,
            AuthProbe.probe(json(ok = false, userChrome = true), now = 1L).verdict,
        )
        assertEquals(
            com.kartikkamboj.arena.data.model.ProbeVerdict.UNKNOWN,
            AuthProbe.probe("{not json", now = 1L).verdict,
        )
    }

    @Test
    fun `the rate-limit flag is carried out of the page for the banner`() {
        val limited = AuthProbe.probe(json(userChrome = true, rateLimited = true), now = 1L)
        assertTrue(limited.seesRateLimitNotice)
        assertEquals(com.kartikkamboj.arena.data.model.ProbeVerdict.AUTHENTICATED, limited.verdict)
    }

    @Test
    fun `the probe script is read-only and touches nothing the site owns`() {
        val script = AuthProbe.SCRIPT
        assertTrue("the probe needs the current url to reject off-origin reads", script.contains("location.href"))
        // The probe is read-only: no fetch/XHR (which would add traffic Arena did not see from the
        // user), and nothing is written to the page.
        listOf("fetch(", "XMLHttpRequest", ".submit(", "localStorage.setItem", ".click()").forEach { forbidden ->
            assertFalse("the probe must not $forbidden", script.contains(forbidden))
        }
    }

    @Test
    fun `fields are parsed defensively`() {
        val fields = AuthProbe.fields(json(userChrome = true, label = "x"))
        assertNotNull(fields)
        assertTrue(fields!!.userChrome)
        assertEquals("x", fields.label)
        assertNull(AuthProbe.fields("{not json"))
        assertNull(AuthProbe.fields(null))
    }
}

class PageExtractorTest {

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Builds the exact payload shape [PageExtractor.LIST_SCRIPT] returns: rows of `t` + `h`. */
    private fun payload(vararg rows: String, ok: Boolean = true, url: String = "https://arena.ai/leaderboard"): String {
        val items = rows.toList().chunked(2).joinToString(",") { pair ->
            val text = pair[0]
            val href = if (pair.size > 1) pair[1] else "#"
            "{\"t\":\"" + escape(text) + "\",\"h\":\"" + href + "\"}"
        }
        return "{\"ok\":" + ok + ",\"url\":\"" + url + "\",\"items\":[" + items + "]}"
    }

    private fun items(raw: String): List<PageExtractor.Item> = PageExtractor.parseItems(raw).first

    @Test
    fun `unwrapped or failed payloads yield nothing rather than guessing`() {
        assertTrue(items("null").isEmpty())
        assertTrue(items("").isEmpty())
        assertTrue(items("{not json").isEmpty())
        assertTrue(items("{\"ok\":false,\"url\":\"https://arena.ai/\",\"items\":[{\"t\":\"1 · A · 1\",\"h\":\"#\"}]}").isEmpty())
        // A well-formed payload is read back as items, with the url the page reported.
        val (rows, _) = PageExtractor.parseItems(payload("1 · Model A · 1200", "#"))
        assertEquals(1, rows.size)
        assertEquals("Model A", PageExtractor.models(rows, 0L).single().displayName)
    }

    @Test
    fun `the page url is returned for the origin check`() {
        val (_, url) = PageExtractor.parseItems(payload("1 · A · 2", "#", url = "https://arena.ai/leaderboard/text"))
        assertEquals("https://arena.ai/leaderboard/text", url)
    }

    @Test
    fun `model rows become entries with a stable id and no invented fields`() {
        val entries = PageExtractor.models(
            items(
                payload(
                    "1 · GPT-5.2 High · OpenAI · 1372",
                    "/leaderboard/model/gpt-5-2-high",
                    "2 · Claude Opus 4.5 · Anthropic · 1355",
                    "/leaderboard/model/claude-opus-4-5",
                    "1 · GPT-5.2 High · OpenAI · 1372",
                    "/leaderboard/model/gpt-5-2-high",
                ),
            ),
            now = 5_000L,
        )
        assertEquals(2, entries.size)
        val first = entries.first { it.rank == 1 }
        assertEquals("GPT-5.2 High", first.displayName)
        assertEquals("OpenAI", first.vendor)
        assertEquals(1, first.rank)
        assertEquals(1372.0, first.score!!, 0.0001)
        assertEquals("live:gpt-5-2-high", first.id)
        assertEquals(5_000L, first.addedAtMillis)
        assertEquals(
            "a leaderboard row is not a promise that the model can be chosen from the app",
            com.kartikkamboj.arena.data.model.ModelAvailability.INFORMATIONAL_ONLY,
            first.availability,
        )
        assertEquals("Claude Opus 4.5", entries.first { it.rank == 2 }.displayName)
        assertEquals(
            "the id is a slug of what was seen, so a re-read matches the same row",
            "live:claude-opus-4-5",
            entries.first { it.rank == 2 }.id,
        )
    }

    @Test
    fun `text that is not a ranked row never becomes a model`() {
        val entries = PageExtractor.models(
            items(
                payload(
                    "Top 10 Agents", "#",
                    "View all", "/leaderboard",
                    "Arena on X", "https://x.com/arena",
                    "Pareto optimal models", "#",
                ),
            ),
            now = 0L,
        )
        assertTrue("refusing to guess is the point: $entries", entries.isEmpty())
    }

    @Test
    fun `a ranked row without a vendor or score is kept as seen and not completed by guessing`() {
        val entries = PageExtractor.models(items(payload("4 · Some Model", "#")), now = 0L)
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("Some Model", entry.displayName)
        assertNull("no vendor was printed, so none is invented", entry.vendor)
        assertNull("no score was printed, so none is invented", entry.score)
        assertEquals(4, entry.rank)
    }

    @Test
    fun `nav chrome is filtered out of link extraction`() {
        val links = PageExtractor.links(
            items(
                payload(
                    "Log in", "/login",
                    "Leaderboard trends", "/leaderboard/trends",
                    "OpenAI", "/leaderboard/organization/openai",
                    "First impressions", "/blog/first-impressions",
                    "My chat", "/thread/abc",
                    "Arena on X", "https://x.com/arena",
                ),
            ),
            baseUrl = "https://arena.ai/leaderboard",
        )
        assertEquals(listOf("My chat"), links.map { it.title })
    }

    @Test
    fun `link extraction resolves hrefs and drops non-http targets`() {
        val links = PageExtractor.links(
            items(
                payload(
                    "Chat A", "/thread/a",
                    "Chat B", "/thread/b",
                    "Script", "javascript:alert(1)",
                    "Blank", "#",
                    "Off site", "https://example.com/x",
                ),
            ),
            baseUrl = "https://arena.ai/history",
        )
        assertEquals(
            // The off-origin link is dropped rather than resolved, and "#" / "javascript:" never
            // become records at all.
            listOf("https://arena.ai/thread/a", "https://arena.ai/thread/b"),
            links.map { it.url },
        )
    }

    @Test
    fun `duplicate links collapse`() {
        val links = PageExtractor.links(
            items(payload("Same", "/thread/x", "Same again", "/thread/x")),
            baseUrl = "https://arena.ai/",
        )
        assertEquals(1, links.size)
    }

    @Test
    fun `the injection script only reads the DOM`() {
        val script = PageExtractor.LIST_SCRIPT
        listOf("fetch(", "XMLHttpRequest", "localStorage.setItem", ".submit(", "eval(").forEach { forbidden ->
            assertFalse("the extractor must not $forbidden", script.contains(forbidden))
        }
        assertTrue(script.contains("querySelectorAll"))
        assertTrue("the result must be JSON so parsing stays deterministic", script.contains("JSON.stringify"))
    }

    @Test
    fun `page chrome is recognised by its label as well as its path`() {
        val links = PageExtractor.links(
            items(
                payload(
                    "Log in", "/login",
                    "Sign in", "/signin",
                    "Learn more", "/about",
                    "Privacy", "/privacy-note",
                    "My chat about knots", "/thread/knots",
                ),
            ),
            baseUrl = "https://arena.ai/",
        )
        assertEquals(listOf("My chat about knots"), links.map { it.title })
    }
}

class RateLimitMonitorTest {

    private val clock = FakeClock(1_000L)

    @Test
    fun `a limit belongs to the slot that hit it`() {
        val monitor = RateLimitMonitor(clock)
        assertNull(monitor.current(1))
        monitor.observeHttpStatus(1, 429)
        assertEquals(1, monitor.activeSlots.size)
        assertTrue(monitor.isLimited(1))
        assertFalse("another account is untouched", monitor.isLimited(2))
        monitor.clear(1)
        assertFalse(monitor.isLimited(1))
    }

    @Test
    fun `only a real 429 counts and a page notice is a weaker signal`() {
        val monitor = RateLimitMonitor(clock)
        assertNull(monitor.observeHttpStatus(1, 200))
        assertNull(monitor.observeHttpStatus(1, 503))
        assertNull(monitor.current(1))

        monitor.observePageNotice(1, true)
        assertEquals(RateLimitMonitor.Source.PAGE_NOTICE, monitor.current(1)?.source)

        monitor.observeHttpStatus(1, 429)
        assertEquals("a real status code outranks a text guess", RateLimitMonitor.Source.HTTP_429, monitor.current(1)?.source)
        monitor.observePageNotice(1, true)
        assertEquals("and is never downgraded by one", RateLimitMonitor.Source.HTTP_429, monitor.current(1)?.source)
    }

    @Test
    fun `the first sighting time is kept across repeated observations`() {
        val monitor = RateLimitMonitor(clock)
        monitor.observeHttpStatus(1, 429)
        clock.advanceMillis(60_000L)
        monitor.observeHttpStatus(1, 429)
        assertEquals(1_000L, monitor.current(1)?.sinceMillis)
    }

    @Test
    fun `alternatives are limited to the user's own verified accounts`() {
        val monitor = RateLimitMonitor(clock)
        val accounts = listOf(
            ArenaAccount(slot = 1, label = "One", status = AccountStatus.CONNECTED),
            ArenaAccount(slot = 2, label = "Two", status = AccountStatus.CONNECTED),
            ArenaAccount(slot = 3, label = "Three", status = AccountStatus.LOGIN_REQUIRED),
            ArenaAccount(slot = 4, label = "Four", status = AccountStatus.SESSION_EXPIRED),
        )
        monitor.observeHttpStatus(3, 429)
        assertEquals(listOf(2), monitor.alternatives(accounts, currentSlot = 1).map { it.slot })
        assertEquals(emptyList<Int>(), monitor.alternatives(accounts.take(1), currentSlot = 1).map { it.slot })
    }

    @Test
    fun `the app never rotates on its own`() {
        assertFalse(RateLimitMonitor(clock).automaticallyRotatesAccounts)
    }

    @Test
    fun `the banner offers manual switching but never a quota number`() {
        val monitor = RateLimitMonitor(clock)
        monitor.observeHttpStatus(2, 429)
        val account = ArenaAccount(slot = 2, label = "Work", status = AccountStatus.RATE_LIMITED)
        val message = monitor.messageFor(account, "Claude Opus 4.5", quotaClaims = false)
        assertTrue(message.contains("Work"))
        assertTrue(message.contains("Claude Opus 4.5"))
        assertFalse(message.contains("remaining"))
        assertFalse(message.contains("requests left"))

        val withClaim = monitor.messageFor(account, null, quotaClaims = true)
        assertTrue(withClaim.contains("five times the quota"))
        monitor.clearAll()
        assertFalse(monitor.isLimited(2))
    }
}

