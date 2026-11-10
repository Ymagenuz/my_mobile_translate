package com.ymagenuz.snipdotranslate.ui.reveal

import kotlin.math.min

// The desktop app's timings: a new paragraph fades in as a whole, and its characters
// follow one after another in reading order.
const val REVEAL_BLOCK_MS = 360L
const val REVEAL_CHARACTER_MS = 300L
const val REVEAL_STAGGER_MS = 10L

// However much text arrives at once, its last character starts within this.
const val REVEAL_SPREAD_MS = 360L

private const val NANOS_PER_MS = 1_000_000L
private const val BLOCK_NANOS = REVEAL_BLOCK_MS * NANOS_PER_MS
private const val CHARACTER_NANOS = REVEAL_CHARACTER_MS * NANOS_PER_MS
private const val NOT_FADING = Long.MIN_VALUE

/**
 * When each character of a growing text arrived, so that it can fade in, as the desktop
 * app's TextReveal does. Times are in nanoseconds on one clock, such as System.nanoTime.
 */
class RevealTimeline {
    private class Arrival(val first: Int, var end: Int, val at: Long, val step: Long)

    private val arrivals = ArrayList<Arrival>()
    private var length = 0
    private var blockStart = NOT_FADING

    /** Records that the text is now [newLength] long. What's new fades in only if [animate]. */
    fun note(newLength: Int, now: Long, animate: Boolean) {
        val first = length
        length = newLength
        if (newLength <= first) {
            // A re-render tidied what was there; nothing new to show.
            arrivals.removeAll { it.first >= newLength }
            for (arrival in arrivals) arrival.end = min(arrival.end, newLength)
            return
        }
        if (!animate) return
        if (first == 0) blockStart = now
        val count = newLength - first
        val step = min(REVEAL_STAGGER_MS * NANOS_PER_MS, REVEAL_SPREAD_MS * NANOS_PER_MS / count)
        arrivals += Arrival(first, newLength, now, step)
    }

    /** How opaque the whole block is; a new block fades in along with its first characters. */
    fun blockAlpha(now: Long): Float =
        if (blockStart == NOT_FADING) 1f else easeOut((now - blockStart).toFloat() / BLOCK_NANOS)

    /** Calls [action] for each character still fading in, with how much of it is still hidden. */
    fun forEachVeiled(now: Long, action: (index: Int, veil: Float) -> Unit) {
        for (arrival in arrivals) {
            for (index in arrival.first until arrival.end) {
                val progress = (now - arrival.at - (index - arrival.first) * arrival.step).toFloat() / CHARACTER_NANOS
                if (progress < 1f) action(index, 1f - easeOut(progress))
            }
        }
    }

    fun hasVeils(now: Long): Boolean = arrivals.any { !it.isFinished(now) }

    fun isActive(now: Long): Boolean = hasVeils(now) || (blockStart != NOT_FADING && now - blockStart < BLOCK_NANOS)

    /** Forgets fades that have finished. */
    fun prune(now: Long) {
        arrivals.removeAll { it.isFinished(now) }
        if (blockStart != NOT_FADING && now - blockStart >= BLOCK_NANOS) blockStart = NOT_FADING
    }

    private fun Arrival.isFinished(now: Long): Boolean = now - at - (end - first - 1) * step >= CHARACTER_NANOS
}

/** Qt's OutCubic, which the desktop app uses: quick at first, settling gently. */
fun easeOut(progress: Float): Float {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    return 1f - remaining * remaining * remaining
}

/** How far a fade that began at [start] has got by [now]. */
fun blockFadeProgress(start: Long, now: Long): Float = easeOut((now - start).toFloat() / BLOCK_NANOS)
