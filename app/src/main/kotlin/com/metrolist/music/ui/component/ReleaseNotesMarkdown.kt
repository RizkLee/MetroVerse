package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

internal enum class ReleaseNoteBlockType {
    HEADING,
    SUBHEADING,
    PARAGRAPH,
    BULLET,
    ORDERED_ITEM,
    IMPORTANT,
    QUOTE,
    DIVIDER,
    TABLE_HEADER,
    TABLE_ROW,
}

internal data class ReleaseNoteBlock(
    val type: ReleaseNoteBlockType,
    val text: String = "",
    val marker: String? = null,
)

internal enum class ReleaseNoteInlineStyle {
    PLAIN,
    BOLD,
    ITALIC,
    CODE,
    LINK,
}

internal data class ReleaseNoteInline(
    val text: String,
    val style: ReleaseNoteInlineStyle,
    val url: String? = null,
)

private val orderedItemPattern = Regex("""^(\d+)\.\s+(.+)$""")
private val inlineMarkdownPattern =
    Regex("""\[([^\]]+)]\((https://[^)\s]+)\)|\*\*([^*]+)\*\*|`([^`]+)`|\*([^*]+)\*""")

internal fun parseReleaseNotesMarkdown(markdown: String): List<ReleaseNoteBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    val blocks = mutableListOf<ReleaseNoteBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index].trim()
        if (line.isEmpty()) {
            index++
            continue
        }

        if (line.matches(Regex("""^>\s*\[!(IMPORTANT|WARNING|CAUTION)]\s*$""", RegexOption.IGNORE_CASE))) {
            val content = mutableListOf<String>()
            index++
            while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                content += lines[index].trimStart().removePrefix(">").trimStart()
                index++
            }
            blocks += ReleaseNoteBlock(
                type = ReleaseNoteBlockType.IMPORTANT,
                text = content.joinToString("\n").trim(),
            )
            continue
        }

        if (line.matches(Regex("""^>\s*\[!(NOTE|TIP)]\s*$""", RegexOption.IGNORE_CASE))) {
            val content = mutableListOf<String>()
            index++
            while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                content += lines[index].trimStart().removePrefix(">").trimStart()
                index++
            }
            blocks += ReleaseNoteBlock(
                type = ReleaseNoteBlockType.QUOTE,
                text = content.joinToString("\n").trim(),
            )
            continue
        }

        if (line == "---" || line == "***") {
            blocks += ReleaseNoteBlock(ReleaseNoteBlockType.DIVIDER)
            index++
            continue
        }

        if (isTableRow(line) && index + 1 < lines.size && isTableDivider(lines[index + 1].trim())) {
            blocks += ReleaseNoteBlock(
                type = ReleaseNoteBlockType.TABLE_HEADER,
                text = tableCells(line).joinToString("  \u00B7  "),
            )
            index += 2
            continue
        }

        if (isTableRow(line)) {
            blocks += ReleaseNoteBlock(
                type = ReleaseNoteBlockType.TABLE_ROW,
                text = tableCells(line).joinToString("  \u00B7  "),
            )
            index++
            continue
        }

        val headingLevel = line.takeWhile { it == '#' }.length
        if (headingLevel in 1..6 && line.getOrNull(headingLevel) == ' ') {
            blocks += ReleaseNoteBlock(
                type = if (headingLevel <= 2) ReleaseNoteBlockType.HEADING else ReleaseNoteBlockType.SUBHEADING,
                text = line.drop(headingLevel + 1).trim(),
            )
            index++
            continue
        }

        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
            blocks += ReleaseNoteBlock(
                type = ReleaseNoteBlockType.BULLET,
                text = line.drop(2).trim(),
            )
            index++
            continue
        }

        val orderedItemMatch = orderedItemPattern.matchEntire(line)
        if (orderedItemMatch != null) {
            blocks += ReleaseNoteBlock(
                type = ReleaseNoteBlockType.ORDERED_ITEM,
                text = orderedItemMatch.groupValues[2].trim(),
                marker = "${orderedItemMatch.groupValues[1]}.",
            )
            index++
            continue
        }

        if (line.startsWith(">")) {
            val content = mutableListOf<String>()
            while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                content += lines[index].trimStart().removePrefix(">").trimStart()
                index++
            }
            blocks += ReleaseNoteBlock(
                type = ReleaseNoteBlockType.QUOTE,
                text = content.joinToString("\n").trim(),
            )
            continue
        }

        val paragraph = mutableListOf(line)
        index++
        while (index < lines.size && !startsMarkdownBlock(lines, index)) {
            paragraph += lines[index].trim()
            index++
        }
        blocks += ReleaseNoteBlock(
            type = ReleaseNoteBlockType.PARAGRAPH,
            text = paragraph.joinToString(" ").trim(),
        )
    }

    return blocks
}

