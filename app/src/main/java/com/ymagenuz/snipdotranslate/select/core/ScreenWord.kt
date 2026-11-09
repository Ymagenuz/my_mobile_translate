package com.ymagenuz.snipdotranslate.select.core

data class Pt(val x: Float, val y: Float)

/** Four corners, clockwise from the top-left of the text as it reads. */
data class Quad(
    val topLeft: Pt,
    val topRight: Pt,
    val bottomRight: Pt,
    val bottomLeft: Pt,
) {
    val corners: List<Pt> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/** Spans from the left edge of [first] to the right edge of [last], so rotated text stays tight. */
fun spanOf(first: Quad, last: Quad): Quad =
    Quad(first.topLeft, last.topRight, last.bottomRight, first.bottomLeft)

/** A selectable word on the screen. */
data class ScreenWord(
    val id: Int,
    val text: String,
    val quad: Quad,
    val lineId: Int,
    val blockId: Int,
)

data class RawSymbol(val text: String, val quad: Quad)

data class RawElement(val text: String, val quad: Quad, val symbols: List<RawSymbol>)

data class RawLine(val elements: List<RawElement>)

data class RawBlock(val lines: List<RawLine>)

/** Returns ascending word boundaries of [text], including 0 and its length. */
fun interface WordSegmenter {
    fun boundaries(text: String): List<Int>
}
