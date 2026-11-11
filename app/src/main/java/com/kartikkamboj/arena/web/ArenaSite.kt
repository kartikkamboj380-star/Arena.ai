package com.kartikkamboj.arena.web

import java.net.URLDecoder

/**
 * The only site this app knows about.
 *
 * There is no API layer here on purpose. Arena publishes no public chat, model or auth endpoint for
 * third parties, so the app renders the real website and lets the website do everything: signing
 * in, model choice, generation, uploads, history, image tools. What follows is only the origin the
 * WebView is allowed to display plus the two helpers that need to understand a URL without
 * `android.net.Uri` (which is a stub under JVM unit tests, and these rules are worth asserting).
 */
object ArenaSite {

    const val SCHEME = "https"
    const val HOST = "arena.ai"
    const val HOST_WWW = "www.arena.ai"

    /** Entry point. After a sign-in or a successful load, this is where the view starts. */
    const val HOME = "https://arena.ai/"

    /** Origin used for cookie-policy calls; host-only, matching how the site sets its cookies. */
    const val COOKIE_URL = "https://arena.ai"

    /** Pages the app renders as itself — the product, not somebody else's site. */
    val TRUSTED_HOSTS = setOf(HOST, HOST_WWW)

    /**
     * Identity-provider pages that a sign-in started on arena.ai has to pass through. They are
     * allowed to *render* in this window so the flow can complete with the site's own cookies
     * intact; nothing else is privileged, no value is ever read out of them, and the user is free
     * to use the browser instead — see docs/AUTHENTICATION.md.
     *
     * This list is a navigation allowance, not an assertion that the provider accepts embedded
     * views — it may refuse, and the app then says so rather than pretending otherwise.
     */
    val AUTH_HOSTS = setOf(
        "accounts.google.com",
        "accounts.gmail.com",
        "oauth2.googleapis.com",
    )

    fun isTrusted(raw: String?): Boolean {
        val parsed = ParsedUrl.of(raw) ?: return false
        return parsed.isHttps && parsed.hostIn(TRUSTED_HOSTS)
    }

    fun isAuthPage(raw: String?): Boolean {
        val parsed = ParsedUrl.of(raw) ?: return false
        return parsed.isHttps && parsed.hostIn(AUTH_HOSTS)
    }

    /**
     * A host name safe to put in a sentence. Anything unparseable degrades to the scheme, so an
     * oversized or fake-looking URL can never masquerade as a domain in the UI.
     */
    fun displayHost(raw: String?): String? = ParsedUrl.of(raw)?.host

    /**
     * Suggests a filename for a download.
     *
     * `Content-Disposition` is honoured first (including the RFC 5987 `filename*` form), then the
     * last path segment, with a mime-derived extension added when the name has none. Nothing here
     * invents a name the site did not offer: if neither source has one, the caller gets `null` and
     * `DownloadManager` picks its own.
     */
    fun suggestedFileName(contentDisposition: String?, url: String?, mimeType: String?): String? {
        fromDisposition(contentDisposition)?.let { return it }
        val tail = ParsedUrl.of(url)?.path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val decoded = runCatching { URLDecoder.decode(tail, "UTF-8") }.getOrDefault(tail)
        val name = decoded.take(96)
        if (name.contains('.')) return name
        val extension = mimeType?.let { MimeTypeExtensions.of(it) } ?: "download"
        return "$name.$extension"
    }

    private fun fromDisposition(header: String?): String? {
        val value = header?.takeIf { it.isNotBlank() } ?: return null
        // filename*=UTF-8''name  — the charset-extended form wins when both are present.
        val extended = Regex("""filename\*\s*=\s*([^;']+?)''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(2)
        val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
        val raw = extended ?: plain ?: return null
        val found = if (extended != null) {
            // The extended form is percent-encoded by definition; a name that cannot be decoded is
            // used as written rather than dropped, so a download still gets a usable filename.
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        } else {
            raw
        }
            .trim()
            .trim('"')
        // A path in a filename is a server trying to escape the destination directory.
        val leaf = found.substringAfterLast('/').substringAfterLast('\\')
        return leaf.takeIf { it.isNotBlank() && it != "." && it != ".." }?.take(96)
    }
}

/**
 * Extension guesses for downloads. Deliberately a tiny table rather than `MimeTypeMap`: this needs
 * to be unit-testable off-device, and the names it can produce are bounded to a handful of formats
 * the site actually serves.
 */
object MimeTypeExtensions {

    private val table = mapOf(
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/svg+xml" to "svg",
        "text/plain" to "txt",
        "text/markdown" to "md",
        "text/html" to "html",
        "text/csv" to "csv",
        "application/json" to "json",
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/octet-stream" to "bin",
    )

    fun of(mimeType: String?): String? =
        table[mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }]
}
