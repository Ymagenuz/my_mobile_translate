package com.ymagenuz.snipdotranslate.select.core

import kotlin.math.abs
import kotlin.math.max

// Enough lines to cover a screen that was scrolled only a little.
private const val MAX_TAIL_LINES = 40

// Less text than this could repeat by chance, such as a short reply or a button.
private const val MIN_MATCH_WEIGHT = 8

// Where wide scripts start: CJK, kana, Hangul and full-width forms.
private const val FIRST_WIDE_CHAR = 0x2E80
private const val MIN_LINE_SIMILARITY = 0.8

// Lines in a row the scan may read differently on the two screens, between ones that repeat.
private const val MAX_MISREAD_RUN = 2

/**
 * Where a new screen picks up the text collected so far. [carried] are the new screen's words
 * that repeat the end of it; they're shown as done and can't be selected again. When the last
 * collected line was at the bottom edge and doesn't appear on the new screen, the edge most
 * likely cut it off and it was misread, so the whole line that follows the repeated ones,
 * [replacement], stands in for it and starts out selected. [continuedBlock] is the new screen's paragraph that carries on
 * the last collected one. All are null or empty when the new screen doesn't repeat the end.
 */
class Junction(
    val carried: Set<Int>,
    val replacement: Set<Int>,
    val continuedBlock: Int?,
) {
    val found: Boolean get() = continuedBlock != null

    companion object {
        val NONE = Junction(emptySet(), emptySet(), null)
    }
}

/**
 * The words selected on earlier screens, kept while the user scrolls to the rest of the text.
 * Only words are kept, never a screenshot. Ids, lines and paragraphs are numbered across all
 * the parts, so [text] reads as one text, with a paragraph that runs across screens joined.
 */
class Collected private constructor(
    val words: List<ScreenWord>,
    val parts: Int,
    // Whether the last line was at the bottom of its screen, where the edge may have cut it.
    private val endsAtEdge: Boolean,
) {
    val isEmpty: Boolean get() = words.isEmpty()

    val text: String get() = assembleText(words)

    /**
     * Adds the words selected on a new screen, in the order they should be read. [screenBottom]
     * is where that screen's content ends, such as the top of the navigation bar.
     */
    fun plus(selected: List<ScreenWord>, junction: Junction, screenBottom: Float): Collected {
        if (selected.isEmpty()) return this
        val last = words.lastOrNull()
        // Only when the stand-in line was kept, or the cut-off one would be lost.
        val replacesLast = junction.replacement.isNotEmpty() &&
            selected.map { it.id }.containsAll(junction.replacement)
        val kept = if (replacesLast) words.dropLast(words.byLine().last().size) else words
        // Counted from all the earlier words, so nothing new shares a number with them.
        val idOffset = (words.maxOfOrNull { it.id } ?: -1) + 1
        val lineOffset = (words.maxOfOrNull { it.lineId } ?: -1) + 1
        val blockOffset = (words.maxOfOrNull { it.blockId } ?: -1) + 1
        val added = selected.map { word ->
            val block = if (last != null && word.blockId == junction.continuedBlock) last.blockId else word.blockId + blockOffset
            word.copy(id = word.id + idOffset, lineId = word.lineId + lineOffset, blockId = block)
        }
        return Collected(kept + added, parts + 1, selected.byLine().last().reachesEdge(selected, screenBottom))
    }

    /**
     * Finds the end of the collected text on a new screen, whose words are in reading order,
     * checked against [kept], where the app's scrolling took the kept words when it could be
     * followed. The text comes first, since it also finds a line the edge cut off. Where it
     * lands somewhere else, as among lines that repeat, or isn't found, the followed places win.
     */
    fun junctionWith(screen: List<ScreenWord>, kept: List<Quad>): Junction {
        val byText = junctionWith(screen)
        val byPosition = keptByPosition(screen, kept)
        if (!byPosition.found) return byText
        return if (byText.found && byText.carried.any { it in byPosition.carried }) byText else byPosition
    }

    /** Finds the end of the collected text on a new screen, whose words are in reading order. */
    fun junctionWith(screen: List<ScreenWord>): Junction {
        val tail = words.byLine().takeLast(MAX_TAIL_LINES).map(::lineKey)
        val lines = screen.byLine()
        val keys = lines.map(::lineKey)
        var best: Match? = null
        // First as collected, then without the last line, in case the edge cut it off.
        val mayDrop = endsAtEdge && tail.size >= 2
        for (dropped in 0..(if (mayDrop) 1 else 0)) {
            val wanted = tail.subList(0, tail.size - dropped)
            // The stand-in for a dropped line must be on the new screen.
            val lastEnd = keys.lastIndex - dropped
            for (end in 0..lastEnd) {
                val count = runEndingAt(wanted, keys, end)
                if (count == 0 || (end - count + 1..end).sumOf { weight(keys[it]) } < MIN_MATCH_WEIGHT) continue
                // Ties keep the earlier find: nothing dropped, and nearer the top.
                if (best == null || count > best.count) best = Match(end, count, dropped)
            }
        }
        val match = best ?: return Junction.NONE
        val stand = if (match.dropped == 1) lines[match.end + 1] else null
        val repeated = repeatedLines(tail.subList(0, tail.size - match.dropped), keys, match)
        return Junction(
            carried = repeated.flatMap { lines[it] }.map { it.id }.toSet(),
            replacement = stand?.map { it.id }?.toSet().orEmpty(),
            continuedBlock = (stand ?: lines[match.end]).last().blockId,
        )
    }

    /**
     * The new screen's lines that repeat [wanted], from the end of [match] upwards. It carries on
     * past a few lines read differently on the two screens when lines above them repeat again,
     * so everything already kept shows as kept.
     */
    private fun repeatedLines(wanted: List<String>, keys: List<String>, match: Match): List<Int> {
        val repeated = (match.end - match.count + 1..match.end).toMutableList()
        val misread = mutableListOf<Int>()
        var kept = wanted.lastIndex - match.count
        var line = match.end - match.count
        while (kept >= 0 && line >= 0) {
            if (similar(wanted[kept], keys[line])) {
                repeated += misread
                misread.clear()
                repeated += line
            } else if (misread.size < MAX_MISREAD_RUN) {
                misread += line
            } else {
                break
            }
            kept--
            line--
        }
        return repeated
    }

    private class Match(val end: Int, val count: Int, val dropped: Int)

    companion object {
        val EMPTY = Collected(emptyList(), 0, false)
    }
}

