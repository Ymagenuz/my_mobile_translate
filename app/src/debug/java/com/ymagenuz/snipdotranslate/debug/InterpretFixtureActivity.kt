package com.ymagenuz.snipdotranslate.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ymagenuz.snipdotranslate.SnipDoApp
import com.ymagenuz.snipdotranslate.domain.SpeechError
import com.ymagenuz.snipdotranslate.domain.SpeechInput
import com.ymagenuz.snipdotranslate.domain.SpeechListener
import com.ymagenuz.snipdotranslate.domain.SpeechOutput
import com.ymagenuz.snipdotranslate.domain.VoiceResult
import com.ymagenuz.snipdotranslate.speech.AudioCapture
import com.ymagenuz.snipdotranslate.speech.ModelSpeechInput
import com.ymagenuz.snipdotranslate.speech.SAMPLE_RATE
import com.ymagenuz.snipdotranslate.speech.transcribeWithModel
import com.ymagenuz.snipdotranslate.ui.MainActivity
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "InterpretFixture"
private const val LEVEL_EVERY_MS = 50L

private val DEFAULT_SCRIPT = listOf(
    "zh=你好，请问去火车站怎么走？",
    "zh=我是第一次来这个城市。",
    "en=Go straight for two blocks and turn left at the bank.",
    "en=It takes about ten minutes on foot.",
    "ja=駅の近くにおいしいラーメン屋がありますよ。",
)

/**
 * Opens the interpretation page with scripted speech instead of the microphone, for an
 * emulator without a speech service. Each recognition in a language says that language's
 * next line; the string extra "script" replaces the lines, as "zh=…|en=…". Must be started
 * in a fresh process, before the page has been opened. Read-aloud text goes to the log.
 *
 * The string extra "phone_languages" (such as "zh,en") makes the scripted recognition hear
 * only those, as Xiaomi's does, and the boolean extra "synthetic_audio" gives the model's
 * recording a voice-like tone instead of the microphone, so languages the phone can't hear
 * go through the model end to end.
 */
class InterpretFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val script = intent.getStringExtra("script")?.split('|') ?: DEFAULT_SCRIPT
        val app = application as SnipDoApp
        val phoneLanguages = intent.getStringExtra("phone_languages")?.split(',')?.toSet()
        app.speechInputFactory = { ScriptedSpeechInput(script, phoneLanguages) }
        if (intent.getBooleanExtra("synthetic_audio", false)) {
            app.modelSpeechInputFactory = {
                ModelSpeechInput(app, { wav, language -> transcribeWithModel(app, wav, language) }, { SyntheticCapture() })
            }
        }
        app.speechOutputFactory = { LoggedSpeechOutput() }
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_INTERPRET, true))
        finish()
    }
}

private class ScriptedSpeechInput(script: List<String>, private val languages: Set<String>?) : SpeechInput {
    private val handler = Handler(Looper.getMainLooper())
    private val lines: Map<String, ArrayDeque<String>> = script
        .map { it.substringBefore('=') to it.substringAfter('=') }
        .groupBy({ it.first }, { it.second })
        .mapValues { ArrayDeque(it.value) }
    private var listener: SpeechListener? = null
    private var heard = ""

    override val isAvailable = true

    override suspend fun recognizedLanguages() = languages

    override fun start(languageTag: String, listener: SpeechListener) {
        handler.removeCallbacksAndMessages(null)
        this.listener = listener
        heard = ""
        val queue = lines[languageTag.substringBefore('-')]
        val line = queue?.firstOrNull()
        Log.i(TAG, "listening in $languageTag: ${line ?: "<silence>"}")
        var at = 300L
        handler.postDelayed({ this.listener?.onReady() }, at)
        if (line == null) {
            handler.postDelayed({ end { it.onError(SpeechError.NO_SPEECH) } }, 5_000)
            return
        }
        at += 700
        // A line is only used up once it starts, so one cancelled before that is said next time.
        handler.postDelayed({
            queue.removeFirstOrNull()
            this.listener?.onSpeechBegin()
        }, at)
        val pieces = if (' ' in line) line.split(' ').map { "$it " } else line.chunked(2)
        pieces.indices.forEach { index ->
            at += 220
            val text = pieces.take(index + 1).joinToString("").trim()
            handler.postDelayed({
                heard = text
                this.listener?.onPartial(text)
            }, at)
        }
        // A level about as often as a microphone reports one: syllables that rise and fall.
        for (tick in 1_000L until at + 700 step LEVEL_EVERY_MS) {
            handler.postDelayed({
                val syllable = (sin(tick / 90.0) + 1) / 2
                this.listener?.onLevel((0.15 + 0.6 * syllable + 0.25 * Random.nextFloat()).toFloat())
            }, tick)
        }
        handler.postDelayed({ end { it.onResult(line) } }, at + 700)
    }

    private fun end(deliver: (SpeechListener) -> Unit) {
        val ended = listener ?: return
        listener = null
        handler.removeCallbacksAndMessages(null)
        deliver(ended)
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ end { it.onResult(heard) } }, 200)
    }

    override fun cancel() {
        listener = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun release() = cancel()
}

private class LoggedSpeechOutput : SpeechOutput {
    private val handler = Handler(Looper.getMainLooper())

    override fun speak(text: String, languageTag: String, onDone: (VoiceResult) -> Unit) {
        Log.i(TAG, "reading aloud in $languageTag: $text")
        handler.postDelayed({ onDone(VoiceResult.SPOKEN) }, 1_000L + 30L * text.length)
    }

    override fun stop() {
        Log.i(TAG, "reading stopped")
        handler.removeCallbacksAndMessages(null)
    }

    override fun release() = stop()
}

/** About 1.8 s of a voice-like tone, then 1.5 s of a quiet room, over and over, in real time. */
private class SyntheticCapture : AudioCapture {
    private var sample = 0L

    override fun start() = true

    override fun read(into: ShortArray, offset: Int, count: Int): Int {
        for (i in 0 until count) {
            val position = sample % (SAMPLE_RATE * 33 / 10)
            val voiced = position < SAMPLE_RATE * 18 / 10
            val t = sample.toDouble() / SAMPLE_RATE
            into[offset + i] = if (voiced) {
                (3_000 * sin(2 * PI * 180 * t) + 1_500 * sin(2 * PI * 360 * t)).toInt().toShort()
            } else {
                Random.nextInt(-25, 25).toShort()
            }
            sample++
        }
        Thread.sleep(count * 1_000L / SAMPLE_RATE)
        return count
    }

    override fun close() = Unit
}
