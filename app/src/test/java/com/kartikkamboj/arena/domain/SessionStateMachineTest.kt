package com.kartikkamboj.arena.domain

import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.model.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session state machine holds the one claim that matters most for honesty: *Connected* is only
 * ever a conclusion drawn from an observed signed-in surface, never from a cookie that happens to
 * exist or from a page that merely finished loading.
 */
class SessionStateMachineTest {

    private val ttl = 12L * 3_600_000L
    private val config = SessionStateMachine.Config(sessionTtlMillis = ttl, autoReprobeOnLoad = true)

    private fun run(
        from: AccountStatus,
        event: SessionStateMachine.SessionEvent,
        hadStoredSession: Boolean = false,
        cfg: SessionStateMachine.Config = config,
    ): SessionStateMachine.Transition =
        SessionStateMachine.transition(from, event, hadStoredSession, cfg)

    @Test
    fun `a page that settles is not a verdict`() {
        val transition = run(AccountStatus.LOGIN_REQUIRED, SessionStateMachine.SessionEvent.LoadFinished)
        assertEquals(AccountStatus.LOGIN_REQUIRED, transition.status)
        assertTrue("a settled page must schedule a probe", transition.has(SessionStateMachine.Effect.REPROBE))

        val quiet = run(
            AccountStatus.LOGIN_REQUIRED,
            SessionStateMachine.SessionEvent.LoadFinished,
            cfg = config.copy(autoReprobeOnLoad = false),
        )
        assertFalse(quiet.has(SessionStateMachine.Effect.REPROBE))
        assertEquals(AccountStatus.LOGIN_REQUIRED, quiet.status)
    }

    @Test
    fun `only a positive probe produces Connected`() {
        val verified = run(
            AccountStatus.CONNECTING,
            SessionStateMachine.SessionEvent.ProbeAuthenticated("me@corp.example", 1_000L),
            hadStoredSession = true,
        )
        assertEquals(AccountStatus.CONNECTED, verified.status)
        assertTrue(verified.has(SessionStateMachine.Effect.SNAPSHOT_JAR))
        assertFalse(verified.has(SessionStateMachine.Effect.CLEAR_JAR))

        // An inconclusive probe never upgrades, and never downgrades either.
        val inconclusive = run(
            AccountStatus.CONNECTED,
            SessionStateMachine.SessionEvent.ProbeInconclusive(2_000L),
            hadStoredSession = true,
        )
        assertEquals(AccountStatus.CONNECTED, inconclusive.status)
        assertFalse(inconclusive.has(SessionStateMachine.Effect.CLEAR_JAR))
    }

    @Test
    fun `a signed-out surface with a stored jar means expiry, not plain logged out`() {
        val expired = run(
            AccountStatus.CONNECTED,
            SessionStateMachine.SessionEvent.ProbeAnonymous(3_000L),
            hadStoredSession = true,
        )
        assertEquals(AccountStatus.SESSION_EXPIRED, expired.status)
        assertTrue(expired.has(SessionStateMachine.Effect.CLEAR_JAR))
        assertTrue(expired.has(SessionStateMachine.Effect.PROMPT_RECONNECT))
        assertTrue(expired.has(SessionStateMachine.Effect.BLOCK_INTERACTION))

        val fresh = run(AccountStatus.NOT_CONNECTED, SessionStateMachine.SessionEvent.ProbeAnonymous(3_000L))
        assertEquals(AccountStatus.LOGIN_REQUIRED, fresh.status)
        assertFalse("nothing to clear for a slot that never had a jar", fresh.has(SessionStateMachine.Effect.CLEAR_JAR))
    }

    @Test
    fun `http 401 and 403 expire the session while 429 does not`() {
        listOf(ErrorKind.UNAUTHORIZED, ErrorKind.FORBIDDEN).forEach { kind ->
            val transition = run(
                AccountStatus.CONNECTED,
                SessionStateMachine.SessionEvent.HttpFailure(kind, if (kind == ErrorKind.UNAUTHORIZED) 401 else 403),
                hadStoredSession = true,
            )
            assertEquals("$kind", AccountStatus.SESSION_EXPIRED, transition.status)
            assertTrue(transition.has(SessionStateMachine.Effect.CLEAR_JAR))
            assertTrue(transition.has(SessionStateMachine.Effect.PROMPT_RECONNECT))
        }

        val limited = run(
            AccountStatus.CONNECTED,
            SessionStateMachine.SessionEvent.HttpFailure(ErrorKind.RATE_LIMITED, 429),
            hadStoredSession = true,
        )
        assertEquals(AccountStatus.RATE_LIMITED, limited.status)
        assertTrue(limited.has(SessionStateMachine.Effect.SHOW_RATE_LIMIT))
        assertFalse("being throttled is not being logged out", limited.has(SessionStateMachine.Effect.CLEAR_JAR))
    }

    @Test
    fun `rate limiting from the page is not treated as authentication failure`() {
        val transition = run(
            AccountStatus.CONNECTED,
            SessionStateMachine.SessionEvent.RateLimited,
            hadStoredSession = true,
        )
        assertEquals(AccountStatus.RATE_LIMITED, transition.status)
        assertFalse(transition.has(SessionStateMachine.Effect.CLEAR_JAR))
    }

