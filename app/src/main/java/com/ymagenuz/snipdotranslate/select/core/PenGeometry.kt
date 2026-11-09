package com.ymagenuz.snipdotranslate.select.core

import kotlin.math.hypot

val Quad.center: Pt
    get() = Pt(corners.map { it.x }.average().toFloat(), corners.map { it.y }.average().toFloat())

val Quad.left: Float get() = corners.minOf { it.x }
val Quad.right: Float get() = corners.maxOf { it.x }
val Quad.top: Float get() = corners.minOf { it.y }
val Quad.bottom: Float get() = corners.maxOf { it.y }

fun distance(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

private fun cross(origin: Pt, a: Pt, b: Pt): Float =
    (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)

fun Quad.contains(point: Pt): Boolean {
    val points = corners
    var positive = false
    var negative = false
    for (index in points.indices) {
        val side = cross(points[index], points[(index + 1) % points.size], point)
        if (side > 0) positive = true
        if (side < 0) negative = true
    }
    return !(positive && negative)
}

fun distanceToSegment(point: Pt, start: Pt, end: Pt): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return distance(point, start)
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    return distance(point, Pt(start.x + t * dx, start.y + t * dy))
}

private fun segmentsCross(a: Pt, b: Pt, c: Pt, d: Pt): Boolean {
    val d1 = cross(c, d, a)
    val d2 = cross(c, d, b)
    val d3 = cross(a, b, c)
    val d4 = cross(a, b, d)
    return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
}

fun Quad.distanceTo(point: Pt): Float {
    if (contains(point)) return 0f
    val points = corners
    return points.indices.minOf { distanceToSegment(point, points[it], points[(it + 1) % points.size]) }
}

/** The closest this quad comes to a stroke segment; zero if they touch. */
fun Quad.distanceToStroke(start: Pt, end: Pt): Float {
    if (contains(start) || contains(end)) return 0f
    val points = corners
    return points.indices.minOf { index ->
        val a = points[index]
        val b = points[(index + 1) % points.size]
        if (segmentsCross(start, end, a, b)) {
            0f
        } else {
            minOf(
                distanceToSegment(a, start, end),
                distanceToSegment(b, start, end),
                distanceToSegment(start, a, b),
                distanceToSegment(end, a, b),
            )
        }
    }
}

fun polygonContains(polygon: List<Pt>, point: Pt): Boolean {
    var inside = false
    var previous = polygon.last()
    for (current in polygon) {
        val crosses = (current.y > point.y) != (previous.y > point.y) &&
            point.x < (previous.x - current.x) * (point.y - current.y) / (previous.y - current.y) + current.x
        if (crosses) inside = !inside
        previous = current
    }
    return inside
}
