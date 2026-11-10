package com.ymagenuz.snipdotranslate.entry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.select.overlay.CapturedScreen
import com.ymagenuz.snipdotranslate.ui.UiLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Lets Android's consent dialog finish fading out, so it isn't in the screenshot.
private const val CAPTURE_SETTLE_MS = 350L
private const val FRAME_TIMEOUT_MS = 3000L
private const val FRAME_POLL_MS = 16L
private const val NOTIFICATION_ID = 1
private const val CHANNEL_ID = "screen_capture"

/**
 * Takes one screenshot with Android's own screen-capture permission, then stops. Android
 * requires a foreground service to hold the capture, so it runs only for that moment.
 */
class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android expects this promptly after the service is started.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (_: IllegalStateException) {
            // Refused by Android, such as when it was started from the background.
            finishCapture(null)
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            finishCapture(null)
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true

        val consent = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_CONSENT, Intent::class.java) }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val width = intent?.getIntExtra(EXTRA_WIDTH, 0) ?: 0
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 0) ?: 0
        val dpi = intent?.getIntExtra(EXTRA_DPI, 0) ?: 0
        scope.launch {
            val bitmap = if (consent != null && width > 0 && height > 0 && dpi > 0) {
                capture(resultCode, consent, width, height, dpi)
            } else {
                null
            }
            finishCapture(bitmap)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        release()
        CapturedScreen.deliver(null)
        super.onDestroy()
    }

    private suspend fun capture(resultCode: Int, consent: Intent, width: Int, height: Int, dpi: Int): Bitmap? {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            manager.getMediaProjection(resultCode, consent)
        } catch (_: SecurityException) {
            null
        } ?: return null
        this.projection = projection
        // Android 14 and later require this before the capture starts.
        projection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader = it }
        display = projection.createVirtualDisplay(
            "SnipDo screen selection",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
        delay(CAPTURE_SETTLE_MS)
        return withTimeoutOrNull(FRAME_TIMEOUT_MS) {
            var image = reader.acquireLatestImage()
            while (image == null) {
                delay(FRAME_POLL_MS)
                image = reader.acquireLatestImage()
            }
            image.use { it.toBitmap() }
        }
    }

    private fun finishCapture(bitmap: Bitmap?) {
        CapturedScreen.deliver(bitmap)
        release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
    }

    private fun notification(): Notification {
        val text = UiLanguage.wrap(this)
        val channel = NotificationChannel(CHANNEL_ID, text.getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_select)
            .setContentTitle(text.getString(R.string.capture_notification))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_CONSENT = "consent"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_DPI = "dpi"

        /** [consent] is the answer from Android's screen-capture dialog. */
        fun start(context: Context, resultCode: Int, consent: Intent, width: Int, height: Int, dpi: Int) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_CONSENT, consent)
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_HEIGHT, height)
                .putExtra(EXTRA_DPI, dpi)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/** Rows can be padded past the image's width, so the padding is cut off. */
private fun Image.toBitmap(): Bitmap {
    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowPadding = plane.rowStride - pixelStride * width
    val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(plane.buffer)
    if (rowPadding == 0) return padded
    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
    padded.recycle()
    return cropped
}
