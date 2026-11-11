package com.kartikkamboj.arena.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the failure card shows, and the code mapping behind them. Tested because this is the app's
 * only claim about a network problem: a wrong mapping sends a user chasing their router instead of a
 * site that is fine, and a sloppy sentence can leak a fragment of a URL.
 */
class WebErrorsTest {

    @Test
    fun `no network outranks every error code`() {
        for (failure in WebFailure.entries) {
            val text = WebErrors.describe(failure, "whatever", offline = true)
            assertTrue(failure.name, text.startsWith("There is no network"))
        }
    }

    @Test
    fun `every failure has a sentence that names a next step`() {
        for (failure in WebFailure.entries) {
            val text = WebErrors.describe(failure, null, offline = false)
            assertTrue(failure.name, text.length > 30)
            assertFalse(failure.name, text.contains("cookie"))
            assertFalse(failure.name, text.contains("token"))
        }
    }

    @Test
    fun `the platform codes map to the failures they describe`() {
        assertEquals(WebFailure.TIMEOUT, WebFailures.fromErrorCode(WebFailures.ERROR_TIMEOUT))
        assertEquals(WebFailure.HOST_NOT_FOUND, WebFailures.fromErrorCode(WebFailures.ERROR_HOST_LOOKUP))
        assertEquals(WebFailure.CONNECT, WebFailures.fromErrorCode(WebFailures.ERROR_CONNECT))
        assertEquals(WebFailure.IO, WebFailures.fromErrorCode(WebFailures.ERROR_IO))
        assertEquals(WebFailure.SSL, WebFailures.fromErrorCode(WebFailures.ERROR_FAILED_SSL_HANDSHAKE))
        assertEquals(WebFailure.UNSAFE_RESOURCE, WebFailures.fromErrorCode(WebFailures.ERROR_UNSAFE_RESOURCE))
        assertEquals(WebFailure.BAD_URL, WebFailures.fromErrorCode(WebFailures.ERROR_BAD_URL))
        assertEquals(WebFailure.REDIRECT_LOOP, WebFailures.fromErrorCode(WebFailures.ERROR_REDIRECT_LOOP))
        assertEquals(WebFailure.PROXY_AUTH, WebFailures.fromErrorCode(WebFailures.ERROR_PROXY_AUTHENTICATION))
        assertEquals(WebFailure.AUTH, WebFailures.fromErrorCode(WebFailures.ERROR_AUTHENTICATION))
        assertEquals(WebFailure.UNSUPPORTED_SCHEME, WebFailures.fromErrorCode(WebFailures.ERROR_UNSUPPORTED_SCHEME))
        assertEquals(WebFailure.FILE, WebFailures.fromErrorCode(WebFailures.ERROR_FILE_NOT_FOUND))
        assertEquals(WebFailure.TOO_MANY_REQUESTS, WebFailures.fromErrorCode(WebFailures.ERROR_TOO_MANY_REQUESTS))
        assertEquals(WebFailure.UNKNOWN, WebFailures.fromErrorCode(WebFailures.ERROR_UNKNOWN))
        assertEquals(WebFailure.UNKNOWN, WebFailures.fromErrorCode(1234))
    }

    @Test
    fun `the code values match the published platform constants`() {
        // Cited from android.webkit.WebViewClient. If Android ever changes one, this is the tripwire
        // that says so instead of a silently wrong error card.
        assertEquals(-1, WebFailures.ERROR_UNKNOWN)
        assertEquals(-2, WebFailures.ERROR_HOST_LOOKUP)
        assertEquals(-3, WebFailures.ERROR_UNSUPPORTED_AUTH_SCHEME)
        assertEquals(-4, WebFailures.ERROR_AUTHENTICATION)
        assertEquals(-5, WebFailures.ERROR_PROXY_AUTHENTICATION)
        assertEquals(-6, WebFailures.ERROR_CONNECT)
        assertEquals(-7, WebFailures.ERROR_IO)
        assertEquals(-8, WebFailures.ERROR_TIMEOUT)
        assertEquals(-9, WebFailures.ERROR_REDIRECT_LOOP)
        assertEquals(-10, WebFailures.ERROR_UNSUPPORTED_SCHEME)
        assertEquals(-11, WebFailures.ERROR_FAILED_SSL_HANDSHAKE)
        assertEquals(-12, WebFailures.ERROR_BAD_URL)
        assertEquals(-13, WebFailures.ERROR_FILE)
        assertEquals(-14, WebFailures.ERROR_FILE_NOT_FOUND)
        assertEquals(-15, WebFailures.ERROR_TOO_MANY_REQUESTS)
        assertEquals(-16, WebFailures.ERROR_UNSAFE_RESOURCE)
    }

    @Test
    fun `only failures that can plausibly pass again are worth retrying`() {
        assertFalse(WebFailure.BAD_URL.retryable)
        assertFalse(WebFailure.UNSAFE_RESOURCE.retryable)
        assertTrue(WebFailure.TIMEOUT.retryable)
        assertTrue(WebFailure.HOST_NOT_FOUND.retryable)
    }

    @Test
    fun `a long platform description is dropped rather than appended`() {
        val short = WebErrors.describe(WebFailure.IO, "socket closed", false)
        assertTrue(short.endsWith("(socket closed)"))
        val long = WebErrors.describe(WebFailure.IO, "x".repeat(400), false)
        assertFalse(long.contains("xxxx"))
    }
}
