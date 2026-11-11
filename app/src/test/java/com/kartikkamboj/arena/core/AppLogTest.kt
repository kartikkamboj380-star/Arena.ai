package com.kartikkamboj.arena.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redaction that lets the app log anything at all. It no longer reads cookies or tokens by design,
 * but the platform hands it strings — error descriptions, URLs, exception messages — that can carry a
 * session, and those must not reach logcat.
 */
class AppLogTest {

    private fun check(input: String, vararg secrets: String) {
        val out = AppLog.redact(input)
        for (secret in secrets) {
            assertFalse("leaked '$secret' in: $out", out.contains(secret))
        }
    }

    @Test
    fun `cookie headers lose their values`() {
        check("Set-Cookie: __Secure-session=abc123def456; Path=/; Secure", "abc123def456")
        check("Cookie: sid=supersecretvalue", "supersecretvalue")
    }

    @Test
    fun `bearer and basic credentials are masked`() {
        check("Authorization: Bearer eyJhbGciOi.abcdefghijklmnop.uvwx", "abcdefghijklmnop")
        check("authorization: Basic dXNlcjpwYXNz", "dXNlcjpwYXNz")
    }

    @Test
    fun `json shaped secrets are masked`() {
        check("""{"password":"hunter2","otp":"481902"}""", "hunter2", "481902")
        check("""{"refresh_token":"r-9f8a7b6c5d4e"}""", "r-9f8a7b6c5d4e")
    }

    @Test
    fun `emails do not belong in logs`() {
        val out = AppLog.redact("failed for someone@example.com while loading")
        assertFalse(out.contains("someone@example.com"))
        assertTrue(out.contains("<email>"))
    }

    @Test
    fun `a diagnosis names the failure and stays short`() {
        val text = AppLog.diagnose(IllegalStateException("connect timed out"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("connect timed out"))
        assertTrue(AppLog.diagnose(RuntimeException("x".repeat(400))).length <= 160)
    }

    @Test
    fun `ordinary sentences are left alone`() {
        val plain = "the page finished loading in 480 ms"
        assertTrue(AppLog.redact(plain) == plain)
    }
}
