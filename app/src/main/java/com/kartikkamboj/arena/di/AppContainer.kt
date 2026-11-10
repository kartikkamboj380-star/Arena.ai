package com.kartikkamboj.arena.di

import android.app.Application
import android.content.ComponentCallbacks2
import android.webkit.CookieManager
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.core.Clock
import com.kartikkamboj.arena.core.NetworkMonitor
import com.kartikkamboj.arena.core.SystemClock
import com.kartikkamboj.arena.data.prefs.SettingsRepository
import com.kartikkamboj.arena.data.repo.AccountRepository
import com.kartikkamboj.arena.data.repo.HistoryRepository
import com.kartikkamboj.arena.data.repo.ModelRepository
import com.kartikkamboj.arena.data.repo.ProjectRepository
import com.kartikkamboj.arena.data.repo.SessionIsolation
import com.kartikkamboj.arena.data.secure.BlobCipher
import com.kartikkamboj.arena.data.secure.EncryptedBlobStore
import com.kartikkamboj.arena.data.secure.KeystoreBlobCipher
import com.kartikkamboj.arena.data.secure.SoftwareGcmCipher
import com.kartikkamboj.arena.data.store.JsonConfig
import com.kartikkamboj.arena.data.store.JsonFileStore
import com.kartikkamboj.arena.web.RateLimitMonitor
import com.kartikkamboj.arena.web.SessionCoordinator
import com.kartikkamboj.arena.web.WebViewSessionIsolation
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency graph for the whole app.
 *
 * A DI framework would add a build-time dependency for ten objects, so this is a plain container
 * with an explicit construction order — the only non-obvious part is that the stores have to be
 * loaded before the session coordinator reads them.
 */
class AppContainer(private val application: Application) {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val clock: Clock = SystemClock

    private val json = JsonConfig.Default

    private val dataDir: File = File(application.noBackupFilesDir, "arena").apply { mkdirs() }

    private val secureDir: File = File(application.noBackupFilesDir, "secure").apply { mkdirs() }

    /**
     * Session material is encrypted with a key in the Android Keystore. If the keystore is
     * unusable (a corrupt keychain, an unusual OEM build) the app keeps working with a
     * process-lifetime key instead of crashing — which means sessions do not survive a restart on
     * that device, and the diagnostics screen says so rather than hiding it.
     */
    val cipher: BlobCipher = runCatching { KeystoreBlobCipher(application) }
        .getOrElse { failure ->
            AppLog.w("Android Keystore unavailable; using a process-lifetime key", failure)
            SoftwareGcmCipher(ephemeralKey())
        }

    private val blobStore = EncryptedBlobStore(secureDir, cipher)

    private val jsonStore = JsonFileStore(dataDir, json)

    val settings: SettingsRepository = SettingsRepository(application)

    val network: NetworkMonitor = NetworkMonitor(application)

    val isolation: SessionIsolation = WebViewSessionIsolation(
        context = application,
        blobStore = blobStore,
        clock = clock,
        settings = settings,
    )

    val accounts: AccountRepository = AccountRepository(
        store = jsonStore,
        isolation = isolation,
        settings = settings,
        clock = clock,
        scope = scope,
    )

    val history: HistoryRepository = HistoryRepository(store = jsonStore, clock = clock)

    val models: ModelRepository = ModelRepository(store = jsonStore, clock = clock)

    /**
     * The project workspace. It sits *above* accounts and history on purpose: a project is the thing
     * that survives an account switch, so it may not be owned by either of them.
     */
    val projects: ProjectRepository = ProjectRepository(
        store = jsonStore,
        clock = clock,
        history = history,
    )

    val rateLimits: RateLimitMonitor = RateLimitMonitor(clock)

    val session: SessionCoordinator by lazy {
        SessionCoordinator(
            application = application,
            accounts = accounts,
            models = models,
            history = history,
            isolation = isolation,
            settings = settings,
            network = network,
            clock = clock,
            rateLimits = rateLimits,
            projects = projects,
            scope = scope,
        )
    }

    val diagnostics: Diagnostics by lazy {
        Diagnostics(
            context = application,
            accounts = accounts,
            models = models,
            history = history,
            isolation = isolation,
            settings = settings,
            network = network,
            cipher = cipher,
            clock = clock,
            rateLimits = rateLimits,
        )
    }

    /** Called once from [Application.onCreate]; ordering matters (repos before session). */
    fun bootstrap() {
        network.start()
        scope.launch {
            history.load()
            models.load()
            accounts.load()
            accounts.markTtlExpiry()
            AppLog.d(
                "container ready: ${accounts.accounts.size} slot(s), ${projects.projects.size} project(s)",
            )
        }
        scope.launch {
            // One rule, watched in one place: whatever the accounts do, the active project is only
            // annotated. No project is created, switched or reset by an account change.
            accounts.selection.collect { slot -> projects.onAccountChanged(slot) }
        }
    }

    /**
     * Response to `onTrimMemory`. Two rules shape it: everything the app owns is written down before
     * anything is given back, and no branch of it may touch authentication state.
     *
     * At ordinary pressure there is nothing honest to release — the caches are small and the page in
     * view is the user's work — so the only action is a flush of the cookie store. At
     * `TRIM_MEMORY_COMPLETE` the WebView goes: it is by far the largest object in this process
     * (renderer plus paint hierarchy), it is disposable, and the session it carried was already
     * captured into the encrypted blob store, so the next attach rebuilds it and reloads the same page.
     */
    fun onLowMemory(level: Int) {
        AppLog.d("memory pressure (level $level): flushing the cookie store")
        runCatching { CookieManager.getInstance().flush() }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            session.releaseForMemoryPressure()
        }
    }

    /** Removes everything this app stored locally. Never touches Arena's own data. */
    suspend fun clearLocalData(includeSessions: Boolean): ClearReport {
        val threads = history.clearAll()
        models.clearCache()
        val blobs = if (includeSessions) isolation.wipeAll() else null
        if (includeSessions) {
            accounts.accounts.forEach { accounts.logout(it.slot) }
        }
        return ClearReport(
            threadsRemoved = threads,
            sessionDataWiped = blobs?.blobsRemoved ?: 0,
            profilesCleared = blobs?.slotsCleared ?: 0,
            siteStoragePurged = blobs?.siteStoragePurged ?: false,
        )
    }

    data class ClearReport(
        val threadsRemoved: Int,
        val sessionDataWiped: Int,
        val profilesCleared: Int,
        val siteStoragePurged: Boolean,
    )

    private fun ephemeralKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
}
