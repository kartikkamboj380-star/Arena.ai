package com.kartikkamboj.arena.web

import java.net.URI

/**
 * Minimal URL decomposition used by the navigation policy.
 *
 * This exists instead of [android.net.Uri] for one reason: `Uri` is a stub in unit tests, and the
 * origin rules below are exactly the kind of security logic that should be asserted on the JVM on
 * every CI run rather than only on a device.
 */
data class ParsedUrl(val scheme: String?, val host: String?, val path: String, val raw: String) {

    val hasArenaHost: Boolean get() = host != null && host in ArenaUrls.ARENA_HOSTS
    val hasIdentityHost: Boolean get() = host != null && host in ArenaUrls.IDENTITY_HOSTS
    val isHttps: Boolean get() = scheme == "https"

    companion object {
        fun of(raw: String?): ParsedUrl? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            return runCatching {
                val uri = URI(value)
                ParsedUrl(
                    scheme = uri.scheme?.lowercase(),
                    host = uri.host?.lowercase()?.removePrefix("."),
                    path = uri.rawPath.orEmpty(),
                    raw = value,
                )
            }.getOrNull()
        }
    }
}
