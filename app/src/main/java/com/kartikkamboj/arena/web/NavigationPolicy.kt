package com.kartikkamboj.arena.web

/**
 * Every navigation the WebView attempts passes through here first.
 *
 * The rules encode the security requirements rather than trusting the page:
 *  * only `https://arena.ai` documents are ever rendered inside the app;
 *  * a navigation that lands on an identity provider is *not* followed in-app — providers refuse
 *    embedded views, so the user is moved to the browser instead of the app showing a dead page
 *    (and instead of any attempt to defeat that check);
 *  * everything else leaves for the browser, and `javascript:`/`data:`/`file:` never reach the
 *    WebView at all.
 */
object NavigationPolicy {

    sealed interface Decision {
        /** Render it in the slot's session. */
        data class LoadInSession(val url: String) : Decision

        /** Leave the app: your browser handles sign-in for providers that block embedded views. */
        data class HandOffToBrowser(val url: String, val reason: HandoffReason) : Decision

        /** Refuse and explain. */
        data class Block(val reason: BlockReason, val url: String) : Decision
    }

    enum class HandoffReason { IDENTITY_PROVIDER, EXTERNAL_LINK }

    enum class BlockReason {
        NON_HTTPS_SCHEME,
        MALFORMED_URL,
        OFF_ORIGIN,
        ;
    }

    /**
     * [allowIdentityHosts] is set only while the user is actively signing a slot in: a session
     * established in the system browser belongs to the browser's cookie jar and can never be moved
     * into this slot, so handing the provider off mid-sign-in is what left users staring at the
     * logged-out homepage. Ordinary navigation still leaves the arena origin for the browser.
     */
    fun classify(rawUrl: String?, allowIdentityHosts: Boolean = false): Decision {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return Decision.Block(BlockReason.MALFORMED_URL, url)

        val parsed = ParsedUrl.of(url) ?: return Decision.Block(BlockReason.MALFORMED_URL, url)
        val scheme = parsed.scheme

        // about:blank is what window.open() hands back before script writes into it.
        if (scheme == "about") return Decision.LoadInSession(url)

        if (scheme == null) {
            // Scheme-less hrefs only ever come from an arena.ai document; resolve and re-check.
            val resolved = ArenaUrls.absolute(url)
                ?: return Decision.Block(BlockReason.MALFORMED_URL, url)
            return classify(resolved)
        }

        if (scheme == "http") {
            // Upgrade rather than downgrade: arena.ai is HTTPS-only, so plain http is either a typo
            // or a stripping attempt, and a session page must never be loaded over cleartext.
            return if (parsed.hasArenaHost) {
                Decision.LoadInSession("https://" + url.substring("http://".length))
            } else {
                Decision.Block(BlockReason.NON_HTTPS_SCHEME, url)
            }
        }

        if (scheme != "https") {
            return Decision.Block(BlockReason.NON_HTTPS_SCHEME, url)
        }

        return when {
            parsed.hasArenaHost -> Decision.LoadInSession(url)
            parsed.hasIdentityHost -> if (allowIdentityHosts) {
                Decision.LoadInSession(url)
            } else {
                Decision.HandOffToBrowser(url, HandoffReason.IDENTITY_PROVIDER)
            }
            // An off-site https link is not dangerous, it is just not ours: the user's browser has
            // its own isolated cookie jar and platform TLS handling. Blocking a plain link the user
            // tapped would be the worse failure, so it leaves the app instead of hijacking the
            // signed-in view. [BlockReason.OFF_ORIGIN] stays for non-http(s) schemes above.
            else -> Decision.HandOffToBrowser(url, HandoffReason.EXTERNAL_LINK)
        }
    }

    /**
     * Whether a `target="_blank"` / `window.open()` request may become a new in-app page. Only
     * arena.ai documents qualify; anything else is opened by the caller in the user's browser.
     */
    fun allowsNewInAppWindow(rawUrl: String?): Boolean =
        classify(rawUrl) is Decision.LoadInSession

    /** Text shown when a sign-in hand-off happens, so the reason is never a mystery. */
    fun handoffExplanation(reason: HandoffReason): String = when (reason) {
        HandoffReason.IDENTITY_PROVIDER ->
            "Your identity provider does not allow sign-in inside another app, so this opened in " +
                "your browser. Finish it there, then sign in inside Arena AI with e-mail to give " +
                "this account slot its own session."

        HandoffReason.EXTERNAL_LINK -> "This link leaves arena.ai, so it opened in your browser."
    }
}
