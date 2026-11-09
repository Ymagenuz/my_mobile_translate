package com.ymagenuz.snipdotranslate.ocr

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.icu.text.BreakIterator
import android.icu.util.ULocale
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ymagenuz.snipdotranslate.select.core.Pt
import com.ymagenuz.snipdotranslate.select.core.Quad
import com.ymagenuz.snipdotranslate.select.core.RawBlock
import com.ymagenuz.snipdotranslate.select.core.RawElement
import com.ymagenuz.snipdotranslate.select.core.RawLine
import com.ymagenuz.snipdotranslate.select.core.RawSymbol
import com.ymagenuz.snipdotranslate.select.core.ScreenWord
import com.ymagenuz.snipdotranslate.select.core.WordSegmenter
import com.ymagenuz.snipdotranslate.select.core.buildWords
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

enum class ScanScript(val locale: ULocale) {
    CHINESE(ULocale.CHINESE),
    LATIN(ULocale.ENGLISH),
    JAPANESE(ULocale.JAPANESE),
    KOREAN(ULocale.KOREAN),
}

/** Reads words and their positions from a bitmap, entirely on the device. */
class OnDeviceScanner : AutoCloseable {
    private val recognizers = mutableMapOf<ScanScript, TextRecognizer>()

    suspend fun scan(bitmap: Bitmap, script: ScanScript): List<ScreenWord> {
        val recognizer = recognizers.getOrPut(script) { createRecognizer(script) }
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return buildWords(result.textBlocks.map { it.toRaw() }, IcuWordSegmenter(script.locale))
    }

    override fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }

    private fun createRecognizer(script: ScanScript): TextRecognizer = when (script) {
        ScanScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        ScanScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        ScanScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        ScanScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
}

private class IcuWordSegmenter(private val locale: ULocale) : WordSegmenter {
    override fun boundaries(text: String): List<Int> {
        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)
        val result = mutableListOf(iterator.first())
        var next = iterator.next()
        while (next != BreakIterator.DONE) {
            result += next
            next = iterator.next()
        }
        return result
    }
}

private fun Text.TextBlock.toRaw() = RawBlock(lines.map { line ->
    RawLine(line.elements.map { element ->
        RawElement(
            text = element.text,
            quad = quadOf(element.cornerPoints, element.boundingBox),
            symbols = element.symbols.map { symbol ->
                RawSymbol(symbol.text, quadOf(symbol.cornerPoints, symbol.boundingBox))
            },
        )
    })
})

private fun quadOf(corners: Array<Point>?, box: Rect?): Quad {
    if (corners != null && corners.size == 4) {
        return Quad(corners[0].toPt(), corners[1].toPt(), corners[2].toPt(), corners[3].toPt())
    }
    val rect = box ?: Rect()
    return Quad(
        Pt(rect.left.toFloat(), rect.top.toFloat()),
        Pt(rect.right.toFloat(), rect.top.toFloat()),
        Pt(rect.right.toFloat(), rect.bottom.toFloat()),
        Pt(rect.left.toFloat(), rect.bottom.toFloat()),
    )
}

private fun Point.toPt() = Pt(x.toFloat(), y.toFloat())

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
