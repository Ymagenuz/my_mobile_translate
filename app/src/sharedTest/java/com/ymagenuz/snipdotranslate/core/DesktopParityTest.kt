package com.ymagenuz.snipdotranslate.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Compares the port with results recorded from the desktop source by scripts/generate_golden.py. */
class DesktopParityTest {
    private val golden: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/golden/desktop_rules.json")).readText(),
    ).jsonObject

    private val prompts = golden.getValue("prompts").jsonObject
    private val rules = golden.getValue("rules").jsonArray.map { it.jsonObject }

    private fun prompt(name: String) = prompts.getValue(name).jsonPrimitive.content

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.flag(name: String) = getValue(name).jsonPrimitive.boolean

    private fun check(field: String, expected: (JsonObject) -> Any?, actual: (String) -> Any?) {
        val mismatches = rules.mapNotNull { case ->
            val text = case.string("text")
            val wanted = expected(case)
            val got = actual(text)
            if (wanted == got) null else "$field of ${Json.encodeToString(text)}: desktop=$wanted port=$got"
        }
        assertEquals(mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun promptsAreWordForWord() {
        assertEquals(prompt("dictionary"), Prompts.dictionary("SOURCE", "TARGET", "TEXT"))
        assertEquals(prompt("auto"), Prompts.autoTranslate("SOURCE", "TARGET", "TEXT"))
        assertEquals(prompt("toEnglish"), Prompts.toEnglish("TEXT"))
        assertEquals(prompt("toChinese"), Prompts.toChinese("TEXT"))
        assertEquals(prompt("back"), Prompts.backTranslate("LANGUAGE", "TEXT"))
        assertEquals(prompt("ocr"), Prompts.OCR)
    }

    @Test
    fun latexDetection() = check("isLatex", { it.flag("isLatex") }, ::isLatexText)

    @Test
    fun markdownDetection() = check("isMarkdown", { it.flag("isMarkdown") }, ::isMarkdownStructuredText)

    @Test
    fun structuredDetection() = check("isStructured", { it.flag("isStructured") }, ::isStructuredText)

    @Test
    fun latexProtectionText() = check("protected", { it.string("protected") }) { protectLatexFragments(it).text }

    @Test
    fun latexProtectionReplacements() = check(
        "replacements",
        { case -> case.getValue("replacements").jsonObject.mapValues { it.value.jsonPrimitive.content } },
        { protectLatexFragments(it).replacements },
    )

    @Test
    fun languageDetectionProse() = check("prose", { it.string("prose") }, ::latexProseForLanguageDetection)

    @Test
    fun formatInstruction() = check("formatInstruction", { it.string("formatInstruction") }, ::markdownFormatInstruction)

    @Test
    fun inputNormalization() = check(
        "normalized",
        { case -> case.getValue("normalized").jsonArray.map { it.jsonPrimitive.content } },
        ::normalizeInputText,
    )

    @Test
    fun dictionaryRecognition() = check("isDictionary", { it.flag("isDictionary") }, ::isDictionaryMode)

    @Test
    fun autoDirection() = check("autoMode", { it.string("autoMode") }, { resolveAutoTranslationMode(it) })

    @Test
    fun languageNaming() = check("language", { it.string("language") }, ::detectTextLanguage)
}
