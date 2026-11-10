package com.kartikkamboj.arena.domain

import com.kartikkamboj.arena.data.model.ErrorKind
import java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Pure mapping layer: WebView / java.net failure shapes in, one [ErrorKind] out.
 *
 * Kept free of Android types so it is unit-testable, and deliberately conservative: an
 * unrecognised shape maps to [ErrorKind.SERVER] with the (redacted) technical detail preserved
 * for the diagnostics card, never guessed at.
 */
object ErrorMapper {

    /** WebView `onReceivedError` request-failed codes we can name, plus their platform constants. */
    const val ERROR_UNKNOWN = -1
    const val ERROR_HOST_LOOKUP = -2
    const val ERROR_CONNECT = -6
    const val ERROR_IO = -7
    const val ERROR_TIMEOUT = -8
    const val ERROR_FAILED_SSL_HANDSHAKE = -11
    const val ERROR_BAD_URL = -12
    const val ERROR_UNSAFE_URL = -30

    /** WebView error code to kind. Codes come from `WebViewClient` documentation. */
    fun fromWebViewErrorCode(code: Int, description: String?): ErrorKind = when (code) {
        ERROR_HOST_LOOKUP -> ErrorKind.NO_NETWORK
        ERROR_CONNECT, ERROR_IO -> {
            // A "timed out" description from the network stack is the only timeout signal WebView
            // exposes here, so sniff it instead of pretending to know more.
            if (description.orEmpty().contains("timeout", ignoreCase = true)) {
                ErrorKind.TIMEOUT
            } else {
                ErrorKind.SERVER
            }
        }

        ERROR_TIMEOUT -> ErrorKind.TIMEOUT
        ERROR_FAILED_SSL_HANDSHAKE -> ErrorKind.TLS
        ERROR_UNSAFE_URL -> ErrorKind.BLOCKED_ORIGIN
        ERROR_BAD_URL -> ErrorKind.PARSE
        else -> ErrorKind.SERVER
    }

    fun fromHttpStatus(status: Int): ErrorKind = when {
        status == HTTP_UNAUTHORIZED -> ErrorKind.UNAUTHORIZED
        status == HTTP_FORBIDDEN -> ErrorKind.FORBIDDEN
        status == RATE_LIMIT_STATUS -> ErrorKind.RATE_LIMITED
        status == HTTP_NOT_FOUND -> ErrorKind.PARSE
        status in 400..499 -> ErrorKind.PARSE
        else -> ErrorKind.SERVER
    }

    fun fromThrowable(error: Throwable): ErrorKind = when (error) {
        is SocketTimeoutException -> ErrorKind.TIMEOUT
        is UnknownHostException -> ErrorKind.NO_NETWORK
        is SSLException -> ErrorKind.TLS
        else -> when {
            error.message.orEmpty().contains("ENETUNREACH", ignoreCase = true) ||
                error.message.orEmpty().contains("No network", ignoreCase = true) -> ErrorKind.NO_NETWORK

            else -> ErrorKind.SERVER
        }
    }

    fun isAuthStatus(status: Int): Boolean =
        status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN

    fun isServerError(status: Int): Boolean = status >= HTTP_INTERNAL_ERROR &&
        status <= HTTP_GATEWAY_TIMEOUT

    /** Not in java.net.HttpURLConnection on all API levels, so it is spelled out. */
    const val RATE_LIMIT_STATUS = 429

    /**
     * Copy that is shown when a rate limit is hit. The reset time is intentionally optional:
     * a WebView cannot surface response headers, so the app never invents one. It is only rendered
     * when [retryAfterSeconds] is non-null, which today means "the service told us in page text".
     */
    fun rateLimitCopy(accountLabel: String, modelLabel: String?, retryAfterSeconds: Int?): String {
        val who = buildString {
            append(accountLabel)
            modelLabel?.let { append(" · ").append(it) }
        }
        val reset = retryAfterSeconds?.let { " Arena reported a retry window of about $it s." }
            ?: " Arena did not report a reset time to this client."
        return "$who is rate-limited.$reset"
    }
}
