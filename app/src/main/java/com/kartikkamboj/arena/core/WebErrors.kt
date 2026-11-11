package com.kartikkamboj.arena.core

/**
 * What went wrong with a load, in terms the user can act on.
 *
 * The enum is deliberately separate from `android.webkit.WebViewClient`'s integer codes: the mapping
 * happens in the web layer, so these sentences can be unit-tested on the JVM without a framework on
 * the classpath. Nothing here guesses at Arena's own health — the app can only report what its own
 * network stack told it, and says so.
 */
enum class WebFailure {
    TIMEOUT,
    HOST_NOT_FOUND,
    CONNECT,
    IO,
    SSL,
    UNSAFE_RESOURCE,
    BAD_URL,
    REDIRECT_LOOP,
    PROXY_AUTH,
    AUTH,
    UNSUPPORTED_SCHEME,
    FILE,
    TOO_MANY_REQUESTS,
    UNKNOWN,
    ;

    /** Whether pressing the retry button has any chance of helping. */
    val retryable: Boolean
        get() = this != BAD_URL && this != UNSAFE_RESOURCE

    /**
     * One sentence, in the user's terms, always including what to do next. No invented diagnosis:
     * the reason and the recovery are the same facts the platform reported.
     */
    fun message(offline: Boolean): String = when {
        offline -> "There is no network right now, so the site cannot be reached. " +
            "Reconnect and pull this page again."

        else -> when (this) {
            TIMEOUT -> "The site took too long to answer. This usually clears on a second try."
            HOST_NOT_FOUND -> "arena.ai could not be resolved from this network — a DNS or captive-portal " +
                "problem rather than a problem with the app."
            CONNECT -> "The connection to the site was refused. A proxy, VPN or firewall is usually the " +
                "reason; try again once that changes."
            IO -> "The connection dropped part-way through loading. Try again."
            SSL -> "The secure connection could not be verified. Nothing was loaded over it; check the " +
                "device's date and any network that intercepts TLS."
            UNSAFE_RESOURCE -> "The page asked to load something this app refuses to load over an " +
                "insecure connection."
            BAD_URL -> "That address is not something the site can open."
            REDIRECT_LOOP -> "The site kept redirecting without settling on a page. Try again, or open it " +
                "in your browser if it continues."
            PROXY_AUTH -> "The network's proxy wants a sign-in this app cannot provide. Open the site in " +
                "your browser instead."
            AUTH -> "The server asked for a sign-in this app does not hold. Use the site's own form."
            UNSUPPORTED_SCHEME -> "This app only opens arena.ai pages; that address asks for something " +
                "else, so it was not loaded."
            FILE -> "Local files are not loaded by this app."
            TOO_MANY_REQUESTS -> "The network refused another request for the moment. Waiting a few " +
                "seconds and trying again is the only fix."
            UNKNOWN -> "The page could not be loaded. Nothing about your account changed."
        }
    }
}

/**
 * Turns a transport failure into a sentence. Kept separate from the web layer so the two rules that
 * matter — "no network beats every error code", and "the app never blames the site for something it
 * cannot see" — are asserted on the JVM.
 */
object WebErrors {

    fun describe(failure: WebFailure, detail: String?, offline: Boolean): String {
        val base = failure.message(offline)
        val extra = detail?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        // The platform's own description can carry a fragment of the URL; it is appended only when
        // it is short, so an error card never becomes a place to dump a cookie-bearing query string.
        return if (extra.length <= 80) "$base (${extra.take(80)})" else base
    }
}

/**
 * Maps the platform's load-error codes to [WebFailure].
 *
 * The numbers are `android.webkit.WebViewClient`'s documented constant values, repeated here on
 * purpose: unit tests run against a mockable framework jar whose constants are all zero, so code
 * that reads them through the framework would silently map everything to the same branch. Copying
 * published API values keeps the mapping real *and* testable, which is what the error card is worth.
 */
object WebFailures {

    const val ERROR_UNKNOWN = -1
    const val ERROR_HOST_LOOKUP = -2
    const val ERROR_UNSUPPORTED_AUTH_SCHEME = -3
    const val ERROR_AUTHENTICATION = -4
    const val ERROR_PROXY_AUTHENTICATION = -5
    const val ERROR_CONNECT = -6
    const val ERROR_IO = -7
    const val ERROR_TIMEOUT = -8
    const val ERROR_REDIRECT_LOOP = -9
    const val ERROR_UNSUPPORTED_SCHEME = -10
    const val ERROR_FAILED_SSL_HANDSHAKE = -11
    const val ERROR_BAD_URL = -12
    const val ERROR_FILE = -13
    const val ERROR_FILE_NOT_FOUND = -14
    const val ERROR_TOO_MANY_REQUESTS = -15
    const val ERROR_UNSAFE_RESOURCE = -16

    fun fromErrorCode(code: Int): WebFailure = when (code) {
        ERROR_TIMEOUT -> WebFailure.TIMEOUT
        ERROR_HOST_LOOKUP -> WebFailure.HOST_NOT_FOUND
        ERROR_CONNECT -> WebFailure.CONNECT
        ERROR_IO -> WebFailure.IO
        ERROR_FAILED_SSL_HANDSHAKE -> WebFailure.SSL
        ERROR_UNSAFE_RESOURCE -> WebFailure.UNSAFE_RESOURCE
        ERROR_BAD_URL -> WebFailure.BAD_URL
        ERROR_REDIRECT_LOOP -> WebFailure.REDIRECT_LOOP
        ERROR_PROXY_AUTHENTICATION -> WebFailure.PROXY_AUTH
        ERROR_AUTHENTICATION, ERROR_UNSUPPORTED_AUTH_SCHEME -> WebFailure.AUTH
        ERROR_UNSUPPORTED_SCHEME -> WebFailure.UNSUPPORTED_SCHEME
        ERROR_FILE, ERROR_FILE_NOT_FOUND -> WebFailure.FILE
        ERROR_TOO_MANY_REQUESTS -> WebFailure.TOO_MANY_REQUESTS
        else -> WebFailure.UNKNOWN
    }
}
