package com.kartikkamboj.arena.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.data.model.ArenaMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * Everything the user can change. Stored in private SharedPreferences because no value here is
 * secret — a deliberate boundary: preferences are never used for tokens or cookies, and
 * [SESSION_KEYS] lists the names that must never appear in them.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val defaultMode: ArenaMode = ArenaMode.AUTO,
    val screenshotProtection: Boolean = true,
    val domStorageEnabled: Boolean = true,
    val isolateSiteStorageOnSwitch: Boolean = true,
    val autoReprobe: Boolean = true,
    val wipeJarOnLogout: Boolean = true,
    val sessionTtlHours: Int = DEFAULT_TTL_HOURS,
    val verboseDiagnostics: Boolean = false,
    val nativeComposerEnabled: Boolean = false,
) {
    val sessionTtlMillis: Long get() = sessionTtlHours * 3_600_000L

    companion object {
        const val DEFAULT_TTL_HOURS = 12
        const val MIN_TTL_HOURS = 1
        const val MAX_TTL_HOURS = 72
        val SESSION_KEYS = setOf("cookie_jar", "session_token", "access_token", "password")
    }
}

class SettingsRepository(context: Context) : SettingsSource {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value).sanitised()
        _settings.value = next
        write(next)
    }

    fun reset() {
        prefs.edit().clear().apply()
        _settings.value = AppSettings()
    }

    private fun AppSettings.sanitised(): AppSettings = copy(
        sessionTtlHours = sessionTtlHours.coerceIn(AppSettings.MIN_TTL_HOURS, AppSettings.MAX_TTL_HOURS),
    )

    private fun read(): AppSettings {
        rejectSessionShapedKeys()
        return AppSettings(
            themeMode = runCatching {
                ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
            }.getOrDefault(ThemeMode.DARK),
            defaultMode = runCatching {
                ArenaMode.valueOf(prefs.getString(KEY_MODE, ArenaMode.AUTO.name) ?: ArenaMode.AUTO.name)
            }.getOrDefault(ArenaMode.AUTO),
            screenshotProtection = prefs.getBoolean(KEY_SCREENSHOT, true),
            domStorageEnabled = prefs.getBoolean(KEY_DOM_STORAGE, true),
            isolateSiteStorageOnSwitch = prefs.getBoolean(KEY_ISOLATE_STORAGE, true),
            autoReprobe = prefs.getBoolean(KEY_AUTO_REPROBE, true),
            wipeJarOnLogout = prefs.getBoolean(KEY_WIPE_ON_LOGOUT, true),
            sessionTtlHours = prefs.getInt(KEY_TTL, AppSettings.DEFAULT_TTL_HOURS),
            verboseDiagnostics = prefs.getBoolean(KEY_VERBOSE, false),
            nativeComposerEnabled = prefs.getBoolean(KEY_NATIVE_COMPOSER, false),
        ).sanitised()
    }

    private fun write(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.themeMode.name)
            .putString(KEY_MODE, settings.defaultMode.name)
            .putBoolean(KEY_SCREENSHOT, settings.screenshotProtection)
            .putBoolean(KEY_DOM_STORAGE, settings.domStorageEnabled)
            .putBoolean(KEY_ISOLATE_STORAGE, settings.isolateSiteStorageOnSwitch)
            .putBoolean(KEY_AUTO_REPROBE, settings.autoReprobe)
            .putBoolean(KEY_WIPE_ON_LOGOUT, settings.wipeJarOnLogout)
            .putInt(KEY_TTL, settings.sessionTtlHours)
            .putBoolean(KEY_VERBOSE, settings.verboseDiagnostics)
            .putBoolean(KEY_NATIVE_COMPOSER, settings.nativeComposerEnabled)
            .apply()
    }

    /**
     * Guards the rule "no credentials in SharedPreferences": if a session-shaped key ever shows up
     * — from an old build or a hand-edged debug file — it is removed and the incident is logged
     * without its value.
     */
    private fun rejectSessionShapedKeys() {
        val offenders = prefs.all.keys.filter { key ->
            AppSettings.SESSION_KEYS.any { forbidden -> key.contains(forbidden, ignoreCase = true) }
        }
        if (offenders.isEmpty()) return
        AppLog.w("removing ${offenders.size} session-shaped keys from preferences")
        prefs.edit().apply { offenders.forEach { remove(it) } }.apply()
    }

    private companion object {
        const val NAME = "arena_ai_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_MODE = "default_mode"
        const val KEY_SCREENSHOT = "screenshot_protection"
        const val KEY_DOM_STORAGE = "dom_storage"
        const val KEY_ISOLATE_STORAGE = "isolate_site_storage"
        const val KEY_AUTO_REPROBE = "auto_reprobe"
        const val KEY_WIPE_ON_LOGOUT = "wipe_on_logout"
        const val KEY_TTL = "session_ttl_hours"
        const val KEY_VERBOSE = "verbose_diagnostics"
        const val KEY_NATIVE_COMPOSER = "native_composer"
    }
}
