package com.kartikkamboj.arena.di

import android.content.Context
import com.kartikkamboj.arena.core.NetworkMonitor
import com.kartikkamboj.arena.web.BrowserEngine

/**
 * The whole object graph.
 *
 * It is a class with two fields and a factory function rather than a dependency-injection framework
 * because there is nothing else to wire: this app owns one WebView and one connectivity signal, and
 * the reason both live here instead of in the Activity is that they must outlive it — a rotation, a
 * dark-mode flip or a multi-window resize must not reload the site or drop the session.
 *
 * There is deliberately no database, no file store, no settings repository and no account model. The
 * site owns its own storage; the app keeps nothing about the user, which is also why turning backup
 * off in the manifest costs nothing to say out loud.
 */
class AppContainer(context: Context) {

    val network: NetworkMonitor = NetworkMonitor(context)

    val engine: BrowserEngine = BrowserEngine(context, network)

    /** Called once from [android.app.Application.onCreate]; nothing is loaded lazily behind a first frame. */
    fun bootstrap() {
        network.start()
    }
}
