package com.ymagenuz.snipdotranslate.select.core

import com.ymagenuz.snipdotranslate.core.incomingText

private val CJK = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]")

private fun isCjk(character: Char) = CJK.matches(character.toString())

private fun isPunctuation(character: Char): Boolean = when (Character.getType(character).toByte()) {
    Character.CONNECTOR_PUNCTUATION,
    Character.DASH_PUNCTUATION,
    Character.START_PUNCTUATION,
    Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION,
    Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION,
    -> true
    else -> false
}

private fun joinsWithoutSpace(before: Char, after: Char): Boolean =
    (isCjk(before) && (isCjk(after) || isPunctuation(after))) || (isPunctuation(before) && isCjk(after))

/** Builds the text to translate from words already in the order they should be read. */
fun assembleText(words: List<ScreenWord>): String {
    if (words.isEmpty()) return ""
    // A single tapped word goes to the dictionary, which sentence punctuation would prevent.
    words.singleOrNull()?.let { word ->
        return word.text.trim { isPunctuation(it) }.ifEmpty { word.text }
    }

    val text = StringBuilder(words.first().text)
    for ((previous, current) in words.zipWithNext()) {
        val noSpace = joinsWithoutSpace(previous.text.last(), current.text.first())
        val separator = when {
            previous.blockId != current.blockId -> "\n\n"
            previous.lineId != current.lineId -> if (noSpace) "" else "\n"
            noSpace && current.id == previous.id + 1 -> ""
            else -> " "
        }
        text.append(separator).append(current.text)
    }
    // Repairs words hyphenated across lines and joins lines that only wrapped.
    return incomingText(text).orEmpty()
}
