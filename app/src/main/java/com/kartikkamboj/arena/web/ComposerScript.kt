package com.kartikkamboj.arena.web

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Optional convenience for the mobile keyboard: put the text the user drafted in the native bar
 * into the page's own composer.
 *
 * Two hard limits, both deliberate:
 *  * it never submits anything. No synthetic Enter, no click on a send button — scripting the
 *    service into sending a prompt on the user's behalf is not something this client will do.
 *  * it only writes into a field that is visible, enabled and editable, using the field's own
 *    native value setter so React keeps its state consistent.
 *
 * The drafted text is embedded with a JSON encoder, so quotes, newlines and angle brackets in the
 * user's text cannot break out of the string literal.
 */
object ComposerScript {

    private val json = Json { encodeDefaults = false }

    fun build(text: String): String {
        val literal = json.encodeToString(String.serializer(), text)
        return """
(function () {
  try {
    var wanted = $literal;
    var nodes = document.querySelectorAll('textarea, [contenteditable="true"], [contenteditable="plaintext-only"]');
    var target = null;
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.disabled === true || n.readOnly === true) { continue; }
      var r = n.getBoundingClientRect();
      if (r.width < 40 || r.height < 8) { continue; }
      target = n;
      break;
    }
    if (!target) { return JSON.stringify({ ok: false, reason: 'no-visible-input' }); }
    var tag = target.tagName;
    if (tag === 'TEXTAREA' || tag === 'INPUT') {
      var proto = (tag === 'INPUT') ? window.HTMLInputElement.prototype : window.HTMLTextAreaElement.prototype;
      var desc = Object.getOwnPropertyDescriptor(proto, 'value');
      if (desc && desc.set) { desc.set.call(target, wanted); } else { target.value = wanted; }
    } else {
      target.textContent = wanted;
    }
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.focus();
    return JSON.stringify({ ok: true, tag: tag, length: wanted.length });
  } catch (ignored) {
    return JSON.stringify({ ok: false, reason: 'script-error' });
  }
})();
"""
    }

    /** @return null on success, otherwise the reason the draft could not be placed. */
    fun interpret(raw: String?): String? {
        val payload = AuthProbe.unwrap(raw)
        if (payload.isEmpty()) return "no-response"
        val element = runCatching { json.parseToJsonElement(payload) }.getOrNull()
            ?: return "unparsable"
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return "unparsable"
        val ok = (obj["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
        return when (ok) {
            true -> null
            else -> (obj["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "failed"
        }
    }
}
