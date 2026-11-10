package com.kartikkamboj.arena.data.model

/**
 * User-facing failure vocabulary.
 *
 * Every value carries copy that a person can act on. Raw stack traces never reach the UI:
 * `AppError.fromThrowable` maps transport types onto these buckets and keeps the technical
 * detail in a single redacted line for the diagnostics card.
 */
enum class ErrorKind(
    val title: String,
    val userMessage: String,
    val actionLabel: String,
    val retryable: Boolean,
) {
    NO_NETWORK(
        title = "No connection",
        userMessage = "Your phone has no network right now, so nothing was sent to Arena.",
        actionLabel = "Retry when online",
        retryable = true,
    ),
    TIMEOUT(
        title = "Arena took too long",
        userMessage = "The request timed out. The prompt was not answered; your draft is still in the box.",
        actionLabel = "Try again",
        retryable = true,
    ),
    TLS(
        title = "Secure connection refused",
        userMessage = "The TLS handshake with arena.ai failed, so the page was not displayed. " +
            "Nothing was submitted over an insecure channel.",
        actionLabel = "Retry",
        retryable = true,
    ),
    SERVER(
        title = "Arena had a problem",
        userMessage = "arena.ai returned a server error. This one is on their side.",
        actionLabel = "Retry",
        retryable = true,
    ),
    UNAUTHORIZED(
        title = "Sign-in needed",
        userMessage = "Arena rejected this account's session. Reconnect the account to continue; " +
            "the conversation is preserved.",
        actionLabel = "Reconnect account",
        retryable = false,
    ),
    FORBIDDEN(
        title = "Access blocked",
        userMessage = "Arena refused this request for the selected account.",
        actionLabel = "Reconnect account",
        retryable = false,
    ),
    RATE_LIMITED(
        title = "Rate limit reached",
        userMessage = "Arena throttled the selected account. Wait, or switch account yourself in Accounts.",
        actionLabel = "Switch account",
        retryable = true,
    ),
    PARSE(
        title = "Unexpected response",
        userMessage = "The page did not contain what this client expected, so it was not interpreted. " +
            "Arena may have changed its interface.",
        actionLabel = "Open the page anyway",
        retryable = true,
    ),
    RENDER_CRASH(
        title = "Web view restarted",
        userMessage = "Android ended the web renderer process. A fresh one was created for this account.",
        actionLabel = "Reload",
        retryable = true,
    ),
    UNSUPPORTED_AUTH(
        title = "This sign-in method needs your browser",
        userMessage = "Identity providers block sign-in inside embedded app views. Arena's e-mail sign-in " +
            "works here; Google sign-in is handed to your browser instead — see docs/AUTHENTICATION.md.",
        actionLabel = "Open in browser",
        retryable = false,
    ),
    BLOCKED_ORIGIN(
        title = "Left the Arena origin",
        userMessage = "A link pointed away from arena.ai. Sessions are only loaded from arena.ai over HTTPS.",
        actionLabel = "Open externally",
        retryable = false,
    ),
    ;

    fun toError(detail: String? = null): AppError = AppError(this, detail)
}

data class AppError(
    val kind: ErrorKind,
    val detail: String? = null,
) {
    val title: String get() = kind.title
    val message: String get() = kind.userMessage
    val actionLabel: String get() = kind.actionLabel
    val retryable: Boolean get() = kind.retryable
}
