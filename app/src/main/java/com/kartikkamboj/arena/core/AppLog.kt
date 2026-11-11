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

    /**
     * The rules, as raw strings so a `\s` in one of them means the regex class `\s` and not "a
     * literal backslash followed by s". That distinction mattered: three of these patterns were
     * inherited from an earlier version of this file with `\\s` inside a raw string, where they match
     * nothing, and the redaction they promised was decorative. `AppLogTest` now pins each shape.
     */
    private val patterns: List<Pair<Regex, String>> = listOf(
        // Set-Cookie / Cookie headers, and anything else cookie-shaped on one line.
        Regex("""(?:set-cookie|cookie)\s*[:=]\s*[^\r\n]+""", RegexOption.IGNORE_CASE)
            to "<cookie-redacted>",

        // "<scheme> <secret>" after an auth header, so the token itself goes rather than its label.
        Regex("""(?:proxy-)?authorization\s*[:=]\s*(?:bearer|basic|token)?\s*[A-Za-z0-9\-._~+/]{4,}""", RegexOption.IGNORE_CASE)
            to "<auth-redacted>",

        // Bearer / basic / long opaque tokens behind a named key.
        Regex("""(bearer|basic|token|api[-_ ]?key|authorization|session|sid)\s*[:=]\s*"?[A-Za-z0-9\-._~+/]{6,}""", RegexOption.IGNORE_CASE)
            to "$1=<redacted>",

        // Anything that looks like a JWT, wherever it appears.
        Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}""")
            to "<jwt>",

        // E-mail addresses: identifiers, and still not something to leave in a log buffer.
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
            to "<email>",

        // Quoted values behind any credential-shaped key, i.e. "refresh_token": "…".
        Regex(""""[A-Za-z_]*(?:pass(?:word)?|secret|code|otp|token|credential|session|sid|key|auth)[A-Za-z_]*"\s*:\s*"[^"]*"""", RegexOption.IGNORE_CASE)
            to "\"<key>\":\"<redacted>\"",

        // The same key shapes unquoted, for the lines the platform writes (a query string, a
        // header dump, an exception message).
        Regex("""[A-Za-z_]*(?:pass(?:word)?|secret|code|otp|token|credential|session|sid|apikey|api_key|auth)[A-Za-z_]*\s*[:=]\s*[A-Za-z0-9\-._~+/%]{4,}""", RegexOption.IGNORE_CASE)
            to "<key>=<redacted>",
    )

    fun redact(input: String): String {
        var out = input
        patterns.forEach { (regex, replacement) ->
            out = regex.replace(out, replacement)
        }
        return out
    }
}
