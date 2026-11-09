package com.ymagenuz.snipdotranslate.domain

import com.ymagenuz.snipdotranslate.core.DEFAULT_API_PROVIDER
import com.ymagenuz.snipdotranslate.core.TranslationMode
import com.ymagenuz.snipdotranslate.core.apiProvider
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationSessionTest {
    private class Call(val prompt: String) {
        val channel = Channel<String>(Channel.UNLIMITED)
        var completed = false

        fun send(vararg chunks: String) = chunks.forEach { channel.trySend(it) }

        fun finish() {
            channel.close()
        }

        fun fail(message: String) {
            channel.close(IOException(message))
        }
    }

    private inner class Harness(testScope: TestScope) {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScope.testScheduler))
        val calls = mutableListOf<Call>()
        val finished = mutableListOf<Triple<String, String, String>>()
        var access: ProviderAccess = ProviderAccess.Ready(apiProvider(DEFAULT_API_PROVIDER), "key")
        var backEnabled = false
        var clock = 0L

        val session = TranslationSession(
            scope = scope,
            streamer = ChatStreamer { _, _, prompt ->
                val call = Call(prompt)
                calls += call
                call.channel.consumeAsFlow().onCompletion { call.completed = true }
            },
            providerAccess = { access },
            backTranslationEnabled = { backEnabled },
            onFinished = { source, result, mode -> finished += Triple(source, result, mode) },
            nowMs = { clock },
        )

        val state get() = session.state.value

        fun translate(text: String, forceDictionary: Boolean = false, source: String = "auto", target: String = "default") {
            session.translate(TranslationRequest(text, forceDictionary, source, target))
        }

        fun close() = scope.cancel()
    }

    private fun test(body: suspend TestScope.(Harness) -> Unit) = runTest {
        val harness = Harness(this)
        try {
            body(harness)
        } finally {
            harness.close()
        }
    }

    private val sentence = "This is a full sentence that should be translated."

    @Test
    fun aFinishedTranslationIsPublishedAndSaved() = test { h ->
        h.translate(sentence)
        assertEquals(RunStatus.RUNNING, h.state.status)
        assertEquals(TranslationMode.EN_TO_ZH, h.state.mode)

        h.calls.single().send("译", "文")
        h.calls.single().finish()

        assertEquals(RunStatus.DONE, h.state.status)
        assertEquals("译文", h.state.text)
        assertEquals(listOf(Triple(sentence, "译文", TranslationMode.EN_TO_ZH)), h.finished)
    }

    @Test
    fun shortTermsAreLookedUpAndDictionaryCanBeForced() = test { h ->
        h.translate("hello")
        assertEquals(TranslationMode.DICTIONARY, h.state.mode)
        assertTrue("专业的多语言词典助手" in h.calls.last().prompt)

        h.translate(sentence, forceDictionary = true)
        assertEquals(TranslationMode.DICTIONARY, h.state.mode)
    }

    @Test
    fun explicitLanguagesReachThePrompt() = test { h ->
        h.translate(sentence, source = "fr", target = "ja")
        assertEquals(TranslationMode.AUTO, h.state.mode)
        assertTrue("原文语言：Français" in h.calls.single().prompt)
        assertTrue("目标语言：日本語" in h.calls.single().prompt)
    }

    @Test
    fun latexIsRestoredEvenWhenAPlaceholderIsSplit() = test { h ->
        h.translate("The value \$E = mc^2\$ is well known in physics classes.")
        val call = h.calls.single()
        assertFalse("\$E = mc^2\$" in call.prompt)

        call.send("结果 [[SNIPDO_LA", "TEX_0000]] 很有名")
        call.finish()

        assertEquals("结果 \$E = mc^2\$ 很有名", h.state.text)
    }

    @Test
    fun partialTextIsThrottledButTheEndIsComplete() = test { h ->
        h.translate(sentence)
        val call = h.calls.single()

        h.clock = 500
        call.send("a")
        assertEquals("a", h.state.text)
        h.clock = 550
        call.send("b")
        assertEquals("a", h.state.text)
        h.clock = 620
        call.send("c")
        assertEquals("abc", h.state.text)
        h.clock = 621
        call.send("d")
        call.finish()
        assertEquals("abcd", h.state.text)
    }

    @Test
    fun stopKeepsWhatArrivedAndCancelsTheCall() = test { h ->
        h.backEnabled = true
        h.translate(sentence)
        val call = h.calls.single()
        h.clock = 500
        call.send("partial")

        h.session.stop()

        assertEquals(RunStatus.STOPPED, h.state.status)
        assertEquals("partial", h.state.text)
        assertTrue(call.completed)
        assertTrue(h.finished.isEmpty())
        assertEquals(1, h.calls.size)
    }

    @Test
    fun failureKeepsWhatArrivedAndRetryRunsTheSameRequest() = test { h ->
        h.backEnabled = true
        h.translate(sentence, source = "en", target = "zh")
        h.clock = 500
        h.calls.single().send("partial")
        h.calls.single().fail("network down")

        assertEquals(RunStatus.FAILED, h.state.status)
        assertEquals("partial", h.state.text)
        assertEquals(RunError.Message("network down"), h.state.error)
        assertTrue(h.finished.isEmpty())
        assertEquals(1, h.calls.size)

        h.session.retry()

        assertEquals(RunStatus.RUNNING, h.state.status)
        assertEquals("", h.state.text)
        assertEquals(2, h.calls.size)
        assertEquals(h.calls[0].prompt, h.calls[1].prompt)
    }

    @Test
    fun aNewTranslationReplacesTheRunningOne() = test { h ->
        h.translate(sentence)
        val old = h.calls.single()

        h.translate("这是另一个需要翻译的完整句子，内容不一样。")
        old.send("late")
        val new = h.calls.last()
        new.send("new")
        new.finish()

        assertTrue(old.completed)
        assertEquals(RunStatus.DONE, h.state.status)
        assertEquals("new", h.state.text)
        assertEquals(1, h.finished.size)
    }

    @Test
    fun missingKeyOrModelFailsWithoutCallingTheApi() = test { h ->
        h.access = ProviderAccess.MissingKey
        h.translate(sentence)
        assertEquals(RunStatus.FAILED, h.state.status)
        assertEquals(RunError.NoKey, h.state.error)

        h.access = ProviderAccess.MissingModel
        h.translate(sentence)
        assertEquals(RunError.NoModel, h.state.error)
        assertTrue(h.calls.isEmpty())
    }

    @Test
    fun blankTextIsIgnored() = test { h ->
        h.translate("   ")
        assertEquals(RunStatus.IDLE, h.state.status)
        assertTrue(h.calls.isEmpty())
    }

    @Test
    fun backTranslationIsOffByDefault() = test { h ->
        h.translate(sentence)
        h.calls.single().send("译文")
        h.calls.single().finish()

        assertEquals(1, h.calls.size)
        assertEquals(BackStatus.HIDDEN, h.state.back.status)
    }

    @Test
    fun backTranslationSeesOnlyTheTranslation() = test { h ->
        h.backEnabled = true
        h.translate(sentence)
        h.calls[0].send("译文内容")
        h.calls[0].finish()

        assertEquals(BackStatus.RUNNING, h.state.back.status)
        assertEquals("en", h.state.back.languageCode)
        val back = h.calls[1]
        assertTrue("忠实地翻译成English" in back.prompt)
        assertTrue("译文内容" in back.prompt)
        assertFalse(sentence in back.prompt)

        back.send("Translated text")
        back.finish()

        assertEquals(BackStatus.DONE, h.state.back.status)
        assertEquals("Translated text", h.state.back.text)
        assertEquals(RunStatus.DONE, h.state.status)
    }

    @Test
    fun chineseSourcesAreBackTranslatedIntoSimplifiedChinese() = test { h ->
        h.backEnabled = true
        h.translate("这是一段需要翻译成英文的完整中文句子。")
        h.calls[0].send("An English result")
        h.calls[0].finish()

        assertTrue("忠实地翻译成简体中文" in h.calls[1].prompt)
    }

    @Test
    fun dictionaryResultsAreNotBackTranslated() = test { h ->
        h.backEnabled = true
        h.translate("hello")
        h.calls[0].send("# hello")
        h.calls[0].finish()

        assertEquals(1, h.calls.size)
        assertEquals(BackStatus.HIDDEN, h.state.back.status)
    }

    @Test
    fun translationsIntoTheSourceLanguageAreNotBackTranslated() = test { h ->
        h.backEnabled = true
        h.translate(sentence, source = "zh", target = "zh")
        h.calls[0].send("译文")
        h.calls[0].finish()

        assertEquals(1, h.calls.size)
        assertEquals(BackStatus.SAME_LANGUAGE, h.state.back.status)
    }

    @Test
    fun stoppingBackTranslationLeavesTheTranslationAlone() = test { h ->
        h.backEnabled = true
        h.translate(sentence)
        h.calls[0].send("译文")
        h.calls[0].finish()
        h.clock = 500
        h.calls[1].send("par")

        h.session.stopBackTranslation()

        assertEquals(BackStatus.STOPPED, h.state.back.status)
        assertEquals("par", h.state.back.text)
        assertEquals(RunStatus.DONE, h.state.status)
        assertEquals("译文", h.state.text)
        assertTrue(h.calls[1].completed)
    }

    @Test
    fun backTranslationFailureLeavesTheTranslationAlone() = test { h ->
        h.backEnabled = true
        h.translate(sentence)
        h.calls[0].send("译文")
        h.calls[0].finish()
        h.calls[1].fail("boom")

        assertEquals(BackStatus.FAILED, h.state.back.status)
        assertEquals("boom", h.state.back.error)
        assertEquals(RunStatus.DONE, h.state.status)
    }

    @Test
    fun togglingAppliesToTheResultOnScreen() = test { h ->
        h.translate(sentence)
        h.calls[0].send("译文")
        h.calls[0].finish()
        assertEquals(1, h.calls.size)

        h.backEnabled = true
        h.session.onBackTranslationToggled(true)
        assertEquals(BackStatus.RUNNING, h.state.back.status)
        assertEquals(2, h.calls.size)

        h.backEnabled = false
        h.session.onBackTranslationToggled(false)
        assertEquals(BackStatus.HIDDEN, h.state.back.status)
        assertTrue(h.calls[1].completed)
    }

    @Test
    fun savedResultsAreShownWithoutBeingSavedAgain() = test { h ->
        h.backEnabled = true
        h.session.showFinished(TranslationRequest(sentence), TranslationMode.EN_TO_ZH, "译文")

        assertEquals(RunStatus.DONE, h.state.status)
        assertEquals("译文", h.state.text)
        assertTrue(h.finished.isEmpty())
        assertEquals(BackStatus.RUNNING, h.state.back.status)
        assertEquals(1, h.calls.size)
    }

    @Test
    fun aHistoryFailureDoesNotLoseTheTranslation() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val call = Call("")
        val session = TranslationSession(
            scope = scope,
            streamer = ChatStreamer { _, _, _ -> call.channel.consumeAsFlow() },
            providerAccess = { ProviderAccess.Ready(apiProvider(DEFAULT_API_PROVIDER), "k") },
            backTranslationEnabled = { false },
            onFinished = { _, _, _ -> throw IOException("disk full") },
        )

        session.translate(TranslationRequest(sentence))
        call.send("译文")
        call.finish()

        assertEquals(RunStatus.DONE, session.state.value.status)
        assertEquals("译文", session.state.value.text)
        scope.cancel()
    }
}
