package com.ymagenuz.snipdotranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_PHOTO_SIDE = 2400

/** Largest power-of-two reduction that keeps the longer side at or above [maxSide]. */
fun photoSampleSize(width: Int, height: Int, maxSide: Int = MAX_PHOTO_SIDE): Int {
    var sample = 1
    while (max(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/** Loads a picture upright and at a sensible size. Returns null if it can't be read. */
suspend fun loadPhoto(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val options = BitmapFactory.Options().apply {
            inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@withContext null

        val orientation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees() } ?: 0
        limitAndRotate(decoded, orientation)
    } catch (_: IOException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun ExifInterface.rotationDegrees(): Int =
    when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

private fun limitAndRotate(source: Bitmap, degrees: Int): Bitmap {
    val scale = MAX_PHOTO_SIDE.toFloat() / max(source.width, source.height)
    if (degrees == 0 && scale >= 1f) return source
    val matrix = Matrix().apply {
        if (scale < 1f) postScale(scale, scale)
        postRotate(degrees.toFloat())
    }
    val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (result !== source) source.recycle()
    return result
}
