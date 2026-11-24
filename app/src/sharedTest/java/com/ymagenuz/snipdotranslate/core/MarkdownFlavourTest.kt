package com.ymagenuz.snipdotranslate.core

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** The desktop's tests for bold beside CJK punctuation (tests/test_markdown_emphasis.py in 13.3.1), on this app's parser. */
class MarkdownFlavourTest {
    @Test
    fun emphasisBesideCjkPunctuationIsFormatted() {
        assertShown(
            "**注意：**请勿关闭" to "[B:注意：]请勿关闭",
            "这是**“引号”**文字" to "这是[B:“引号”]文字",
            "这是**粗体，**文字" to "这是[B:粗体，]文字",
            "请**务必（重要）**保存" to "请[B:务必（重要）]保存",
            "这是**《书名》**的内容" to "这是[B:《书名》]的内容",
            "日本語の**「太字」**です" to "日本語の[B:「太字」]です",
            "한국어 **「굵게」**입니다" to "한국어 [B:「굵게」]입니다",
            "**Note：**Do this" to "[B:Note：]Do this",
            "这是*“斜体”*文字" to "这是[I:“斜体”]文字",
            "***“粗斜”***文字" to "[IB:“粗斜”]文字",
            "这是~~删除~~文字" to "这是[S:删除]文字",
            "这是~~“删除”~~文字" to "这是[S:“删除”]文字",
        )
    }

    @Test
    fun aPairBesideCjkPunctuationNoLongerStealsTheNextOne() {
        // GitHub's rules alone bolded "文字和" and left both pairs as marks.
        assertShown(
            "这是**“引号”**文字和**粗体**" to "这是[B:“引号”]文字和[B:粗体]",
            "**注意：**请勿关闭，**务必**保存" to "[B:注意：]请勿关闭，[B:务必]保存",
        )
    }

    @Test
    fun emphasisBesideCjkPunctuationWorksInsideBlocks() {
        assertShown(
            "- **步骤一：**打开\n- **步骤二：**保存" to "- [B:步骤一：]打开 / - [B:步骤二：]保存",
            "1. **第一：**打开" to "1. [B:第一：]打开",
            "> **提示：**这是引用" to "> [B:提示：]这是引用",
            "## **标题：**内容" to "## [B:标题：]内容",
            "| 名称 | 说明 |\n| --- | --- |\n| **键：**值 | 普通 |" to "| 名称 | 说明 | / | --- | --- | / | [B:键：]值 | 普通 |",
            "见[链接](https://a.com/x_y)和**“粗体”**" to "见[链接](https://a.com/x_y)和[B:“粗体”]",
            "访问https://a.com/a_b_c，**注意：**大小写" to "访问https://a.com/a_b_c，[B:注意：]大小写",
        )
    }

    @Test
    fun textThatReadsTheSameEitherWayIsLeftAlone() {
        assertShown(
            "周一~周五，上午9点~下午6点" to "周一~周五，上午9点~下午6点",
            "用__下划线__加粗" to "用__下划线__加粗",
            "word**(bold)**word" to "word**(bold)**word",
            "未闭合的**“引号" to "未闭合的**“引号",
            "代码 `**“不是”**` 和**“是”**的" to "代码 `**“不是”**` 和[B:“是”]的",
            "```\n**“代码”**中文\n```" to "```\n**“代码”**中文\n```".replace("\n", " / "),
            "a **b** c *d* e" to "a [B:b] c [I:d] e",
            "use __bold__ and _it_ here" to "use [B:bold] and [I:it] here",
            "这是**粗体**文字" to "这是[B:粗体]文字",
        )
    }

    @Test
    fun textWithoutCjkBesideItsMarksParsesAsGitHubDoes() {
        val samples = listOf(
            "**bold** and *it* and ~~gone~~",
            "word**(bold)**word and **\"quoted\"**text",
            "snake_case_name and __init__ and _a_",
            "- **Step:** open\n> *quote*\n\n| a | **b** |\n| - | - |\n| `c*d` | [e](https://x.y/*z*) |",
            "***triple*** and **nested *mix* here** and *a **b** c*",
            "这是 **“引号”** 文字", // spaces around the marks: GitHub already reads this
            "2*3 和 4*5", // digits beside the marks, so GitHub's reading, which makes "3 和 4" italic
        )
        for (sample in samples) {
            assertEquals(sample, tree(sample, GFMFlavourDescriptor()), tree(sample, CjkFriendlyFlavour))
        }
    }

    private fun assertShown(vararg cases: Pair<String, String>) {
        val wrong = cases.mapNotNull { (text, expected) ->
            val actual = shown(text)
            if (actual == expected) null else "$text\n  expected: $expected\n  shown:    $actual"
        }
        assertEquals("", wrong.joinToString("\n"))
    }

    /** The source with each emphasised run tagged and its marks taken out: [B:...] bold, [I:...] italic, [S:...] struck. */
    private fun shown(text: String): String {
        val root = parse(text, CjkFriendlyFlavour)
        return buildString { appendShown(root, text, this, tags = "") }.replace("\n", " / ")
    }

    private fun appendShown(node: ASTNode, text: String, out: StringBuilder, tags: String) {
        val tag = when (node.type) {
            MarkdownElementTypes.STRONG -> "B"
            MarkdownElementTypes.EMPH -> "I"
            GFMElementTypes.STRIKETHROUGH -> "S"
            else -> null
        }
        if (node.children.isEmpty()) {
            out.append(text, node.startOffset, node.endOffset)
            return
        }
        val inner = node.children.filterNot { tag != null && it.type in MARKS }
        when {
            tag == null -> inner.forEach { appendShown(it, text, out, tags) }
            // Nested emphasis that spans the whole run is shown as one tag, as [IB:...].
            tags.isEmpty() -> {
                val content = StringBuilder()
                val allTags = tag + nestedTags(inner)
                inner.forEach { appendShown(it, text, content, allTags) }
                out.append('[').append(allTags).append(':').append(content).append(']')
            }
            else -> inner.forEach { appendShown(it, text, out, tags) }
        }
    }

    private fun nestedTags(children: List<ASTNode>): String {
        val only = children.singleOrNull() ?: return ""
        val tag = when (only.type) {
            MarkdownElementTypes.STRONG -> "B"
            MarkdownElementTypes.EMPH -> "I"
            GFMElementTypes.STRIKETHROUGH -> "S"
            else -> return ""
        }
        return tag + nestedTags(only.children.filterNot { it.type in MARKS })
    }

    /** The tree as types and ranges, to compare two flavours' readings of the same text. */
    private fun tree(text: String, flavour: MarkdownFlavourDescriptor): String {
        fun describe(node: ASTNode): String =
            "${node.type}(${node.startOffset},${node.endOffset})" +
                node.children.joinToString(",", "[", "]") { describe(it) }
        return describe(parse(text, flavour))
    }

    private fun parse(text: CharSequence, flavour: MarkdownFlavourDescriptor): ASTNode =
        MarkdownParser(flavour, cancellationToken = CancellationToken.NonCancellable).buildMarkdownTreeFromString(text)

    private companion object {
        val MARKS = setOf(MarkdownTokenTypes.EMPH, GFMTokenTypes.TILDE)
    }
}
