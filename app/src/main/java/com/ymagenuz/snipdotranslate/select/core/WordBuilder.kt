package com.ymagenuz.snipdotranslate.select.core

private val CJK_WITHOUT_SPACES = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]")

/**
 * Turns recognised text into selectable words.
 *
 * Spaced scripts use the recogniser's elements as they are. Chinese and Japanese
 * have no spaces to split on, so adjacent character boxes are joined into a run
 * and split into words by [segmenter].
 */
fun buildWords(blocks: List<RawBlock>, segmenter: WordSegmenter): List<ScreenWord> {
    val words = mutableListOf<ScreenWord>()
    var nextLineId = 0

    blocks.forEachIndexed { blockId, block ->
        for (line in block.lines) {
            val lineId = nextLineId++
            val run = mutableListOf<RawSymbol>()

            fun add(text: String, quad: Quad) {
                words += ScreenWord(words.size, text, quad, lineId, blockId)
            }

            fun flushRun() {
                segmentRun(run, segmenter).forEach { (text, quad) -> add(text, quad) }
                run.clear()
            }

            for (element in line.elements) {
                if (element.symbols.isNotEmpty() && CJK_WITHOUT_SPACES.containsMatchIn(element.text)) {
                    run += element.symbols
                } else {
                    flushRun()
                    add(element.text, element.quad)
                }
            }
            flushRun()
        }
    }
    return words
}

private fun segmentRun(
    run: List<RawSymbol>,
    segmenter: WordSegmenter,
): List<Pair<String, Quad>> {
    if (run.isEmpty()) return emptyList()

    val text = run.joinToString("") { it.text }
    val symbolAtOffset = IntArray(text.length)
    var offset = 0
    run.forEachIndexed { index, symbol ->
        repeat(symbol.text.length) { symbolAtOffset[offset++] = index }
    }

    return segmenter.boundaries(text).zipWithNext().mapNotNull { (start, end) ->
        if (start >= end) return@mapNotNull null
        val first = symbolAtOffset[start]
        val last = symbolAtOffset[end - 1]
        text.substring(start, end) to spanOf(run[first].quad, run[last].quad)
    }
}
