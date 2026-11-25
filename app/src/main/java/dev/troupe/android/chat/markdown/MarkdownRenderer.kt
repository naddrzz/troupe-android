package dev.troupe.android.chat.markdown

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.troupe.android.R
import dev.troupe.android.core.theme.MonoFamily
import dev.troupe.android.core.theme.SyntaxPalette
import dev.troupe.android.core.theme.TroupeTheme
import dev.troupe.android.core.ui.TroupeIconButton
import dev.troupe.android.core.ui.TroupeIcons

private const val URL_TAG = "URL"

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class CodeFence(val language: String, val code: String) : MarkdownBlock
}

/**
 * Lightweight native Jetpack Compose Markdown renderer for Agent Chat messages.
 * Supports headings, paragraphs, inline bold/italic/code/links, bullet/numbered lists, quotes,
 * and theme-aware syntax-highlighted code fences (`SyntaxPalette`) with 1-tap copy.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = TroupeTheme.palette.foreground,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val blocks = remember(markdown) { parseBlocks(markdown) }

    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> MarkdownInlineText(
                        text = renderInline(block.text, palette.syntax, palette.mutedSurface, palette.brandText),
                        style = when (block.level) {
                            1 -> typo.titleLarge
                            2 -> typo.titleMedium
                            else -> typo.titleSmall
                        },
                        color = palette.syntax.heading,
                    )

                    is MarkdownBlock.Paragraph -> MarkdownInlineText(
                        text = renderInline(block.text, palette.syntax, palette.mutedSurface, palette.brandText),
                        style = typo.bodyMedium,
                        color = textColor,
                    )

                    is MarkdownBlock.Bullet -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Text("•", style = typo.bodyMedium, color = palette.mutedForeground)
                        MarkdownInlineText(
                            text = renderInline(block.text, palette.syntax, palette.mutedSurface, palette.brandText),
                            style = typo.bodyMedium,
                            color = textColor,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    is MarkdownBlock.NumberedItem -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Text("${block.number}.", style = typo.bodyMedium, color = palette.mutedForeground)
                        MarkdownInlineText(
                            text = renderInline(block.text, palette.syntax, palette.mutedSurface, palette.brandText),
                            style = typo.bodyMedium,
                            color = textColor,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    is MarkdownBlock.Quote -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.mutedSurface.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        MarkdownInlineText(
                            text = renderInline(block.text, palette.syntax, palette.mutedSurface, palette.brandText),
                            style = typo.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = palette.secondary,
                        )
                    }

                    is MarkdownBlock.CodeFence -> CodeBlockCard(
                        language = block.language,
                        code = block.code,
                    )
                }
            }
        }
    }
}

/**
 * Renders annotated Markdown text while preserving [SelectionContainer] long-press text selection:
 * only a quick single tap on a URL span opens the link; long-presses pass straight to selection.
 */
@Composable
private fun MarkdownInlineText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val linkRanges = remember(text) { text.getStringAnnotations(URL_TAG, 0, text.length) }
    val linkActions = linkRanges.map { range ->
        val label = text.substring(range.start, range.end).ifBlank { range.item }
        val actionLabel = stringResource(R.string.markdown_open_link, label)
        CustomAccessibilityAction(label = actionLabel) {
            runCatching { uriHandler.openUri(range.item) }.isSuccess
        }
    }
    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { layoutResult = it },
        modifier = if (linkRanges.isNotEmpty()) {
            modifier
                .semantics { customActions = linkActions }
                .pointerInput(text) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val up = waitForUpOrCancellation()
                        if (up != null && !up.isConsumed &&
                            (up.uptimeMillis - down.uptimeMillis) < viewConfiguration.longPressTimeoutMillis
                        ) {
                            val offset = layoutResult?.getOffsetForPosition(up.position) ?: return@awaitEachGesture
                            val url = text.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()?.item
                            if (url != null) {
                                runCatching { uriHandler.openUri(url) }
                            }
                        }
                    }
                }
        } else {
            modifier
        },
    )
}

@Composable
private fun CodeBlockCard(
    language: String,
    code: String,
) {
    val palette = TroupeTheme.palette
    val typo = TroupeTheme.typography
    val context = LocalContext.current
    val highlighted = remember(code, palette.syntax) { highlightCode(code, palette.syntax) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.card)
            .border(0.5.dp, palette.border, RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.elevated)
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = language.ifBlank { "code" },
                style = typo.labelSmall.copy(fontFamily = MonoFamily),
                color = palette.mutedForeground,
            )
            TroupeIconButton(
                icon = TroupeIcons.Copy,
                contentDescription = stringResource(R.string.chat_copy_code),
                onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("code", code))
                },
                size = 36.dp,
                iconSize = 15.dp,
                contentColor = palette.mutedForeground,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                text = highlighted,
                style = typo.bodySmall.copy(fontFamily = MonoFamily, lineHeight = 18.sp),
                color = palette.foreground,
            )
        }
    }
}

