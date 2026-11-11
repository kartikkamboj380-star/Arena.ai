package com.kartikkamboj.arena

import android.app.Application
import android.content.ComponentCallbacks2
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
     * On a 3 GB phone this is the system asking for memory back before it kills the process. The
     * response is graduated in [BrowserEngine.onLowMemory]: caches first, the view only at the last
     * level. Session material is never touched, because the site's own cookies and storage are what
     * make a recovery a re-render rather than a sign-in.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            container.engine.onLowMemory(level)
        }
    }

    override fun onTerminate() {
        // Reached on emulators only; real process death is handled by flushing cookies on ON_STOP.
        runCatching { container.engine.shutdown() }
        super.onTerminate()
    }
}
