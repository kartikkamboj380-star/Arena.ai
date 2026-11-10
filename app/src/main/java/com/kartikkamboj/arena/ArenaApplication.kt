package com.kartikkamboj.arena

import android.app.Application
import android.content.ComponentCallbacks2
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.di.AppContainer

/**
 * Process entry point. Owns the [AppContainer] for the life of the process, which is what lets the
 * live web session survive navigation and configuration changes.
 */
class ArenaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.bootstrap()
        AppLog.d("Arena AI ${BuildConfig.VERSION_NAME} starting (package ${packageName})")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // On a 2 GB phone the WebView cache is the first thing worth giving back.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            container.onLowMemory(level)
        }
    }

    override fun onTerminate() {
        // Only reached on emulators; real process death relies on the capture done at ON_STOP.
        runCatching { container.session.shutdown() }
        super.onTerminate()
    }
}
