package com.kartikkamboj.arena.core

import android.util.Log
import com.kartikkamboj.arena.BuildConfig

/**
 * Single logging funnel for the app.
 *
 * Two rules are enforced here rather than at each call site:
 *
 *  1. Debug output only exists in debug builds, so a release APK cannot be coaxed into
 *     echoing session material through `adb logcat`.
 *  2. Every string passes through [redact] first, which masks cookie values, bearer
 *     tokens, e-mail addresses and anything that looks like a JWT before it reaches the
 *     log buffer. Diagnostics therefore stay useful without ever becoming a credential
 *     leak.
 */
object AppLog {

    private const val TAG = "ArenaAI"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, redact(message))
    }

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, redact(message))
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, redact(message) + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, redact(message) + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }

    /**
     * Diagnostic summary of a throwable that never contains a stack trace. Users see a mapped
     * message; the type and message alone are enough to triage, and both are redacted.
     */
    fun diagnose(error: Throwable): String =
        redact("${error.javaClass.simpleName}: ${error.message.orEmpty()}").take(160)

    private val patterns: List<Pair<Regex, String>> = listOf(
        // Set-Cookie / Cookie headers and anything cookie-shaped.
        Regex("""(?i)(set-cookie|cookie)\s*[:=]\s*[^\r\n]+""") to "$1=<redacted>",
        // Bearer / basic auth and long opaque tokens.
        Regex("""(?i)(bearer|basic|token|api[-_ ]?key|authorization|session|sid)\s*[:=]\s*[A-Za-z0-9\-._~+/]{6,}""") to "$1=<redacted>",
        // JWTs.
        Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}""") to "<jwt>",
        // E-mail addresses (identifiers, but they still do not belong in logs).
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "<email>",
        // Quoted values behind sensitive-looking keys, e.g. "password": "..."
        Regex("\"(pass(?:word)?|secret|code|otp|refresh|auth)\"\\s*:\\s*\"[^\"]*\"") to "\"$1\":\"<redacted>\"",
    )

    fun redact(input: String): String {
        var out = input
        patterns.forEach { (regex, replacement) ->
            out = regex.replace(out, replacement)
        }
        return out
    }
}
