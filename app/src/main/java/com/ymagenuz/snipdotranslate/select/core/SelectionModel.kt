package com.ymagenuz.snipdotranslate.select.core

import kotlin.math.abs
import kotlin.math.max

enum class Pen { TEXT, PLUS, MINUS, LASSO, PARAGRAPH, AREA }

enum class WordOrder { READING, SELECTION }

data class AreaRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

private const val VERTICAL_WEIGHT = 4f
private const val MIN_AREA_SIDE = 16f

/**
 * The words selected on a scanned screen. [words] must be in reading order, with
 * each word's id equal to its position. [tapTolerance] is how far from a word a
 * tap still counts, and [dragSlop] how far a finger moves before a tap is a drag.
 */
class SelectionModel(
    val words: List<ScreenWord>,
    private val tapTolerance: Float,
    private val dragSlop: Float,
) {
    private var selected = LinkedHashSet<Int>()
    private val undoStack = ArrayDeque<LinkedHashSet<Int>>()
    private val redoStack = ArrayDeque<LinkedHashSet<Int>>()

    private var pen = Pen.TEXT
    private var radius = 0f
    private var base = LinkedHashSet<Int>()
    private var start = Pt(0f, 0f)
    private var last = Pt(0f, 0f)
    private var moved = false
    private var anchor: Int? = null
    private var stroke = mutableListOf<Pt>()
    private var active = false

    val selectedIds: Set<Int> get() = selected
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val hasSelection: Boolean get() = selected.isNotEmpty()

    fun beginStroke(pen: Pen, at: Pt, radius: Float = 0f) {
        this.pen = pen
        this.radius = radius
        base = LinkedHashSet(selected)
        start = at
        last = at
        moved = false
        anchor = null
        stroke = mutableListOf(at)
        active = true
        if (pen == Pen.PLUS || pen == Pen.MINUS) applyBrush(at, at)
    }

    fun extendStroke(to: Pt) {
        if (!active) return
        if (!moved && distance(start, to) >= dragSlop) moved = true
        when (pen) {
            Pen.TEXT -> if (moved) extendRange(to)
            Pen.PLUS, Pen.MINUS -> applyBrush(last, to)
            Pen.LASSO -> stroke += to
            Pen.AREA, Pen.PARAGRAPH -> Unit
        }
        last = to
    }

    /** Finishes the stroke. Returns the rectangle if it was drawn with the area pen. */
    fun endStroke(): AreaRect? {
        if (!active) return null
        active = false
        var area: AreaRect? = null
        when (pen) {
            Pen.TEXT -> if (!moved) toggleWordAt(start)
            Pen.PARAGRAPH -> if (!moved) toggleBlockAt(start)
            Pen.LASSO -> addEnclosedWords()
            Pen.AREA -> area = areaOf(start, last)
            Pen.PLUS, Pen.MINUS -> Unit
        }
        if (selected != base) {
            undoStack.addLast(base)
            redoStack.clear()
        }
        return area
    }

    /** Abandons the stroke, for example when a second finger starts a zoom. */
    fun cancelStroke() {
        if (!active) return
        active = false
        selected = base
    }

    fun selectAll() = replaceSelection(LinkedHashSet(words.map { it.id }))

    fun clear() = replaceSelection(LinkedHashSet())

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(selected)
        selected = previous
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(selected)
        selected = next
    }

    fun selectedText(order: WordOrder): String {
        val ids = if (order == WordOrder.READING) selected.sorted() else selected.toList()
        return assembleText(ids.map { words[it] })
    }

    private fun replaceSelection(updated: LinkedHashSet<Int>) {
        if (updated == selected) return
        undoStack.addLast(selected)
        redoStack.clear()
        selected = updated
    }

    private fun extendRange(to: Pt) {
        if (words.isEmpty()) return
        val from = anchor ?: nearestWord(start).also { anchor = it }
        val current = nearestWord(to)
        selected = LinkedHashSet(base).apply { addAll(minOf(from, current)..max(from, current)) }
    }

    private fun applyBrush(from: Pt, to: Pt) {
        val touched = words.filter { it.quad.distanceToStroke(from, to) <= radius }
            .sortedBy { distance(from, it.quad.center) }
        if (pen == Pen.PLUS) {
            touched.forEach { selected.add(it.id) }
        } else {
            touched.forEach { selected.remove(it.id) }
        }
    }

    private fun wordAt(point: Pt): ScreenWord? =
        words.minByOrNull { it.quad.distanceTo(point) }?.takeIf { it.quad.distanceTo(point) <= tapTolerance }

    /** Nearest by line first, so a finger between two lines picks the closer line. */
    private fun nearestWord(point: Pt): Int = words.minBy { word ->
        val dx = max(max(word.quad.left - point.x, point.x - word.quad.right), 0f)
        val dy = max(max(word.quad.top - point.y, point.y - word.quad.bottom), 0f)
        dx + VERTICAL_WEIGHT * dy
    }.id

    private fun toggleWordAt(point: Pt) {
        val word = wordAt(point) ?: return
        if (!selected.remove(word.id)) selected.add(word.id)
    }

    private fun toggleBlockAt(point: Pt) {
        val hit = wordAt(point) ?: return
        val block = words.filter { it.blockId == hit.blockId }.map { it.id }
        if (selected.containsAll(block)) selected.removeAll(block.toSet()) else selected.addAll(block)
    }

    private fun addEnclosedWords() {
        if (stroke.size < 3) return
        words.filter { polygonContains(stroke, it.quad.center) }.forEach { selected.add(it.id) }
    }

    private fun areaOf(a: Pt, b: Pt): AreaRect? {
        if (abs(a.x - b.x) < MIN_AREA_SIDE || abs(a.y - b.y) < MIN_AREA_SIDE) return null
        return AreaRect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }
}
