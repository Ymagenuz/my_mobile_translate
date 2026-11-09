package com.ymagenuz.snipdotranslate.select.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.entry.SelectOnScreenTileService
import com.ymagenuz.snipdotranslate.ocr.OnDeviceScanner
import com.ymagenuz.snipdotranslate.ui.UiLanguage
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class SelectionAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scanner by lazy { OnDeviceScanner() }
    private var captureJob: Job? = null
    private var overlay: SelectionOverlay? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        overlay?.close()
        scope.cancel()
        scanner.close()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Closes the selection if the screen turns, since the frozen image no longer fits. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.close()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun startScan() {
        if (captureJob?.isActive == true || overlay != null) return
        captureJob = scope.launch {
            val shadeClosed = SelectOnScreenTileService.expectShadeClosed()
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            // A fixed delay would catch the closing animation on slower phones. The tile
            // stops listening as the shade finishes closing; the timeout covers phones that don't say.
            withTimeoutOrNull(SHADE_CLOSE_TIMEOUT_MS) { shadeClosed.await() }
            delay(SHADE_SETTLE_MS)

            val bitmap = captureScreen()
            if (bitmap == null) {
                Toast.makeText(this@SelectionAccessibilityService, R.string.selection_capture_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            showOverlay(bitmap)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun showOverlay(bitmap: Bitmap) {
        val windows = getSystemService(WindowManager::class.java)
        val match = WindowManager.LayoutParams.MATCH_PARENT
        val params = WindowManager.LayoutParams(
            match,
            match,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        overlay = SelectionOverlay(
            // Wrapped each time, because the service outlives a change of language.
            context = UiLanguage.wrap(this),
            image = bitmap,
            scanner = scanner,
            isPhoto = false,
            attach = { windows.addView(it, params) },
            detach = { windows.removeViewImmediate(it) },
            onClosed = { overlay = null },
        ).also { it.show() }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Executor { it.run() },
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    // ML Kit and the selection canvas both need a software bitmap.
                    val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    buffer.close()
                    continuation.resume(software)
                }

                override fun onFailure(errorCode: Int) {
                    continuation.resume(null)
                }
            },
        )
    }

    companion object {
        private const val SHADE_CLOSE_TIMEOUT_MS = 1100L
        private const val SHADE_SETTLE_MS = 60L

        @Volatile
        var instance: SelectionAccessibilityService? = null
            private set
    }
}
