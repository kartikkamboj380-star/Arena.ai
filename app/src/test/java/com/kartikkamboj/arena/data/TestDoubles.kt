package com.kartikkamboj.arena.data

import com.kartikkamboj.arena.core.Clock
import com.kartikkamboj.arena.core.FakeClock
import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.model.ArenaAccount
import com.kartikkamboj.arena.data.model.CookieEntry
import com.kartikkamboj.arena.data.prefs.AppSettings
import com.kartikkamboj.arena.data.prefs.SettingsSource
import com.kartikkamboj.arena.data.repo.IsolationCapability
import com.kartikkamboj.arena.data.repo.RestoreOutcome
import com.kartikkamboj.arena.data.repo.SessionIsolation
import com.kartikkamboj.arena.data.repo.WipeReport
import com.kartikkamboj.arena.data.store.JsonConfig
import com.kartikkamboj.arena.data.store.JsonFileStore
import com.kartikkamboj.arena.domain.CookieJarCodec
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test doubles for the persistence layer.
 *
 * [FakeIsolation] is the important one: it *enforces* the invariant the production code relies on
 * (the shared jar belongs to exactly one slot, and only that slot may capture into it), which is
 * what makes "cookies are never merged across accounts" a test that can actually fail rather than
 * a comment that says so.
 */

/** A real JSON store rooted in a temp directory, so persistence and reload are genuinely covered. */
internal fun testJsonStore(directory: File): JsonFileStore = JsonFileStore(directory, JsonConfig.Default)

/** Settings backed by an in-memory flow, matching the [SettingsSource] the repositories read. */
internal class FakeSettings(initial: AppSettings = AppSettings()) : SettingsSource {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) {
        _settings.value = transform(_settings.value)
    }
}

internal class FakeIsolation(
    private val clock: Clock = FakeClock(),
) : SessionIsolation {

    /** The live shared jar: name to value, like [android.webkit.CookieManager] before it is parsed. */
    val live = LinkedHashMap<String, String>()

    /** What each slot has captured. */
    val stored = LinkedHashMap<Int, MutableList<CookieEntry>>()

    private var active: Int? = null

    var wipes: Int = 0
        private set

    override val capability: IsolationCapability = IsolationCapability(
        multiProfileSupported = false,
        profileApiPresent = false,
        webViewPackage = "fake",
        webViewVersion = "0",
    )

    override val activeSlot: Int? get() = active

    override fun bind(slot: Int) {
        active = slot
    }

    override fun unbind(slot: Int) {
        if (active == slot) active = null
    }

    override suspend fun capture(slot: Int): Int {
        check(active == slot) {
            "capture($slot) while the live jar belongs to $active — jars would be crossed"
        }
        val entries = live.mapNotNull { (name, value) ->
            CookieJarCodec.build(name, value, ttl, clock.nowMillis())
        }
        stored[slot] = entries.toMutableList()
        return entries.size
    }

    override suspend fun restore(slot: Int): RestoreOutcome {
        // Whatever happens, this slot is about to own the shared jar, and the previous owner's
        // cookies are gone first — that ordering is the whole isolation story.
        live.clear()
        active = slot
        val entries = stored[slot] ?: return RestoreOutcome.NothingStored
        if (entries.isEmpty()) return RestoreOutcome.NothingStored
        entries.forEach { live[it.name] = it.value }
        return RestoreOutcome.Applied(entries.size, clock.nowMillis() + ttl)
    }

    override suspend fun release(slot: Int) {
        stored.remove(slot)
        if (active == slot) live.clear()
        if (stored.isEmpty()) wipes++
    }

    override suspend fun storedEntries(slot: Int): List<CookieEntry> = stored[slot]?.toList().orEmpty()

    override suspend fun refreshFromLive(slot: Int): Int {
        if (active == slot) capture(slot)
        return stored[slot]?.size ?: 0
    }

    override suspend fun wipeAll(): WipeReport {
        val removed = stored.size
        stored.clear()
        live.clear()
        active = null
        wipes++
        return WipeReport(slotsCleared = removed, blobsRemoved = removed, siteStoragePurged = true)
    }

    private val ttl: Long get() = 12L * 3_600_000L
}

/** Convenience for building accounts in policy tests. */
internal fun account(
    slot: Int,
    status: AccountStatus = AccountStatus.NOT_CONNECTED,
    cookieCount: Int = 0,
    label: String = "Account $slot",
): ArenaAccount = ArenaAccount(
    slot = slot,
    label = label,
    status = status,
    cookieCount = cookieCount,
    snapshotCreatedAtMillis = if (cookieCount > 0) 1L else 0L,
)