/**
 * Where a new screen picks up, from where the app's scrolling took the kept words, [kept], for
 * when their text isn't found on it. The words under those places are carried, and the last of
 * them carries on its paragraph.
 */
fun keptByPosition(screen: List<ScreenWord>, kept: List<Quad>): Junction {
    val carried = screen.filter { word -> kept.any { it.contains(word.quad.center) } }
    if (carried.isEmpty()) return Junction.NONE
    return Junction(carried.map { it.id }.toSet(), emptySet(), carried.maxBy { it.id }.blockId)
}

/** How many of [wanted]'s last lines repeat, in order, in the lines up to [end]. */
private fun runEndingAt(wanted: List<String>, keys: List<String>, end: Int): Int {
    var count = 0
    while (count < wanted.size && end - count >= 0 && similar(wanted[wanted.lastIndex - count], keys[end - count])) count++
    return count
}

/** Words grouped into their lines, keeping the order they're in. */
private fun List<ScreenWord>.byLine(): List<List<ScreenWord>> {
    val lines = mutableListOf<MutableList<ScreenWord>>()
    for (word in this) {
        if (lines.lastOrNull()?.last()?.lineId == word.lineId) lines.last() += word else lines += mutableListOf(word)
    }
    return lines
}

/**
 * Whether this line ends less than a line's height above [screenBottom], measured by the
 * [selected] words' usual height, since a line cut by the edge is shorter than the rest.
 */
private fun List<ScreenWord>.reachesEdge(selected: List<ScreenWord>, screenBottom: Float): Boolean {
    val heights = selected.map { it.quad.bottom - it.quad.top }.sorted()
    return screenBottom - maxOf { it.quad.bottom } < heights[heights.size / 2]
}

/** A line's text without spaces or case, which the scan may read differently each time. */
private fun lineKey(line: List<ScreenWord>): String =
    line.joinToString("") { it.text }.filterNot { it.isWhitespace() }.lowercase()

/** How much a line says: a Chinese, Japanese or Korean character says more than a Latin letter. */
private fun weight(key: String): Int = key.sumOf { if (it.code >= FIRST_WIDE_CHAR) 2 else 1 }

/** Whether two lines are the same text, allowing for a few characters scanned differently. */
private fun similar(a: String, b: String): Boolean {
    if (a == b) return true
    if (a.isEmpty() || b.isEmpty()) return false
    val longer = max(a.length, b.length)
    val allowed = longer * (1 - MIN_LINE_SIMILARITY)
    return abs(a.length - b.length) <= allowed && editDistance(a, b) <= allowed
}

private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
        }
        previous = current.also { current = previous }
    }
    return previous[b.length]
}
