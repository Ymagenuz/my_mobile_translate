package com.ymagenuz.snipdotranslate.core

import java.util.Locale

object TranslationMode {
    const val AUTO = "auto"
    const val EN_TO_ZH = "en2zh"
    const val ZH_TO_EN = "zh2en"
    const val DICTIONARY = "dictionary"
}

const val SOURCE_AUTO = "auto"
const val TARGET_DEFAULT = "default"

data class LanguageOption(val code: String, val promptLabel: String)

val SOURCE_LANGUAGES = listOf(
    LanguageOption(SOURCE_AUTO, "Auto"),
    LanguageOption("zh", "中文"),
    LanguageOption("en", "English"),
    LanguageOption("ja", "日本語"),
    LanguageOption("ko", "한국어"),
    LanguageOption("fr", "Français"),
    LanguageOption("de", "Deutsch"),
    LanguageOption("es", "Español"),
    LanguageOption("ru", "Русский"),
    LanguageOption("it", "Italiano"),
)

val TARGET_LANGUAGES = listOf(LanguageOption(TARGET_DEFAULT, "默认")) + SOURCE_LANGUAGES.drop(1)

/** The language name written into model prompts; it never changes with the UI. */
fun promptLanguageLabel(code: String, target: Boolean = false): String =
    (if (target) TARGET_LANGUAGES else SOURCE_LANGUAGES).firstOrNull { it.code == code }?.promptLabel ?: "Auto"

private val MARKDOWN_BLOCK = Regex(
    """(?:\A|(?<=\n))$WS*(#{1,6}$WS+|[-*+]$WS+|\p{Nd}+\.$WS+|>$WS+|```|\|[^\n]*\|)""",
)
private val HYPHENATED_LINE_BREAK = Regex("-$WS*\n$WS*")
private val SOFT_LINE_BREAK = Regex("""(?<![.!?。！？:：;；>”"'])\n(?!\n)""")
private val PARAGRAPH_BREAK = Regex("\n+")
private val WHITESPACE_RUN = Regex("$WS+")
private val SENTENCE_PUNCTUATION = Regex("[。！？；：.!?;:]")
private val HAN = Regex("[\\u4e00-\\u9fff]")
private val LATIN_WORD = Regex("[A-Za-z]+(?:[-'][A-Za-z]+)?")
private val SENTENCE_MARKERS = Regex(
    "[\\u6211\\u4f60\\u4ed6\\u5979\\u5b83\\u4e86\\u5417\\u5462\\u5427\\u554a\\u54e6\\u5440]" +
        "|\\u8fd9\\u662f|\\u90a3\\u662f|\\u4e0d\\u662f|\\u6ca1\\u6709|\\u5f88|\\u592a",
)
private val KANA_OR_HANGUL = Regex("[\\u3040-\\u30ff\\uac00-\\ud7af]")
private val KANA = Regex("[\\u3040-\\u30ff]")
private val HANGUL = Regex("[\\uac00-\\ud7af]")
private val CYRILLIC = Regex("[\\u0400-\\u04ff]")
private val LATIN_LETTER = Regex("[A-Za-z]")
private val CHINESE_MARKER = Regex("[的了是在我你他她它们这那不有和与及为就都而也很请将把被让]")
private val LETTER_RUN = Regex("""[\p{L}\p{Nl}\p{No}]+""")

// A few frequent function words per language, in the desktop app's tie-break order.
private val LATIN_LANGUAGE_MARKERS = linkedMapOf(
    "en" to setOf("the", "and", "of", "to", "is", "are", "that", "with", "for", "this"),
    "fr" to setOf("le", "les", "des", "est", "et", "une", "du", "pour", "dans", "pas"),
    "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "mit", "den"),
    "es" to setOf("el", "los", "las", "y", "es", "por", "para", "con", "una", "del"),
    "it" to setOf("il", "gli", "della", "che", "è", "per", "non", "sono", "una", "con"),
)

private fun String.codePointLength(): Int = codePointCount(0, length)

fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")

fun isMarkdownStructuredText(text: String?): Boolean =
    !text.isNullOrEmpty() && MARKDOWN_BLOCK.containsMatchIn(text)

fun isStructuredText(text: String?): Boolean = isMarkdownStructuredText(text) || isLatexText(text)

fun markdownFormatInstruction(text: String): String {
    val hasMarkdown = isMarkdownStructuredText(text)
    val hasLatex = isLatexText(text)
    if (!hasMarkdown && !hasLatex) return ""

    val requirements = mutableListOf("格式要求：")
    if (hasMarkdown) {
        requirements += "- 输入文本包含 Markdown/结构化格式；请保留标题层级、段落、列表、表格、链接和代码块结构。"
        requirements += "- 只翻译自然语言内容，不要翻译 Markdown 标记、URL、代码块、行内代码、变量名、函数名、文件路径和 HTML/XML 标签名。"
        requirements += "- 如果输入是表格，请保持相同的列数和行数；只翻译单元格里的自然语言。"
    }
    if (hasLatex) {
        requirements += "- 输入文本包含 LaTeX；保持命令名、环境、花括号、换行及整体文档结构不变。"
        requirements += "- 只翻译正文、标题、图表标题以及文本命令参数中的自然语言；不要翻译公式、注释、引用键、标签、URL、文件路径或宏名称。"
        requirements += "- [[SNIPDO_LATEX_0000]] 这类标记是受保护的 LaTeX 片段占位符；必须逐字原样输出，每个标记恰好保留一次，不得改写、翻译或添加空格。"
        requirements += "- 直接输出原始 LaTeX，不要添加 Markdown 代码围栏。"
    }
    requirements += "- 直接输出保留格式后的译文，不要解释你做了什么。"
    return requirements.joinToString("\n")
}

