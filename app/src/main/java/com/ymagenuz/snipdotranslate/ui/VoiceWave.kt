package com.ymagenuz.snipdotranslate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

// How often the wave takes a level, and so how fast it moves: one bar's place every 70 ms.
private const val WAVE_STEP_MS = 70L

// More than fit across the widest talk button.
private const val WAVE_BARS = 64

// How much of each new bar comes from the one before it, so a single click doesn't stand alone.
internal const val WAVE_SMOOTHING = 0.35f

private val BAR_WIDTH = 3.dp
private val BAR_GAP = 3.dp

// What silence looks like: a row of dots rather than nothing.
private val BAR_MIN_HEIGHT = 3.dp

/** The level a new bar gets after [previous], given the microphone's [level] from 0 to 1. */
internal fun easedLevel(previous: Float, level: Float): Float =
    previous * WAVE_SMOOTHING + level.coerceIn(0f, 1f) * (1f - WAVE_SMOOTHING)

/** [wave] moved on by one bar: the oldest level gone from the front and [level] added at the end. */
internal fun nextWave(wave: FloatArray, level: Float): FloatArray {
    val next = FloatArray(wave.size)
    wave.copyInto(next, destinationOffset = 0, startIndex = 1)
    next[next.lastIndex] = easedLevel(wave.last(), level)
    return next
}

/**
 * The microphone's level as a row of bars moving to the left, the newest on the right, so that a
 * speaker can see they are being heard. Each bar is as tall as the voice was loud, and the bars
 * fade towards the left, where they leave.
 */
@Composable
fun VoiceWave(level: StateFlow<Float>, color: Color, modifier: Modifier = Modifier) {
    var wave by remember { mutableStateOf(FloatArray(WAVE_BARS)) }
    // How far the bars have moved towards their next places, from 0 to 1.
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(level) {
        var stepStart = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            if (now - stepStart >= WAVE_STEP_MS) {
                wave = nextWave(wave, level.value)
                stepStart = now
                phase = 0f
            } else {
                phase = (now - stepStart) / WAVE_STEP_MS.toFloat()
            }
        }
    }

    // Only the drawing reads the wave and its phase, so each frame redraws without recomposing.
    Canvas(modifier = modifier.clipToBounds()) {
        val barWidth = BAR_WIDTH.toPx()
        val step = barWidth + BAR_GAP.toPx()
        val minHeight = BAR_MIN_HEIGHT.toPx()
        val shown = minOf(wave.size, (size.width / step).toInt() + 1)
        // The bar coming in from the right edge already has the height it will be taken at.
        val incoming = easedLevel(wave.last(), level.value)
        for (age in -1 until shown) {
            val value = if (age < 0) incoming else wave[wave.lastIndex - age]
            val height = minHeight + (size.height - minHeight) * value
            val place = age + phase
            val distance = (place / shown).coerceIn(0f, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width - barWidth - place * step, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
                alpha = 1f - distance * distance,
            )
        }
    }
}
