package com.ymagenuz.snipdotranslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationTest {
    private val a = SpeakerSetup('A', "zh")
    private val b = SpeakerSetup('B', "en")

    @Test
    fun speakersStartWithThePhoneLanguageAndEnglish() {
        assertEquals(listOf(a, b), defaultSpeakers("zh"))
        assertEquals(listOf(SpeakerSetup('A', "en"), SpeakerSetup('B', "zh")), defaultSpeakers("en"))
        assertEquals(listOf(SpeakerSetup('A', "ja"), SpeakerSetup('B', "en")), defaultSpeakers("JA"))
        // A language the app doesn't offer.
        assertEquals(listOf(SpeakerSetup('A', "en"), SpeakerSetup('B', "zh")), defaultSpeakers("pt"))
    }

    @Test
    fun everyAppLanguageButAutoCanBeSpoken() {
        assertFalse(SOURCE_AUTO in INTERPRET_LANGUAGES)
        assertEquals(SOURCE_LANGUAGES.size - 1, INTERPRET_LANGUAGES.size)
        INTERPRET_LANGUAGES.forEach { assertTrue(speechLanguageTag(it).startsWith("$it-")) }
    }

    @Test
    fun engineLanguagesAreReadInTheirUsualSpellings() {
        assertEquals(
            setOf("zh", "en", "ja", "ko"),
            recognizedLanguageCodes(listOf("cmn-Hans-CN", "en-US", "en-GB", "ja_JP", "ko", "yue-Hant-HK", "pt-BR", "")),
        )
        assertEquals(setOf("zh", "en"), KNOWN_ENGINE_LANGUAGES["com.xiaomi.mibrain.speech"])
    }

    @Test
    fun theModelIsAskedToWriteDownTheSpeakersLanguage() {
        val prompt = buildTranscriptionPrompt("ja")
        assertTrue(prompt.contains("用日本語说的话逐字转写"))
        assertTrue(prompt.contains(NO_SPEECH_MARK))

        assertEquals("駅はどこですか。", transcriptionText("  駅はどこですか。\r\n"))
        listOf("NO_SPEECH", "no_speech.", " NO_SPEECH。 ").forEach { assertEquals(it, "", transcriptionText(it)) }
    }

    @Test
    fun addedSpeakersTakeTheNextFreeLetterAndAnUnusedLanguage() {
        val three = withAddedSpeaker(listOf(a, b))!!
        assertEquals(SpeakerSetup('C', "ja"), three.last())

        // A removed letter is reused, and the list stays in letter order.
        val gap = listOf(a, SpeakerSetup('C', "ja"))
        assertEquals(listOf('A', 'B', 'C'), withAddedSpeaker(gap)!!.map { it.letter })

        var full = listOf(a, b)
        repeat(MAX_SPEAKERS - 2) { full = withAddedSpeaker(full)!! }
        assertEquals(SPEAKER_LETTERS.toList(), full.map { it.letter })
        assertEquals(MAX_SPEAKERS, full.map { it.language }.distinct().size)
        assertNull(withAddedSpeaker(full))
    }

    @Test
    fun atLeastTwoSpeakersStay() {
        assertNull(withoutSpeaker(listOf(a, b), 'A'))
        val c = SpeakerSetup('C', "ja")
        assertEquals(listOf(a, c), withoutSpeaker(listOf(a, b, c), 'B'))
    }

    @Test
    fun onlyOfferedLanguagesCanBeChosen() {
        assertEquals(listOf(a, SpeakerSetup('B', "ko")), withSpeakerLanguage(listOf(a, b), 'B', "ko"))
        assertEquals(listOf(a, b), withSpeakerLanguage(listOf(a, b), 'B', "auto"))
    }

    @Test
    fun speakersRoundTripAndBadValuesAreRejected() {
        val speakers = listOf(a, b, SpeakerSetup('D', "fr"))
        assertEquals("A=zh,B=en,D=fr", encodeSpeakers(speakers))
        assertEquals(speakers, decodeSpeakers(encodeSpeakers(speakers)))
        assertEquals(listOf(a, b), decodeSpeakers("B=en,A=zh"))

        listOf(null, "", "A=zh", "A=zh,A=en", "A=zh,B=xx", "A=zh,G=en", "AB=zh,B=en", "A:zh,B:en", "A=zh,B=en,")
            .forEach { assertNull(it, decodeSpeakers(it)) }
    }

    @Test
    fun eachSpeakerIsTranslatedIntoTheOtherLanguagesOnce() {
        val c = SpeakerSetup('C', "en")
        val d = SpeakerSetup('D', "zh")
        val speakers = listOf(a, b, c, d)
        assertEquals(listOf("en"), interpretationTargets(a, speakers))
        assertEquals(listOf("zh"), interpretationTargets(b, speakers))
        assertEquals(emptyList<String>(), interpretationTargets(a, listOf(a, d)))
    }

    @Test
    fun promptNamesBothLanguagesAndCarriesRecentContext() {
        val context = (1..5).map { ContextLine(if (it % 2 == 0) 'B' else 'A', "line $it") } +
            ContextLine('B', "x".repeat(400))
        val prompt = buildInterpretationPrompt("  请问车站怎么走  ", "zh", "en", context)

        assertTrue(prompt.contains("说话人用中文说的一段话"))
        assertTrue(prompt.contains("口译成English。"))
        assertTrue(prompt.trimEnd().endsWith("需要口译的内容：\n请问车站怎么走"))
        assertTrue(prompt.contains("最近的对话"))
        assertFalse(prompt.contains("line 3"))
        assertTrue(prompt.contains("B：line 4\nA：line 5\nB：" + "x".repeat(300) + "\n"))
        assertFalse(prompt.contains("x".repeat(301)))

        assertFalse(buildInterpretationPrompt("hi", "en", "ja").contains("最近的对话"))
    }
}
