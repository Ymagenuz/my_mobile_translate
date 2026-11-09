package com.ymagenuz.snipdotranslate.ocr

import android.graphics.Bitmap
import android.util.Base64
import com.ymagenuz.snipdotranslate.SnipDoApp
import com.ymagenuz.snipdotranslate.api.visionMessageContent
import com.ymagenuz.snipdotranslate.core.Prompts
import com.ymagenuz.snipdotranslate.core.normalizeNewlines
import com.ymagenuz.snipdotranslate.domain.ProviderAccess
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IMAGE_SIDE = 2048
private const val JPEG_QUALITY = 85

sealed interface VisionResult {
    data class Text(val text: String) : VisionResult

    data object NoKey : VisionResult

    data object NoModel : VisionResult

    data class Unsupported(val providerName: String) : VisionResult

    data object NoTextFound : VisionResult

    data class Failed(val message: String) : VisionResult
}

/** Encodes an image the way the provider expects, keeping uploads a sensible size. */
fun bitmapToDataUrl(bitmap: Bitmap): String {
    val scale = MAX_IMAGE_SIDE.toFloat() / max(bitmap.width, bitmap.height)
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        bitmap
    }
    val bytes = ByteArrayOutputStream().use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        output.toByteArray()
    }
    if (scaled !== bitmap) scaled.recycle()
    return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/** Reads the text in an image with the provider's vision model, as the desktop OCR does. */
suspend fun readImageWithVision(app: SnipDoApp, image: Bitmap): VisionResult {
    val access = when (val result = app.providerAccess()) {
        is ProviderAccess.Ready -> result
        ProviderAccess.MissingKey -> return VisionResult.NoKey
        ProviderAccess.MissingModel -> return VisionResult.NoModel
    }
    if (!access.provider.supportsVision) return VisionResult.Unsupported(access.provider.shortName)

    return try {
        val url = withContext(Dispatchers.Default) { bitmapToDataUrl(image) }
        val reply = app.chatClient.complete(access.provider, access.apiKey, visionMessageContent(Prompts.OCR, url))
        val text = normalizeNewlines(reply).trim()
        if (text.uppercase(Locale.ROOT) == Prompts.NO_TEXT_FOUND) VisionResult.NoTextFound else VisionResult.Text(text)
    } catch (failure: IOException) {
        VisionResult.Failed(failure.message ?: failure.javaClass.simpleName)
    }
}