    @Test
    fun `transport failures are connection errors, never sign-in prompts`() {
        val transition = run(
            AccountStatus.CONNECTED,
            SessionStateMachine.SessionEvent.TransportFailure(ErrorKind.NO_NETWORK),
            hadStoredSession = true,
        )
        assertEquals(AccountStatus.CONNECTION_ERROR, transition.status)
        assertTrue(transition.has(SessionStateMachine.Effect.BLOCK_INTERACTION))
        assertFalse("offline must not silently wipe a stored session", transition.has(SessionStateMachine.Effect.CLEAR_JAR))
        assertFalse(transition.has(SessionStateMachine.Effect.PROMPT_RECONNECT))

        val tls = run(
            AccountStatus.CONNECTING,
            SessionStateMachine.SessionEvent.HttpFailure(ErrorKind.TLS, 0),
            hadStoredSession = false,
        )
        assertEquals(AccountStatus.CONNECTION_ERROR, tls.status)
    }

    @Test
    fun `starting a load keeps an authenticated slot and never claims anything for a new one`() {
        val known = run(AccountStatus.CONNECTED, SessionStateMachine.SessionEvent.LoadStarted, hadStoredSession = true)
        assertEquals(AccountStatus.CONNECTED, known.status)

        val unknown = run(AccountStatus.NOT_CONNECTED, SessionStateMachine.SessionEvent.LoadStarted)
        assertEquals(AccountStatus.CONNECTING, unknown.status)
    }

    @Test
    fun `logout clears the jar and asks for a new view`() {
        val transition = run(
            AccountStatus.CONNECTED,
            SessionStateMachine.SessionEvent.LoggedOut,
            hadStoredSession = true,
        )
        assertEquals(AccountStatus.NOT_CONNECTED, transition.status)
        assertTrue(transition.has(SessionStateMachine.Effect.CLEAR_JAR))
        assertTrue(transition.has(SessionStateMachine.Effect.RECREATE_VIEW))
    }

    @Test
    fun `switching away snapshots and preserves status`() {
        val transition = run(AccountStatus.CONNECTED, SessionStateMachine.SessionEvent.SwitchedAway, hadStoredSession = true)
        assertEquals("a backgrounded slot must not be re-labelled", AccountStatus.CONNECTED, transition.status)
        assertTrue(transition.has(SessionStateMachine.Effect.SNAPSHOT_JAR))
    }

    @Test
    fun `dismissing a banner re-probes instead of declaring a verdict`() {
        val rateLimited = run(
            AccountStatus.RATE_LIMITED,
            SessionStateMachine.SessionEvent.Dismissed,
            hadStoredSession = true,
        )
        assertEquals(
            "a banner can only be cleared by a probe, never by closing it",
            AccountStatus.CONNECTED,
            rateLimited.status,
        )
        assertTrue(rateLimited.has(SessionStateMachine.Effect.REPROBE))

        val notConnected = run(AccountStatus.LOGIN_REQUIRED, SessionStateMachine.SessionEvent.Dismissed)
        assertEquals(AccountStatus.LOGIN_REQUIRED, notConnected.status)
    }

    @Test
    fun `boot status never upgrades a stored jar`() {
        assertEquals(
            AccountStatus.NOT_CONNECTED,
            SessionStateMachine.bootStatus(hadStoredSession = false, snapshotAgeMillis = 0L, config = config),
        )
        // Stored and inside the TTL is still "unverified", which the UI renders that way.
        assertEquals(
            AccountStatus.NOT_CONNECTED,
            SessionStateMachine.bootStatus(hadStoredSession = true, snapshotAgeMillis = 60_000L, config = config),
        )
        assertEquals(
            AccountStatus.SESSION_EXPIRED,
            SessionStateMachine.bootStatus(hadStoredSession = true, snapshotAgeMillis = ttl + 1L, config = config),
        )
    }

    @Test
    fun `an aged-out stored session is expired and cleared`() {
        val transition = run(
            AccountStatus.CONNECTED,
            SessionStateMachine.SessionEvent.SessionAgedOut(10L * ttl),
            hadStoredSession = true,
        )
        assertEquals(AccountStatus.SESSION_EXPIRED, transition.status)
        assertTrue(transition.has(SessionStateMachine.Effect.CLEAR_JAR))
        assertTrue(transition.has(SessionStateMachine.Effect.PROMPT_RECONNECT))

        val nothingStored = run(
            AccountStatus.CONNECTED,
            SessionStateMachine.SessionEvent.SessionAgedOut(10L * ttl),
            hadStoredSession = false,
        )
        assertEquals("no stored session means nothing to expire", AccountStatus.NOT_CONNECTED, nothingStored.status)
    }

    @Test
    fun `transitions never mutate the input status and always explain themselves`() {
        val transition = run(AccountStatus.CONNECTING, SessionStateMachine.SessionEvent.ProbeAnonymous(1L))
        assertEquals(AccountStatus.LOGIN_REQUIRED, transition.status)
        assertTrue("every transition carries a human-readable reason", transition.reason.isNotBlank())
    }
}
