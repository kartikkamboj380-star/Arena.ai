package com.kartikkamboj.arena.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.core.Clock
import com.kartikkamboj.arena.data.model.CookieEntry
import com.kartikkamboj.arena.data.model.CookieJarSnapshot
import com.kartikkamboj.arena.data.prefs.SettingsSource
import com.kartikkamboj.arena.data.repo.IsolationCapability
import com.kartikkamboj.arena.data.repo.RestoreOutcome
import com.kartikkamboj.arena.data.repo.SessionIsolation
import com.kartikkamboj.arena.data.repo.WipeReport
import com.kartikkamboj.arena.data.secure.EncryptedBlobStore
import com.kartikkamboj.arena.domain.AccountPolicy
import com.kartikkamboj.arena.domain.CookieJarCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The one place that touches [CookieManager], and therefore the place where per-slot isolation is
 * actually implemented (see [SessionIsolation] for why the platform cannot do it spatially).
 *
 * Correctness hinges on a single invariant, asserted in `SessionIsolationTest`: the shared cookie
 * jar belongs to [activeSlot] and to nobody else. Capturing from a slot that is not active would
 * copy another account's session into the wrong blob, so every write path checks it first.
 */
class WebViewSessionIsolation(
    context: Context,
    private val blobStore: EncryptedBlobStore,
    private val clock: Clock,
    private val settings: SettingsSource,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SessionIsolation {

    private val appContext = context.applicationContext

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** Slot whose cookies currently live in the shared jar. Null when nothing is loaded. */
    @Volatile
    private var active: Int? = null

    override val activeSlot: Int? get() = active

    override fun bind(slot: Int) {
        active = slot
    }

    override fun unbind(slot: Int) {
        if (active == slot) active = null
    }

    override val capability: IsolationCapability by lazy { readCapability() }

    private val cookies: CookieManager get() = CookieManager.getInstance()

    override suspend fun capture(slot: Int): Int = withContext(main) {
        if (active != slot) {
            // The shared jar is not this slot's; keep whatever was already stored for it.
            return@withContext runCatching { readSnapshot(slot)?.entries?.size ?: 0 }.getOrDefault(0)
        }
        val entries = readLive()
        val ttl = settings.settings.value.sessionTtlMillis
        val snapshot = CookieJarSnapshot(
            slot = slot,
            capturedAtMillis = clock.nowMillis(),
            ttlMillis = ttl,
            entries = entries,
        )
        val written = blobStore.write(key(slot), json.encodeToString(CookieJarSnapshot.serializer(), snapshot))
        AppLog.d("slot $slot captured ${entries.size} cookie(s) [written=$written]")
        entries.size
    }

    override suspend fun refreshFromLive(slot: Int): Int = capture(slot)

    override suspend fun restore(slot: Int): RestoreOutcome = withContext(main) {
        val now = clock.nowMillis()
        val snapshot = runCatching { readSnapshot(slot) }.getOrElse { failure ->
            AppLog.w("snapshot unreadable for slot $slot", failure)
            return@withContext RestoreOutcome.Failed("snapshot-unreadable")
        }
        wipeLive(preserveActiveBinding = false)
        if (snapshot == null) {
            active = slot
            return@withContext RestoreOutcome.NothingStored
        }
        if (snapshot.isExpired(now)) {
            blobStore.delete(key(slot))
            AppLog.d("slot $slot snapshot past its ${snapshot.ttlMillis / 3_600_000L} h TTL; dropped")
            return@withContext RestoreOutcome.Expired(snapshot.capturedAtMillis, snapshot.ttlMillis)
        }
        val usable = CookieJarCodec.prune(snapshot.entries, now)
        if (usable.isEmpty()) {
            active = slot
            return@withContext RestoreOutcome.NothingStored
        }
        usable.forEach { entry ->
            runCatching { cookies.setCookie(ArenaUrls.COOKIE_URL, CookieJarCodec.toSetCookie(entry)) }
        }
        runCatching { cookies.flush() }
        // From this moment the shared jar is this slot's, which is what every later capture
        // relies on. restore() is the only place the binding moves forward.
        active = slot
        AppLog.d("slot $slot restored ${usable.size} cookie(s)")
        RestoreOutcome.Applied(usable.size, snapshot.effectiveExpiry(now))
    }

    override suspend fun release(slot: Int) {
        withContext(main) {
            blobStore.delete(key(slot))
            deleteProfileData(slot)
            if (active == slot) {
                wipeLive(preserveActiveBinding = false)
                unbind(slot)
            }
            AppLog.d("slot $slot released")
        }
    }

    override suspend fun storedEntries(slot: Int): List<CookieEntry> =
        runCatching { readSnapshot(slot)?.entries.orEmpty() }.getOrDefault(emptyList())

    override suspend fun wipeAll(): WipeReport = withContext(main) {
        val slots = (1..AccountPolicy.MAX_ACCOUNTS).toList()
        val removed = blobStore.deleteAll()
        slots.forEach { deleteProfileData(it) }
        val storagePurged = wipeLive(preserveActiveBinding = false)
        active = null
        WipeReport(slotsCleared = slots.size, blobsRemoved = removed, siteStoragePurged = storagePurged)
    }

    private fun readLive(): List<CookieEntry> {
        val header = runCatching { cookies.getCookie(ArenaUrls.COOKIE_URL) }.getOrNull()
        val ttl = settings.settings.value.sessionTtlMillis
        return CookieJarCodec.parse(header, ttl, clock.nowMillis())
    }

    private suspend fun readSnapshot(slot: Int): CookieJarSnapshot? {
        val text = blobStore.read(key(slot)) ?: return null
        return runCatching { json.decodeFromString(CookieJarSnapshot.serializer(), text) }.getOrNull()
    }

    /**
     * Empties the shared jar. Site storage (localStorage, IndexedDB, HTTP cache) is wiped with it
     * when [com.kartikkamboj.arena.data.prefs.AppSettings.isolateSiteStorageOnSwitch] is on,
     * because leaving it behind would let the next slot read the previous slot's client state.
     */
    private fun wipeLive(preserveActiveBinding: Boolean): Boolean {
        runCatching { cookies.removeAllCookies(null) }
        runCatching { cookies.removeSessionCookies(null) }
        runCatching { cookies.flush() }
        var purged = false
        if (settings.settings.value.isolateSiteStorageOnSwitch) {
            purged = runCatching { WebStorage.getInstance().deleteAllData(); true }.getOrDefault(false)
            activeProfile()?.let { profile ->
                runCatching { profile.getWebStorage().deleteAllData() }
            }
        }
        if (!preserveActiveBinding) active = null
        return purged
    }

    private fun key(slot: Int): String = "arena_account_$slot"

    // --- AndroidX multi-profile support, used only when the platform actually has it ---------

    private fun profileStoreOrNull(): ProfileStore? = runCatching {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return@runCatching null
        ProfileStore.getInstance()
    }.getOrNull()

    private fun activeProfile() = runCatching { profileStoreOrNull()?.getProfile(key(active ?: return@runCatching null)) }
        .getOrNull()

    private fun deleteProfileData(slot: Int) {
        val store = profileStoreOrNull() ?: return
        runCatching { store.deleteProfile(key(slot)) }
            .onSuccess { AppLog.d("profile data deleted for slot $slot") }
            // A live WebView on that profile makes deletion throw; the retry happens on next wipe.
            .onFailure { AppLog.w("profile delete deferred for slot $slot", it) }
    }

    private fun readCapability(): IsolationCapability {
        val multiProfile = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }.getOrDefault(false)
        val packageInfo = runCatching { WebViewCompat.getCurrentWebViewPackage(appContext) }.getOrNull()
        return IsolationCapability(
            multiProfileSupported = multiProfile,
            profileApiPresent = profileStoreOrNull() != null,
            webViewPackage = packageInfo?.packageName,
            webViewVersion = packageInfo?.versionName,
        )
    }
}
