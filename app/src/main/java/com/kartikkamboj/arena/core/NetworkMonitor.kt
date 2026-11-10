package com.kartikkamboj.arena.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Best-effort "is there a network" signal used to short-circuit WebView loads and to explain
 * failures. It only reports OS level connectivity — it deliberately does not claim anything
 * about reachability of arena.ai, which is judged by the actual load callbacks.
 */
class NetworkMonitor(context: Context) {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _available = MutableStateFlow(manager.safeInitialCheck())

    val available: StateFlow<Boolean> = _available.asStateFlow()

    fun isCurrentlyAvailable(): Boolean = manager.safeInitialCheck()

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _available.value = true
        }

        override fun onLost(network: Network) {
            _available.value = false
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _available.value = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
            )
        }
    }

    fun start() {
        val manager = manager ?: return
        if (registered) return
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
            .onFailure { AppLog.w("NetworkMonitor: default callback unavailable", it) }
    }

    fun stop() {
        val manager = manager ?: return
        if (!registered) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun ConnectivityManager?.safeInitialCheck(): Boolean {
        val cm = this ?: return true
        return runCatching {
            val network = cm.activeNetwork ?: return@runCatching true
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrElse { true }
    }
}
