package xyz.sakulik.d20.app.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.ClickableText
import xyz.sakulik.d20.app.ui.theme.TRPGTheme
import xyz.sakulik.d20.app.util.MarkdownBlock
import xyz.sakulik.d20.app.util.MarkdownDocumentParser
import xyz.sakulik.d20.app.util.MarkdownInline
import xyz.sakulik.d20.app.util.MarkdownLinkPolicy

private const val LINK_ANNOTATION = "markdown-link"

@Composable
fun RichMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = TRPGTheme.colors.onNarrativeSurface
) {
    val document = remember(markdown) { MarkdownDocumentParser.parse(markdown) }
    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            document.blocks.forEach { block ->
                MarkdownBlockView(block = block, baseStyle = style, color = color)
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    baseStyle: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    when (block) {
        is MarkdownBlock.Heading -> InlineMarkdownText(
            content = block.content,
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                3 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            }.copy(fontWeight = FontWeight.Bold),
            color = color,
            modifier = modifier.fillMaxWidth()
        )

        is MarkdownBlock.Paragraph -> InlineMarkdownText(
            content = block.content,
            style = baseStyle,
            color = color,
            modifier = modifier.fillMaxWidth()
        )

        is MarkdownBlock.Quote -> Row(
            modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)
        ) {
            Surface(
                modifier = Modifier.width(4.dp).fillMaxHeight().heightIn(min = 24.dp),
                color = TRPGTheme.colors.primaryAccent,
                shape = RoundedCornerShape(2.dp)
            ) {}
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                block.blocks.forEach { nested ->
                    MarkdownBlockView(
                        block = nested,
                        baseStyle = baseStyle.copy(fontStyle = FontStyle.Italic),
                        color = color.copy(alpha = 0.82f)
                    )
                }
            }
        }

        is MarkdownBlock.ListBlock -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            block.items.forEachIndexed { index, itemBlocks ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (block.ordered) "${block.startNumber + index}." else "•",
                        style = baseStyle,
                        color = TRPGTheme.colors.primaryAccent,
                        modifier = Modifier.width(28.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemBlocks.forEach { nested ->
                            MarkdownBlockView(nested, baseStyle, color)
                        }
                    }
                }
            }
        }

        is MarkdownBlock.CodeBlock -> Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = color.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.14f))
        ) {
            Column {
                block.language?.let { language ->
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = TRPGTheme.colors.primaryAccent,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp)
                    )
                }
                Text(
                    text = block.code,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    ),
                    color = color,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        }

        MarkdownBlock.Divider -> HorizontalDivider(
            modifier = modifier.padding(vertical = 4.dp),
            color = TRPGTheme.colors.dividerColor.copy(alpha = 0.45f)
        )

        is MarkdownBlock.Table -> MarkdownTable(block, baseStyle, color, modifier)
    }
}

@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    baseStyle: TextStyle,
    color: Color,
    modifier: Modifier
) {
    val rows = listOf(table.header) + table.rows
    Box(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column {
            rows.filter { it.isNotEmpty() }.forEachIndexed { rowIndex, row ->
                Row {
                    row.forEach { cell ->
                        Surface(
                            color = if (rowIndex == 0) {
                                TRPGTheme.colors.primaryAccent.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            },
                            border = BorderStroke(0.5.dp, color.copy(alpha = 0.18f)),
                            modifier = Modifier.width(160.dp)
                        ) {
                            InlineMarkdownText(
                                content = cell,
                                style = baseStyle.copy(
                                    fontWeight = if (rowIndex == 0) FontWeight.Bold else null
                                ),
                                color = color,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineMarkdownText(
    content: List<MarkdownInline>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val linkColor = TRPGTheme.colors.primaryAccent
    val annotated = remember(content, color, linkColor) {
        buildAnnotatedString {
            appendInlineNodes(content, color, linkColor)
        }
    }
    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = annotated,
        style = style.copy(color = color),
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations(LINK_ANNOTATION, offset, offset)
                .firstOrNull()
                ?.item
                ?.takeIf(MarkdownLinkPolicy::isAllowed)
                ?.let { destination -> runCatching { uriHandler.openUri(destination) } }
        }
    )
}

private fun AnnotatedString.Builder.appendInlineNodes(
    nodes: List<MarkdownInline>,
    color: Color,
    linkColor: Color
) {
    nodes.forEach { node ->
        when (node) {
            is MarkdownInline.PlainText -> append(node.value)
            is MarkdownInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlineNodes(node.children, color, linkColor)
            }
            is MarkdownInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlineNodes(node.children, color, linkColor)
            }
            is MarkdownInline.Strikethrough -> withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough)
            ) {
                appendInlineNodes(node.children, color, linkColor)
            }
            is MarkdownInline.InlineCode -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = color.copy(alpha = 0.10f)
                )
            ) {
                append(" ${node.value} ")
            }
            is MarkdownInline.Link -> appendLink(
                destination = node.destination,
                label = node.children,
                color = color,
                linkColor = linkColor
            )
            is MarkdownInline.Image -> {
                append("🖼 ")
                appendLink(
                    destination = node.destination,
                    label = node.description.ifEmpty {
                        listOf(MarkdownInline.PlainText("图片"))
                    },
                    color = color,
                    linkColor = linkColor
                )
            }
            is MarkdownInline.LineBreak -> append('\n')
        }
    }
}

private fun AnnotatedString.Builder.appendLink(
    destination: String,
    label: List<MarkdownInline>,
    color: Color,
    linkColor: Color
) {
    if (!MarkdownLinkPolicy.isAllowed(destination)) {
        appendInlineNodes(label, color, linkColor)
        return
    }
    pushStringAnnotation(LINK_ANNOTATION, destination)
    withStyle(
        SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline
        )
    ) {
        appendInlineNodes(label, color, linkColor)
    }
    pop()
}