private val ORDERED_LIST_REGEX = Regex("^(\\d+)\\.\\s+(.*)$")

private fun parseBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        val text = para.toString().trim()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
        para.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val ordered = ORDERED_LIST_REGEX.matchEntire(trimmed)
        when {
            trimmed.startsWith("```") -> {
                flushPara()
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines += lines[i]
                    i++
                }
                blocks += MarkdownBlock.CodeFence(lang, codeLines.joinToString("\n"))
            }
            trimmed.startsWith("### ") -> {
                flushPara()
                blocks += MarkdownBlock.Heading(3, trimmed.removePrefix("### ").trim())
            }
            trimmed.startsWith("## ") -> {
                flushPara()
                blocks += MarkdownBlock.Heading(2, trimmed.removePrefix("## ").trim())
            }
            trimmed.startsWith("# ") -> {
                flushPara()
                blocks += MarkdownBlock.Heading(1, trimmed.removePrefix("# ").trim())
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushPara()
                blocks += MarkdownBlock.Bullet(trimmed.drop(2).trim())
            }
            ordered != null -> {
                flushPara()
                blocks += MarkdownBlock.NumberedItem(ordered.groupValues[1], ordered.groupValues[2].trim())
            }
            trimmed.startsWith("> ") -> {
                flushPara()
                blocks += MarkdownBlock.Quote(trimmed.removePrefix("> ").trim())
            }
            trimmed.isEmpty() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flushPara()
    return blocks
}

private fun renderInline(
    text: String,
    syntax: SyntaxPalette,
    codeBg: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i++])
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = MonoFamily,
                            background = codeBg,
                            color = syntax.string,
                            fontSize = 13.sp,
                        ),
                    ) {
                        append(" ${text.substring(i + 1, end)} ")
                    }
                    i = end + 1
                } else {
                    append(text[i++])
                }
            }
            text[i] == '[' -> {
                val mid = text.indexOf("](", i + 1)
                val end = if (mid > 0) text.indexOf(')', mid + 2) else -1
                if (mid > i + 1 && end > mid + 2) {
                    val label = text.substring(i + 1, mid)
                    val destination = text.substring(mid + 2, end).trim()
                    val isSafeWebUrl = destination.startsWith("https://", ignoreCase = true) ||
                        destination.startsWith("http://", ignoreCase = true) ||
                        destination.startsWith("mailto:", ignoreCase = true)
                    if (isSafeWebUrl) {
                        pushStringAnnotation(tag = URL_TAG, annotation = destination)
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                fontWeight = FontWeight.Medium,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) {
                            append(label)
                        }
                        pop()
                    } else {
                        withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)) {
                            append("$label ($destination)")
                        }
                    }
                    i = end + 1
                } else {
                    append(text[i++])
                }
            }
            else -> append(text[i++])
        }
    }
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for", "while",
    "import", "package", "private", "public", "internal", "suspend", "data", "sealed", "override",
    "const", "let", "function", "export", "async", "await", "type", "fn", "pub", "struct", "enum",
    "impl", "match", "use", "mod", "def", "from", "true", "false", "null", "nil", "None",
)

private fun highlightCode(code: String, syntax: SyntaxPalette): AnnotatedString = buildAnnotatedString {
    val lines = code.lines()
    lines.forEachIndexed { idx, line ->
        if (idx > 0) append('\n')
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("#")) {
            withStyle(SpanStyle(color = syntax.comment)) { append(line) }
        } else {
            var pos = 0
            while (pos < line.length) {
                val c = line[pos]
                when {
                    c == '"' || c == '\'' -> {
                        val end = findQuoteEnd(line, pos, c)
                        withStyle(SpanStyle(color = syntax.string)) {
                            append(line.substring(pos, end))
                        }
                        pos = end
                    }
                    c.isDigit() -> {
                        var end = pos
                        while (end < line.length && (line[end].isDigit() || line[end] == '.')) end++
                        withStyle(SpanStyle(color = syntax.number)) {
                            append(line.substring(pos, end))
                        }
                        pos = end
                    }
                    c.isLetter() || c == '_' -> {
                        var end = pos
                        while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++
                        val word = line.substring(pos, end)
                        when {
                            word in KEYWORDS -> withStyle(SpanStyle(color = syntax.keyword)) { append(word) }
                            word.first().isUpperCase() -> withStyle(SpanStyle(color = syntax.type)) { append(word) }
                            else -> append(word)
                        }
                        pos = end
                    }
                    else -> {
                        append(c)
                        pos++
                    }
                }
            }
        }
    }
}

private fun findQuoteEnd(line: String, start: Int, quote: Char): Int {
    var i = start + 1
    while (i < line.length) {
        if (line[i] == '\\' && i + 1 < line.length) {
            i += 2
            continue
        }
        if (line[i] == quote) return i + 1
        i++
    }
    return line.length
}
