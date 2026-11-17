package com.ymagenuz.snipdotranslate.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceWaveTest {
    @Test
    fun eachStepDropsTheOldestLevelAndAddsTheNewestAtTheEnd() {
        val wave = floatArrayOf(0.1f, 0.2f, 0.3f, 0f)

        val next = nextWave(wave, 1f)

        assertEquals(wave.size, next.size)
        assertArrayEquals(floatArrayOf(0.2f, 0.3f, 0f), next.copyOfRange(0, 3), 0f)
        // After silence, a loud level arrives eased, not at its full height.
        assertEquals(1f - WAVE_SMOOTHING, next.last(), 1e-6f)
        // The wave it came from is left as it was, since the page may still be drawing it.
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0f), wave, 0f)
    }

    @Test
    fun aSteadyVoiceReachesItsLevelAndSilenceFallsAway() {
        var wave = FloatArray(8)
        repeat(20) { wave = nextWave(wave, 0.8f) }
        assertEquals(0.8f, wave.last(), 0.001f)

        repeat(20) { wave = nextWave(wave, 0f) }
        assertEquals(0f, wave.last(), 0.001f)
    }

    @Test
    fun levelsOutsideTheRangeAreKeptWithinIt() {
        var wave = FloatArray(4)
        repeat(20) { wave = nextWave(wave, 5f) }
        assertTrue(wave.all { it in 0f..1f })
        assertEquals(1f, wave.last(), 0.001f)

        repeat(20) { wave = nextWave(wave, -3f) }
        assertTrue(wave.all { it in 0f..1f })
    }
}
