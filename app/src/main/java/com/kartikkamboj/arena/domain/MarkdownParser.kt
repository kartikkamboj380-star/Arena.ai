package com.kartikkamboj.arena.domain

/**
 * A deliberately small Markdown reader for locally stored text: thread notes, and the excerpts a
 * user imports from their own Arena history.
 *
 * Arena's answers themselves are rendered by Arena inside the web surface — this client has no API
 * access to the transcript, so it never re-renders or restates a model response. What lives here
 * is the parsing of text the user wrote or pasted, which is real and unit-testable: fenced code,
 * headings, lists, quotes, rules, and the inline subset (bold, italic, code, links).
 */
enum class MdStyle { BOLD, ITALIC, CODE, LINK }

sealed interface MdBlock {
    data class Heading(val level: Int, val inline: List<MdInline>) : MdBlock
    data class Paragraph(val inline: List<MdInline>) : MdBlock
    data class Code(val language: String?, val lines: List<String>) : MdBlock
    data class ListBlock(val ordered: Boolean, val items: List<List<MdInline>>) : MdBlock
    data class Quote(val inline: List<MdInline>) : MdBlock
    data object Rule : MdBlock
}

data class MdInline(val text: String, val styles: Set<MdStyle> = emptySet(), val link: String? = null)

data class MarkdownDocument(val blocks: List<MdBlock>) {
    fun toPlainText(): String = blocks.joinToString("\n\n") { block ->
        when (block) {
            is MdBlock.Heading -> "#".repeat(block.level) + " " + block.inline.textOf()
            is MdBlock.Paragraph -> block.inline.textOf()
            is MdBlock.Code -> block.lines.joinToString("\n")
            is MdBlock.ListBlock -> block.items.mapIndexed { i, item ->
                if (block.ordered) "${i + 1}. ${item.textOf()}" else "• ${item.textOf()}"
            }.joinToString("\n")

            is MdBlock.Quote -> "> " + block.inline.textOf()
            MdBlock.Rule -> "────────"
        }
    }
}

private fun List<MdInline>.textOf(): String = joinToString("") { it.text }

object MarkdownParser {

    const val MAX_LINES = 400
    const val MAX_CODE_LINES = 80
    const val MAX_INLINE_LENGTH = 8_000

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val FENCE = Regex("^```\\s*([A-Za-z0-9+#._-]{0,24})\\s*$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val RULE = Regex("^\\s*([-*_])\\s*\\1\\s*\\1[\\s*_-]*$")
    private val LINK = Regex("\\[([^\\]]{1,200})]\\(([^)\\s]{1,400})\\)")
    private val SAFE_LINK_SCHEMES = setOf("https", "http", "mailto")

    fun parse(source: String): MarkdownDocument {
        val text = source.replace("\r\n", "\n").take(MAX_LINES * 500)
        val lines = text.split('\n').take(MAX_LINES)
        val blocks = mutableListOf<MdBlock>()
        var index = 0
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val joined = paragraph.joinToString(" ").trim()
            paragraph.clear()
            if (joined.isNotEmpty()) blocks += MdBlock.Paragraph(inline(joined))
        }

        while (index < lines.size) {
            val line = lines[index]
            when {
                FENCE.matches(line) -> {
                    flushParagraph()
                    val language = FENCE.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                    val code = mutableListOf<String>()
                    index++
                    while (index < lines.size && !FENCE.matches(lines[index]) &&
                        code.size < MAX_CODE_LINES
                    ) {
                        code += lines[index]
                        index++
                    }
                    // Skip the closing fence if present; an unterminated fence just ends at EOF.
                    if (index < lines.size && FENCE.matches(lines[index])) index++
                    blocks += MdBlock.Code(language, code)
                    continue
                }

                HEADING.matches(line) -> {
                    flushParagraph()
                    val match = HEADING.find(line)!!
                    blocks += MdBlock.Heading(
                        level = match.groupValues[1].length,
                        inline = inline(match.groupValues[2]),
                    )
                }

                RULE.matches(line) -> {
                    flushParagraph()
                    blocks += MdBlock.Rule
                }

                QUOTE.matches(line) -> {
                    flushParagraph()
                    blocks += MdBlock.Quote(inline(QUOTE.find(line)!!.groupValues[1]))
                }

                BULLET.matches(line) -> {
                    flushParagraph()
                    val items = mutableListOf<List<MdInline>>()
                    while (index < lines.size && BULLET.matches(lines[index])) {
                        items += inline(BULLET.find(lines[index])!!.groupValues[1])
                        index++
                    }
                    blocks += MdBlock.ListBlock(ordered = false, items = items)
                    continue
                }

                NUMBERED.matches(line) -> {
                    flushParagraph()
                    val items = mutableListOf<List<MdInline>>()
                    while (index < lines.size && NUMBERED.matches(lines[index])) {
                        items += inline(NUMBERED.find(lines[index])!!.groupValues[2])
                        index++
                    }
                    blocks += MdBlock.ListBlock(ordered = true, items = items)
                    continue
                }

                line.isBlank() -> flushParagraph()

                else -> paragraph += line.trim()
            }
            index++
        }
        flushParagraph()
        return MarkdownDocument(blocks)
    }

    /** Inline pass: bold, italic, code spans and links. No nesting, no HTML, ever. */
    fun inline(input: String): List<MdInline> {
        val text = input.take(MAX_INLINE_LENGTH)
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<MdInline>()
        val buffer = StringBuilder()
        var active = linkedSetOf<MdStyle>()

        fun flush() {
            if (buffer.isEmpty()) return
            out += MdInline(buffer.toString(), active.toSet())
            buffer.setLength(0)
        }

        fun toggle(style: MdStyle) {
            flush()
            active = if (style in active) linkedSetOf<MdStyle>().apply {
                addAll(active.filter { it != style })
            } else linkedSetOf<MdStyle>().apply {
                addAll(active)
                add(style)
            }
        }

        var i = 0
        var consumedUntil = 0
        val links = LINK.findAll(text).associateBy { it.range.first }
        while (i < text.length) {
            if (i >= consumedUntil) {
                val link = links[i]
                if (link != null) {
                    flush()
                    val target = sanitise(link.groupValues[2]).takeIf { it.isNotBlank() }
                    out += MdInline(link.groupValues[1], setOf(MdStyle.LINK), target)
                    i = link.range.last + 1
                    consumedUntil = i
                    continue
                }
            }
            when {
                text.startsWith("**", i) -> {
                    toggle(MdStyle.BOLD); i += 2; continue
                }

                text.startsWith("`", i) -> {
                    toggle(MdStyle.CODE); i += 1; continue
                }

                text.startsWith("___", i) -> {
                    toggle(MdStyle.ITALIC); i += 3; continue
                }

                text.startsWith("*", i) -> {
                    toggle(MdStyle.ITALIC); i += 1; continue
                }

                else -> {
                    buffer.append(text[i])
                    i += 1
                }
            }
            consumedUntil = maxOf(consumedUntil, i)
        }
        flush()
        return out.ifEmpty { listOf(MdInline(text)) }
    }

    /** Links that cannot be followed from a note (javascript:, data:) lose their target. */
    private fun sanitise(raw: String): String {
        val trimmed = raw.trim().trimEnd('.', ')')
        // Relative paths and anchors carry no scheme and stay as plain text targets.
        if (!trimmed.contains(':')) return trimmed
        val scheme = trimmed.substringBefore(":").lowercase()
        return if (scheme in SAFE_LINK_SCHEMES) trimmed else ""
    }
}