private fun startsMarkdownBlock(
    lines: List<String>,
    index: Int,
): Boolean {
    val line = lines[index].trim()
    if (line.isEmpty()) return true
    if (line == "---" || line == "***" || line.startsWith(">")) return true
    if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) return true
    if (orderedItemPattern.matches(line)) return true
    if (isTableRow(line)) return true

    val headingLevel = line.takeWhile { it == '#' }.length
    return headingLevel in 1..6 && line.getOrNull(headingLevel) == ' '
}

private fun isTableRow(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith('|') &&
        trimmed.endsWith('|') &&
        trimmed.count { it == '|' } >= 3 &&
        !isTableDivider(trimmed)
}

private fun isTableDivider(line: String): Boolean {
    if ('|' !in line) return false
    val cells = tableCells(line)
    return cells.isNotEmpty() && cells.all { it.matches(Regex("""^:?-{3,}:?$""")) }
}

private fun tableCells(line: String): List<String> =
    line
        .trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split('|')
        .map(String::trim)
        .filter(String::isNotEmpty)

internal fun parseReleaseNoteInline(text: String): List<ReleaseNoteInline> {
    val result = mutableListOf<ReleaseNoteInline>()
    var cursor = 0

    inlineMarkdownPattern.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            result += ReleaseNoteInline(
                text = text.substring(cursor, match.range.first),
                style = ReleaseNoteInlineStyle.PLAIN,
            )
        }

        val segment =
            when {
                match.groupValues[1].isNotEmpty() ->
                    ReleaseNoteInline(
                        text = match.groupValues[1],
                        style = ReleaseNoteInlineStyle.LINK,
                        url = match.groupValues[2],
                    )

                match.groupValues[3].isNotEmpty() ->
                    ReleaseNoteInline(match.groupValues[3], ReleaseNoteInlineStyle.BOLD)

                match.groupValues[4].isNotEmpty() ->
                    ReleaseNoteInline(match.groupValues[4], ReleaseNoteInlineStyle.CODE)

                else ->
                    ReleaseNoteInline(match.groupValues[5], ReleaseNoteInlineStyle.ITALIC)
            }
        result += segment
        cursor = match.range.last + 1
    }

    if (cursor < text.length) {
        result += ReleaseNoteInline(
            text = text.substring(cursor),
            style = ReleaseNoteInlineStyle.PLAIN,
        )
    }
    if (result.isEmpty() && text.isNotEmpty()) {
        result += ReleaseNoteInline(text, ReleaseNoteInlineStyle.PLAIN)
    }
    return result
}

@Composable
fun ReleaseNotesMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseReleaseNotesMarkdown(markdown) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        blocks.forEach { block ->
            when (block.type) {
                ReleaseNoteBlockType.HEADING ->
                    MarkdownInlineText(
                        text = block.text,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )

                ReleaseNoteBlockType.SUBHEADING ->
                    MarkdownInlineText(
                        text = block.text,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )

                ReleaseNoteBlockType.PARAGRAPH ->
                    MarkdownInlineText(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                ReleaseNoteBlockType.BULLET,
                ReleaseNoteBlockType.ORDERED_ITEM,
                -> {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = block.marker ?: "\u2022",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(22.dp),
                        )
                        MarkdownInlineText(
                            text = block.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                ReleaseNoteBlockType.IMPORTANT ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.error),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            MarkdownInlineText(
                                text = block.text,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                ReleaseNoteBlockType.QUOTE ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MarkdownInlineText(
                            text = block.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }

                ReleaseNoteBlockType.DIVIDER -> HorizontalDivider()

                ReleaseNoteBlockType.TABLE_HEADER ->
                    MarkdownInlineText(
                        text = block.text,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                ReleaseNoteBlockType.TABLE_ROW ->
                    MarkdownInlineText(
                        text = block.text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
            }
        }
    }
}

@Composable
private fun MarkdownInlineText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val annotatedText = remember(text, color, linkColor, codeBackground, uriHandler) {
        buildAnnotatedString {
            parseReleaseNoteInline(text).forEach { inline ->
                when (inline.style) {
                    ReleaseNoteInlineStyle.PLAIN -> append(inline.text)
                    ReleaseNoteInlineStyle.BOLD ->
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(inline.text)
                        }

                    ReleaseNoteInlineStyle.ITALIC ->
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(inline.text)
                        }

                    ReleaseNoteInlineStyle.CODE ->
                        withStyle(
                            SpanStyle(
                                background = codeBackground,
                                fontFamily = FontFamily.Monospace,
                            ),
                        ) {
                            append(inline.text)
                        }

                    ReleaseNoteInlineStyle.LINK -> {
                        val url = inline.url ?: return@forEach
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = url,
                                styles =
                                    TextLinkStyles(
                                        style =
                                            SpanStyle(
                                                color = linkColor,
                                                textDecoration = TextDecoration.Underline,
                                            ),
                                    ),
                            ) {
                                runCatching { uriHandler.openUri(url) }
                            },
                        ) {
                            append(inline.text)
                        }
                    }
                }
            }
        }
    }

    Text(
        text = annotatedText,
        style = style,
        color = color,
        modifier = modifier,
    )
}
