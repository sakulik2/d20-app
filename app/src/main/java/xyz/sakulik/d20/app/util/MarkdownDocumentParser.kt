package xyz.sakulik.d20.app.util

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

data class MarkdownDocument(val blocks: List<MarkdownBlock>)

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class ListBlock(
        val ordered: Boolean,
        val startNumber: Int,
        val items: List<List<MarkdownBlock>>
    ) : MarkdownBlock
    data class CodeBlock(val code: String, val language: String?) : MarkdownBlock
    data object Divider : MarkdownBlock
    data class Table(
        val header: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>
    ) : MarkdownBlock
}

sealed interface MarkdownInline {
    data class PlainText(val value: String) : MarkdownInline
    data class Strong(val children: List<MarkdownInline>) : MarkdownInline
    data class Emphasis(val children: List<MarkdownInline>) : MarkdownInline
    data class Strikethrough(val children: List<MarkdownInline>) : MarkdownInline
    data class InlineCode(val value: String) : MarkdownInline
    data class Link(val destination: String, val children: List<MarkdownInline>) : MarkdownInline
    data class Image(val destination: String, val description: List<MarkdownInline>) : MarkdownInline
    data class LineBreak(val hard: Boolean) : MarkdownInline
}

object MarkdownDocumentParser {
    private val extensions: List<Extension> = listOf(
        StrikethroughExtension.create(),
        TablesExtension.create()
    )
    private val parser = Parser.builder().extensions(extensions).build()

    fun parse(markdown: String): MarkdownDocument {
        if (markdown.isBlank()) return MarkdownDocument(emptyList())
        val root = parser.parse(markdown.replace("\r\n", "\n").replace('\r', '\n'))
        return MarkdownDocument(parseBlocks(root))
    }

    private fun parseBlocks(parent: Node): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        parent.children().forEach { node ->
            when (node) {
                is Heading -> blocks += MarkdownBlock.Heading(node.level, parseInlines(node))
                is Paragraph -> blocks += MarkdownBlock.Paragraph(parseInlines(node))
                is BlockQuote -> blocks += MarkdownBlock.Quote(parseBlocks(node))
                is BulletList -> blocks += parseList(node, ordered = false, startNumber = 1)
                is OrderedList -> blocks += parseList(node, ordered = true, startNumber = node.startNumber)
                is FencedCodeBlock -> blocks += MarkdownBlock.CodeBlock(
                    code = node.literal.trimEnd('\n'),
                    language = node.info.orEmpty().trim().substringBefore(' ').ifBlank { null }
                )
                is IndentedCodeBlock -> blocks += MarkdownBlock.CodeBlock(
                    code = node.literal.trimEnd('\n'),
                    language = null
                )
                is ThematicBreak -> blocks += MarkdownBlock.Divider
                is TableBlock -> blocks += parseTable(node)
                is HtmlBlock -> blocks += MarkdownBlock.CodeBlock(node.literal.trimEnd(), "html")
                is LinkReferenceDefinition -> Unit
                else -> blocks += parseBlocks(node)
            }
        }
        return blocks
    }

    private fun parseList(
        list: Node,
        ordered: Boolean,
        startNumber: Int
    ): MarkdownBlock.ListBlock {
        val items = list.children()
            .filterIsInstance<ListItem>()
            .map { item -> parseBlocks(item) }
            .toList()
        return MarkdownBlock.ListBlock(ordered, startNumber, items)
    }

    private fun parseTable(table: TableBlock): MarkdownBlock.Table {
        var header = emptyList<List<MarkdownInline>>()
        val rows = mutableListOf<List<List<MarkdownInline>>>()
        table.children().forEach { section ->
            when (section) {
                is TableHead -> section.children().filterIsInstance<TableRow>().forEach { row ->
                    if (header.isEmpty()) {
                        header = parseTableRow(row)
                    }
                }
                is TableBody -> section.children().filterIsInstance<TableRow>().forEach { row ->
                    rows.add(parseTableRow(row))
                }
            }
        }
        return MarkdownBlock.Table(header, rows)
    }

    private fun parseTableRow(row: TableRow): List<List<MarkdownInline>> {
        return row.children().filterIsInstance<TableCell>().map(::parseInlines).toList()
    }

    private fun parseInlines(parent: Node): List<MarkdownInline> {
        val inlines = mutableListOf<MarkdownInline>()
        parent.children().forEach { node ->
            when (node) {
                is Text -> inlines += MarkdownInline.PlainText(node.literal)
                is StrongEmphasis -> inlines += MarkdownInline.Strong(parseInlines(node))
                is Emphasis -> inlines += MarkdownInline.Emphasis(parseInlines(node))
                is Strikethrough -> inlines += MarkdownInline.Strikethrough(parseInlines(node))
                is Code -> inlines += MarkdownInline.InlineCode(node.literal)
                is Link -> inlines += MarkdownInline.Link(node.destination, parseInlines(node))
                is Image -> inlines += MarkdownInline.Image(node.destination, parseInlines(node))
                is HardLineBreak -> inlines += MarkdownInline.LineBreak(hard = true)
                is SoftLineBreak -> inlines += MarkdownInline.LineBreak(hard = false)
                is HtmlInline -> inlines += MarkdownInline.PlainText(node.literal)
                else -> inlines += parseInlines(node)
            }
        }
        return inlines
    }
}

object MarkdownLinkPolicy {
    fun isAllowed(destination: String): Boolean {
        val scheme = destination.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme in setOf("http", "https", "mailto")
    }
}

private fun Node.children(): Sequence<Node> = sequence {
    var child = firstChild
    while (child != null) {
        yield(child)
        child = child.next
    }
}
