package com.ymagenuz.snipdotranslate.speech

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000

/** 30 ms of audio at [SAMPLE_RATE]. */
const val FRAME_SAMPLES = 480

private const val FRAME_MS = 30

// Speech must be this far above the room's noise, and never quieter than the floor.
private const val VOICE_ABOVE_NOISE_DB = 9f
private const val VOICE_FLOOR_DB = -52f
private const val SILENCE_DB = -90f

/**
 * Finds sentences in microphone audio by loudness, for speech the phone's own recognition
 * can't hear. Speech starts when the sound rises clearly above the room's noise and ends
 * after a pause; a sentence that runs on too long is cut, and a click or cough too short
 * to be speech is dropped. Not thread-safe: one thread feeds it frames.
 */
class VoiceSegmenter(
    private val startFrames: Int = 90 / FRAME_MS,
    private val endFrames: Int = 750 / FRAME_MS,
    private val preRollFrames: Int = 300 / FRAME_MS,
    private val tailFrames: Int = 210 / FRAME_MS,
    private val maxFrames: Int = 25_000 / FRAME_MS,
    private val minVoicedFrames: Int = 240 / FRAME_MS,
) {
    sealed interface Event {
        data object Started : Event

        class Ended(val pcm: ShortArray) : Event

        /** What started was too short to be speech. */
        data object Discarded : Event
    }

    /** How loud the last frame was, from 0 for silence to 1 for loud speech. */
    var level = 0f
        private set

    private var noiseDb = Float.NaN
    private val preRoll = ArrayDeque<ShortArray>()
    private val frames = mutableListOf<ShortArray>()
    private var inSpeech = false
    private var voicedRun = 0
    private var quietRun = 0
    private var voicedTotal = 0

    /** Feeds one frame; with [mayStart] false, quiet stays quiet and no new sentence begins. */
    fun accept(frame: ShortArray, mayStart: Boolean = true): Event? {
        val db = decibels(frame)
        level = ((db + 60f) / 40f).coerceIn(0f, 1f)
        val voiced = db > max(noiseDb.takeUnless { it.isNaN() }?.plus(VOICE_ABOVE_NOISE_DB) ?: VOICE_FLOOR_DB, VOICE_FLOOR_DB)
        return if (inSpeech) continueSpeech(frame, voiced) else waitForSpeech(frame, db, voiced && mayStart)
    }

    /** Ends what's being said, as when listening stops. */
    fun finish(): Event? {
        preRoll.clear()
        voicedRun = 0
        return if (inSpeech) endSentence() else null
    }

    private fun waitForSpeech(frame: ShortArray, db: Float, voiced: Boolean): Event? {
        learnNoise(db)
        preRoll.addLast(frame)
        while (preRoll.size > preRollFrames) preRoll.removeFirst()
        voicedRun = if (voiced) voicedRun + 1 else 0
        if (voicedRun < startFrames) return null
        inSpeech = true
        frames.clear()
        frames.addAll(preRoll)
        preRoll.clear()
        voicedTotal = voicedRun
        voicedRun = 0
        quietRun = 0
        return Event.Started
    }

    private fun continueSpeech(frame: ShortArray, voiced: Boolean): Event? {
        frames += frame
        if (voiced) {
            voicedTotal++
            quietRun = 0
        } else {
            quietRun++
        }
        return if (quietRun >= endFrames || frames.size >= maxFrames) endSentence() else null
    }

    private fun endSentence(): Event {
        inSpeech = false
        // Keep a little of the pause, so the last word isn't clipped.
        repeat((quietRun - tailFrames).coerceAtLeast(0)) { frames.removeAt(frames.lastIndex) }
        val enough = voicedTotal >= minVoicedFrames
        val pcm = if (enough) join(frames) else null
        frames.clear()
        quietRun = 0
        voicedTotal = 0
        return if (pcm != null) Event.Ended(pcm) else Event.Discarded
    }

    // Falls quickly to a quieter room and rises slowly, so speech doesn't become the "noise".
    private fun learnNoise(db: Float) {
        noiseDb = when {
            noiseDb.isNaN() -> db
            db < noiseDb -> noiseDb + (db - noiseDb) * 0.3f
            else -> noiseDb + (db - noiseDb) * 0.01f
        }
    }

    private fun join(parts: List<ShortArray>): ShortArray {
        val joined = ShortArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { part ->
            part.copyInto(joined, at)
            at += part.size
        }
        return joined
    }
}

/** The loudness of a frame in dB below full scale. */
fun decibels(frame: ShortArray): Float {
    if (frame.isEmpty()) return SILENCE_DB
    var sum = 0.0
    frame.forEach { sum += it.toDouble() * it }
    val rms = sqrt(sum / frame.size)
    return if (rms < 1.0) SILENCE_DB else (20 * log10(rms / Short.MAX_VALUE)).toFloat()
}

/** 16-bit mono PCM as a WAV file. */
fun wavBytes(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
    val dataBytes = pcm.size * 2
    val bytes = ByteArray(44 + dataBytes)
    fun putInt(at: Int, value: Int) {
        for (i in 0 until 4) bytes[at + i] = (value shr (8 * i)).toByte()
    }
    fun putShort(at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value shr 8).toByte()
    }
    "RIFF".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
    putInt(4, 36 + dataBytes)
    "WAVEfmt ".forEachIndexed { i, c -> bytes[8 + i] = c.code.toByte() }
    putInt(16, 16)
    putShort(20, 1)
    putShort(22, 1)
    putInt(24, sampleRate)
    putInt(28, sampleRate * 2)
    putShort(32, 2)
    putShort(34, 16)
    "data".forEachIndexed { i, c -> bytes[36 + i] = c.code.toByte() }
    putInt(40, dataBytes)
    pcm.forEachIndexed { i, sample -> putShort(44 + i * 2, sample.toInt()) }
    return bytes
}
