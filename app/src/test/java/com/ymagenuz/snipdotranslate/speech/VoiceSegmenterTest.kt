package com.ymagenuz.snipdotranslate.speech

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSegmenterTest {
    private var phase = 0.0

    // A steady tone stands in for speech; quiet hiss for the room.
    private fun voice(amplitude: Double = 4_000.0) = ShortArray(FRAME_SAMPLES) {
        phase += 2 * PI * 220 / SAMPLE_RATE
        (amplitude * sin(phase)).toInt().toShort()
    }

    private fun quiet() = ShortArray(FRAME_SAMPLES) { if (it % 2 == 0) 20 else -20 }

    private fun VoiceSegmenter.feed(frames: Int, make: () -> ShortArray, mayStart: Boolean = true) =
        (1..frames).mapNotNull { accept(make(), mayStart) }

    @Test
    fun aSentenceStartsWithVoiceAndEndsAfterAPause() {
        val segmenter = VoiceSegmenter()
        assertTrue(segmenter.feed(20, ::quiet).isEmpty())

        val started = segmenter.feed(40, ::voice)
        assertEquals(listOf(VoiceSegmenter.Event.Started), started)
        assertTrue(segmenter.level > 0.5f)

        // Shorter than the pause that ends a sentence.
        assertTrue(segmenter.feed(10, ::quiet).isEmpty())
        assertTrue(segmenter.feed(20, ::voice).isEmpty())

        val ended = segmenter.feed(30, ::quiet).single() as VoiceSegmenter.Event.Ended
        // The voice, plus the moment before it began and a little of the pause after.
        val voicedFrames = 40 + 10 + 20
        val frames = ended.pcm.size / FRAME_SAMPLES
        assertTrue("$frames frames", frames in voicedFrames..voicedFrames + 10 + 7)
    }

    @Test
    fun aShortSoundIsNotASentence() {
        val segmenter = VoiceSegmenter()
        segmenter.feed(20, ::quiet)
        assertEquals(listOf(VoiceSegmenter.Event.Started), segmenter.feed(4, ::voice))
        assertEquals(listOf(VoiceSegmenter.Event.Discarded), segmenter.feed(30, ::quiet))
    }

    @Test
    fun nothingStartsWhileNotListening() {
        val segmenter = VoiceSegmenter()
        segmenter.feed(20, ::quiet)
        assertTrue(segmenter.feed(40, ::voice, mayStart = false).isEmpty())
        assertNull(segmenter.finish())
    }

    @Test
    fun stoppingEndsTheSentenceBeingSaid() {
        val segmenter = VoiceSegmenter()
        segmenter.feed(20, ::quiet)
        segmenter.feed(30, ::voice)

        val ended = segmenter.finish() as VoiceSegmenter.Event.Ended
        assertTrue(ended.pcm.size >= 30 * FRAME_SAMPLES)
        assertNull(segmenter.finish())
    }

    @Test
    fun aLongSentenceIsCut() {
        val segmenter = VoiceSegmenter(maxFrames = 100)
        segmenter.feed(20, ::quiet)
        val events = segmenter.feed(150, ::voice)
        assertEquals(VoiceSegmenter.Event.Started, events[0])
        assertTrue(events[1] is VoiceSegmenter.Event.Ended)
        // Still speaking, so the next sentence begins.
        assertEquals(VoiceSegmenter.Event.Started, events[2])
    }

    @Test
    fun aLouderRoomNeedsLouderSpeech() {
        val segmenter = VoiceSegmenter()
        val noisy = { voice(amplitude = 300.0) }
        assertTrue(segmenter.feed(100, noisy).isEmpty())
        assertEquals(listOf(VoiceSegmenter.Event.Started), segmenter.feed(10, { voice(amplitude = 6_000.0) }))
    }

    @Test
    fun wavHeaderDescribesSixteenBitMono() {
        val wav = wavBytes(shortArrayOf(1, -1, 256))
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(44 + 6, wav.size)
        fun int(at: Int) = (0 until 4).sumOf { (wav[at + it].toInt() and 0xFF) shl (8 * it) }
        assertEquals(SAMPLE_RATE, int(24))
        assertEquals(6, int(40))
        assertEquals(1, wav[22].toInt())
        assertEquals(16, wav[34].toInt())
        // Little-endian samples.
        assertEquals(listOf<Byte>(1, 0, -1, -1, 0, 1), wav.drop(44))
    }
}
