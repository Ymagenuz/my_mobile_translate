package com.ymagenuz.snipdotranslate.select.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WordBuilderTest {
    private fun box(left: Float, right: Float) =
        Quad(Pt(left, 0f), Pt(right, 0f), Pt(right, 10f), Pt(left, 10f))

    private fun element(text: String, left: Float, right: Float, symbols: List<RawSymbol> = emptyList()) =
        RawElement(text, box(left, right), symbols)

    private fun chars(text: String, startX: Float) = text.mapIndexed { index, char ->
        RawSymbol(char.toString(), box(startX + index * 10f, startX + (index + 1) * 10f))
    }

    @Test
    fun spacedWordsKeepTheirElementBoxes() {
        val blocks = listOf(RawBlock(listOf(RawLine(listOf(element("Hello", 0f, 50f), element("world", 60f, 110f))))))

        val words = buildWords(blocks) { error("spaced text is not segmented") }

        assertEquals(listOf("Hello", "world"), words.map { it.text })
        assertEquals(box(60f, 110f), words[1].quad)
    }

    @Test
    fun chineseRunsAreSplitBySegmenterAndSpanTheirCharacters() {
        val symbols = chars("翻译软件", 0f)
        val blocks = listOf(RawBlock(listOf(RawLine(listOf(element("翻译软件", 0f, 40f, symbols))))))

        val words = buildWords(blocks) { listOf(0, 2, 4) }

        assertEquals(listOf("翻译", "软件"), words.map { it.text })
        assertEquals(box(0f, 20f), words[0].quad)
        assertEquals(box(20f, 40f), words[1].quad)
    }

    @Test
    fun adjacentChineseElementsFormOneRun() {
        val blocks = listOf(
            RawBlock(
                listOf(
                    RawLine(
                        listOf(
                            element("翻", 0f, 10f, chars("翻", 0f)),
                            element("译", 10f, 20f, chars("译", 10f)),
                        ),
                    ),
                ),
            ),
        )

        val words = buildWords(blocks) { listOf(0, 2) }

        assertEquals(listOf("翻译"), words.map { it.text })
        assertEquals(box(0f, 20f), words[0].quad)
    }

    @Test
    fun mixedLinesKeepVisualOrder() {
        val blocks = listOf(
            RawBlock(
                listOf(
                    RawLine(
                        listOf(
                            element("使用", 0f, 20f, chars("使用", 0f)),
                            element("Kotlin", 30f, 90f),
                            element("编写", 100f, 120f, chars("编写", 100f)),
                        ),
                    ),
                ),
            ),
        )

        val words = buildWords(blocks) { text -> listOf(0, text.length) }

        assertEquals(listOf("使用", "Kotlin", "编写"), words.map { it.text })
        assertEquals(listOf(0, 1, 2), words.map { it.id })
    }

    @Test
    fun linesAndBlocksAreNumbered() {
        val line = RawLine(listOf(element("a", 0f, 10f)))
        val blocks = listOf(RawBlock(listOf(line, line)), RawBlock(listOf(line)))

        val words = buildWords(blocks) { error("not used") }

        assertEquals(listOf(0, 1, 2), words.map { it.lineId })
        assertEquals(listOf(0, 0, 1), words.map { it.blockId })
    }
}
