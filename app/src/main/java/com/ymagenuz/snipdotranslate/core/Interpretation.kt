package com.ymagenuz.snipdotranslate.core

import java.util.Locale

/** Languages a speaker can speak. Auto is left out, because speech recognition needs one. */
val INTERPRET_LANGUAGES: List<String> = SOURCE_LANGUAGES.map { it.code }.filter { it != SOURCE_AUTO }

const val SPEAKER_LETTERS = "ABCDEF"
val MAX_SPEAKERS = SPEAKER_LETTERS.length
const val MIN_SPEAKERS = 2

private const val CONTEXT_LINES = 3
private const val CONTEXT_LINE_CHARS = 300

/** One person in the conversation: the letter on their button and the language they speak. */
data class SpeakerSetup(val letter: Char, val language: String)

/** The locale given to speech recognition and text-to-speech for each language. */
fun speechLanguageTag(code: String): String = when (code) {
    "zh" -> "zh-CN"
    "en" -> "en-US"
    "ja" -> "ja-JP"
    "ko" -> "ko-KR"
    "fr" -> "fr-FR"
    "de" -> "de-DE"
    "es" -> "es-ES"
    "ru" -> "ru-RU"
    "it" -> "it-IT"
    else -> code
}

/**
 * What known recognition engines can hear, for those that don't say. Xiaomi's (seen on a
 * Xiaomi 17 with HyperOS 4) hears only Chinese and English, and returns nothing for the rest.
 */
val KNOWN_ENGINE_LANGUAGES: Map<String, Set<String>> = mapOf(
    "com.xiaomi.mibrain.speech" to setOf("zh", "en"),
)

/** The app's languages among those an engine reports, written like "cmn-Hans-CN" or "en_US". */
fun recognizedLanguageCodes(tags: Collection<String>): Set<String> = tags.mapNotNull { tag ->
    val language = Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase()
    // Mandarin; Cantonese ("yue") isn't what the app's Chinese means.
    (if (language == "cmn") "zh" else language).takeIf { it in INTERPRET_LANGUAGES }
}.toSet()

/** Speaker A speaks the phone's language and B speaks English, or Chinese if A already does. */
fun defaultSpeakers(deviceLanguage: String): List<SpeakerSetup> {
    val first = deviceLanguage.lowercase().takeIf { it in INTERPRET_LANGUAGES } ?: "en"
    val second = if (first == "en") "zh" else "en"
    return listOf(SpeakerSetup('A', first), SpeakerSetup('B', second))
}

/** Adds a speaker with the first free letter and a language nobody speaks yet; null when full. */
fun withAddedSpeaker(speakers: List<SpeakerSetup>): List<SpeakerSetup>? {
    if (speakers.size >= MAX_SPEAKERS) return null
    val letter = SPEAKER_LETTERS.first { candidate -> speakers.none { it.letter == candidate } }
    val language = INTERPRET_LANGUAGES.firstOrNull { code -> speakers.none { it.language == code } }
        ?: speakers.lastOrNull()?.language
        ?: INTERPRET_LANGUAGES.first()
    return (speakers + SpeakerSetup(letter, language)).sortedBy { it.letter }
}

/** Removes a speaker; null when that would leave fewer than two. */
fun withoutSpeaker(speakers: List<SpeakerSetup>, letter: Char): List<SpeakerSetup>? =
    if (speakers.size <= MIN_SPEAKERS) null else speakers.filter { it.letter != letter }

fun withSpeakerLanguage(speakers: List<SpeakerSetup>, letter: Char, language: String): List<SpeakerSetup> =
    speakers.map { if (it.letter == letter && language in INTERPRET_LANGUAGES) it.copy(language = language) else it }

fun encodeSpeakers(speakers: List<SpeakerSetup>): String =
    speakers.joinToString(",") { "${it.letter}=${it.language}" }

/** Reads saved speakers; null when the value isn't a usable set, so the defaults apply. */
fun decodeSpeakers(text: String?): List<SpeakerSetup>? {
    if (text.isNullOrBlank()) return null
    val speakers = text.split(',').map { part ->
        val letter = part.substringBefore('=', "")
        val language = part.substringAfter('=', "")
        if (letter.length != 1 || letter[0] !in SPEAKER_LETTERS || language !in INTERPRET_LANGUAGES) return null
        SpeakerSetup(letter[0], language)
    }
    val distinct = speakers.distinctBy { it.letter }.size == speakers.size
    return speakers.takeIf { distinct && it.size in MIN_SPEAKERS..MAX_SPEAKERS }?.sortedBy { it.letter }
}

/** What one speaker says is translated into everyone else's languages, apart from their own. */
fun interpretationTargets(speaker: SpeakerSetup, speakers: List<SpeakerSetup>): List<String> =
    speakers.filter { it.letter != speaker.letter }.map { it.language }.filter { it != speaker.language }.distinct()

/** Something said earlier in the conversation, given to the model to make sense of what follows. */
data class ContextLine(val letter: Char, val text: String)

/** The most recent finished lines before the one being translated. */
fun recentContext(lines: List<ContextLine>): List<ContextLine> =
    lines.takeLast(CONTEXT_LINES).map { it.copy(text = it.text.take(CONTEXT_LINE_CHARS)) }

/**
 * The desktop app has no interpretation, so unlike [Prompts] this prompt is the Android app's own.
 * The text comes from speech recognition, which mishears words and leaves out punctuation.
 */
fun buildInterpretationPrompt(
    text: String,
    sourceLang: String,
    targetLang: String,
    context: List<ContextLine> = emptyList(),
): String {
    val source = promptLanguageLabel(sourceLang)
    val target = promptLanguageLabel(targetLang, target = true)
    val recent = recentContext(context)
    val contextSection = if (recent.isEmpty()) {
        ""
    } else {
        "\n最近的对话（仅供理解上下文，不要翻译）：\n" +
            recent.joinToString("\n") { "${it.letter}：${it.text}" } + "\n"
    }
    return """
你是一名专业的同声传译员，正在为面对面的对话做口译。下面是说话人用${source}说的一段话，由语音识别转写，可能有同音错字、漏字或缺少标点。请把它口译成${target}。

规则：
1. 结合上下文纠正明显的语音识别错误，忠实传达原意和语气，不要补充原文没有的信息。
2. 译文要口语化、简洁自然，适合直接朗读。
3. 只输出译文，不要解释，不要加前缀、引号或 Markdown。
$contextSection
需要口译的内容：
${text.pyStrip()}
"""
}

/** What the model answers when a recording has no words in it. */
const val NO_SPEECH_MARK = "NO_SPEECH"

/** Asks an audio-capable chat model to write down a recording, as OCR asks a vision model to read. */
fun buildTranscriptionPrompt(language: String): String = """
请把这段录音里说话人用${promptLanguageLabel(language)}说的话逐字转写出来。
要求：
1. 只输出转写的原文，不要翻译、不要解释，不要加引号、前缀或 Markdown。
2. 按说话内容加上合适的标点。
3. 如果录音里没有能听清的话，只输出 NO_SPEECH。
"""

/** What the model wrote down, or "" when it heard no words. */
fun transcriptionText(reply: String): String {
    val text = normalizeNewlines(reply).trim()
    return if (text.trimEnd('.', '。').uppercase(Locale.ROOT) == NO_SPEECH_MARK) "" else text
}
