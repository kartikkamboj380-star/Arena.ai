package com.kartikkamboj.arena.domain

import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.model.ErrorKind

/**
 * The single authority on how an account slot's status changes.
 *
 * Design goals, all of which are covered by unit tests:
 *
 *  * No optimistic "connected". [AccountStatus.CONNECTED] requires a positive probe
 *    ([SessionEvent.ProbeAuthenticated]); a page that merely finished loading stays
 *    [AccountStatus.CONNECTING].
 *  * Losing a session is explained, not swallowed: an unknown page never downgrades a working
 *    slot, while an explicit logged-out page does.
 *  * Effects are returned to the caller instead of being executed here, so the machine is pure
 *    and the Android layer (cookie jars, WebView reloads, snackbars) stays testable by proxy.
 */
object SessionStateMachine {

    data class Transition(
        val status: AccountStatus,
        val effects: Set<Effect>,
        val reason: String,
    ) {
        fun has(effect: Effect): Boolean = effect in effects
    }

    enum class Effect {
        /** Persist the slot's current cookie jar before it is swapped away. */
        SNAPSHOT_JAR,

        /** Drop the slot's jar and any WebView data (logout, removal, expiry). */
        CLEAR_JAR,

        /** Refuse new interaction; the page may still be readable. */
        BLOCK_INTERACTION,

        /** Ask the user to reconnect; preserves the transcript in Arena's own history. */
        PROMPT_RECONNECT,

        /** Re-run the authentication probe once the next page settles. */
        REPROBE,

        /** Show the rate-limit banner naming the account and model. */
        SHOW_RATE_LIMIT,

        /** Teardown and rebuild the WebView for this slot. */
        RECREATE_VIEW,

        /** Nothing to do — the transition was a no-op. */
        NONE,
    }

    sealed interface SessionEvent {
        /** User (or the app, on first paint) started a load for this slot. */
        data object LoadStarted : SessionEvent

        /** A load finished and the DOM probe is about to answer. */
        data object LoadFinished : SessionEvent

        /** Probe saw signed-in chrome. [label] is whatever Arena displays as the user handle. */
        data class ProbeAuthenticated(val label: String?, val atMillis: Long) : SessionEvent

        /** Probe saw the site's logged-out surface. */
        data class ProbeAnonymous(val atMillis: Long) : SessionEvent

        /** Probe could not tell (blank page, intermediate redirect, changed markup). */
        data class ProbeInconclusive(val atMillis: Long) : SessionEvent

        /** Stored jar older than the configured TTL, or absent after a previous login. */
        data class SessionAgedOut(val atMillis: Long) : SessionEvent

        data class HttpFailure(val kind: ErrorKind, val status: Int) : SessionEvent

        data class TransportFailure(val kind: ErrorKind) : SessionEvent

        /** User logged this slot out deliberately. */
        data object LoggedOut : SessionEvent

        /** User switched to another slot; the leaving slot keeps its status. */
        data object SwitchedAway : SessionEvent

        /** HTTP 429, or Arena's own "slow down" copy observed in the page. */
        data object RateLimited : SessionEvent

        /** User acknowledged a rate limit / error banner. */
        data object Dismissed : SessionEvent
    }

    /** Configuration the machine needs but should not read from Android itself. */
    data class Config(
        val sessionTtlMillis: Long,
        val autoReprobeOnLoad: Boolean = true,
    )

