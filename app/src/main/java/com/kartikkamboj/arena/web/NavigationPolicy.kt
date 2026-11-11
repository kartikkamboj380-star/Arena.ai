package com.kartikkamboj.arena.web

/**
 * Every main-frame navigation the WebView attempts is decided here, before anything renders.
 *
 * The rules implement the trust requirement rather than trusting the page:
 *  * anything the site serves on its own domain family — `arena.ai` and any `*.arena.ai` — is content
 *    the app shows as itself, which **includes the auth service** (`auth.arena.ai`);
 *  * Google's sign-in and OAuth endpoints may render in this window, so a redirect chain started on
 *    the site can complete with the site's own cookies intact;
 *  * while the window is *already* showing provider UI, the provider's own domains stay in this
 *    window too, because a chain broken in the middle is a failed sign-in rather than a refusal;
 *  * every other site leaves for the user's browser, where their own session and the platform's TLS
 *    handling apply, instead of being swallowed by a signed-in view;
 *  * `javascript:`, `data:` and `file:` never reach the WebView at all.
 *
 * The order of the last two bullets is the point of this class. Deciding each leg in isolation is what
 * strands an OAuth chain: the moment one hop is on a host the list did not anticipate, it opens in a
 * browser tab, and a session made there cannot be brought back into this window (see
 * docs/AUTHENTICATION.md). So the decision is *contextual* — [fromProviderPage] carries the one fact
 * about the previous page that changes the answer — while still never trusting an arbitrary host.
 *
 * No branch of this class inspects a page's DOM, fills a form, reads a cookie or rewrites a user
 * agent. Where a provider refuses an embedded view, that is reported through the load-error state
 * (`PagePhase.AUTH_REFUSED`), not worked around.
 */
object NavigationPolicy {

    sealed interface Decision {
        /** Render it in the app's WebView. */
        data class LoadInApp(val url: String) : Decision

        /** Leave the app: the user's browser (or another app) handles it. */
        data class HandOffToBrowser(val url: String, val reason: HandoffReason) : Decision

        /** Refuse, and say why. */
        data class Block(val reason: BlockReason, val url: String) : Decision
    }

    enum class HandoffReason {
        /** An ordinary https link to someone else's site. */
        EXTERNAL_LINK,

        /** `mailto:`/`tel:`/`sms:` — the system's own handlers, not a browser tab. */
        APP_HANDLER,

        /** Plain http: the app never carries a session over cleartext, the browser can decide. */
        INSECURE_SCHEME,
    }

    enum class BlockReason {
        MALFORMED_URL,
        JAVASCRIPT_SCHEME,
        DATA_SCHEME,
        FILE_SCHEME,
        ABOUT_BLANK_ONLY,
    }

    /** Schemes handled by an installed app rather than by a browser. */
    private val appHandlerSchemes = setOf("mailto", "tel", "sms", "smsto", "geo", "calendar", "whatsapp")

    /**
     * @param fromProviderPage whether the window is currently showing the provider's own UI, which is
     *   what allows the rest of that provider's domains to continue in this window.
     */
    fun classify(rawUrl: String?, fromProviderPage: Boolean = false): Decision {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return Decision.Block(BlockReason.MALFORMED_URL, url)

        // URI() is strict about characters a browser accepts (a space in a path, for instance), so a
        // parse failure falls back to a structural read instead of becoming a false refusal.
        val parsed = ParsedUrl.of(url) ?: ParsedUrl.lenient(url) ?: return Decision.Block(BlockReason.MALFORMED_URL, url)
        val scheme = parsed.scheme ?: return Decision.Block(BlockReason.MALFORMED_URL, url)

        return when {
            // about:blank is what a browser hands back before script writes into it; nothing else
            // under `about:` gets a signed-in view.
            scheme == "about" ->
                if (url.equals("about:blank", ignoreCase = true) || url.equals("about:srcdoc", ignoreCase = true)) {
                    Decision.LoadInApp(url)
                } else {
                    Decision.Block(BlockReason.ABOUT_BLANK_ONLY, url)
                }

            scheme == "javascript" -> Decision.Block(BlockReason.JAVASCRIPT_SCHEME, url)
            scheme == "data" -> Decision.Block(BlockReason.DATA_SCHEME, url)
            scheme == "file" -> Decision.Block(BlockReason.FILE_SCHEME, url)

            scheme == "http" ->
                // The site is HTTPS-only, so a cleartext request for it is a typo or a stripping
                // attempt: upgrade rather than downgrade. Anyone else's http is their browser's call.
                if (parsed.hostUnder(ArenaSite.SITE_DOMAIN)) {
                    Decision.LoadInApp(url.replaceFirst("http://", "https://", ignoreCase = true))
                } else {
                    Decision.HandOffToBrowser(url, HandoffReason.INSECURE_SCHEME)
                }

            scheme == "https" ->
                if (parsed.hostUnder(ArenaSite.SITE_DOMAIN) || parsed.hostIn(ArenaSite.AUTH_HOSTS) ||
                    (fromProviderPage && ArenaSite.isProviderPage(url))
                ) {
                    Decision.LoadInApp(url)
                } else {
                    Decision.HandOffToBrowser(url, HandoffReason.EXTERNAL_LINK)
                }

            scheme in appHandlerSchemes -> Decision.HandOffToBrowser(url, HandoffReason.APP_HANDLER)

            // intent:, market:, and anything unrecognised are not this app's business.
            else -> Decision.Block(BlockReason.MALFORMED_URL, url)
        }
    }

