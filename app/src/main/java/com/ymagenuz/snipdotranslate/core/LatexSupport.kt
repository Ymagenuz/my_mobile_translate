package com.ymagenuz.snipdotranslate.core

const val LATEX_PLACEHOLDER_PREFIX = "[[SNIPDO_LATEX_"
const val LATEX_PLACEHOLDER_SUFFIX = "]]"

/**
 * Python's Unicode `\s`, spelled out. `\s`, `\w` and `\b` mean different things on
 * the JVM and on Android's ICU regex engine, and `(?U)` is not accepted on Android.
 */
internal const val WS = """[\s\p{Z}\x0B\x1C-\x1F\x85]"""

/** What Python's `\b` means straight after a word character. */
private const val NOT_WORD_AHEAD = """(?![\p{L}\p{N}_])"""

internal fun String.pyStrip(): String = trim { it.isWhitespace() || it == '\u0085' }

private val LATEX_SIGNAL = Regex(
    """\\(?:documentclass|usepackage|begin|end|chapter|section|subsection|""" +
        """subsubsection|paragraph|title|author|maketitle|item|caption|""" +
        """textbf|textit|emph|label|ref|eqref|pageref|autoref|cref|Cref|""" +
        """cite|citep|citet|frac|dfrac|tfrac|sqrt|sum|prod|int|lim|""" +
        """alpha|beta|gamma|delta|theta|lambda|mu|pi|sigma|phi|omega|""" +
        """mathrm|mathbf|mathit|mathbb|mathcal|operatorname|text)""" +
        """\*?(?=$WS*[\[{]|$NOT_WORD_AHEAD)|\\[\[(]""",
)

// The possessive run keeps a very long line from overflowing the regex stack.
private val INLINE_MATH = Regex(
    """(?<!\\)\$(?!\$)(?:[^$\\\r\n]++|\\[^\n])+(?<!\\)\$(?!\$)""",
)

private val DISPLAY_MATH = Regex(
    """(?<!\\)\$\$.*?(?<!\\)\$\$|\\\[.*?\\\]""",
    RegexOption.DOT_MATCHES_ALL,
)

private val PROTECTED_LATEX = Regex(
    """\\begin\{(?<mathEnv>""" +
        """equation\*?|align\*?|alignat\*?|gather\*?|multline\*?|""" +
        """flalign\*?|displaymath|math|split|cases|matrix|pmatrix|bmatrix|""" +
        """Bmatrix|vmatrix|Vmatrix|smallmatrix|array|subequations|""" +
        """tikzpicture|lstlisting|minted|verbatim|Verbatim""" +
        """)\}.*?\\end\{\k<mathEnv>\}""" +
        """|(?<!\\)\$\$.*?(?<!\\)\$\$""" +
        """|\\\[.*?\\\]""" +
        """|\\\(.*?\\\)""" +
        """|(?<!\\)\$(?!\$)(?:[^$\\\r\n]++|\\.)+(?<!\\)\$(?!\$)""" +
        """|\\(?:label|ref|eqref|pageref|autoref|cref|Cref|cite|citep|citet|""" +
        """nocite|bibliography|bibliographystyle|includegraphics|url|path|""" +
        """href|input|include)\*?(?:$WS*\[[^\]\r\n]*\])*$WS*\{(?:\\.|[^{}\r\n])*\}""" +
        """|\\verb\*?(?<verbDelim>[^\p{L}\p{N}_\s\p{Z}\x0B\x1C-\x1F\x85]).*?\k<verbDelim>""" +
        """|(?<!\\)%[^\r\n]*""",
    RegexOption.DOT_MATCHES_ALL,
)

private val BEGIN_OR_END = Regex("""\\(?:begin|end)$WS*\{[^{}]*\}""")
private val DOCUMENT_COMMAND = Regex(
    """\\(?:documentclass|usepackage|input|include)(?:$WS*\[[^\]]*\])?$WS*\{[^{}]*\}""",
)
private val ANY_COMMAND = Regex("""\\[A-Za-z@]+\*?""")
private val ESCAPED_CHARACTER = Regex("""\\[^\n]""")
private val LATEX_PUNCTUATION = Regex("""[{}\[\]&~_^]""")
private val WHITESPACE_RUN = Regex("$WS+")

