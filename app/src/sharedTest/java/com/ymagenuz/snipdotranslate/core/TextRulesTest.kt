package com.ymagenuz.snipdotranslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {
    @Test
    fun protectedFragmentsRoundTrip() {
        val source = """\section{Energy}
Einstein wrote ${'$'}E = mc^2${'$'} in Equation~\ref{eq:mass}.
\begin{align}
  F &= ma \\
  E &= mc^2
\end{align}
% Keep this source comment unchanged."""

        val protected = protectLatexFragments(source)

        assertTrue(LATEX_PLACEHOLDER_PREFIX in protected.text)
        assertFalse("\$E = mc^2\$" in protected.text)
        assertFalse("""\ref{eq:mass}""" in protected.text)
        assertTrue("""\section{Energy}""" in protected.text)
        assertEquals(4, protected.replacements.size)
        assertEquals(source, restoreLatexPlaceholders(protected.text, protected.replacements))
    }

    @Test
    fun streamRestorerHandlesPlaceholdersSplitAcrossChunks() {
        val source = "The relation \$E = mc^2\$ is fundamental."
        val protected = protectLatexFragments(source)
        val restorer = LatexStreamRestorer(protected.replacements)

        val restored = protected.text.map { restorer.feed(it.toString()) }.joinToString("") + restorer.finish()

        assertEquals(source, restored)
    }

    @Test
    fun streamRestorerPassesThroughWithoutReplacements() {
        val restorer = LatexStreamRestorer()
        assertEquals("[[SNIPDO", restorer.feed("[[SNIPDO"))
        assertEquals("", restorer.finish())
    }

    @Test
    fun streamRestorerFlushesAnUnfinishedPrefixAtTheEnd() {
        val restorer = LatexStreamRestorer(mapOf("[[SNIPDO_LATEX_0000]]" to "x"))
        assertEquals("abc", restorer.feed("abc[[SNIP"))
        assertEquals("[[SNIP", restorer.finish())
    }

    @Test
    fun veryLongLinesDoNotOverflowTheRegexStack() {
        val long = "\$5 " + "word ".repeat(100_000)
        assertFalse(isLatexText(long))
        assertEquals(long, protectLatexFragments(long).text)
    }

    @Test
    fun incomingTextIsCleanedOrRefused() {
        assertEquals("A wrapped line of text.", incomingText("A wrapped\nline of text."))
        assertEquals("One.\nTwo.", incomingText("One.\n\nTwo."))
        assertEquals(null, incomingText("   \n "))
        assertEquals(null, incomingText(null))
    }

    @Test
    fun percentSignsSurviveNormalization() {
        assertEquals(listOf("Growth was 100%25 this year"), normalizeInputText("Growth was 100%25 this year"))
    }

    @Test
    fun autoDirectionFollowsExplicitLanguages() {
        assertEquals(TranslationMode.ZH_TO_EN, resolveAutoTranslationMode("mixed text", "zh", "default"))
        assertEquals(TranslationMode.EN_TO_ZH, resolveAutoTranslationMode("混合 text", "en", "default"))
        assertEquals(TranslationMode.ZH_TO_EN, resolveAutoTranslationMode("任意文本", "auto", "en"))
        assertEquals(TranslationMode.EN_TO_ZH, resolveAutoTranslationMode("any text", "auto", "zh"))
        assertEquals(TranslationMode.AUTO, resolveAutoTranslationMode("any text", "auto", "ja"))
    }

    @Test
    fun builtPromptsCarryFormatAndProtection() {
        val markdown = buildTranslationPrompt("# Title\n\n- Item", TranslationMode.AUTO).prompt
        assertTrue("请保留标题层级、段落、列表、表格、链接和代码块结构" in markdown)
        assertTrue("翻译成地道的简体中文" in markdown)

        val latex = buildTranslationPrompt("""\section{Energy} see ${'$'}E=mc^2${'$'}""", TranslationMode.AUTO)
        assertFalse("\$E=mc^2\$" in latex.prompt)
        assertEquals(1, latex.replacements.size)
    }

    @Test
    fun dictionaryPromptOmitsFormatInstruction() {
        val prompt = buildTranslationPrompt("# Title", TranslationMode.DICTIONARY).prompt
        assertFalse("格式要求" in prompt)
    }

    @Test
    fun backTranslationSeesOnlyTheTranslation() {
        val prompt = buildBackTranslationPrompt("This is a test sentence.", "简体中文").prompt
        assertTrue("翻译成简体中文" in prompt)
        assertTrue("This is a test sentence." in prompt)
        assertTrue("忠实直译" in prompt)
    }
}
