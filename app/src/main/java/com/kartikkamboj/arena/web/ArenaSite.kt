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

    /**
     * The site's registrable domain, and the one thing "ours" means: `arena.ai` itself and every
     * subdomain of it. An explicit list of hosts was the previous rule, and it is what stranded
     * sign-in — Arena's auth service is on `auth.arena.ai`.
     */
    const val SITE_DOMAIN = "arena.ai"

    /** Entry point. After a sign-in or a successful load, this is where the view starts. */
    const val HOME = "$SCHEME://$SITE_DOMAIN/"

    /** Origin used for cookie-policy calls; host-only, matching how the site sets its cookies. */
    const val COOKIE_URL = "$SCHEME://$SITE_DOMAIN"

    /**
     * The site's own registrable domain. Anything under it is the site, and it has to be: Arena serves
     * its authentication on a subdomain (`https://auth.arena.ai/` answers a live gateway of its own), so
     * an exact-host rule sends the sign-in page out to a browser tab on the very first hop — and a
     * session created in that tab cannot come back to this window. docs/AUTHENTICATION.md has the
     * whole chain, the two return paths that are closed, and what this trades away.
     *
     * The trade is stated plainly: this trusts whatever DNS for `arena.ai` says, which is Arena's
     * boundary to get right, not this app's. A browser behaves the same way about cookies for a
     * parent domain; refusing to would break sign-in, which is the worse failure.
     */
    /**
     * Domains the identity provider itself serves pages from. Allowed to render **only while the
     * window is already showing provider UI** — see [NavigationPolicy]'s `fromProviderPage` rule — so
     * a stray link to someone's Google Doc still leaves the app like any other external link.
     */
    val PROVIDER_DOMAINS = setOf("google.com", "googleapis.com", "gmail.com")

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

    /**
     * Part of the site, on any of its own subdomains. What the policy uses to decide "ours", and what
     * an incoming `ACTION_VIEW` link is checked against before it can move the window.
     */
    fun isSiteFamily(raw: String?): Boolean {
        val parsed = ParsedUrl.of(raw) ?: return false
        return parsed.isHttps && parsed.hostUnder(SITE_DOMAIN)
    }

    /** Google's published sign-in and OAuth endpoints, by exact host. */
    fun isAuthPage(raw: String?): Boolean {
        val parsed = ParsedUrl.of(raw) ?: return false
        return parsed.isHttps && parsed.hostIn(AUTH_HOSTS)
    }

    /**
     * Whether this URL is somewhere the provider serves its own UI. Wider than [isAuthPage], and used
     * for two different things: telling the user whose page they are looking at, and keeping a
     * redirect chain that wanders across the provider's domains inside this window instead of
     * breaking it.
     */
    fun isProviderPage(raw: String?): Boolean {
        val parsed = ParsedUrl.of(raw) ?: return false
        return parsed.isHttps && PROVIDER_DOMAINS.any { parsed.hostUnder(it) }
    }

    /**
     * A URL reduced to what may be written in a log: host and path, never the query. OAuth puts a
     * `code`, a `state` and an `id_token` in the query string, so a "helpful" full-URL log line would
     * put a live credential in logcat. [AppLog] redacts anyway; not writing it down is the better rule.
     */
    fun forLog(raw: String?): String {
        val parsed = ParsedUrl.of(raw) ?: ParsedUrl.lenient(raw) ?: return "an unparseable url"
        val path = parsed.path.takeIf { it.isNotBlank() && it != "/" } ?: ""
        return "${parsed.host ?: "?"}$path"
    }

    /**
     * A host name safe to put in a sentence. Anything unparseable degrades to the scheme, so an
     * oversized or fake-looking URL can never masquerade as a domain in the UI.
     */
    fun displayHost(raw: String?): String? = ParsedUrl.of(raw)?.host

    /**
     * The *names* of the query parameters on a provider URL, never their values.
     *
     * This exists because Google's failure page distinguishes causes that look identical from outside:
     * `Error 400: invalid_request`, `redirect_uri_mismatch`, `origin_mismatch`, `missing parameter`
     * — all of them are a 400 on `accounts.google.com`, and the only thing that tells them apart is
     * which OAuth parameters the authorization request carried. Printing the key list answers that
     * without writing a `code`, a `state`, a `nonce` or an `id_token` into logcat.
     *
     * Returns an empty string when there is no query, so a caller can append it unconditionally.
     */
    fun oauthParameterNames(raw: String?): String {
        val value = raw?.substringAfter('?', "").orEmpty().substringBefore('#')
        if (value.isEmpty()) return ""
        return value.split('&')
            .mapNotNull { pair ->
                val key = pair.substringBefore('=').trim()
                key.takeIf { it.isNotEmpty() && OAUTH_KEYS.contains(key.lowercase()) }
            }
            .distinct()
            .sorted()
            .joinToString(",")
    }

    /** The parameter names worth reporting. Anything else in a query is not this app's business. */
    private val OAUTH_KEYS = setOf(
        "client_id", "redirect_uri", "response_type", "response_mode", "scope", "state", "nonce",
        "prompt", "login_hint", "ux_mode", "origin", "include_granted_scopes", "access_type",
        "code_challenge", "code_challenge_method", "grant_type", "error", "error_description",
    )

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