/**
 * Cleans up incoming text into paragraphs: repairs words hyphenated across lines
 * and joins lines that only wrapped. The desktop's URL-decoding is left out on
 * purpose, because it only undid SnipDo's encoding and would corrupt any `%`.
 */
fun normalizeInputText(rawText: String?): List<String> {
    if (rawText.isNullOrEmpty()) return emptyList()

    val text = normalizeNewlines(rawText.pyStrip())
    if (isStructuredText(text)) return listOf(text.pyStrip())

    val joined = SOFT_LINE_BREAK.replace(HYPHENATED_LINE_BREAK.replace(text, ""), " ")
    return PARAGRAPH_BREAK.split(joined).map { it.pyStrip() }.filter { it.isNotEmpty() }
}

/** Prepares text received from another app; null when there is nothing to translate. */
fun incomingText(rawText: CharSequence?): String? =
    normalizeInputText(rawText?.toString()).joinToString("\n").pyStrip().takeIf { it.isNotEmpty() }

/** Recognises short lookup terms locally, without an extra API request. */
fun isDictionaryMode(text: String?): Boolean {
    val rawText = text.orEmpty().pyStrip()
    val clean = WHITESPACE_RUN.replace(rawText, " ")
    if (clean.isEmpty() || "\n" in rawText) return false
    if (isStructuredText(rawText)) return false
    if (SENTENCE_PUNCTUATION.containsMatchIn(clean)) return false

    val hanCount = HAN.findAll(clean).count()
    val latinWords = LATIN_WORD.findAll(clean).count()
    val length = clean.codePointLength()

    if (hanCount > 0) {
        if (SENTENCE_MARKERS.containsMatchIn(clean)) return false
        return hanCount <= 4 && length <= 8 && latinWords <= 1
    }
    if (latinWords > 0) {
        return if (latinWords == 1) length <= 32 else latinWords <= 3 && length <= 28
    }
    return length <= 10 && clean.codePoints().anyMatch(Character::isLetter)
}

/** Resolves the translation direction locally, without a language-detection request. */
fun resolveAutoTranslationMode(
    text: String?,
    sourceLang: String = SOURCE_AUTO,
    targetLang: String = TARGET_DEFAULT,
): String {
    if (targetLang == "en") return TranslationMode.ZH_TO_EN
    if (targetLang == "zh") return TranslationMode.EN_TO_ZH
    if (targetLang != TARGET_DEFAULT) return TranslationMode.AUTO

    if (sourceLang == "zh") return TranslationMode.ZH_TO_EN
    if (sourceLang != SOURCE_AUTO) return TranslationMode.EN_TO_ZH

    val trimmed = text.orEmpty().pyStrip()
    if (trimmed.isEmpty()) return TranslationMode.EN_TO_ZH
    val clean = latexProseForLanguageDetection(trimmed)
    if (clean.isEmpty()) return TranslationMode.EN_TO_ZH

    val hanCount = HAN.findAll(clean).count()
    val kanaOrHangulCount = KANA_OR_HANGUL.findAll(clean).count()
    val latinLetterCount = LATIN_LETTER.findAll(clean).count()
    val markerCount = CHINESE_MARKER.findAll(clean).count()

    // Kana or Hangul normally means Japanese or Korean, but a small quoted term
    // should not override a predominantly Chinese paragraph.
    if (kanaOrHangulCount > 0 && kanaOrHangulCount * 2 >= hanCount) return TranslationMode.EN_TO_ZH
    if (hanCount > 0 && (latinLetterCount == 0 || hanCount * 4 + markerCount * 4 >= latinLetterCount)) {
        return TranslationMode.ZH_TO_EN
    }
    return TranslationMode.EN_TO_ZH
}

/** Decides how a text will be handled before anything is sent. */
fun resolveEffectiveMode(
    text: String,
    forceDictionary: Boolean,
    sourceLang: String = SOURCE_AUTO,
    targetLang: String = TARGET_DEFAULT,
): String = when {
    forceDictionary || isDictionaryMode(text) -> TranslationMode.DICTIONARY
    else -> resolveAutoTranslationMode(text, sourceLang, targetLang)
}

/** The language code of a translation's output; empty when it isn't known. */
fun translationTargetLanguage(mode: String, targetLang: String): String = when (mode) {
    TranslationMode.ZH_TO_EN -> "en"
    TranslationMode.EN_TO_ZH -> "zh"
    else -> if (targetLang == TARGET_DEFAULT) "" else targetLang
}

/** The language name a back-translation prompt asks for. */
fun backTranslationLanguageLabel(code: String): String =
    if (code == "zh") "简体中文" else promptLanguageLabel(code)

/** Names the source language locally, so back-translation never sees the source. */
fun detectTextLanguage(text: String?): String {
    if (resolveAutoTranslationMode(text) == TranslationMode.ZH_TO_EN) return "zh"
    val sample = latexProseForLanguageDetection(text.orEmpty().pyStrip())
    if (KANA.containsMatchIn(sample)) return "ja"
    if (HANGUL.containsMatchIn(sample)) return "ko"
    if (CYRILLIC.containsMatchIn(sample)) return "ru"

    val words = LETTER_RUN.findAll(sample.lowercase(Locale.ROOT)).map { it.value }.toList()
    val scores = LATIN_LANGUAGE_MARKERS.mapValues { (_, markers) -> words.count { it in markers } }
    val best = scores.entries.first { it.value == scores.values.max() }.key
    return if (scores.getValue(best) > scores.getValue("en")) best else "en"
}
