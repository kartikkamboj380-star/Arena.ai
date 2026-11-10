package com.kartikkamboj.arena.web

import com.kartikkamboj.arena.core.Clock
import com.kartikkamboj.arena.data.model.ArenaAccount
import com.kartikkamboj.arena.domain.ErrorMapper

/**
 * Tracks throttling *per slot* and produces the copy the UI shows.
 *
 * Three rules, all of them tested:
 *  * a limit is recorded against the account that hit it — never against the app as a whole;
 *  * the only alternatives offered are accounts the user themselves connected, and choosing one is
 *    always a user action: this class exposes suggestions but the app never rotates automatically,
 *    because working around a rate limit by spreading it across five identities would be abuse of
 *    the service, not a feature;
 *  * no reset time is shown unless the service supplied one. A WebView cannot read response
 *    headers, so in practice that means "never", and the copy says exactly that.
 */
class RateLimitMonitor(private val clock: Clock) {

    enum class Source { NONE, HTTP_429, PAGE_NOTICE }

    data class Limit(
        val slot: Int,
        val sinceMillis: Long,
        val source: Source,
    )

    private val limits = mutableMapOf<Int, Limit>()

    val activeSlots: Set<Int> get() = limits.keys.toSet()

    fun observeHttpStatus(slot: Int, status: Int): Limit? {
        if (status != ErrorMapper.RATE_LIMIT_STATUS) return limits[slot]
        return touch(slot, Source.HTTP_429)
    }

    fun observePageNotice(slot: Int, noticePresent: Boolean): Limit? {
        if (!noticePresent) return limits[slot]
        return touch(slot, Source.PAGE_NOTICE)
    }

    private fun touch(slot: Int, source: Source): Limit {
        val existing = limits[slot]
        val limit = Limit(
            slot = slot,
            sinceMillis = existing?.sinceMillis ?: clock.nowMillis(),
            // An HTTP 429 outranks a text guess and is never downgraded by a later notice.
            source = if (existing?.source == Source.HTTP_429) Source.HTTP_429 else source,
        )
        limits[slot] = limit
        return limit
    }

    fun current(slot: Int): Limit? = limits[slot]

    fun isLimited(slot: Int): Boolean = limits.containsKey(slot)

    /** Clear the record for a slot: user dismissed the banner, logged out, or recovered. */
    fun clear(slot: Int) {
        limits.remove(slot)
    }

    fun clearAll() = limits.clear()

    /**
     * Copy for the banner. Deliberately names the account and the model the user selected, and
     * states plainly that no reset window was exposed to this client.
     */
    fun messageFor(
        account: ArenaAccount?,
        modelLabel: String?,
        quotaClaims: Boolean = false,
    ): String {
        val accountLabel = account?.shortName ?: "This account"
        val base = ErrorMapper.rateLimitCopy(accountLabel, modelLabel, retryAfterSeconds = null)
        return if (quotaClaims) {
            base + " Five profiles do not mean five times the quota."
        } else {
            base + " Waiting, or using another of your own accounts, are the only legitimate options."
        }
    }

    /**
     * Verified, *other* slots the user could switch to by hand. Empty means "wait", and the UI must
     * not offer a switch that does not exist.
     */
    fun alternatives(accounts: List<ArenaAccount>, currentSlot: Int?): List<ArenaAccount> =
        accounts.filter { it.slot != currentSlot && it.isAuthenticated && !isLimited(it.slot) }

    /** The app never auto-rotates; the flag exists so the invariant is explicit and testable. */
    val automaticallyRotatesAccounts: Boolean get() = false
}
