package com.kartikkamboj.arena.data.model

import kotlinx.serialization.Serializable

/**
 * A cookie the app is allowed to carry between launches for one slot.
 *
 * Android's `CookieManager.getCookie()` only hands back `name=value` pairs — attributes are not
 * exposed — so the app reconstructs host-only cookies for [ORIGIN] on restore and records its own
 * capture timestamp. That is why expiry is governed by [expiresAtMillis] (app-side TTL) rather
 * than by the server's `Expires` attribute, which this integration cannot read.
 */
@Serializable
data class CookieEntry(
    val name: String,
    val value: String,
    val path: String = "/",
    val secure: Boolean = true,
    val expiresAtMillis: Long = 0L,
) {
    companion object {
        const val ORIGIN = "arena.ai"
    }

    fun isStale(now: Long): Boolean = expiresAtMillis in 1 until now
}

@Serializable
data class CookieJarSnapshot(
    val slot: Int,
    val capturedAtMillis: Long,
    val ttlMillis: Long,
    val entries: List<CookieEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    fun effectiveExpiry(now: Long): Long? {
        val fromCookies = entries.mapNotNull { it.expiresAtMillis.takeIf { e -> e > 0L } }.minOrNull()
        val fromTtl = capturedAtMillis + ttlMillis
        return listOfNotNull(fromCookies, fromTtl.takeIf { capturedAtMillis > 0L }).minOrNull()
    }

    fun isExpired(now: Long): Boolean = effectiveExpiry(now)?.let { it <= now } ?: false
}

/** Result of asking the loaded page "is somebody signed in?" — see web/AuthProbe. */
data class AuthProbeResult(
    val observedAtMillis: Long,
    val verdict: ProbeVerdict,
    val url: String,
    val accountLabel: String? = null,
    val seesLoginPrompt: Boolean = false,
    val seesRateLimitNotice: Boolean = false,
    val title: String = "",
)

enum class ProbeVerdict {
    /** The page exposed a signed-in affordance (avatar / account menu / user name). */
    AUTHENTICATED,

    /** The page exposed the site's own "Log In" affordance and nothing user-specific. */
    ANONYMOUS,

    /** Nothing recognisable: keep the previous status rather than guessing. */
    UNKNOWN,
}

/** Everything the UI needs to know about the live web session of the selected slot. */
data class SessionState(
    val slot: Int? = null,
    val loading: Boolean = false,
    val progress: Int = 0,
    val url: String = "",
    val title: String = "",
    val authenticated: Boolean = false,
    val lastProbe: AuthProbeResult? = null,
    val error: AppError? = null,
    val rateLimited: Boolean = false,
    val selectionText: String = "",
    val canGoBack: Boolean = false,
) {
    val canSend: Boolean get() = !loading && error == null && !rateLimited
}
