package com.kartikkamboj.arena.web

import java.net.URI

/**
 * Minimal URL decomposition, used instead of [android.net.Uri] for one reason: `Uri` is a stub in
 * JVM unit tests, and the navigation rules in [NavigationPolicy] are exactly the kind of security
 * logic that should be asserted on every CI run rather than only on a device.
 */
data class ParsedUrl(val scheme: String?, val host: String?, val path: String, val raw: String) {

    val isHttps: Boolean get() = scheme == "https"

    /** Membership test that keeps a null host from ever matching, instead of pretending it is "". */
    fun hostIn(hosts: Set<String>): Boolean = host != null && host in hosts

    companion object {
        /**
         * Structural fallback for addresses a browser would accept but [URI] rejects — a space in a
         * path, an unescaped bracket. Only the scheme and authority are read, which is all the
         * navigation rules need; a URL that cannot be read this way is refused, not guessed.
         */
        fun lenient(raw: String?): ParsedUrl? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            val schemeEnd = value.indexOf(':')
            if (schemeEnd <= 0) return null
            val scheme = value.take(schemeEnd).lowercase()
            if (scheme.any { !it.isLetterOrDigit() && it != '+' && it != '-' && it != '.' }) return null
            val rest = value.substring(schemeEnd + 1)
            val body = rest.removePrefix("//")
            val authorityEnd = body.indexOfAny(charArrayOf('/', '?', '#')).takeIf { it >= 0 } ?: body.length
            val authority = body.take(authorityEnd)
            val host = authority.substringAfterLast('@').substringBefore(':').lowercase().trimEnd('.').takeIf { it.isNotEmpty() }
            val path = body.substring(authorityEnd).substringBefore('#').substringBefore('?')
            return ParsedUrl(scheme = scheme, host = host, path = path, raw = value)
        }

        fun of(raw: String?): ParsedUrl? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            return runCatching {
                val uri = URI(value)
                ParsedUrl(
                    scheme = uri.scheme?.lowercase(),
                    host = uri.host?.lowercase()?.removeSuffix(".")?.takeIf { it.isNotEmpty() },
                    path = uri.rawPath.orEmpty(),
                    raw = value,
                )
            }.getOrNull()
        }
    }
}
