package com.kartikkamboj.arena.di

import android.content.Context
import com.kartikkamboj.arena.BuildConfig
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.core.Clock
import com.kartikkamboj.arena.core.NetworkMonitor
import com.kartikkamboj.arena.core.relativeAge
import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.prefs.SettingsSource
import com.kartikkamboj.arena.data.repo.AccountRepository
import com.kartikkamboj.arena.data.repo.HistoryRepository
import com.kartikkamboj.arena.data.repo.ModelRepository
import com.kartikkamboj.arena.data.repo.SessionIsolation
import com.kartikkamboj.arena.data.secure.BlobCipher
import com.kartikkamboj.arena.domain.AccountPolicy
import com.kartikkamboj.arena.web.RateLimitMonitor

enum class Tone { NEUTRAL, GOOD, WARN, BAD }

data class DiagnosticsLine(val label: String, val value: String, val tone: Tone = Tone.NEUTRAL)

data class DiagnosticsReport(val generatedAtMillis: Long, val lines: List<DiagnosticsLine>)

/**
 * Connection diagnostics, with the sensitivity rule applied at the type level: every value here is
 * either a device fact or a *count*. No cookie value, no header, no token and no e-mail address is
 * ever read into a report — the jar is summarised by entry count only.
 */
class Diagnostics(
    private val context: Context,
    private val accounts: AccountRepository,
    private val models: ModelRepository,
    private val history: HistoryRepository,
    private val isolation: SessionIsolation,
    private val settings: SettingsSource,
    private val network: NetworkMonitor,
    private val cipher: BlobCipher,
    private val clock: Clock,
    private val rateLimits: RateLimitMonitor,
) {

    suspend fun report(): DiagnosticsReport {
        val now = clock.nowMillis()
        val config = settings.settings.value
        val registry = accounts.registry.value
        val lines = buildList {
            add(DiagnosticsLine("App", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                    if (BuildConfig.DEBUG) "debug" else "release"))
            add(DiagnosticsLine("Package", BuildConfig.APPLICATION_ID))
            add(DiagnosticsLine("Service origin", "https://arena.ai · HTTPS only", Tone.GOOD))

            val capability = isolation.capability
            add(
                DiagnosticsLine(
                    "WebView package",
                    capability.webViewPackage ?: "unknown",
                    if (capability.webViewPackage == null) Tone.WARN else Tone.GOOD,
                ),
            )
            add(DiagnosticsLine("WebView version", capability.webViewVersion ?: "unknown"))
            add(
                DiagnosticsLine(
                    "Session isolation",
                    capability.summary,
                    if (capability.multiProfileSupported) Tone.GOOD else Tone.WARN,
                ),
            )
            add(
                DiagnosticsLine(
                    "Per-profile storage available",
                    if (capability.multiProfileSupported) "yes (MULTI_PROFILE)" else "no on this device",
                    if (capability.multiProfileSupported) Tone.GOOD else Tone.WARN,
                ),
            )
            add(
                DiagnosticsLine(
                    "Encryption key",
                    "${cipher.keyId} · ${if (cipher.hardwareBacked) "Android Keystore" else "in-memory fallback"}",
                    if (cipher.hardwareBacked) Tone.GOOD else Tone.WARN,
                ),
            )
            add(
                DiagnosticsLine(
                    "Network",
                    if (network.available.value) "connected" else "unavailable",
                    if (network.available.value) Tone.GOOD else Tone.BAD,
                ),
            )
            add(
                DiagnosticsLine(
                    "Selected slot",
                    registry.selectedSlot?.let { "Account $it" } ?: "none — the app will not load Arena",
                    if (registry.selectedSlot == null) Tone.WARN else Tone.GOOD,
                ),
            )

            accounts.accounts.forEach { account ->
                val jarNote = when {
                    account.cookieCount > 0 && account.isAuthenticated -> "${account.cookieCount} cookie(s) in use"
                    account.cookieCount > 0 -> "${account.cookieCount} stored, unverified"
                    else -> "no stored cookies"
                }
                add(
                    DiagnosticsLine(
                        "Slot ${account.slot} · ${account.shortName}",
                        "${account.status.name} · $jarNote · probe ${account.lastProbeAtMillis.let { if (it > 0) relativeAge(it, now) else "never" }}",
                        toneFor(account.status),
                    ),
                )
            }
            if (accounts.accounts.isEmpty()) {
                add(
                    DiagnosticsLine(
                        "Account slots",
                        "none created (up to ${AccountPolicy.MAX_ACCOUNTS})",
                        Tone.WARN,
                    ),
                )
            }

            val limited = rateLimits.activeSlots
            add(
                DiagnosticsLine(
                    "Rate-limited slots",
                    if (limited.isEmpty()) "none observed" else limited.joinToString(", ") { "Account $it" },
                    if (limited.isEmpty()) Tone.GOOD else Tone.WARN,
                ),
            )
            add(
                DiagnosticsLine(
                    "Automatic account rotation",
                    "disabled by design — a rate limit is never spread across other accounts",
                    Tone.GOOD,
                ),
            )
            add(DiagnosticsLine("Model entries", "${models.models.size} · last read ${relativeAge(models.catalog.value.lastRefreshAtMillis, now)}"))
            add(DiagnosticsLine("Pinned model", models.activeSelection()?.displayName ?: "none"))
            add(DiagnosticsLine("Local thread records", "${history.threads.value.size}"))
            add(
                DiagnosticsLine(
                    "Local session TTL",
                    "${config.sessionTtlHours} h (jars older than this are dropped, not reused)",
                ),
            )
            add(
                DiagnosticsLine(
                    "Site storage wiped on switch",
                    if (config.isolateSiteStorageOnSwitch) "yes" else "no — localStorage can leak between slots",
                    if (config.isolateSiteStorageOnSwitch) Tone.GOOD else Tone.WARN,
                ),
            )
            add(
                DiagnosticsLine(
                    "Screenshot protection",
                    if (config.screenshotProtection) "on" else "off",
                    if (config.screenshotProtection) Tone.GOOD else Tone.NEUTRAL,
                ),
            )
            add(
                DiagnosticsLine(
                    "Passwords held by this app",
                    "none — Arena's own page receives them, this client never renders a login form",
                    Tone.GOOD,
                ),
            )
            add(
                DiagnosticsLine(
                    "Analytics / crash SDKs",
                    "none bundled",
                    Tone.GOOD,
                ),
            )
        }
        AppLog.d("diagnostics built with ${lines.size} line(s)")
        return DiagnosticsReport(now, lines)
    }

    private fun toneFor(status: AccountStatus): Tone = when (status) {
        AccountStatus.CONNECTED -> Tone.GOOD
        AccountStatus.CONNECTION_ERROR, AccountStatus.SESSION_EXPIRED -> Tone.BAD
        AccountStatus.RATE_LIMITED, AccountStatus.LOGIN_REQUIRED -> Tone.WARN
        AccountStatus.NOT_CONNECTED, AccountStatus.CONNECTING -> Tone.NEUTRAL
    }
}
