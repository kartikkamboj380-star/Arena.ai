package com.kartikkamboj.arena.data.model

import kotlinx.serialization.Serializable

/**
 * Lifecycle of one account slot, as *observed* by this client.
 *
 * [CONNECTED] is only ever set from a positive probe result (see
 * `com.kartikkamboj.arena.domain.SessionStateMachine`): the app never reports a connected
 * account because a cookie happens to exist, and never assumes a login succeeded because a
 * page finished loading.
 */
@Serializable
enum class AccountStatus {
    /** Slot exists but was never authenticated, or its jar was cleared. */
    NOT_CONNECTED,

    /** A load is in flight and no verdict has come back yet. */
    CONNECTING,

    /** The probe observed a signed-in Arena page for this slot's isolated jar. */
    CONNECTED,

    /** The page rendered Arena's logged-out surface. */
    LOGIN_REQUIRED,

    /** Previously authenticated, now rejected (401/403 or a lost session cookie). */
    SESSION_EXPIRED,

    /** The service throttled this account (HTTP 429 observed). */
    RATE_LIMITED,

    /** DNS/TLS/timeout/render-process failure — transport, not authentication. */
    CONNECTION_ERROR,
    ;

    val isTerminalIdle: Boolean
        get() = this == NOT_CONNECTED || this == LOGIN_REQUIRED ||
            this == SESSION_EXPIRED || this == CONNECTION_ERROR || this == RATE_LIMITED

    /** Whether the user may keep interacting with an already loaded page. */
    val allowsInteraction: Boolean
        get() = this != CONNECTION_ERROR && this != CONNECTING
}

/**
 * One of the five local identities the user can juggle. Nothing here is secret:
 * [observedLabel] and [emailHint] are labels read back from the user's own browser session and
 * are used purely to tell slots apart. Credentials themselves are never captured by the app —
 * the real arena.ai page handles them, inside this slot's own cookie jar.
 */
@Serializable
data class ArenaAccount(
    val slot: Int,
    val label: String,
    val observedLabel: String? = null,
    val emailHint: String? = null,
    val status: AccountStatus = AccountStatus.NOT_CONNECTED,
    val createdAtMillis: Long = 0L,
    val lastActiveAtMillis: Long = 0L,
    val lastProbeAtMillis: Long = 0L,
    val lastProbeOk: Boolean? = null,
    val cookieCount: Int = 0,
    val snapshotCreatedAtMillis: Long = 0L,
    val interactionCount: Int = 0,
    val note: String = "",
) {
    /** Stable key for the slot's encrypted cookie-jar file and its WebView data partition. */
    val jarKey: String get() = "arena_account_$slot"

    val hasStoredSession: Boolean get() = cookieCount > 0

    val isAuthenticated: Boolean get() = status == AccountStatus.CONNECTED

    /** Short human handle used in the chat header, e.g. "Account 2". */
    val shortName: String get() = label.ifBlank { "Account $slot" }

    fun withStatus(next: AccountStatus, now: Long): ArenaAccount = copy(
        status = next,
        lastActiveAtMillis = if (next == AccountStatus.CONNECTED) now else lastActiveAtMillis,
    )
}

/** Envelope persisted on disk; the version field lets future migrations refuse stale data. */
@Serializable
data class AccountRegistry(
    val version: Int = CURRENT_VERSION,
    val selectedSlot: Int? = null,
    val accounts: List<ArenaAccount> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
