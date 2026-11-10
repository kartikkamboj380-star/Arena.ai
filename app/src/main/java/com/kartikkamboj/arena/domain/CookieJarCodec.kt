package com.kartikkamboj.arena.domain

import com.kartikkamboj.arena.data.model.CookieEntry

/**
 * Serialises and audits the per-slot cookie jars that make multi-account use possible.
 *
 * This type is intentionally pure: it parses `name=value; other=value` header text, merges,
 * prunes and summarises. No Android, no CookieManager. That is what makes the isolation rules
 * unit-testable — which matters, because the whole point of the exercise is that two slots must
 * never observe each other's session material.
 */
object CookieJarCodec {

    const val MAX_ENTRIES = 64
    const val MAX_VALUE_LENGTH = 4096
    const val MAX_TOTAL_LENGTH = 32_000

    /** RFC 6265 cookie-name is a token; anything else is dropped rather than escaped. */
    private val NAME_PATTERN = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,64}$")

    /** Reject values that could break out of the Set-Cookie line we re-inject. */
    private val UNSAFE_VALUE = Regex("[\r\n;,\"'\\\\]")

    /**
     * Set-Cookie attribute names. `CookieManager.getCookie()` returns cookie pairs only, so seeing
     * one of these means the input was a raw header rather than the manager's output; storing
     * "Expires"/"Path" as if they were session material would be wrong, so they are skipped.
     */
    private val ATTRIBUTE_NAMES = setOf(
        "expires", "max-age", "domain", "path", "secure", "httponly", "samesite", "priority",
        "partitioned",
    )

    /** Cookie names that must never reach disk even encrypted. */
    private val FORBIDDEN_NAME_HINTS = listOf("password", "passwd", "secret", "otp", "pin")

    /** Cookie names that plausibly carry session identity, used only for status reporting. */
    private val SESSION_NAME_HINTS = listOf("session", "sid", "auth", "jwt", "token", "connect")

    fun parse(header: String?, ttlMillis: Long, now: Long): List<CookieEntry> {
        if (header.isNullOrBlank()) return emptyList()
        val out = LinkedHashMap<String, CookieEntry>()
        header.split(';').asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { pair ->
                val name = pair.substringBefore('=', "").trim()
                if (name.lowercase() in ATTRIBUTE_NAMES) return@forEach
                val value = pair.substringAfter('=', "").trim()
                val entry = build(name, value, ttlMillis, now) ?: return@forEach
                out[entry.key()] = entry
                if (out.size >= MAX_ENTRIES) return out.values.toList()
            }
        return out.values.toList()
    }

    fun build(name: String, rawValue: String, ttlMillis: Long, now: Long): CookieEntry? {
        if (!NAME_PATTERN.matches(name)) return null
        if (name.containsAny(FORBIDDEN_NAME_HINTS)) return null
        val value = rawValue.trim()
        if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) return null
        if (UNSAFE_VALUE.containsMatchIn(value)) return null
        return CookieEntry(
            name = name,
            value = value,
            expiresAtMillis = if (ttlMillis > 0L) now + ttlMillis else 0L,
        )
    }

    fun CookieEntry.key(): String = "$name@$path"

    /** Replace-by-name merge, so a refreshed jar never keeps a second copy of a rotated cookie. */
    fun merge(existing: List<CookieEntry>, incoming: List<CookieEntry>): List<CookieEntry> {
        val byKey = LinkedHashMap<String, CookieEntry>()
        existing.forEach { byKey[it.key()] = it }
        incoming.forEach { byKey[it.key()] = it }
        return byKey.values.take(MAX_ENTRIES).toList()
    }

    fun prune(entries: List<CookieEntry>, now: Long): List<CookieEntry> =
        entries.filter { !it.isStale(now) && !it.name.containsAny(FORBIDDEN_NAME_HINTS) }

    /** Valid `Set-Cookie` fragment used when a snapshot is injected back into the WebView. */
    fun toSetCookie(entry: CookieEntry): String = buildString {
        append(entry.name).append('=').append(entry.value)
        append("; Path=").append(entry.path.ifBlank { "/" })
        if (entry.secure) append("; Secure")
        append("; SameSite=Lax")
        // Never mint an Expires the server did not give us: without one the cookie stays
        // session-scoped inside the WebView, and this app owns re-injection.
    }

    /** Header form of a jar, used for the "cookies carried" line in diagnostics. */
    fun toCookieHeader(entries: List<CookieEntry>): String {
        val joined = entries.joinToString("; ") { "${it.name}=${it.value}" }
        return joined.take(MAX_TOTAL_LENGTH)
    }

    /**
     * Names and lengths only. This string is the only representation of a jar that is ever
     * written to a log or shown on the diagnostics screen.
     */
    fun summarise(entries: List<CookieEntry>): String =
        entries.joinToString(", ") { "${it.name}(${it.value.length})" }.ifBlank { "empty" }

    fun carriesSessionIdentity(entries: List<CookieEntry>): Boolean =
        entries.any { entry -> entry.name.lowercase().containsAny(SESSION_NAME_HINTS) }

    /** Two jars must never be interchangeable; used by the isolation test. */
    fun overlap(a: List<CookieEntry>, b: List<CookieEntry>): Set<String> =
        a.map { it.name }.toSet() intersect b.map { it.name }.toSet()

    private fun String.containsAny(needles: List<String>): Boolean =
        needles.any { contains(it, ignoreCase = true) }
}