data class ProtectedText(val text: String, val replacements: Map<String, String>)

fun isLatexText(text: String?): Boolean {
    val candidate = text.orEmpty()
    if (candidate.isEmpty()) return false
    return LATEX_SIGNAL.containsMatchIn(candidate) ||
        DISPLAY_MATH.containsMatchIn(candidate) ||
        hasInlineMath(candidate)
}

private fun hasInlineMath(text: String): Boolean = INLINE_MATH.findAll(text).any { match ->
    // In ordinary prose, "$5 to $10" is two currency prefixes rather than a
    // LaTeX pair. A digit right after the closing delimiter tells them apart.
    val end = match.range.last + 1
    end >= text.length || !text[end].isDigit()
}

/** Replaces non-translatable LaTeX fragments with stable placeholders. */
fun protectLatexFragments(text: String?): ProtectedText {
    val source = text.orEmpty()
    if (source.isEmpty() || !isLatexText(source)) return ProtectedText(source, emptyMap())

    val replacements = linkedMapOf<String, String>()
    val protected = PROTECTED_LATEX.replace(source) { match ->
        val number = replacements.size.toString().padStart(4, '0')
        val token = "$LATEX_PLACEHOLDER_PREFIX$number$LATEX_PLACEHOLDER_SUFFIX"
        replacements[token] = match.value
        token
    }
    return ProtectedText(protected, replacements)
}

fun restoreLatexPlaceholders(text: String?, replacements: Map<String, String>): String =
    replacements.entries.fold(text.orEmpty()) { restored, (token, original) ->
        restored.replace(token, original)
    }

/** Removes formula and control noise while keeping translatable prose. */
fun latexProseForLanguageDetection(text: String?): String {
    val candidate = text.orEmpty()
    if (!isLatexText(candidate)) return candidate

    val protected = protectLatexFragments(candidate)
    var prose = protected.replacements.keys.fold(protected.text) { current, token ->
        current.replace(token, " ")
    }
    for (pattern in listOf(BEGIN_OR_END, DOCUMENT_COMMAND, ANY_COMMAND, ESCAPED_CHARACTER, LATEX_PUNCTUATION)) {
        prose = pattern.replace(prose, " ")
    }
    return WHITESPACE_RUN.replace(prose, " ").pyStrip()
}

/** Restores placeholders even when a streamed token spans API chunks. */
class LatexStreamRestorer(replacements: Map<String, String> = emptyMap()) {
    private val replacements = replacements.toMap()
    private var buffer = ""

    fun feed(chunk: String): String {
        if (chunk.isEmpty()) return ""
        if (replacements.isEmpty()) return chunk

        buffer += chunk
        val output = StringBuilder()

        while (buffer.isNotEmpty()) {
            val start = buffer.indexOf(LATEX_PLACEHOLDER_PREFIX)
            if (start < 0) {
                val retained = possiblePrefixSuffixLength(buffer)
                output.append(buffer, 0, buffer.length - retained)
                buffer = buffer.substring(buffer.length - retained)
                break
            }

            if (start > 0) {
                output.append(buffer, 0, start)
                buffer = buffer.substring(start)
            }

            val end = buffer.indexOf(LATEX_PLACEHOLDER_SUFFIX, LATEX_PLACEHOLDER_PREFIX.length)
            if (end < 0) break

            val tokenEnd = end + LATEX_PLACEHOLDER_SUFFIX.length
            val token = buffer.substring(0, tokenEnd)
            output.append(replacements[token] ?: token)
            buffer = buffer.substring(tokenEnd)
        }

        return output.toString()
    }

    fun finish(): String {
        val remaining = restoreLatexPlaceholders(buffer, replacements)
        buffer = ""
        return remaining
    }

    private fun possiblePrefixSuffixLength(text: String): Int {
        val maximum = minOf(text.length, LATEX_PLACEHOLDER_PREFIX.length - 1)
        return (maximum downTo 1).firstOrNull { length ->
            LATEX_PLACEHOLDER_PREFIX.startsWith(text.substring(text.length - length))
        } ?: 0
    }
}
