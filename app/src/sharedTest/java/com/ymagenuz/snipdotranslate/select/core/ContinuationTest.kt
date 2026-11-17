package com.ymagenuz.snipdotranslate.select.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuationTest {
    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        Quad(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom))

    private var nextLine = 0

    /** Lays out words 100 wide with 20 gaps on one line 40 tall. */
    private fun line(block: Int, y: Float, vararg text: String): List<ScreenWord> {
        val lineId = nextLine++
        return text.mapIndexed { index, word ->
            val left = index * 120f
            ScreenWord(0, word, rect(left, y, left + 100f, y + 40f), lineId, block)
        }
    }

    private fun screen(vararg lines: List<ScreenWord>) = inReadingOrder(lines.toList().flatten())

    /** The words of a screen's lines, by their position from the top. */
    private fun List<ScreenWord>.lines(vararg positions: Int): List<ScreenWord> {
        val ids = map { it.lineId }.distinct()
        return filter { word -> positions.any { ids[it] == word.lineId } }
    }

    private fun List<ScreenWord>.ids() = map { it.id }.toSet()

    /** Keeps a first screen's words; by default that screen ends just below them. */
    private fun keep(words: List<ScreenWord>, screenBottom: Float = words.maxOf { it.quad.bottom } + 4f) =
        Collected.EMPTY.plus(words, Junction.NONE, screenBottom)

    /** Adds a later screen's words, well above its bottom edge. */
    private fun Collected.add(words: List<ScreenWord>, junction: Junction) = plus(words, junction, screenBottom = 2000f)

    private val firstScreen = screen(
        line(0, 0f, "The", "story", "begins", "here"),
        line(0, 60f, "and", "it", "carries", "on"),
        line(0, 120f, "past", "the", "bottom", "edge"),
    )

    /** The same paragraph scrolled up by two lines. */
    private val secondScreen = screen(
        line(0, 0f, "and", "it", "carries", "on"),
        line(0, 60f, "past", "the", "bottom", "edge"),
        line(0, 120f, "until", "it", "ends."),
        line(1, 300f, "A", "new", "paragraph"),
    )

    /** How the whole paragraph reads when it fits on one screen. */
    private val wholeParagraph = assembleText(
        screen(
            line(0, 0f, "The", "story", "begins", "here"),
            line(0, 60f, "and", "it", "carries", "on"),
            line(0, 120f, "past", "the", "bottom", "edge"),
            line(0, 180f, "until", "it", "ends."),
        ),
    )

    @Test
    fun linesRepeatedFromTheLastScreenAreCarried() {
        val kept = keep(firstScreen)
        val junction = kept.junctionWith(secondScreen)

        assertEquals(secondScreen.lines(0, 1).ids(), junction.carried)
        assertTrue(junction.replacement.isEmpty())
        assertTrue(junction.found)
    }

    @Test
    fun aParagraphRunningAcrossScreensReadsAsOne() {
        val kept = keep(firstScreen)
        val junction = kept.junctionWith(secondScreen)
        val all = kept.add(secondScreen.lines(2), junction)

        assertEquals(wholeParagraph, all.text)
        assertEquals(2, all.parts)
    }

    @Test
    fun aLineCutOffByTheEdgeIsReplacedByTheWholeLine() {
        val cut = screen(
            line(0, 0f, "The", "story", "begins", "here"),
            line(0, 60f, "and", "it", "carries", "on"),
            // The bottom half of the line was off the screen.
            line(0, 120f, "pa5t", "tke"),
        )
        val kept = keep(cut)
        val junction = kept.junctionWith(secondScreen)

        assertEquals(secondScreen.lines(0).ids(), junction.carried)
        assertEquals(secondScreen.lines(1).ids(), junction.replacement)
        assertEquals(wholeParagraph, kept.add(secondScreen.lines(1, 2), junction).text)
    }

    @Test
    fun aMissingLineAwayFromTheEdgeIsntReplaced() {
        val cut = screen(
            line(0, 0f, "The", "story", "begins", "here"),
            line(0, 60f, "and", "it", "carries", "on"),
            line(0, 120f, "pa5t", "tke"),
        )
        val junction = keep(cut, screenBottom = 1000f).junctionWith(secondScreen)

        assertTrue(junction.replacement.isEmpty())
    }

    @Test
    fun theCutOffLineStaysWhenItsReplacementIsUnselected() {
        val cut = screen(
            line(0, 0f, "The", "story", "begins", "here"),
            line(0, 60f, "and", "it", "carries", "on"),
            line(0, 120f, "pa5t", "tke"),
        )
        val kept = keep(cut)
        val junction = kept.junctionWith(secondScreen)

        assertTrue("pa5t" in kept.add(secondScreen.lines(2), junction).text)
    }

    @Test
    fun aLineScannedSlightlyDifferentlyStillMatches() {
        val kept = keep(firstScreen)
        val misread = screen(
            line(0, 0f, "and", "it", "carries", "0n"),
            line(0, 60f, "past", "the", "bottom", "edge"),
            line(0, 120f, "until", "it", "ends."),
        )

        assertEquals(misread.lines(0, 1).ids(), kept.junctionWith(misread).carried)
    }

    @Test
    fun linesAboveOneReadDifferentlyAreCarriedToo() {
        val kept = keep(
            screen(
                line(0, 0f, "The", "story", "begins", "here"),
                line(0, 60f, "and", "it", "carries", "on"),
                line(0, 120f, "past", "the", "bottom", "edge"),
            ),
        )
        val misread = screen(
            line(0, 0f, "The", "story", "begins", "here"),
            // Read quite differently here, as when something on the screen got in the way.
            line(0, 60f, "anb", "1t", "carr", "ies", "orn", "⋮"),
            line(0, 120f, "past", "the", "bottom", "edge"),
            line(0, 180f, "until", "it", "ends."),
        )

        assertEquals(misread.lines(0, 1, 2).ids(), kept.junctionWith(misread).carried)
    }

    @Test
    fun aMisreadLineWithNothingRepeatedAboveIsntCarried() {
        val kept = keep(
            screen(
                line(0, 0f, "Something", "unrelated", "entirely"),
                line(0, 60f, "past", "the", "bottom", "edge"),
            ),
        )
        val other = screen(
            line(0, 0f, "A", "different", "first", "line"),
            line(0, 60f, "past", "the", "bottom", "edge"),
        )

        assertEquals(other.lines(1).ids(), kept.junctionWith(other).carried)
    }

    @Test
    fun anUnrelatedScreenStartsANewParagraph() {
        val kept = keep(firstScreen)
        val other = screen(line(0, 0f, "Something", "completely", "different"))
        val junction = kept.junctionWith(other)

        assertFalse(junction.found)
        assertTrue(junction.carried.isEmpty())
        val separate = assembleText(
            screen(
                line(0, 0f, "The", "story", "begins", "here"),
                line(0, 60f, "and", "it", "carries", "on"),
                line(0, 120f, "past", "the", "bottom", "edge"),
                line(1, 300f, "Something", "completely", "different"),
            ),
        )
        assertEquals(separate, kept.add(other, junction).text)
    }

    @Test
    fun shortLinesDontMatchByChance() {
        val kept = keep(screen(line(0, 0f, "OK")))
        val other = screen(line(0, 0f, "OK"), line(0, 60f, "Next", "message"))

        assertFalse(kept.junctionWith(other).found)
    }

    @Test
    fun theMatchMustIncludeTheLastLineKept() {
        // The first line repeats, but what was kept last isn't on the new screen, and it
        // wasn't at the bottom edge, so it wasn't cut off either.
        val kept = keep(firstScreen.lines(0, 2), screenBottom = 1000f)
        val other = screen(
            line(0, 0f, "The", "story", "begins", "here"),
            line(0, 60f, "an", "unrelated", "line"),
        )

        assertTrue(kept.junctionWith(other).carried.isEmpty())
    }

    @Test
    fun chineseRunningAcrossScreensJoinsWithoutASpace() {
        val first = screen(
            line(0, 0f, "人工智能", "正在", "改变"),
            line(0, 60f, "我们", "学习", "语言", "的"),
        )
        val second = screen(
            line(0, 0f, "我们", "学习", "语言", "的"),
            line(0, 60f, "方式", "。"),
        )
        val kept = keep(first)
        val junction = kept.junctionWith(second)

        assertEquals("人工智能正在改变我们学习语言的方式。", kept.add(second.lines(1), junction).text)
    }

    @Test
    fun nothingSelectedOnTheNewScreenKeepsWhatWasCollected() {
        val kept = keep(firstScreen)

        assertSame(kept, kept.add(emptyList(), kept.junctionWith(secondScreen)))
    }

    @Test
    fun aThirdScreenCarriesOnFromTheSecond() {
        val kept = keep(firstScreen)
        val two = kept.add(secondScreen.lines(2), kept.junctionWith(secondScreen))
        val third = screen(
            line(0, 0f, "until", "it", "ends."),
            line(1, 200f, "A", "new", "paragraph"),
        )
        val junction = two.junctionWith(third)

        assertEquals(third.lines(0).ids(), junction.carried)
        val three = two.add(third.lines(1), junction)
        assertEquals(3, three.parts)
        assertEquals("$wholeParagraph\nA new paragraph", three.text)
    }

    @Test
    fun keptWordsAreFoundWhereTheScrollingTookThem() {
        // The kept words were on the first screen's last two lines, then scrolled up by 60.
        val kept = firstScreen.lines(1, 2).map { word ->
            val q = word.quad
            Quad(
                Pt(q.topLeft.x, q.topLeft.y - 60f),
                Pt(q.topRight.x, q.topRight.y - 60f),
                Pt(q.bottomRight.x, q.bottomRight.y - 60f),
                Pt(q.bottomLeft.x, q.bottomLeft.y - 60f),
            )
        }
        val scrolled = screen(
            line(0, 0f, "and", "it", "carries", "on"),
            line(0, 60f, "past", "the", "bottom", "edge"),
            line(0, 120f, "until", "it", "ends."),
        )
        val junction = keptByPosition(scrolled, kept)

        assertEquals(scrolled.lines(0, 1).ids(), junction.carried)
        assertTrue(junction.found)
        assertEquals(wholeParagraph, keep(firstScreen).add(scrolled.lines(2), junction).text)
    }

    private fun Quad.movedBy(dy: Float) =
        Quad(Pt(topLeft.x, topLeft.y + dy), Pt(topRight.x, topRight.y + dy), Pt(bottomRight.x, bottomRight.y + dy), Pt(bottomLeft.x, bottomLeft.y + dy))

    @Test
    fun amongRepeatedLinesTheFollowedPlaceWins() {
        val first = screen(
            line(0, 0f, "Android", "license"),
            line(0, 60f, "Android", "license"),
            line(0, 120f, "Android", "license"),
        )
        // The last one was kept, then scrolled up by 60, so it's the second line now.
        val kept = keep(first.lines(2))
        val places = first.lines(2).map { it.quad.movedBy(-60f) }
        val scrolled = screen(
            line(0, 0f, "Android", "license"),
            line(0, 60f, "Android", "license"),
            line(0, 120f, "Android", "license"),
        )

        assertEquals(scrolled.lines(0).ids(), kept.junctionWith(scrolled).carried)
        assertEquals(scrolled.lines(1).ids(), kept.junctionWith(scrolled, places).carried)
    }

    @Test
    fun whereTextAndPlaceAgreeTheTextStillReplacesACutLine() {
        val cut = screen(
            line(0, 0f, "The", "story", "begins", "here"),
            line(0, 60f, "and", "it", "carries", "on"),
            line(0, 120f, "pa5t", "tke"),
        )
        // Scrolled up by 60: the second line is now at the top.
        val places = cut.map { it.quad.movedBy(-60f) }
        val junction = keep(cut).junctionWith(secondScreen, places)

        assertEquals(secondScreen.lines(1).ids(), junction.replacement)
    }

    @Test
    fun nothingUnderTheKeptPlacesFindsNothing() {
        val far = listOf(Quad(Pt(0f, 900f), Pt(100f, 900f), Pt(100f, 940f), Pt(0f, 940f)))

        assertFalse(keptByPosition(secondScreen, far).found)
    }

    @Test
    fun lockedWordsCantBeSelected() {
        val locked = secondScreen.lines(0).ids()
        val model = SelectionModel(secondScreen, tapTolerance = 20f, dragSlop = 10f, locked = locked)

        model.selectAll()
        assertTrue(model.selectedIds.none { it in locked })
        model.clear()
        val word = secondScreen.first()
        model.beginStroke(Pen.TEXT, word.quad.center)
        model.endStroke()
        assertFalse(model.hasSelection)
    }

    @Test
    fun initialWordsStartSelectedWithoutAnUndoStep() {
        val start = secondScreen.lines(1).ids()
        val model = SelectionModel(secondScreen, tapTolerance = 20f, dragSlop = 10f, initial = start)

        assertEquals(start, model.selectedIds)
        assertFalse(model.canUndo)
    }
}
