package com.kartikkamboj.arena.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kartikkamboj.arena.domain.MdBlock
import com.kartikkamboj.arena.domain.MdInline
import com.kartikkamboj.arena.domain.MdStyle
import com.kartikkamboj.arena.domain.MarkdownParser
import com.kartikkamboj.arena.ui.theme.ArenaPalette
import com.kartikkamboj.arena.ui.theme.LocalDarkTheme

/**
 * Renders locally stored Markdown: a thread note, or an excerpt the user imported from their own
 * Arena history page.
 *
 * The scope is deliberate. Model answers are displayed by Arena inside the web surface because this
 * client cannot read them from the service — so this renderer is only ever mounted on text the app
 * itself stores, and never presented as a substitute for the real transcript.
 */
@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
) {
    val document = remember(source) { MarkdownParser.parse(source) }
    Column(modifier = modifier.fillMaxWidth()) {
        document.blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = annotated(block.inline, headlineFor(block.level)),
                    style = headlineFor(block.level),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )

                is MdBlock.Paragraph -> Text(
                    text = annotated(block.inline, MaterialTheme.typography.bodyMedium),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(vertical = 3.dp),
                )

                is MdBlock.Quote -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                        )
                        .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Text(
                        text = annotated(block.inline, MaterialTheme.typography.bodyMedium),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is MdBlock.ListBlock -> Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArenaPalette.Violet,
                                modifier = Modifier.width(if (block.ordered) 24.dp else 18.dp),
                            )
                            Text(
                                text = annotated(item, MaterialTheme.typography.bodyMedium),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }

                is MdBlock.Code -> CodeBlockCard(block.language, block.lines.joinToString("\n"))

                MdBlock.Rule -> Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .padding(vertical = 10.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
        }
    }
}

@Composable
private fun headlineFor(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

/** Inline runs → annotated string. Link targets stay visible as text, never as raw HTML. */
@Composable
private fun annotated(parts: List<MdInline>, base: TextStyle): AnnotatedString =
    buildAnnotatedString {
        parts.forEach { part ->
            val isCode = MdStyle.CODE in part.styles
            val isLink = MdStyle.LINK in part.styles && !part.link.isNullOrBlank()
            pushStyle(
                SpanStyle(
                    fontWeight = if (MdStyle.BOLD in part.styles) FontWeight.SemiBold else base.fontWeight,
                    fontStyle = if (MdStyle.ITALIC in part.styles) FontStyle.Italic else base.fontStyle,
                    fontFamily = if (isCode) FontFamily.Monospace else base.fontFamily,
                    textDecoration = if (isLink) TextDecoration.Underline else base.textDecoration,
                    background = if (isCode) MaterialTheme.colorScheme.surfaceVariant else Color.Unspecified,
                    color = when {
                        isLink -> ArenaPalette.Cyan
                        else -> base.color
                    },
                ),
            )
            append(part.text)
            pop()
            val link = part.link
            // link is only ever populated for a LINK span, so null is the whole test.
            if (link != null) {
                append("  ")
                pushStyle(
                    SpanStyle(
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                append(link.take(60))
                pop()
            }
        }
    }

@Composable
private fun CodeBlockCard(language: String?, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (LocalDarkTheme.current) Color(0xFF0D0E12) else Color(0xFFF2F3F7))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "code${language?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "Copy code",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { runCatching { clipboard.setText(AnnotatedString(code)) } }
                    .padding(3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            code.split("\n").forEach { line ->
                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
