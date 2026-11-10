package com.kartikkamboj.arena.data.prefs

import kotlinx.coroutines.flow.StateFlow

/**
 * Read side of the user's settings, as the domain needs it.
 *
 * The repositories take this instead of [SettingsRepository] so the policy they enforce can be
 * driven from a JVM test without an Android context.
 */
interface SettingsSource {
    val settings: StateFlow<AppSettings>

    fun update(transform: (AppSettings) -> AppSettings)
}
