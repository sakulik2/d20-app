package xyz.sakulik.d20.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentParserTest {

    @Test
    fun preservesChinesePunctuationWithoutInjectingSpaces() {
        val document = MarkdownDocumentParser.parse("他说：“**不要动**。”然后转身。")
        val paragraph = document.blocks.single() as MarkdownBlock.Paragraph

        assertEquals(
            listOf(
                MarkdownInline.PlainText("他说：“"),
                MarkdownInline.Strong(listOf(MarkdownInline.PlainText("不要动"))),
                MarkdownInline.PlainText("。”然后转身。")
            ),
            paragraph.content
        )
    }

    @Test
    fun parsesNestedInlineFormattingEscapesAndLinks() {
        val document = MarkdownDocumentParser.parse(
            "**粗体里有 *斜体* 与 `代码`**，\\*不是斜体\\*，[规则](https://example.com/rules)。"
        )
        val paragraph = document.blocks.single() as MarkdownBlock.Paragraph
        val strong = paragraph.content.first() as MarkdownInline.Strong

        assertTrue(strong.children.any { it is MarkdownInline.Emphasis })
        assertTrue(strong.children.any { it is MarkdownInline.InlineCode })
        assertTrue(paragraph.content.any {
            it is MarkdownInline.PlainText && it.value.contains("*不是斜体*")
        })
        assertTrue(paragraph.content.any {
            it is MarkdownInline.Link && it.destination == "https://example.com/rules"
        })
    }

    @Test
    fun parsesHeadingsQuotesListsCodeAndDivider() {
        val markdown = """
            ## 场景

            > 风从门后吹来。

            3. 第三项
            4. 第四项

            ---

            ```kotlin
            val roll = 20
            ```
        """.trimIndent()

        val blocks = MarkdownDocumentParser.parse(markdown).blocks

        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Quote)
        val list = blocks[2] as MarkdownBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.startNumber)
        assertEquals(2, list.items.size)
        assertEquals(MarkdownBlock.Divider, blocks[3])
        assertEquals(
            MarkdownBlock.CodeBlock("val roll = 20", "kotlin"),
            blocks[4]
        )
    }

    @Test
    fun parsesGfmStrikethroughAndTables() {
        val markdown = """
            ~~旧状态~~ **新状态**

            | 目标 | HP |
            | --- | ---: |
            | 地精 | 7 |
        """.trimIndent()

        val blocks = MarkdownDocumentParser.parse(markdown).blocks
        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        val table = blocks[1] as MarkdownBlock.Table

        assertTrue(paragraph.content.first() is MarkdownInline.Strikethrough)
        assertEquals(2, table.header.size)
        assertEquals(1, table.rows.size)
        assertEquals(
            listOf(MarkdownInline.PlainText("地精")),
            table.rows.single().first()
        )
    }

    @Test
    fun allowsOnlyExternalWebAndMailLinks() {
        assertTrue(MarkdownLinkPolicy.isAllowed("https://example.com"))
        assertTrue(MarkdownLinkPolicy.isAllowed("HTTP://example.com"))
        assertTrue(MarkdownLinkPolicy.isAllowed("mailto:gm@example.com"))
        assertFalse(MarkdownLinkPolicy.isAllowed("javascript:alert(1)"))
        assertFalse(MarkdownLinkPolicy.isAllowed("file:///data/data/private"))
        assertFalse(MarkdownLinkPolicy.isAllowed("/relative/path"))
    }

    @Test
    fun keepsIncompleteStreamingMarkdownVisible() {
        val document = MarkdownDocumentParser.parse("故事正在生成：**尚未结束")
        val paragraph = document.blocks.single() as MarkdownBlock.Paragraph

        assertEquals(
            listOf(MarkdownInline.PlainText("故事正在生成：**尚未结束")),
            paragraph.content
        )
    }

    @Test
    fun preservesSoftAndHardLineBreaks() {
        val document = MarkdownDocumentParser.parse("第一行\n第二行  \n第三行")
        val paragraph = document.blocks.single() as MarkdownBlock.Paragraph
        val breaks = paragraph.content.filterIsInstance<MarkdownInline.LineBreak>()

        assertEquals(listOf(false, true), breaks.map { it.hard })
    }
}
