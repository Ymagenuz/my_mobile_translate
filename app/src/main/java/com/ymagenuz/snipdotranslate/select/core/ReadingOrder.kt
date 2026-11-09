package com.ymagenuz.snipdotranslate.select.core

private const val MIN_LINES_PER_BLOCK_FOR_COLUMNS = 2.0

private class TextRegion(val blockId: Int, val words: List<ScreenWord>) {
    val left = words.minOf { it.quad.left }
    val right = words.maxOf { it.quad.right }
    val top = words.minOf { it.quad.top }
    val bottom = words.maxOf { it.quad.bottom }
    val lineCount = words.map { it.lineId }.distinct().size
}

/**
 * Sorts words into natural reading order and renumbers them, so that a word's id is
 * its reading position. Text columns read column by column; rows such as settings
 * lists, where each block is a single line, read row by row.
 */
fun inReadingOrder(words: List<ScreenWord>): List<ScreenWord> {
    if (words.isEmpty()) return emptyList()
    val regions = words.groupBy { it.blockId }.map { (blockId, blockWords) -> TextRegion(blockId, blockWords) }
    return orderRegions(regions)
        .flatMap { region ->
            region.words
                .groupBy { it.lineId }
                .values
                .sortedBy { line -> line.map { it.quad.top }.average() }
                .flatten()
        }
        .mapIndexed { index, word -> word.copy(id = index) }
}

private fun orderRegions(regions: List<TextRegion>): List<TextRegion> {
    if (regions.size <= 1) return regions

    val rows = splitAlong(regions, { it.top }, { it.bottom })
    val columns = splitAlong(regions, { it.left }, { it.right })
    val isTextColumns = columns.size > 1 && regions.map { it.lineCount }.average() >= MIN_LINES_PER_BLOCK_FOR_COLUMNS
    return when {
        isTextColumns -> columns.flatMap(::orderRegions)
        rows.size > 1 -> rows.flatMap(::orderRegions)
        columns.size > 1 -> columns.flatMap(::orderRegions)
        else -> regions.sortedWith(compareBy({ it.top }, { it.left }))
    }
}

/** Splits regions wherever a clear gap runs all the way across them on this axis. */
private fun splitAlong(
    regions: List<TextRegion>,
    start: (TextRegion) -> Float,
    end: (TextRegion) -> Float,
): List<List<TextRegion>> {
    val groups = mutableListOf<MutableList<TextRegion>>()
    var groupEnd = Float.NEGATIVE_INFINITY
    for (region in regions.sortedBy(start)) {
        if (groups.isEmpty() || start(region) >= groupEnd) {
            groups += mutableListOf(region)
            groupEnd = end(region)
        } else {
            groups.last() += region
            groupEnd = maxOf(groupEnd, end(region))
        }
    }
    return groups
}
