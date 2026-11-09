package com.ymagenuz.snipdotranslate.select.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionModelTest {
    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        Quad(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom))

    private var nextLine = 0

    /** Lays out words 100 wide with 20 gaps on one line 40 tall. */
    private fun line(block: Int, y: Float, vararg text: String, startX: Float = 0f): List<ScreenWord> {
        val lineId = nextLine++
        return text.mapIndexed { index, word ->
            val left = startX + index * 120f
            ScreenWord(0, word, rect(left, y, left + 100f, y + 40f), lineId, block)
        }
    }

    private fun ordered(vararg lines: List<ScreenWord>) = inReadingOrder(lines.toList().flatten())

    private fun model(words: List<ScreenWord>) = SelectionModel(words, tapTolerance = 20f, dragSlop = 10f)

    private val page: List<ScreenWord> = ordered(
        line(0, 0f, "one", "two", "three"),
        line(0, 60f, "four", "five", "six"),
        line(1, 200f, "seven", "eight", "nine"),
    )

    private fun center(id: Int) = page[id].quad.center

    private fun SelectionModel.tap(pen: Pen, at: Pt) {
        beginStroke(pen, at)
        endStroke()
    }

    private fun SelectionModel.drag(pen: Pen, radius: Float, vararg points: Pt): AreaRect? {
        beginStroke(pen, points.first(), radius)
        points.drop(1).forEach(::extendStroke)
        return endStroke()
    }

    private fun SelectionModel.read() = selectedIds.sorted().map { words[it].text }

    @Test
    fun tappingAWordTogglesIt() {
        val m = model(page)
        m.tap(Pen.TEXT, center(1))
        assertEquals(listOf("two"), m.read())
        m.tap(Pen.TEXT, center(1))
        assertEquals(emptyList<String>(), m.read())
    }

    @Test
    fun tappingAwayFromWordsDoesNothing() {
        val m = model(page)
        m.tap(Pen.TEXT, Pt(5000f, 5000f))
        assertFalse(m.hasSelection)
        assertFalse(m.canUndo)
    }

    @Test
    fun aTapCloseToAWordStillCounts() {
        val m = model(page)
        m.tap(Pen.TEXT, Pt(50f, -15f))
        assertEquals(listOf("one"), m.read())
    }

    @Test
    fun draggingSelectsEverythingBetweenInReadingOrder() {
        val m = model(page)
        m.drag(Pen.TEXT, 0f, center(1), center(4))
        assertEquals(listOf("two", "three", "four", "five"), m.read())
    }

    @Test
    fun draggingBackwardsSelectsTheSameRange() {
        val m = model(page)
        m.drag(Pen.TEXT, 0f, center(4), center(1))
        assertEquals(listOf("two", "three", "four", "five"), m.read())
    }

    @Test
    fun theRangeFollowsTheFingerAndShrinksWhenItReturns() {
        val m = model(page)
        m.beginStroke(Pen.TEXT, center(0))
        m.extendStroke(center(5))
        assertEquals(6, m.selectedIds.size)
        m.extendStroke(center(2))
        assertEquals(listOf("one", "two", "three"), m.read())
        m.endStroke()
    }

    @Test
    fun aNewDragKeepsEarlierSelections() {
        val m = model(page)
        m.tap(Pen.TEXT, center(8))
        m.drag(Pen.TEXT, 0f, center(0), center(1))
        assertEquals(listOf("one", "two", "nine"), m.read())
    }

    @Test
    fun aSmallWobbleIsStillATap() {
        val m = model(page)
        val c = center(1)
        m.drag(Pen.TEXT, 0f, c, Pt(c.x + 3f, c.y + 3f))
        assertEquals(listOf("two"), m.read())
    }

    @Test
    fun betweenLinesTheCloserLineIsChosen() {
        val m = model(page)
        m.drag(Pen.TEXT, 0f, center(0), Pt(50f, 45f))
        assertEquals(listOf("one"), m.read().take(1))
        assertEquals(1, m.selectedIds.size)
    }

    @Test
    fun plusAddsOnlyWordsAlongTheStroke() {
        val m = model(page)
        // Straight down the first column, skipping everything between in reading order.
        m.drag(Pen.PLUS, 10f, center(0), center(3))
        assertEquals(listOf("one", "four"), m.read())
    }

    @Test
    fun aWiderPlusStrokeReachesMoreWords() {
        val m = model(page)
        val narrow = model(page)
        narrow.drag(Pen.PLUS, 5f, Pt(170f, 20f), Pt(170f, 20f))
        assertEquals(listOf("two"), narrow.read())

        // Reaches the row's neighbours and the word just below, but not the diagonals at 80.6.
        val wide = model(page)
        wide.drag(Pen.PLUS, 80f, Pt(170f, 20f), Pt(170f, 20f))
        assertEquals(listOf("one", "two", "three", "five"), wide.read())
    }

    @Test
    fun aFastStrokeDoesNotSkipWordsBetweenSamples() {
        val m = model(page)
        m.drag(Pen.PLUS, 5f, center(0), center(2))
        assertEquals(listOf("one", "two", "three"), m.read())
    }

    @Test
    fun minusRemovesOnlyWordsAlongTheStroke() {
        val m = model(page)
        m.selectAll()
        m.drag(Pen.MINUS, 10f, center(1), center(4))
        assertEquals(listOf("one", "three", "four", "six", "seven", "eight", "nine"), m.read())
    }

    @Test
    fun lassoAddsWordsWhoseCentresAreInside() {
        val m = model(page)
        m.drag(Pen.LASSO, 0f, Pt(-10f, -10f), Pt(230f, -10f), Pt(230f, 110f), Pt(-10f, 110f))
        assertEquals(listOf("one", "two", "four", "five"), m.read())
    }

    @Test
    fun paragraphPenTogglesAWholeBlock() {
        val m = model(page)
        m.tap(Pen.PARAGRAPH, center(7))
        assertEquals(listOf("seven", "eight", "nine"), m.read())
        m.tap(Pen.PARAGRAPH, center(7))
        assertFalse(m.hasSelection)
    }

    @Test
    fun paragraphPenCompletesAPartlySelectedBlock() {
        val m = model(page)
        m.tap(Pen.TEXT, center(6))
        m.tap(Pen.PARAGRAPH, center(7))
        assertEquals(listOf("seven", "eight", "nine"), m.read())
    }

    @Test
    fun areaPenReturnsARectangleWithoutSelecting() {
        val m = model(page)
        val area = m.drag(Pen.AREA, 0f, Pt(300f, 100f), Pt(40f, 20f))
        assertEquals(AreaRect(40f, 20f, 300f, 100f), area)
        assertFalse(m.hasSelection)
        assertFalse(m.canUndo)
    }

    @Test
    fun aTinyAreaIsIgnored() {
        assertNull(model(page).drag(Pen.AREA, 0f, Pt(10f, 10f), Pt(12f, 300f)))
    }

    @Test
    fun undoAndRedoStepThroughStrokes() {
        val m = model(page)
        m.tap(Pen.TEXT, center(0))
        m.tap(Pen.TEXT, center(1))
        assertTrue(m.canUndo)

        m.undo()
        assertEquals(listOf("one"), m.read())
        assertTrue(m.canRedo)
        m.undo()
        assertFalse(m.hasSelection)
        m.redo()
        m.redo()
        assertEquals(listOf("one", "two"), m.read())
    }

    @Test
    fun aNewStrokeClearsRedo() {
        val m = model(page)
        m.tap(Pen.TEXT, center(0))
        m.undo()
        m.tap(Pen.TEXT, center(1))
        assertFalse(m.canRedo)
    }

    @Test
    fun selectAllAndClearCanBeUndone() {
        val m = model(page)
        m.selectAll()
        assertEquals(page.size, m.selectedIds.size)
        m.clear()
        assertFalse(m.hasSelection)
        m.undo()
        assertEquals(page.size, m.selectedIds.size)
    }

    @Test
    fun cancellingRestoresTheSelection() {
        val m = model(page)
        m.tap(Pen.TEXT, center(0))
        m.beginStroke(Pen.TEXT, center(3))
        m.extendStroke(center(8))
        m.cancelStroke()
        assertEquals(listOf("one"), m.read())
    }

    @Test
    fun readingOrderIsTheDefaultOutput() {
        val m = model(page)
        m.drag(Pen.PLUS, 5f, center(3), center(0))
        assertEquals("one\nfour", m.selectedText(WordOrder.READING).replace(" ", "\n"))
    }

    @Test
    fun selectionOrderFollowsTheStroke() {
        val m = model(page)
        m.drag(Pen.PLUS, 5f, center(3), center(0))
        assertEquals(listOf("four", "one"), m.selectedText(WordOrder.SELECTION).split(" "))
    }

    @Test
    fun readingOrderFollowsTextColumns() {
        val left = line(0, 0f, "a1", startX = 0f) + line(0, 60f, "a2", startX = 0f)
        val right = line(1, 0f, "b1", startX = 400f) + line(1, 60f, "b2", startX = 400f)
        assertEquals(listOf("a1", "a2", "b1", "b2"), inReadingOrder(right + left).map { it.text })
    }

    @Test
    fun aHeadingAboveColumnsComesFirst() {
        val heading = line(9, -100f, "title", startX = 0f)
        val left = line(0, 0f, "a1") + line(0, 60f, "a2")
        val right = line(1, 0f, "b1", startX = 400f) + line(1, 60f, "b2", startX = 400f)
        assertEquals("title", inReadingOrder(right + left + heading).first().text)
    }

    @Test
    fun singleLineRowsReadAcrossNotDown() {
        val rows = line(0, 0f, "WiFi") + line(1, 0f, "On", startX = 800f) +
            line(2, 60f, "Bluetooth") + line(3, 60f, "Off", startX = 800f)
        assertEquals(listOf("WiFi", "On", "Bluetooth", "Off"), inReadingOrder(rows).map { it.text })
    }

    @Test
    fun overlappingBlocksStillGetAnOrder() {
        val first = line(0, 0f, "upper", startX = 0f)
        val second = line(1, 20f, "lower", startX = 50f)
        assertEquals(listOf("upper", "lower"), inReadingOrder(second + first).map { it.text })
    }

    @Test
    fun idsMatchReadingPositions() {
        assertEquals(page.indices.toList(), page.map { it.id })
    }

    @Test
    fun anEmptyPageIsHandled() {
        val m = model(ordered())
        m.tap(Pen.TEXT, Pt(0f, 0f))
        m.drag(Pen.TEXT, 0f, Pt(0f, 0f), Pt(300f, 300f))
        m.selectAll()
        assertFalse(m.hasSelection)
        assertEquals("", m.selectedText(WordOrder.READING))
    }

    @Test
    fun rotatedTextIsHitOnItsRealShape() {
        val tilted = Quad(Pt(0f, 100f), Pt(100f, 0f), Pt(130f, 30f), Pt(30f, 130f))
        assertTrue(tilted.contains(Pt(65f, 65f)))
        assertFalse(tilted.contains(Pt(5f, 5f)))
        assertNotNull(tilted.center)
    }

    private fun word(id: Int, text: String, line: Int = 0, block: Int = 0) =
        ScreenWord(id, text, rect(0f, 0f, 1f, 1f), line, block)

    @Test
    fun latinWordsAreSpaced() {
        assertEquals("hello brave world", assembleText(listOf(word(0, "hello"), word(1, "brave"), word(2, "world"))))
    }

    @Test
    fun chineseWordsAreNotSpaced() {
        assertEquals("人工智能，翻译", assembleText(listOf(word(0, "人工"), word(1, "智能"), word(2, ","), word(3, "翻译")).map {
            if (it.text == ",") it.copy(text = "，") else it
        }))
    }

    @Test
    fun mixedTextKeepsSpacesAroundLatinWords() {
        assertEquals("使用 Kotlin 编写", assembleText(listOf(word(0, "使用"), word(1, "Kotlin"), word(2, "编写"))))
    }

    @Test
    fun wrappedChineseLinesJoinDirectly() {
        assertEquals("翻译软件", assembleText(listOf(word(0, "翻译", line = 0), word(1, "软件", line = 1))))
    }

    @Test
    fun wrappedLatinLinesJoinWithASpace() {
        assertEquals("one two", assembleText(listOf(word(0, "one", line = 0), word(1, "two", line = 1))))
    }

    @Test
    fun hyphenatedLinesAreRepaired() {
        assertEquals("example text", assembleText(listOf(word(0, "exam-", line = 0), word(1, "ple", line = 1), word(2, "text", line = 1))))
    }

    @Test
    fun blocksBecomeSeparateParagraphs() {
        assertEquals("One.\nTwo.", assembleText(listOf(word(0, "One.", block = 0), word(5, "Two.", line = 1, block = 1))))
    }

    @Test
    fun aSingleWordLosesEdgePunctuationSoItCanBeLookedUp() {
        assertEquals("dog", assembleText(listOf(word(0, "dog."))))
        assertEquals("print", assembleText(listOf(word(0, "print:"))))
        assertEquals("don't", assembleText(listOf(word(0, "\"don't\""))))
        assertEquals("...", assembleText(listOf(word(0, "..."))))
    }

    @Test
    fun separatedChineseFragmentsDoNotRunTogether() {
        assertEquals("翻译 软件", assembleText(listOf(word(0, "翻译"), word(4, "软件"))))
    }
}
