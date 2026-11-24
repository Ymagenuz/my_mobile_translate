package com.ymagenuz.snipdotranslate.core

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.HighlightDelimiterParser
import org.intellij.markdown.flavours.gfm.StrikeThroughDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.DelimiterParser
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser

/**
 * GitHub's Markdown, with bold, italic and strikethrough beside Chinese, Japanese and Korean text read as the writer meant.
 *
 * CommonMark takes ** as bold only at a word's edge, and finds that edge by spaces and punctuation. These languages put
 * no spaces between words, so "**注意：**请勿关闭" and "这是**“引号”**文字" kept their marks as text, and a mark left
 * over could pair with a later one: "这是**“引号”**文字和**粗体**" bolded "文字和". Here a mark beside a CJK character
 * is at a word's edge whatever punctuation is on its other side, as markdown-cjk-friendly proposes and as the desktop
 * app does since 13.3.1. Marks with no CJK character beside them are read exactly as GitHub reads them.
 */
object CjkFriendlyFlavour : GFMFlavourDescriptor() {
    private val gfmParsers = super.sequentialParserManager

    override val sequentialParserManager: SequentialParserManager = object : SequentialParserManager() {
        override fun getParserSequence(): List<SequentialParser> = gfmParsers.getParserSequence().map { parser ->
            if (parser is EmphasisLikeParser) {
                EmphasisLikeParser(
                    CjkFriendlyDelimiters(EmphStrongDelimiterParser()),
                    // A single ~ is a range in Chinese, as in 周一~周五, so only ~~ is read the CJK way.
                    CjkFriendlyDelimiters(StrikeThroughDelimiterParser(), onlyRunsOf = 2),
                    HighlightDelimiterParser(),
                )
            } else {
                parser
            }
        }
    }
}

/**
 * [gfm]'s delimiters, except that a run with a CJK character just before or after it may open or close emphasis
 * whatever punctuation is on its other side. Pairing them up and making the nodes is left to [gfm].
 */
private class CjkFriendlyDelimiters(
    private val gfm: DelimiterParser,
    private val onlyRunsOf: Int? = null,
) : DelimiterParser() {
    override fun scan(tokens: TokensCache, iterator: TokensCache.Iterator, delimiters: MutableList<Info>): Int {
        val first = delimiters.size
        val steps = gfm.scan(tokens, iterator, delimiters)
        if (steps == 0 || (onlyRunsOf != null && steps != onlyRunsOf)) return steps
        var last = iterator
        repeat(steps - 1) { last = last.advance() }
        if (!isCjk(codePointBefore(tokens, iterator.start)) && !isCjk(codePointAt(tokens, last.end))) return steps
        // As in CommonMark, only * and ~ may open or close inside a word; _ may not.
        val (canOpen, canClose) = canOpenClose(tokens, iterator, last, canSplitText = getType(iterator) != '_')
        for (index in first until delimiters.size) {
            delimiters[index].canOpen = canOpen
            delimiters[index].canClose = canClose
        }
        return steps
    }

    override fun process(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>,
        result: SequentialParser.ParsingResultBuilder,
    ) = gfm.process(tokens, iterator, delimiters, result)

    // Only asked about runs beside a CJK character, where punctuation no longer matters; whitespace still does.
    override fun isLeftFlankingRun(leftIt: TokensCache.Iterator, rightIt: TokensCache.Iterator): Boolean =
        !isWhitespace(rightIt, 1)

    override fun isRightFlankingRun(tokens: TokensCache, leftIt: TokensCache.Iterator, rightIt: TokensCache.Iterator): Boolean =
        leftIt.charLookup(-1) != getType(leftIt) && !isWhitespace(leftIt, -1)
}

private fun codePointBefore(tokens: TokensCache, index: Int): Int =
    if (index - 1 < tokens.originalTextRange.first) -1 else Character.codePointBefore(tokens.originalText, index)

private fun codePointAt(tokens: TokensCache, index: Int): Int =
    if (index > tokens.originalTextRange.last) -1 else Character.codePointAt(tokens.originalText, index)

/** Chinese, Japanese and Korean characters and punctuation, the same ranges as the desktop's markdown_emphasis.py. */
private val CJK_RANGES = listOf(
    0x1100..0x11FF, // Hangul Jamo
    0x2E80..0x2FDF, // CJK and Kangxi radicals
    0x2FF0..0x303F, // ideographic description, CJK symbols and punctuation
    0x3040..0x33FF, // kana, Bopomofo, Hangul compatibility Jamo, enclosed CJK
    0x3400..0x4DBF, // CJK extension A
    0x4E00..0x9FFF, // CJK unified ideographs
    0xA960..0xA97F, // Hangul Jamo extended A
    0xAC00..0xD7FF, // Hangul syllables, Jamo extended B
    0xF900..0xFAFF, // CJK compatibility ideographs
    0xFE10..0xFE1F, // vertical forms
    0xFE30..0xFE6F, // CJK compatibility forms, small form variants
    0xFF00..0xFFEF, // halfwidth and fullwidth forms
    0x1B000..0x1B16F, // kana supplement and extended
    0x20000..0x3FFFF, // CJK extensions B onwards
)

private fun isCjk(codePoint: Int): Boolean = CJK_RANGES.any { codePoint in it }