    fun transition(
        current: AccountStatus,
        event: SessionEvent,
        hadStoredSession: Boolean,
        config: Config,
    ): Transition {
        val effects = linkedSetOf<Effect>()

        fun to(next: AccountStatus, reason: String, vararg extra: Effect) = Transition(
            status = next,
            effects = (effects + extra).toSet(),
            reason = reason,
        )

        return when (event) {
            SessionEvent.LoadStarted -> when {
                current == AccountStatus.CONNECTED ->
                    to(current, "already authenticated; load is cosmetic", Effect.NONE)

                else -> to(
                    AccountStatus.CONNECTING,
                    "load started, awaiting probe verdict",
                )
            }

            SessionEvent.LoadFinished -> when {
                config.autoReprobeOnLoad -> to(
                    current,
                    "page settled; probe decides the status",
                    Effect.REPROBE,
                )

                else -> to(current, "page settled; auto re-probe disabled")
            }

            is SessionEvent.ProbeAuthenticated -> to(
                AccountStatus.CONNECTED,
                "probe observed signed-in chrome",
                Effect.SNAPSHOT_JAR,
            )

            is SessionEvent.ProbeAnonymous -> {
                val wasAuthenticated = current == AccountStatus.CONNECTED ||
                    current == AccountStatus.RATE_LIMITED || hadStoredSession
                if (wasAuthenticated) {
                    effects.add(Effect.CLEAR_JAR)
                    effects.add(Effect.BLOCK_INTERACTION)
                    effects.add(Effect.PROMPT_RECONNECT)
                    to(
                        AccountStatus.SESSION_EXPIRED,
                        "stored session present but Arena rendered the logged-out surface",
                    )
                } else {
                    to(
                        AccountStatus.LOGIN_REQUIRED,
                        "logged-out surface, no prior session for this slot",
                        Effect.PROMPT_RECONNECT,
                    )
                }
            }

            is SessionEvent.ProbeInconclusive ->
                // Never downgrade on a guess, never upgrade on a guess.
                to(current, "probe inconclusive; status held at ${current.name}")

            is SessionEvent.SessionAgedOut -> {
                if (!hadStoredSession) {
                    to(AccountStatus.NOT_CONNECTED, "no stored session for this slot")
                } else {
                    effects.add(Effect.BLOCK_INTERACTION)
                    effects.add(Effect.PROMPT_RECONNECT)
                    effects.add(Effect.CLEAR_JAR)
                    to(
                        AccountStatus.SESSION_EXPIRED,
                        "stored jar exceeded the ${config.sessionTtlMillis / 3_600_000L} h local TTL",
                    )
                }
            }

            is SessionEvent.HttpFailure -> when (event.kind) {
                ErrorKind.UNAUTHORIZED, ErrorKind.FORBIDDEN -> {
                    effects.add(Effect.CLEAR_JAR)
                    effects.add(Effect.BLOCK_INTERACTION)
                    effects.add(Effect.PROMPT_RECONNECT)
                    to(
                        AccountStatus.SESSION_EXPIRED,
                        "HTTP ${event.status} from arena.ai for this slot",
                    )
                }

                ErrorKind.RATE_LIMITED -> to(
                    AccountStatus.RATE_LIMITED,
                    "HTTP 429 from arena.ai for this slot",
                    Effect.SHOW_RATE_LIMIT,
                    Effect.BLOCK_INTERACTION,
                )

                ErrorKind.TLS -> to(
                    AccountStatus.CONNECTION_ERROR,
                    "TLS handshake failed",
                    Effect.BLOCK_INTERACTION,
                )

                else -> to(
                    AccountStatus.CONNECTION_ERROR,
                    "HTTP ${event.status} from arena.ai",
                    Effect.BLOCK_INTERACTION,
                )
            }

            is SessionEvent.TransportFailure -> to(
                AccountStatus.CONNECTION_ERROR,
                "transport failure: ${event.kind.name}",
                Effect.BLOCK_INTERACTION,
            )

            is SessionEvent.RateLimited -> to(
                AccountStatus.RATE_LIMITED,
                "rate-limit notice observed in the page",
                Effect.SHOW_RATE_LIMIT,
                Effect.BLOCK_INTERACTION,
            )

            SessionEvent.LoggedOut -> to(
                AccountStatus.NOT_CONNECTED,
                "user initiated logout",
                Effect.CLEAR_JAR,
                Effect.RECREATE_VIEW,
                Effect.BLOCK_INTERACTION,
            )

            SessionEvent.SwitchedAway -> to(
                current,
                "slot not in focus; snapshotting and keeping status",
                Effect.SNAPSHOT_JAR,
            )

            SessionEvent.Dismissed -> when (current) {
                AccountStatus.RATE_LIMITED -> to(
                    if (hadStoredSession) AccountStatus.CONNECTED else AccountStatus.LOGIN_REQUIRED,
                    "rate-limit notice dismissed by user; re-probe to confirm",
                    Effect.REPROBE,
                )

                AccountStatus.CONNECTION_ERROR -> to(
                    if (hadStoredSession) AccountStatus.CONNECTED else AccountStatus.NOT_CONNECTED,
                    "error cleared; re-probe to confirm",
                    Effect.REPROBE,
                )

                else -> to(current, "nothing to dismiss")
            }
        }
    }

    /**
     * Status a slot shows immediately after the app starts, before any probe: never claim more
     * than "there is a stored jar we have not verified yet".
     */
    fun bootStatus(
        hadStoredSession: Boolean,
        snapshotAgeMillis: Long,
        config: Config,
    ): AccountStatus = when {
        // Nothing stored: the slot is empty and stays visibly empty.
        !hadStoredSession -> AccountStatus.NOT_CONNECTED
        // Stored but past the local TTL: the only claim we can make is that it must be reverified.
        snapshotAgeMillis > config.sessionTtlMillis -> AccountStatus.SESSION_EXPIRED
        // Stored and inside the TTL, yet unverified until a probe says otherwise. The UI renders
        // this as "Stored session — not verified yet" rather than as a live login.
        else -> AccountStatus.NOT_CONNECTED
    }

    fun describe(event: SessionEvent): String = when (event) {
        SessionEvent.LoadStarted -> "LoadStarted"
        SessionEvent.LoadFinished -> "LoadFinished"
        is SessionEvent.ProbeAuthenticated -> "ProbeAuthenticated(${event.label})"
        is SessionEvent.ProbeAnonymous -> "ProbeAnonymous"
        is SessionEvent.ProbeInconclusive -> "ProbeInconclusive"
        is SessionEvent.SessionAgedOut -> "SessionAgedOut"
        is SessionEvent.HttpFailure -> "HttpFailure(${event.kind}/${event.status})"
        is SessionEvent.TransportFailure -> "TransportFailure(${event.kind})"
        SessionEvent.LoggedOut -> "LoggedOut"
        SessionEvent.SwitchedAway -> "SwitchedAway"
        SessionEvent.RateLimited -> "RateLimited"
        SessionEvent.Dismissed -> "Dismissed"
    }
}
