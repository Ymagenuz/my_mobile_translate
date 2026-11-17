package com.ymagenuz.snipdotranslate.speech

import com.ymagenuz.snipdotranslate.domain.SpeechEngine
import com.ymagenuz.snipdotranslate.domain.SpeechError
import com.ymagenuz.snipdotranslate.domain.SpeechInput
import com.ymagenuz.snipdotranslate.domain.SpeechListener

/**
 * The phone's speech recognition, with the translation model hearing the languages the
 * phone can't, when Supports audio input is on. [phoneLanguages] is what the phone says it
 * can hear, or null when it can't tell, which counts as everything.
 */
class CombinedSpeechInput(
    private val phone: SpeechInput,
    private val model: SpeechInput,
    private val phoneLanguages: () -> Set<String>?,
    private val modelHears: () -> Boolean,
) : SpeechInput {
    private var active: SpeechInput? = null

    // Languages the phone turned down while listening, although it didn't say beforehand.
    private val phoneRefused = mutableSetOf<String>()

    override val isAvailable: Boolean get() = phone.isAvailable || modelHears()

    override val finishTimeoutMs: Long get() = (active ?: phone).finishTimeoutMs

    fun phoneHears(language: String): Boolean =
        phone.isAvailable && language !in phoneRefused && phoneLanguages()?.contains(language) != false

    override fun start(languageTag: String, listener: SpeechListener) {
        val language = languageTag.substringBefore('-')
        val route = when {
            phoneHears(language) -> phone
            modelHears() -> model
            else -> {
                active = null
                listener.onError(SpeechError.LANGUAGE_UNSUPPORTED)
                return
            }
        }
        active = route
        if (route === phone) {
            route.start(languageTag, PhoneListener(language, listener))
        } else {
            route.start(languageTag, listener)
        }
    }

    override fun stop() {
        active?.stop()
    }

    override fun cancel() {
        active?.cancel()
    }

    override fun release() {
        phone.release()
        model.release()
    }

    override fun engineWithoutMicrophone(): SpeechEngine? =
        if (active === phone) phone.engineWithoutMicrophone() else null

    override fun lastErrorDetail(): String? = active?.lastErrorDetail()

    override suspend fun recognizedLanguages(): Set<String>? = phone.recognizedLanguages()

    /** Passes the phone's events on, sending a language it turns down to the model instead. */
    private inner class PhoneListener(
        private val language: String,
        private val listener: SpeechListener,
    ) : SpeechListener by listener {
        override fun onError(error: SpeechError) {
            if (error == SpeechError.LANGUAGE_UNSUPPORTED && modelHears()) {
                phoneRefused += language
                // Listening tries again straight away, this time through the model.
                listener.onError(SpeechError.TRANSIENT)
            } else {
                listener.onError(error)
            }
        }
    }
}
