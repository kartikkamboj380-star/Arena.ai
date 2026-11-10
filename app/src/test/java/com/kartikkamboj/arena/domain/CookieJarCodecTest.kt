package com.kartikkamboj.arena.domain

import com.kartikkamboj.arena.data.model.CookieEntry
import com.kartikkamboj.arena.data.model.CookieJarSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cookie-jar codec is the boundary between "session material" and the filesystem, so every rule
 * here is a safety rule: unparseable names are dropped rather than escaped, values that could break
 * out of a `Set-Cookie` line are refused, and material that looks like a credential is never kept at
 * all — not even encrypted.
 */
class CookieJarCodecTest {

    private val now = 1_000_000L
    private val ttl = 60_000L

    @Test
    fun `parses the pairs CookieManager actually returns`() {
        val entries = CookieJarCodec.parse("sid=abc123; csrf-token=xyz", ttl, now)
        assertEquals(2, entries.size)
        assertEquals("sid", entries[0].name)
        assertEquals("abc123", entries[0].value)
        assertTrue("only https pages are loaded, so every entry is marked secure", entries[0].secure)
        assertEquals("/", entries[0].path)
        assertEquals(now + ttl, entries[0].expiresAtMillis)
        assertEquals("csrf-token", entries[1].name)
    }

    @Test
    fun `blank input is an empty jar, not a failure`() {
        assertTrue(CookieJarCodec.parse(null, ttl, now).isEmpty())
        assertTrue(CookieJarCodec.parse("   ", ttl, now).isEmpty())
    }

    @Test
    fun `Set-Cookie attribute names are skipped rather than stored as cookies`() {
        val entries = CookieJarCodec.parse(
            "session=abc; Expires=Wed, 21 Oct 2026 07:47:00 GMT; Path=/api; Secure; HttpOnly; SameSite=Lax",
            ttl,
            now,
        )
        assertEquals(listOf("session"), entries.map { it.name })
    }

    @Test
    fun `re-injection carries no server-side expiry because none was observable`() {
        val entry = CookieJarCodec.build("sid", "abc", ttl, now)!!
        val setCookie = CookieJarCodec.toSetCookie(entry)
        assertTrue(setCookie.startsWith("sid=abc"))
        assertTrue(setCookie.contains("Path=/"))
        assertTrue(setCookie.contains("Secure"))
        assertTrue(setCookie.contains("SameSite=Lax"))
        assertFalse(
            "the app must not mint an Expires the server never sent",
            setCookie.contains("Expires", ignoreCase = true) || setCookie.contains("Max-Age", ignoreCase = true),
        )
    }

    @Test
    fun `values that could break out of a header are refused`() {
        val bad = listOf(
            "a;b", // would create a second cookie
            "a\nSet-Cookie: evil=1", // header injection
            "line1\r\nline2", // trim keeps the interior break
            "quoted\"value",
            "'single'",
            "back\\slash",
        )
        bad.forEach { value ->
            assertNull("'$value' must not be accepted", CookieJarCodec.build("sid", value, ttl, now))
        }
    }

    @Test
    fun `names outside the RFC token grammar are dropped instead of escaped`() {
        listOf("", "bad name", "n<script>", "eq=l", "q?uote", "semi;colon", "x".repeat(65)).forEach { name ->
            assertNull("'$name' is not a cookie-name", CookieJarCodec.build(name, "v", ttl, now))
        }
        // The token grammar is wide: these are all legitimate names.
        listOf("sid", "__Host-session", "csrf-token", "auth.v2", "~weird").forEach { name ->
            assertNotNull("'$name' should be accepted", CookieJarCodec.build(name, "v", ttl, now))
        }
    }

    @Test
    fun `credential-shaped cookie names are never stored`() {
        listOf("password", "user_password", "app_secret", "otp", "otp_backup", "atmpin").forEach { name ->
            assertNull("$name must never reach disk", CookieJarCodec.build(name, "v", ttl, now))
        }
        assertNotNull(CookieJarCodec.build("session_id", "v", ttl, now))
    }

    @Test
    fun `empty values are not a session`() {
        assertNull(CookieJarCodec.build("sid", "", ttl, now))
        assertNull(CookieJarCodec.build("sid", "   ", ttl, now))
        assertNull(CookieJarCodec.build("sid", "x".repeat(CookieJarCodec.MAX_VALUE_LENGTH + 1), ttl, now))
    }

    @Test
    fun `a jar is capped so a hostile page cannot bloat storage`() {
        val header = (1..300).joinToString("; ") { "name$it=value$it" }
        val entries = CookieJarCodec.parse(header, ttl, now)
        assertTrue(entries.size <= CookieJarCodec.MAX_ENTRIES)
    }

