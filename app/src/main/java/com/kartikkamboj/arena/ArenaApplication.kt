package com.kartikkamboj.arena

import android.app.Application
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.di.AppContainer

/**
 * Process entry point, and the reason the web session survives configuration changes: it owns the
 * [AppContainer], which owns the one WebView.
 */
class ArenaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.bootstrap()
        AppLog.d("Arena ${BuildConfig.VERSION_NAME} starting (${packageName})")
    }

    /**
     * On a 3 GB phone this is the system asking for memory back before it kills the process. The level
     * is passed through untouched and [BrowserEngine.onLowMemory] decides what it means, because since
     * API 34 only some of these levels are still delivered — deciding the threshold here would encode a
     * distinction the platform no longer makes. Session material is never touched: the site's own
     * cookies and storage are what make a recovery a re-render rather than a sign-in.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.engine.onLowMemory(level)
    }

    override fun onTerminate() {
        // Reached on emulators only; real process death is handled by flushing cookies on ON_STOP.
        runCatching { container.engine.shutdown() }
        super.onTerminate()
    }
}