    /**
     * Resolves a page-supplied reference against the page it came from, then applies the same
     * rules. Used for `target="_blank"` targets, where the URL arrives as written in the document.
     */
    fun classifyReference(reference: String?, baseUrl: String?, fromProviderPage: Boolean = false): Decision {
        val ref = reference?.trim().orEmpty()
        if (ref.isEmpty()) return Decision.Block(BlockReason.MALFORMED_URL, ref)
        if (ref.startsWith("//")) return classify("https:$ref", fromProviderPage)
        if (ref.contains("://")) return classify(ref, fromProviderPage)
        val base = ParsedUrl.of(baseUrl) ?: return classify(ref, fromProviderPage)
        if (!base.hostUnder(ArenaSite.SITE_DOMAIN)) {
            // A relative link is only meaningful against a page we trust; anything else is treated
            // as the opaque string it is.
            return classify(ref)
        }
        val path = when {
            ref.startsWith("/") -> ref
            ref.startsWith("#") || ref.startsWith("?") -> base.path.trimEnd('/') + ref
            else -> {
                val parent = base.path.substringBeforeLast('/', "")
                "$parent/${ref.trimStart('.', '/')}"
            }
        }
        return classify("https://${base.host}$path", fromProviderPage)
    }

    /**
     * Whether a `window.open()` / `target="_blank"` request may become a page in this WebView. Only
     * arena.ai content qualifies; everything else is handed to the caller to open externally. One
     * WebView is a hard rule of this app, so a second window is never created.
     */
    fun allowsInAppWindow(rawUrl: String?, baseUrl: String? = null, fromProviderPage: Boolean = false): String? {
        val decision = if (baseUrl == null) {
            classify(rawUrl, fromProviderPage)
        } else {
            classifyReference(rawUrl, baseUrl, fromProviderPage)
        }
        return (decision as? Decision.LoadInApp)?.url
    }

    /**
     * What a decision was, in the form that goes to a log: never a URL with a query in it, because an
     * OAuth callback carries `code`/`state`/`id_token` there. Callers pass the host and path separately.
     */
    fun logSummary(decision: Decision): String = when (decision) {
        is Decision.LoadInApp -> "in-app"
        is Decision.HandOffToBrowser -> "hand-off(${decision.reason.name.lowercase()})"
        is Decision.Block -> "block(${decision.reason.name.lowercase()})"
    }

    /** Wording shown to the user when something leaves the app, so a hand-off is never a mystery. */
    fun handoffExplanation(reason: HandoffReason, host: String?): String = when (reason) {
        HandoffReason.EXTERNAL_LINK ->
            "${host ?: "That link"} is not arena.ai, so it opened in your browser."

        HandoffReason.INSECURE_SCHEME ->
            "${host ?: "That link"} is not an HTTPS page, so this app handed it to your browser " +
                "rather than loading it over an unencrypted connection."

        HandoffReason.APP_HANDLER ->
            "${host ?: "That link"} is an e-mail, phone or maps address, so the app that handles " +
                "it was asked to open it."
    }

    /** Wording shown when a navigation is refused outright. */
    fun blockExplanation(reason: BlockReason): String = when (reason) {
        BlockReason.MALFORMED_URL -> "That address could not be understood."
        BlockReason.JAVASCRIPT_SCHEME -> "The page asked to run script as a link. That is not allowed."
        BlockReason.DATA_SCHEME -> "Inline document data is not loaded by this app."
        BlockReason.FILE_SCHEME -> "Local files are not loaded by this app."
        BlockReason.ABOUT_BLANK_ONLY -> "Only a blank document from the page itself is accepted."
    }
}
