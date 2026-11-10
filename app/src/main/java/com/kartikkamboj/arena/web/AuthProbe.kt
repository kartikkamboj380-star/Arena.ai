package com.kartikkamboj.arena.web

import com.kartikkamboj.arena.data.model.AuthProbeResult
import com.kartikkamboj.arena.data.model.ProbeVerdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How this client decides whether a slot is signed in.
 *
 * There is no Arena API to ask, and scraping a private endpoint would be both undocumented and
 * the wrong way to answer the question. So the app asks the page it already loaded — one
 * read-only expression, evaluated through [android.webkit.WebView.evaluateJavascript], which
 * returns a value to the app without exposing any app object to the page. There is no
 * `addJavascriptInterface` anywhere in this codebase.
 *
 * The verdict is deliberately conservative: [ProbeVerdict.UNKNOWN] keeps the previous status
 * instead of guessing, and only a positive observation of signed-in chrome yields
 * [ProbeVerdict.AUTHENTICATED].
 */
object AuthProbe {

    /**
     * Read-only probe. It never touches form fields, never reads input values, and never returns
     * page text — only the booleans and the short account label needed to classify the session.
     */
    const val SCRIPT: String = """
(function () {
  try {
    var body = document.body;
    var text = (body && body.innerText) ? body.innerText : '';
    var lower = text.toLowerCase();
    var loginPrompt = /(^|\n|\s)(log in|login|sign in|get started)(\s|$|!|:|\.)/.test(lower);
    var userChrome = !!document.querySelector(
      '[data-testid*="user" i], [class*="avatar" i], [aria-label*="account" i]');
    var label = '';
    var labelNode = document.querySelector(
      '[data-testid*="user-name"], [class*="user-email" i], [class*="account-name" i]');
    if (labelNode) { label = (labelNode.textContent || '').trim().slice(0, 80); }
    var rateLimited = /(rate limit|too many requests|slow down|try again in)/.test(lower);
    var expired = /(session expired|sign back in|log in again|please log in)/.test(lower);
    return JSON.stringify({
      ok: true,
      url: location.href,
      title: (document.title || '').slice(0, 120),
      loginPrompt: loginPrompt,
      userChrome: userChrome,
      label: label,
      rateLimited: rateLimited,
      sessionExpired: expired,
      textLength: text.length
    });
  } catch (ignored) {
    return JSON.stringify({ ok: false, error: 'probe-failed' });
  }
})();
"""

    /** Copies the current text selection so the user can paste a real answer elsewhere. */
    const val SELECTION_SCRIPT: String = """
(function () {
  try {
    var sel = window.getSelection ? String(window.getSelection()) : '';
    return sel.slice(0, 20000);
  } catch (ignored) {
    return '';
  }
})();
"""

    /** Scroll to the bottom of the document — used by the floating jump button. */
    const val SCROLL_TO_BOTTOM_SCRIPT: String = """
(function () {
  try {
    var scroller = document.scrollingElement || document.documentElement;
    scroller.scrollTop = scroller.scrollHeight;
    return 'ok';
  } catch (ignored) {
    return 'no';
  }
})();
"""

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Fields(
        val ok: Boolean,
        val url: String,
        val title: String,
        val loginPrompt: Boolean,
        val userChrome: Boolean,
        val label: String?,
        val rateLimited: Boolean,
        val sessionExpired: Boolean,
        val textLength: Int,
    )

    /**
     * `evaluateJavascript` hands back a JSON literal, so a script that returns a string arrives
     * quoted and escaped. Strip that layer once, here, rather than at every call site.
     */
    fun unwrap(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null") return ""
        if (!trimmed.startsWith("\"")) return trimmed
        return runCatching {
            json.parseToJsonElement(trimmed).jsonPrimitive.content
        }.getOrDefault(trimmed.removeSurrounding("\""))
    }

    fun fields(raw: String?): Fields? {
        val payload = unwrap(raw)
        if (payload.isEmpty()) return null
        val element: JsonElement = runCatching { json.parseToJsonElement(payload) }.getOrNull()
            ?: return null
        val obj = element as? JsonObject ?: return null
        return Fields(
            ok = obj.boolean("ok") ?: false,
            url = obj.string("url").orEmpty(),
            title = obj.string("title").orEmpty(),
            loginPrompt = obj.boolean("loginPrompt") ?: false,
            userChrome = obj.boolean("userChrome") ?: false,
            label = obj.string("label")?.takeIf { it.isNotBlank() },
            rateLimited = obj.boolean("rateLimited") ?: false,
            sessionExpired = obj.boolean("sessionExpired") ?: false,
            textLength = obj.string("textLength")?.toIntOrNull() ?: 0,
        )
    }

    fun probe(raw: String?, now: Long): AuthProbeResult {
        val fields = fields(raw)
            ?: return AuthProbeResult(
                observedAtMillis = now,
                verdict = ProbeVerdict.UNKNOWN,
                url = "",
            )
        if (!fields.ok) {
            return AuthProbeResult(
                observedAtMillis = now,
                verdict = ProbeVerdict.UNKNOWN,
                url = fields.url,
                title = fields.title,
            )
        }
        // A probe that answered from somewhere other than arena.ai proves nothing about our
        // session, so it is treated as inconclusive rather than as evidence.
        if (!ArenaUrls.isArenaUrl(fields.url)) {
            return AuthProbeResult(
                observedAtMillis = now,
                verdict = ProbeVerdict.UNKNOWN,
                url = fields.url,
                title = fields.title,
            )
        }
        val verdict = classify(fields)
        return AuthProbeResult(
            observedAtMillis = now,
            verdict = verdict,
            url = fields.url,
            title = fields.title,
            accountLabel = fields.label,
            seesLoginPrompt = fields.loginPrompt,
            seesRateLimitNotice = fields.rateLimited || fields.sessionExpired,
        )
    }

    fun classify(fields: Fields): ProbeVerdict = when {
        fields.label != null -> ProbeVerdict.AUTHENTICATED
        fields.userChrome && !fields.loginPrompt -> ProbeVerdict.AUTHENTICATED
        fields.loginPrompt && !fields.userChrome -> ProbeVerdict.ANONYMOUS
        fields.textLength < 40 -> ProbeVerdict.UNKNOWN
        else -> ProbeVerdict.UNKNOWN
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    /** Guard against a page that answers with a list instead of the expected object. */
    internal fun looksLikeObject(raw: String?): Boolean {
        val payload = unwrap(raw)
        return payload.startsWith("{") && runCatching { json.parseToJsonElement(payload) !is JsonArray }.getOrDefault(false)
    }
}
