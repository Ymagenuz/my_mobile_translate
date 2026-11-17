package com.ymagenuz.snipdotranslate.domain

import com.ymagenuz.snipdotranslate.core.DEFAULT_API_PROVIDER
import com.ymagenuz.snipdotranslate.core.SpeakerSetup
import com.ymagenuz.snipdotranslate.core.apiProvider
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterpretationSessionTest {
    private class FakeInput(override val isAvailable: Boolean = true) : SpeechInput {
        var listener: SpeechListener? = null
        var language: String? = null
        var starts = 0
        var stops = 0
        var cancels = 0

        override fun start(languageTag: String, listener: SpeechListener) {
            this.listener = listener
            language = languageTag
            starts++
        }

        override fun stop() {
            stops++
        }

        override fun cancel() {
            cancels++
            listener = null
        }

        override fun release() = Unit

        var blockedEngine: SpeechEngine? = null

        override fun engineWithoutMicrophone() = blockedEngine

        var finishTimeout = FINISH_TIMEOUT_MS

        override val finishTimeoutMs get() = finishTimeout

        var detail: String? = null

        override fun lastErrorDetail() = detail

        /** The current recognition's events; fails if nothing is listening. */
        val events: SpeechListener get() = listener!!

        /** Ends the current recognition with a result, as recognition does after a sentence. */
        fun result(text: String) {
            val ended = events
            listener = null
            ended.onResult(text)
        }

        fun error(error: SpeechError) {
            val ended = events
            listener = null
            ended.onError(error)
        }
    }

    private class FakeOutput : SpeechOutput {
        val spoken = mutableListOf<Pair<String, String>>()
        var pending: ((VoiceResult) -> Unit)? = null
        var stops = 0

        override fun speak(text: String, languageTag: String, onDone: (VoiceResult) -> Unit) {
            spoken += text to languageTag
            pending = onDone
        }

        fun finish(result: VoiceResult = VoiceResult.SPOKEN) {
            val done = pending!!
            pending = null
            done(result)
        }

        override fun stop() {
            stops++
            pending = null
        }

        override fun release() = Unit
    }

    private class Call(val prompt: String) {
        val channel = Channel<String>(Channel.UNLIMITED)

        fun reply(vararg chunks: String) {
            chunks.forEach { channel.trySend(it) }
            channel.close()
        }
    }

    private inner class Harness(val test: TestScope, available: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(test.testScheduler))
        val input = FakeInput(available)
        val output = FakeOutput()
        val calls = mutableListOf<Call>()
        var access: ProviderAccess = ProviderAccess.Ready(apiProvider(DEFAULT_API_PROVIDER), "key")
        var clock = 0L

        val session = InterpretationSession(
            scope = scope,
            input = input,
            output = output,
            streamer = ChatStreamer { _, _, prompt ->
                val call = Call(prompt)
                calls += call
                call.channel.consumeAsFlow()
            },
            providerAccess = { access },
            nowMs = { clock },
        ).apply {
            setSpeakers(listOf(SpeakerSetup('A', "zh"), SpeakerSetup('B', "en")))
        }

        val state get() = session.state.value
        val listening get() = state.listening
        val texts get() = state.utterances.map { it.text }

        fun tap(letter: Char) {
            session.press(letter)
            session.release(letter, held = false)
        }

        fun advance(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }

        fun close() = scope.cancel()
    }

    private fun test(available: Boolean = true, body: suspend TestScope.(Harness) -> Unit) = runTest {
        val harness = Harness(this, available)
        try {
            body(harness)
        } finally {
            harness.close()
        }
    }

    @Test
    fun aTapListensUntilTappedAgainAndTranslatesEachSentence() = test { h ->
        h.session.setReadAloud(false)
        h.tap('A')
        assertEquals(Listening('A', held = false, phase = ListenPhase.STARTING), h.listening)
        assertEquals("zh-CN", h.input.language)

        h.input.events.onReady()
        assertEquals(ListenPhase.READY, h.listening?.phase)
        h.input.events.onPartial("请问")
        assertEquals(ListenPhase.HEARING, h.listening?.phase)
        assertEquals(listOf("请问"), h.texts)
        assertFalse(h.state.utterances.single().final)

        h.input.result("请问车站怎么走")
        val utterance = h.state.utterances.single()
        assertTrue(utterance.final)
        assertEquals("请问车站怎么走", utterance.text)
        assertEquals(listOf(Rendition("en")), utterance.renditions)
        // Still listening for the next sentence.
        assertEquals(2, h.input.starts)
        assertEquals(ListenPhase.STARTING, h.listening?.phase)

        val call = h.calls.single()
        assertTrue(call.prompt.contains("口译成English"))
        assertTrue(call.prompt.contains("请问车站怎么走"))
        call.reply(" How do I get", " to the station?")
        assertEquals(
            Rendition("en", "How do I get to the station?", RenditionStatus.DONE),
            h.state.utterances.single().renditions.single(),
        )

        h.tap('A')
        assertNull(h.listening)
        assertEquals(1, h.input.cancels)
    }

    @Test
    fun stoppingWhileSomeoneSpeaksWaitsForTheirLastWords() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.events.onPartial("我想去")
        h.tap('A')
        assertEquals(ListenPhase.FINISHING, h.listening?.phase)
        assertEquals(1, h.input.stops)

        h.input.result("我想去车站")
        assertNull(h.listening)
        assertEquals(listOf("我想去车站"), h.texts)
        assertTrue(h.state.utterances.single().final)
    }

    @Test
    fun theLastWordsHeardAreKeptIfTheResultNeverComes() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.events.onPartial("我想去")
        h.tap('A')

        h.advance(FINISH_TIMEOUT_MS + 1)
        assertNull(h.listening)
        assertEquals(listOf("我想去"), h.texts)
        assertTrue(h.state.utterances.single().final)
        assertEquals(1, h.calls.size)
    }

    @Test
    fun holdingListensAcrossSentencesUntilReleased() = test { h ->
        h.session.press('A')
        h.session.holdStarted('A')
        assertEquals(true, h.listening?.held)

        h.input.events.onReady()
        h.input.result("第一句")
        assertEquals(2, h.input.starts)
        h.input.events.onReady()
        h.input.events.onPartial("第二")

        h.session.release('A', held = true)
        assertEquals(ListenPhase.FINISHING, h.listening?.phase)
        h.input.result("第二句")
        assertNull(h.listening)
        assertEquals(listOf("第一句", "第二句"), h.texts)
    }

    @Test
    fun pressingAndHoldingAnActiveButtonStopsWhenReleased() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.session.press('A')
        h.session.holdStarted('A')
        h.input.events.onPartial("最后一句")
        h.session.release('A', held = true)
        h.input.result("最后一句话")

        assertNull(h.listening)
        assertEquals(listOf("最后一句话"), h.texts)
    }

    @Test
    fun pressingAnotherSpeakerKeepsWhatWasHeardAndSwitches() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.events.onPartial("你好吗")
        val earlier = h.input.events

        h.tap('B')
        assertEquals(Listening('B', held = false, phase = ListenPhase.STARTING), h.listening)
        assertEquals("en-US", h.input.language)
        assertEquals(1, h.input.cancels)
        val utterance = h.state.utterances.single()
        assertEquals('A', utterance.letter)
        assertTrue(utterance.final)
        assertEquals(listOf("en"), utterance.renditions.map { it.language })

        // Anything still arriving from A's recognition is ignored.
        earlier.onResult("你好吗？")
        earlier.onPartial("stale")
        assertEquals(listOf("你好吗"), h.texts)

        h.input.events.onReady()
        h.input.result("Fine, thanks")
        assertEquals(listOf("zh"), h.state.utterances.last().renditions.map { it.language })
    }

    @Test
    fun aPressTakenOverByScrollingDropsWhatItStarted() = test { h ->
        h.session.press('A')
        h.input.events.onReady()
        h.session.abandonPress('A')

        assertNull(h.listening)
        assertTrue(h.state.utterances.isEmpty())
    }

    @Test
    fun silenceKeepsListeningUntilTheIdleLimit() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.error(SpeechError.NO_SPEECH)
        assertEquals(2, h.input.starts)
        assertEquals(ListenPhase.STARTING, h.listening?.phase)

        h.clock += IDLE_LISTEN_LIMIT_MS
        h.input.events.onReady()
        h.input.error(SpeechError.NO_SPEECH)
        assertNull(h.listening)
        assertEquals(InterpretNotice.IdleStopped, h.state.notice)
    }

    @Test
    fun holdingIsNeverStoppedForSilence() = test { h ->
        h.session.press('A')
        h.session.holdStarted('A')
        h.clock += IDLE_LISTEN_LIMIT_MS * 2
        h.input.error(SpeechError.NO_SPEECH)

        assertEquals(2, h.input.starts)
        assertEquals(true, h.listening?.held)
    }

    @Test
    fun translationsAreReadAloudBetweenSentencesWhileListeningPauses() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.result("你好")
        val utteranceId = h.state.utterances.single().id
        h.calls.single().reply("Hello")

        assertEquals(listOf("Hello" to "en-US"), h.output.spoken)
        assertEquals(Reading(utteranceId, "en"), h.state.reading)
        assertEquals(ListenPhase.PAUSED, h.listening?.phase)
        assertNull(h.input.listener)

        h.output.finish()
        assertNull(h.state.reading)
        assertEquals(2, h.input.starts)
        h.advance(RESUME_AFTER_READING_MS + 1)
        assertEquals(3, h.input.starts)
        assertEquals(ListenPhase.STARTING, h.listening?.phase)
    }

    @Test
    fun readingWaitsForTheSpeakerToFinishTheirSentence() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.result("第一句")
        h.input.events.onReady()
        h.input.events.onSpeechBegin()
        h.calls.single().reply("First")
        assertTrue(h.output.spoken.isEmpty())

        h.input.result("第二句")
        assertEquals(listOf("First" to "en-US"), h.output.spoken)
        assertEquals(ListenPhase.PAUSED, h.listening?.phase)
        assertEquals(2, h.input.starts)
    }

    @Test
    fun holdingIsNeverInterruptedByReadingAloud() = test { h ->
        h.session.press('A')
        h.session.holdStarted('A')
        h.input.events.onReady()
        h.input.result("一")
        h.input.events.onReady()
        h.calls.single().reply("One")
        assertTrue(h.output.spoken.isEmpty())

        h.session.release('A', held = true)
        assertNull(h.listening)
        assertEquals(listOf("One" to "en-US"), h.output.spoken)
    }

    @Test
    fun anotherSpeakersPressStopsReadingAloud() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.result("一")
        h.tap('A')
        h.calls.single().reply("One")
        assertEquals(1, h.output.spoken.size)

        h.session.press('B')
        assertEquals(1, h.output.stops)
        assertNull(h.state.reading)
        assertEquals('B', h.listening?.letter)
    }

    @Test
    fun readAloudOffResumesListeningAndReplayReadsAgain() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.result("你好")
        h.calls.single().reply("Hello")
        assertEquals(ListenPhase.PAUSED, h.listening?.phase)

        h.session.setReadAloud(false)
        assertNull(h.state.reading)
        h.advance(RESUME_AFTER_READING_MS + 1)
        assertEquals(ListenPhase.STARTING, h.listening?.phase)

        h.tap('A')
        h.session.replay(h.state.utterances.single().id, "en")
        assertEquals(listOf("Hello" to "en-US", "Hello" to "en-US"), h.output.spoken)
    }

    @Test
    fun aMissingVoiceIsReported() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.result("你好")
        h.tap('A')
        h.calls.single().reply("Hello")
        h.output.finish(VoiceResult.NO_VOICE)

        assertEquals(InterpretNotice.NoVoice("en"), h.state.notice)
    }

    @Test
    fun eachSentenceIsTranslatedIntoEveryOtherLanguageOnce() = test { h ->
        h.session.setReadAloud(false)
        h.session.setSpeakers(
            listOf(SpeakerSetup('A', "zh"), SpeakerSetup('B', "en"), SpeakerSetup('C', "en"), SpeakerSetup('D', "ja")),
        )
        h.tap('A')
        h.input.result("大家好")
        assertEquals(listOf("en", "ja"), h.state.utterances.single().renditions.map { it.language })
        assertEquals(2, h.calls.size)
        assertTrue(h.calls[1].prompt.contains("口译成日本語"))

        h.session.setSpeakers(listOf(SpeakerSetup('A', "zh"), SpeakerSetup('B', "zh")))
        h.tap('B')
        h.input.result("一样的语言")
        assertTrue(h.state.utterances.last().renditions.isEmpty())
        assertEquals(2, h.calls.size)
    }

    @Test
    fun earlierLinesAreSentAsContext() = test { h ->
        h.session.setReadAloud(false)
        h.tap('A')
        h.input.result("第一句")
        h.input.result("第二句")

        assertFalse(h.calls[0].prompt.contains("最近的对话"))
        assertTrue(h.calls[1].prompt.contains("最近的对话（仅供理解上下文，不要翻译）：\nA：第一句\n"))
    }

    @Test
    fun translationProblemsShowOnTheTranslation() = test { h ->
        h.session.setReadAloud(false)
        h.access = ProviderAccess.MissingKey
        h.tap('A')
        h.input.result("你好")
        assertEquals(
            Rendition("en", status = RenditionStatus.FAILED, error = RunError.NoKey),
            h.state.utterances.single().renditions.single(),
        )

        h.access = ProviderAccess.Ready(apiProvider(DEFAULT_API_PROVIDER), "key")
        h.input.result("再见")
        val call = h.calls.single()
        call.channel.trySend("Good")
        call.channel.close(IOException("connection reset"))
        assertEquals(
            Rendition("en", "Good", RenditionStatus.FAILED, RunError.Message("connection reset")),
            h.state.utterances.last().renditions.single(),
        )
    }

    @Test
    fun recognitionProblemsStopListeningWithANotice() = test { h ->
        h.tap('A')
        h.input.events.onPartial("你好")
        h.input.error(SpeechError.LANGUAGE_UNSUPPORTED)
        assertNull(h.listening)
        assertEquals(InterpretNotice.LanguageUnsupported("zh"), h.state.notice)
        // Remembered, so the next press explains at once instead of trying again.
        assertEquals(setOf("zh"), h.state.unheard)
        // What was heard before the problem is kept.
        assertEquals(listOf("你好"), h.texts)

        h.tap('B')
        assertNull(h.state.notice)
        h.input.error(SpeechError.PERMISSION)
        assertEquals(InterpretNotice.NeedMicrophone, h.state.notice)
    }

    @Test
    fun aLanguageThePhoneCannotHearExplainsInsteadOfListening() = test { h ->
        h.session.setLanguageSupport(unheard = setOf("ja", "ko"))
        h.session.setSpeakers(listOf(SpeakerSetup('A', "zh"), SpeakerSetup('B', "ja")))
        h.tap('B')

        assertNull(h.listening)
        assertEquals(0, h.input.starts)
        assertEquals(InterpretNotice.LanguageUnsupported("ja"), h.state.notice)

        // They still get translations into their language.
        h.tap('A')
        h.input.result("你好")
        assertEquals(listOf("ja"), h.state.utterances.single().renditions.map { it.language })
    }

    @Test
    fun learningThatTheActiveLanguageCannotBeHeardStopsListening() = test { h ->
        h.tap('A')
        h.input.events.onPartial("你好")
        h.session.setLanguageSupport(unheard = setOf("zh"))

        assertNull(h.listening)
        assertEquals(listOf("你好"), h.texts)
    }

    @Test
    fun aSentenceWrittenDownAfterListeningMovedOnStillCounts() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.events.onSpeechBegin()
        val earlier = h.input.events

        // Nothing of A's had arrived yet, so nothing was kept when B took over.
        h.tap('B')
        earlier.onResult("还在路上的一句话")

        val utterance = h.state.utterances.single()
        assertEquals('A', utterance.letter)
        assertEquals("还在路上的一句话", utterance.text)
        assertEquals(listOf("en"), utterance.renditions.map { it.language })
        assertEquals('B', h.listening?.letter)
    }

    @Test
    fun moreSentencesFromARecognitionThatAlreadyAnsweredStillCount() = test { h ->
        h.session.setReadAloud(false)
        h.tap('A')
        val first = h.input.events
        h.input.result("第一句")
        first.onResult("第二句")

        assertEquals(listOf("第一句", "第二句"), h.texts)
        assertEquals(2, h.calls.size)
    }

    @Test
    fun wordsThrownAwayStayThrownAway() = test { h ->
        h.tap('A')
        val earlier = h.input.events
        h.session.clear()
        earlier.onResult("迟到的话")

        assertTrue(h.state.utterances.isEmpty())
    }

    @Test
    fun theModelGetsLongerForTheLastWords() = test { h ->
        h.input.finishTimeout = 20_000L
        h.tap('A')
        h.input.events.onReady()
        h.input.events.onSpeechBegin()
        h.tap('A')

        h.advance(FINISH_TIMEOUT_MS + 1)
        assertEquals(ListenPhase.FINISHING, h.listening?.phase)
        h.input.result("慢慢写下来的话")
        assertNull(h.listening)
        assertEquals(listOf("慢慢写下来的话"), h.texts)
    }

    @Test
    fun modelProblemsSayWhatToFix() = test { h ->
        h.tap('A')
        h.input.error(SpeechError.CLOUD_NOT_READY)
        assertNull(h.listening)
        assertEquals(InterpretNotice.CloudNotReady("zh"), h.state.notice)

        h.input.detail = "This model does not accept audio."
        h.tap('A')
        h.input.error(SpeechError.CLOUD_FAILED)
        assertEquals(InterpretNotice.CloudFailed("This model does not accept audio."), h.state.notice)
    }

    @Test
    fun languagesHeardThroughTheModelCanTalk() = test { h ->
        h.session.setLanguageSupport(unheard = emptySet(), throughModel = setOf("ja"))
        h.session.setSpeakers(listOf(SpeakerSetup('A', "zh"), SpeakerSetup('B', "ja")))
        h.tap('B')

        assertEquals('B', h.listening?.letter)
        assertEquals("ja-JP", h.input.language)
        assertEquals(setOf("ja"), h.state.throughModel)
    }

    @Test
    fun aRecognitionAppWithoutTheMicrophoneIsNamed() = test { h ->
        val engine = SpeechEngine("Speech engine", "com.example.speech")
        h.input.blockedEngine = engine
        h.tap('A')
        h.input.error(SpeechError.ENGINE_MICROPHONE)
        assertNull(h.listening)
        assertEquals(InterpretNotice.EngineNeedsMicrophone(engine), h.state.notice)

        // If it can no longer be told which app it is, the notice stays general.
        h.input.blockedEngine = null
        h.tap('A')
        h.input.error(SpeechError.ENGINE_MICROPHONE)
        assertEquals(InterpretNotice.RecognitionFailed, h.state.notice)
    }

    @Test
    fun aBusyRecognizerIsRetriedAFewTimes() = test { h ->
        h.tap('A')
        repeat(3) { attempt ->
            h.input.error(SpeechError.TRANSIENT)
            assertEquals(ListenPhase.STARTING, h.listening?.phase)
            h.advance(400)
            assertEquals(attempt + 2, h.input.starts)
        }
        h.input.error(SpeechError.TRANSIENT)
        assertNull(h.listening)
        assertEquals(InterpretNotice.RecognitionFailed, h.state.notice)
    }

    @Test
    fun withoutSpeechRecognitionNothingListens() = test(available = false) { h ->
        assertEquals(InterpretNotice.NoRecognizer, h.state.notice)
        h.session.dismissNotice()
        h.tap('A')

        assertNull(h.listening)
        assertEquals(0, h.input.starts)
        assertEquals(InterpretNotice.NoRecognizer, h.state.notice)
    }

    @Test
    fun leavingThePageStopsListeningAndReadingAloud() = test { h ->
        h.tap('A')
        h.input.events.onReady()
        h.input.events.onPartial("你好")
        h.session.setActive(false)

        assertNull(h.listening)
        assertEquals(listOf("你好"), h.texts)
        // A translation finishing while the page is away isn't read aloud.
        h.calls.single().reply("Hello")
        assertTrue(h.output.spoken.isEmpty())

        h.session.setActive(true)
        h.tap('A')
        assertEquals('A', h.listening?.letter)
    }

    @Test
    fun changingTheActiveSpeakerStopsTheirListening() = test { h ->
        h.tap('A')
        h.input.events.onPartial("你好")
        h.session.setSpeakers(listOf(SpeakerSetup('A', "ja"), SpeakerSetup('B', "en")))

        assertNull(h.listening)
        assertEquals("zh", h.state.utterances.single().language)
    }

    @Test
    fun clearingEmptiesTheConversationAndCancelsTranslations() = test { h ->
        h.tap('A')
        h.input.result("你好")
        h.input.events.onPartial("还有")
        h.session.clear()

        assertNull(h.listening)
        assertTrue(h.state.utterances.isEmpty())
        h.calls.single().reply("Hello")
        assertTrue(h.state.utterances.isEmpty())
        assertTrue(h.output.spoken.isEmpty())
    }
}
