package com.ymagenuz.snipdotranslate.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ymagenuz.snipdotranslate.domain.SpeechOutput
import com.ymagenuz.snipdotranslate.domain.VoiceResult
import java.util.Locale

/** The phone's text-to-speech. Must be used on the main thread; callbacks arrive there too. */
class SystemSpeechOutput(context: Context) : SpeechOutput {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<String, (VoiceResult) -> Unit>()
    private var nextId = 0L

    // Null until the engine has started; then whether it did.
    private var ready: Boolean? = null
    private var waiting: (() -> Unit)? = null

    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            waiting?.invoke()
            waiting = null
        }
    }

    init {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = finish(utteranceId, VoiceResult.SPOKEN)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, VoiceResult.FAILED)

            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, VoiceResult.FAILED)

            // After stop(); its callback was already dropped.
            override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
        })
    }

    private fun finish(utteranceId: String?, result: VoiceResult) {
        main.post { utteranceId?.let(callbacks::remove)?.invoke(result) }
    }

    override fun speak(text: String, languageTag: String, onDone: (VoiceResult) -> Unit) {
        if (ready == null) {
            waiting = { speakNow(text, languageTag, onDone) }
        } else {
            speakNow(text, languageTag, onDone)
        }
    }

    private fun speakNow(text: String, languageTag: String, onDone: (VoiceResult) -> Unit) {
        if (ready != true) {
            onDone(VoiceResult.NO_VOICE)
            return
        }
        val language = engine.setLanguage(Locale.forLanguageTag(languageTag))
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            onDone(VoiceResult.NO_VOICE)
            return
        }
        val id = "interpret-${nextId++}"
        callbacks[id] = onDone
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            onDone(VoiceResult.FAILED)
        }
    }

    override fun stop() {
        waiting = null
        callbacks.clear()
        if (ready == true) engine.stop()
    }

    override fun release() {
        stop()
        engine.shutdown()
    }
}
