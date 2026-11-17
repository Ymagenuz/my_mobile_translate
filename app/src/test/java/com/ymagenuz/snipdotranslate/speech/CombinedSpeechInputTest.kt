package com.ymagenuz.snipdotranslate.speech

import com.ymagenuz.snipdotranslate.domain.SpeechEngine
import com.ymagenuz.snipdotranslate.domain.SpeechError
import com.ymagenuz.snipdotranslate.domain.SpeechInput
import com.ymagenuz.snipdotranslate.domain.SpeechListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSpeechInputTest {
    private class FakeInput(override val isAvailable: Boolean = true, override val finishTimeoutMs: Long = 1L) : SpeechInput {
        val started = mutableListOf<String>()
        var listener: SpeechListener? = null
        var stops = 0
        var cancels = 0

        override fun start(languageTag: String, listener: SpeechListener) {
            started += languageTag
            this.listener = listener
        }

        override fun stop() {
            stops++
        }

        override fun cancel() {
            cancels++
        }

        override fun release() = Unit

        override fun engineWithoutMicrophone() = SpeechEngine("Engine", "com.example")

        override fun lastErrorDetail() = "detail"
    }

    private class Events : SpeechListener {
        val errors = mutableListOf<SpeechError>()
        val results = mutableListOf<String>()

        override fun onReady() = Unit

        override fun onSpeechBegin() = Unit

        override fun onLevel(level: Float) = Unit

        override fun onPartial(text: String) = Unit

        override fun onResult(text: String) {
            results += text
        }

        override fun onError(error: SpeechError) {
            errors += error
        }
    }

    private val phone = FakeInput(finishTimeoutMs = 3_000)
    private val model = FakeInput(finishTimeoutMs = 20_000)
    private var phoneLanguages: Set<String>? = setOf("zh", "en")
    private var modelHears = true
    private val input = CombinedSpeechInput(phone, model, { phoneLanguages }, { modelHears })

    @Test
    fun thePhoneHearsWhatItCanAndTheModelTheRest() {
        input.start("zh-CN", Events())
        input.start("ja-JP", Events())
        assertEquals(listOf("zh-CN"), phone.started)
        assertEquals(listOf("ja-JP"), model.started)
        assertEquals(20_000L, input.finishTimeoutMs)
        assertNull(input.engineWithoutMicrophone())

        input.stop()
        input.cancel()
        assertEquals(1, model.stops)
        assertEquals(1, model.cancels)
        assertEquals(0, phone.stops)
    }

    @Test
    fun aPhoneThatCannotTellHearsEverything() {
        phoneLanguages = null
        input.start("ko-KR", Events())
        assertEquals(listOf("ko-KR"), phone.started)
        assertEquals("Engine", input.engineWithoutMicrophone()?.label)
    }

    @Test
    fun withoutAudioInputALanguageThePhoneCannotHearIsTurnedDown() {
        modelHears = false
        val events = Events()
        input.start("fr-FR", events)
        assertEquals(listOf(SpeechError.LANGUAGE_UNSUPPORTED), events.errors)
        assertTrue(model.started.isEmpty())
    }

    @Test
    fun aPhoneWithoutRecognitionLeavesEverythingToTheModel() {
        val without = CombinedSpeechInput(FakeInput(isAvailable = false), model, { null }, { modelHears })
        assertTrue(without.isAvailable)
        without.start("zh-CN", Events())
        assertEquals(listOf("zh-CN"), model.started)

        modelHears = false
        assertFalse(without.isAvailable)
    }

    @Test
    fun aLanguageThePhoneTurnsDownGoesToTheModelNextTime() {
        phoneLanguages = null
        val events = Events()
        input.start("it-IT", events)
        phone.listener!!.onError(SpeechError.LANGUAGE_UNSUPPORTED)
        // Tried again straight away, through the model.
        assertEquals(listOf(SpeechError.TRANSIENT), events.errors)
        assertFalse(input.phoneHears("it"))

        input.start("it-IT", events)
        assertEquals(listOf("it-IT"), model.started)

        // Other events pass straight through.
        input.start("en-US", events)
        phone.listener!!.onResult("hello")
        assertEquals(listOf("hello"), events.results)
    }
}
