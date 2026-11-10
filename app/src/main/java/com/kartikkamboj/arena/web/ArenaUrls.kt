package com.kartikkamboj.arena.web

/**
 * The complete set of Arena endpoints this client knows about.
 *
 * Every entry below was read off the public site (navigation links on https://arena.ai/ and
 * https://arena.ai/leaderboard). Nothing here is a guessed API: Arena publishes no chat, model or
 * auth endpoint for third parties, which is why this client drives the real web app in a WebView
 * instead of calling an invented JSON API. See docs/AUTHENTICATION.md.
 *
 * All helpers are pure string work (no [android.net.Uri]) so the origin rules can be unit tested.
 */
object ArenaUrls {

    const val SCHEME = "https"
    const val HOST = "arena.ai"
    const val HOST_WWW = "www.arena.ai"

    /** Origin used for CookieManager reads/writes; host-only, matching how the site sets cookies. */
    const val COOKIE_URL = "https://arena.ai"

    const val CHAT = "https://arena.ai/"
    const val AGENT = "https://arena.ai/agent"
    const val LEADERBOARD = "https://arena.ai/leaderboard"
    const val LEADERBOARD_TEXT = "https://arena.ai/leaderboard/text"
    const val LEADERBOARD_AGENT = "https://arena.ai/leaderboard/agent"
    const val HISTORY = "https://arena.ai/history"
    const val HISTORY_SEARCH = "https://arena.ai/history/search"
    const val TERMS = "https://arena.ai/terms-of-use"
    const val PRIVACY = "https://arena.ai/privacy-policy"

    val ARENA_HOSTS = setOf(HOST, HOST_WWW)

    /**
     * Hosts that belong to an identity provider rather than to Arena. Reaching one inside the app
     * is expected to fail (providers refuse embedded views), so the user is moved to the browser
     * instead of the app showing an error page — and instead of any attempt to defeat that check.
     */
    val IDENTITY_HOSTS = setOf(
        "accounts.google.com",
        "www.google.com",
        "oauth2.googleapis.com",
    )

    /** Paths that are site chrome rather than a conversation or ranking. */
    val NON_CONTENT_PREFIXES = listOf(
        "/leaderboard", "/blog", "/terms", "/privacy", "/agent", "/cookies",
        "/jobs", "/about", "/contact", "/careers", "/pricing", "/faq",
    )

    fun isArenaUrl(raw: String?): Boolean {
        val parsed = ParsedUrl.of(raw) ?: return false
        return parsed.isHttps && parsed.hasArenaHost
    }

    /**
     * Resolves a page-supplied href against [base]. Only same-site results are produced: an
     * off-origin absolute URL is returned unchanged so the caller can route it, while anything
     * with a non-http scheme is dropped.
     */
    fun absolute(href: String?, base: String = CHAT): String? {
        val value = href?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("javascript:", true) || value.startsWith("data:", true) ||
            value.startsWith("blob:", true) || value.startsWith("file:", true)
        ) {
            return null
        }
        if (value.startsWith("https://") || value.startsWith("http://")) {
            val parsed = ParsedUrl.of(value) ?: return null
            return if (parsed.hasArenaHost) value else null
        }
        if (value.startsWith("//")) {
            val parsed = ParsedUrl.of("https:$value") ?: return null
            return if (parsed.hasArenaHost) "https:$value" else null
        }
        if (value.startsWith("/")) return "$SCHEME://$HOST$value"
        if (value.startsWith("#") || value.startsWith("?")) return base.trimEnd('/') + value
        // "history/" or "?x=1" style relative links: anchor them at the site root.
        val parsedBase = ParsedUrl.of(base) ?: return null
        return if (parsedBase.hasArenaHost) "$SCHEME://$HOST/${value.trimStart('.', '/')}" else null
    }

    fun isContentPath(url: String?): Boolean {
        val parsed = ParsedUrl.of(url) ?: return false
        if (!parsed.isHttps || !parsed.hasArenaHost) return false
        val path = parsed.path
        if (path.isBlank() || path == "/") return false
        return NON_CONTENT_PREFIXES.none { path == it || path.startsWith("$it/") }
    }
}
