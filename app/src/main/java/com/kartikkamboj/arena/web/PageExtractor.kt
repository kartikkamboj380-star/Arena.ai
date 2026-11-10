package com.kartikkamboj.arena.web

import com.kartikkamboj.arena.data.model.ModelAvailability
import com.kartikkamboj.arena.data.model.ModelEntry
import com.kartikkamboj.arena.data.model.ModelSource
import com.kartikkamboj.arena.data.repo.ExtractedLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Best-effort reading of what is already on the screen, used by two user-initiated actions:
 * refreshing the model list from the leaderboard, and importing the titles shown on the user's own
 * history page.
 *
 * It is extraction, not integration: Arena has no public API, so the DOM is the only honest source,
 * which means the result is labelled [ModelAvailability.INFORMATIONAL_ONLY] and an empty result is
 * reported as an empty result. Nothing here invents a row when the page has changed.
 */
object PageExtractor {

    /**
     * Collects short text + href pairs from the rendered document. Read-only, capped, and never
     * returns whole-page text: the app asks for candidate rows, not for the user's content.
     */
    const val LIST_SCRIPT: String = """
(function () {
  try {
    var nodes = document.querySelectorAll('a[href], [role="row"], tr, li');
    var out = [];
    for (var i = 0; i < nodes.length && out.length < 300; i++) {
      var el = nodes[i];
      var t = (el.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 180);
      if (!t) { continue; }
      var h = (el.getAttribute && el.getAttribute('href')) || '';
      out.push({ t: t, h: h });
    }
    return JSON.stringify({ ok: true, url: location.href, items: out });
  } catch (ignored) {
    return JSON.stringify({ ok: false, url: location.href, items: [] });
  }
})();
"""

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Item(val text: String, val href: String)

    private val ROW = Regex("^(\\d{1,3})[.)\\s]*(.+)$")
    // Arena prints Elo in the thousands ("· OpenAI · 1372") and percentages as "97.3%", so a
    // four-digit tail must be readable as the number: capping at three digits truncated 1372 to 372
    // and left the discarded "1" glued onto the vendor label.
    private val TRAILING_SCORE = Regex("[\\s,]*(\\d{1,6}(?:\\.\\d+)?)\\s*%?\\s*$")
    private val VENDOR_SEPARATOR = "·"
    /** Short labels that are buttons, not pages — compared exactly, see [isChrome]. */
    private val CHROME_LABELS = setOf(
        "log in", "sign in", "sign up", "log out", "sign out", "menu", "close", "copy", "share",
        "feedback", "privacy", "terms", "help", "more", "dismiss",
    )

    private val IGNORED_WORDS = listOf(
        "view all", "view details", "pareto", "leaderboard trends", "trends", "see more",
        "get more done", "try it now", "start a chat", "log in", "privacy", "terms",
        "first impressions", "read the", "learn more", "subscribe", "follow",
    )

    /**
     * Whether a piece of page text is chrome rather than content. Whole labels are matched exactly
     * and phrases are matched as substrings, because a chat legitimately titled "Close the loop"
     * must survive while a button reading "Close" must not be imported as a conversation.
     */
    private fun isChrome(text: String): Boolean {
        val trimmed = text.trim().lowercase()
        return trimmed in CHROME_LABELS || IGNORED_WORDS.any { trimmed.contains(it) }
    }

    fun parseItems(raw: String?): Pair<List<Item>, String> {
        val payload = AuthProbe.unwrap(raw)
        if (payload.isEmpty()) return emptyList<Item>() to ""
        val element = runCatching { json.parseToJsonElement(payload) }.getOrNull()
        val obj = element as? JsonObject ?: return emptyList<Item>() to ""
        if ((obj["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() != true) {
            return emptyList<Item>() to ""
        }
        val url = (obj["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val array = obj["items"] as? JsonArray ?: return emptyList<Item>() to url
        val items = array.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val text = (item["t"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val href = (item["h"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) null else Item(text, href)
        }
        return items to url
    }

    /** Turns rendered rows into catalog entries; unparseable rows are skipped, never guessed. */
    fun models(items: List<Item>, now: Long): List<ModelEntry> {
        val byId = LinkedHashMap<String, ModelEntry>()
        items.forEach { item ->
            val row = ROW.matchEntire(item.text) ?: return@forEach
            val rank = row.groupValues[1].toIntOrNull() ?: return@forEach
            var remainder = row.groupValues[2].trim()
            if (remainder.length < 3) return@forEach
            if (isChrome(remainder)) return@forEach

            val score = TRAILING_SCORE.find(remainder)?.groupValues?.get(1)?.toDoubleOrNull()
            if (score != null) {
                remainder = remainder.substringBeforeLast(TRAILING_SCORE.find(remainder)!!.groupValues[0])
                    .trim()
            }
            // "·" only appears in Arena's `name · vendor` rows (live sessions), so it is treated as
            // that separator and nothing else. Rows without it keep their full text as the label:
            // guessing where a vendor name ends and a model version begins would be invention.
            var vendor: String? = null
            if (remainder.contains(VENDOR_SEPARATOR)) {
                val parts = remainder.split(VENDOR_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    remainder = parts.first()
                    vendor = parts.drop(1).joinToString(" ")
                }
            }
            // The separator is trimmed off both ends: a row whose score was removed can still be left
            // holding a trailing "·", and that is presentation noise in a stored label.
            val name = remainder.trim().trimStart('·', ' ', '-', ':').trimEnd('-', ':', ' ', '·').trim()
            if (name.length < 3) return@forEach
            val id = "live:" + slug(name)
            if (byId.containsKey(id)) return@forEach
            byId[id] = ModelEntry(
                id = id,
                displayName = name.take(60),
                vendor = vendor?.take(40),
                source = ModelSource.LEADERBOARD_LIVE,
                availability = ModelAvailability.INFORMATIONAL_ONLY,
                note = "read from the Arena leaderboard page",
                rank = rank,
                score = score,
                addedAtMillis = now,
            )
        }
        return byId.values.sortedBy { it.rank ?: Int.MAX_VALUE }
    }

    /** History import: only links that look like Arena content pages, resolved to absolute URLs. */
    fun links(items: List<Item>, baseUrl: String): List<ExtractedLink> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<ExtractedLink>()
        items.forEach { item ->
            // "#" is a placeholder rather than a page: resolving it against the current URL would
            // file a history row for the page the user is already looking at, and every SPA on the
            // site uses one for its in-page buttons.
            val href = item.href.trim()
            if (href.isEmpty() || href.startsWith("#")) return@forEach
            val absolute = ArenaUrls.absolute(href, baseUrl) ?: return@forEach
            if (!ArenaUrls.isContentPath(absolute) && absolute != ArenaUrls.CHAT) return@forEach
            if (item.text.length < 3) return@forEach
            // Page chrome is labelled by its text as well as by its path: "/login" is a real path
            // and it is not somebody's conversation.
            if (isChrome(item.text)) return@forEach
            if (!seen.add(absolute)) return@forEach
            out += ExtractedLink(title = item.text.take(120), url = absolute)
        }
        return out
    }

    internal fun slug(raw: String): String = raw.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(48)
        .ifEmpty { "model" }
}
