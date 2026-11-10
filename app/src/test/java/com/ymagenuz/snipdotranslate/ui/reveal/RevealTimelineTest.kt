package com.ymagenuz.snipdotranslate.ui.reveal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MS = 1_000_000L

class RevealTimelineTest {
    private fun veils(timeline: RevealTimeline, now: Long): Map<Int, Float> {
        val result = linkedMapOf<Int, Float>()
        timeline.forEachVeiled(now) { index, veil -> result[index] = veil }
        return result
    }

    @Test
    fun newCharactersStartHiddenAndFadeInOneAfterAnother() {
        val timeline = RevealTimeline()
        timeline.note(5, now = 0, animate = true)

        val start = veils(timeline, 0)
        assertEquals((0 until 5).toList(), start.keys.toList())
        start.values.forEach { assertEquals(1f, it, 0f) }

        // 10 ms apart, as on the desktop: later characters are further behind.
        val later = veils(timeline, 100 * MS)
        assertTrue(later.getValue(0) < later.getValue(4))
        assertTrue(later.getValue(4) < 1f)
    }

    @Test
    fun everythingIsShownOnceTheLastCharacterHasFaded() {
        val timeline = RevealTimeline()
        timeline.note(5, now = 0, animate = true)
        // The last starts 40 ms in and takes 300 ms.
        assertTrue(timeline.isActive(339 * MS))
        assertTrue(veils(timeline, 340 * MS).isEmpty())
        assertFalse(timeline.hasVeils(340 * MS))
        // The block's own fade takes 360 ms.
        assertTrue(timeline.isActive(359 * MS))
        assertFalse(timeline.isActive(360 * MS))
    }

    @Test
    fun aLargeArrivalStillStartsItsLastCharacterWithinTheSpread() {
        val timeline = RevealTimeline()
        timeline.note(1000, now = 0, animate = true)
        // 360 ms spread plus the 300 ms fade, rather than 1000 x 10 ms.
        assertTrue(veils(timeline, 659 * MS).containsKey(999))
        assertTrue(veils(timeline, 661 * MS).isEmpty())
    }

    @Test
    fun onlyTheNewPartFadesWhenTextGrows() {
        val timeline = RevealTimeline()
        timeline.note(3, now = 0, animate = true)
        timeline.prune(1000 * MS)
        timeline.note(6, now = 1000 * MS, animate = true)
        assertEquals(listOf(3, 4, 5), veils(timeline, 1000 * MS).keys.toList())
        // The block is already showing, so it doesn't fade again.
        assertEquals(1f, timeline.blockAlpha(1000 * MS), 0f)
    }

    @Test
    fun aNewBlockFadesInWithItsFirstCharacters() {
        val timeline = RevealTimeline()
        timeline.note(4, now = 0, animate = true)
        assertEquals(0f, timeline.blockAlpha(0), 0f)
        val halfway = timeline.blockAlpha(180 * MS)
        assertTrue(halfway > 0.5f && halfway < 1f)
        assertEquals(1f, timeline.blockAlpha(360 * MS), 0f)
    }

    @Test
    fun textThatIsNotStreamedShowsAtOnce() {
        val timeline = RevealTimeline()
        timeline.note(10, now = 0, animate = false)
        assertTrue(veils(timeline, 0).isEmpty())
        assertEquals(1f, timeline.blockAlpha(0), 0f)
        assertFalse(timeline.isActive(0))
    }

    @Test
    fun shrinkingTextDropsFadesBeyondItsEnd() {
        val timeline = RevealTimeline()
        timeline.note(4, now = 0, animate = true)
        timeline.note(8, now = 50 * MS, animate = true)
        // A re-render tidied the text, such as markup characters disappearing.
        timeline.note(6, now = 60 * MS, animate = true)
        val left = veils(timeline, 60 * MS).keys
        assertTrue(left.all { it < 6 })
        assertTrue(left.containsAll(listOf(4, 5)))
    }

    @Test
    fun easingMatchesQtOutCubic() {
        assertEquals(0f, easeOut(0f), 0f)
        assertEquals(0.875f, easeOut(0.5f), 1e-6f)
        assertEquals(1f, easeOut(1f), 0f)
        assertEquals(1f, easeOut(2f), 0f)
        assertEquals(0f, easeOut(-1f), 0f)
    }
}