    @Test
    fun `merging replaces by name and path rather than duplicating a rotated cookie`() {
        val old = listOf(
            CookieEntry("sid", "old"),
            CookieEntry("csrf", "keep"),
        )
        val incoming = listOf(CookieEntry("sid", "new"), CookieEntry("theme", "dark"))
        val merged = CookieJarCodec.merge(old, incoming)
        assertEquals(3, merged.size)
        assertEquals("new", merged.first { it.name == "sid" }.value)
    }

    @Test
    fun `pruning removes stale entries and credential-shaped names`() {
        val entries = listOf(
            CookieEntry("fresh", "a", expiresAtMillis = now + 1),
            CookieEntry("stale", "b", expiresAtMillis = now - 1),
            CookieEntry("no-expiry", "c"),
            CookieEntry("session_secret_otp", "d"),
        )
        val pruned = CookieJarCodec.prune(entries, now)
        assertEquals(listOf("fresh", "no-expiry"), pruned.map { it.name })
    }

    @Test
    fun `a zero expiry means the app owns re-injection, not that the cookie is expired`() {
        val entries = CookieJarCodec.prune(listOf(CookieEntry("sid", "v", expiresAtMillis = 0L)), now + 10_000L)
        assertEquals(1, entries.size)
    }

    @Test
    fun `the header form is the joined jar and is clipped`() {
        val entries = listOf(CookieEntry("a", "1"), CookieEntry("b", "2"))
        assertEquals("a=1; b=2", CookieJarCodec.toCookieHeader(entries))
        val huge = List(64) { CookieEntry("n$it", "v".repeat(1_000)) }
        assertEquals(CookieJarCodec.MAX_TOTAL_LENGTH, CookieJarCodec.toCookieHeader(huge).length)
    }

    @Test
    fun `summaries never include a value`() {
        val entries = listOf(CookieEntry("sid", "super-secret"), CookieEntry("__Host-session", "also-secret"))
        val summary = CookieJarCodec.summarise(entries)
        assertFalse(summary.contains("super-secret"))
        assertFalse(summary.contains("also-secret"))
        assertEquals("sid(12), __Host-session(11)", summary)
        assertEquals("empty", CookieJarCodec.summarise(emptyList()))
    }

    @Test
    fun `session identity is reported without inspecting contents`() {
        assertTrue(CookieJarCodec.carriesSessionIdentity(listOf(CookieEntry("connect.sid", "v"))))
        assertTrue(CookieJarCodec.carriesSessionIdentity(listOf(CookieEntry("auth_token", "v"))))
        assertFalse(CookieJarCodec.carriesSessionIdentity(listOf(CookieEntry("theme", "dark"))))
        assertFalse(CookieJarCodec.carriesSessionIdentity(emptyList()))
    }

    @Test
    fun `two jars that share nothing prove the slots stayed separate`() {
        val a = listOf(CookieEntry("sid", "1"), CookieEntry("csrf", "2"))
        val b = listOf(CookieEntry("sid", "3"))
        assertEquals(setOf("sid"), CookieJarCodec.overlap(a, b))
        assertEquals(
            emptySet<String>(),
            CookieJarCodec.overlap(a, listOf(CookieEntry("theme", "dark"))),
        )
    }

    @Test
    fun `snapshots expire by their own bookkeeping, not by guesswork`() {
        val fresh = CookieJarCodec.build("sid", "v", ttl, now)!!
        assertFalse(fresh.isStale(now))
        assertTrue(fresh.isStale(now + ttl + 1))

        val snapshot = CookieJarSnapshot(slot = 1, capturedAtMillis = now, ttlMillis = ttl, entries = listOf(fresh))
        assertFalse(snapshot.isExpired(now + ttl / 2))
        assertTrue(snapshot.isExpired(now + ttl + 1))
        assertEquals(now + ttl, snapshot.effectiveExpiry(now + 1))

        val cookieDiesFirst = snapshot.copy(entries = listOf(fresh.copy(expiresAtMillis = now + 10)))
        assertEquals(now + 10, cookieDiesFirst.effectiveExpiry(now))

        // With no cookies at all in the jar, only the local TTL speaks, and it says: expired.
        assertTrue(cookieDiesFirst.copy(entries = emptyList()).isExpired(now + ttl + 1))
    }

    @Test
    fun `a jar with no expiry at all has no local TTL claim`() {
        val snapshot = CookieJarSnapshot(slot = 1, capturedAtMillis = 0L, ttlMillis = 0L)
        assertNull(snapshot.effectiveExpiry(now))
        assertFalse(snapshot.isExpired(now + 10_000L))
        assertTrue(snapshot.isEmpty)
    }
}
