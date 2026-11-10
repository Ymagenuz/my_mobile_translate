package com.ymagenuz.snipdotranslate.select.overlay

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.graphics.Insets
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

/**
 * The optional way to select on screen, for people who turn it on in Accessibility settings:
 * it captures without Android asking each time, shows the frozen screen in an overlay over
 * everything, and adds a floating button or gesture that starts a selection from any screen.
 * While it isn't running, the tile uses Android's screen-capture permission instead.
 */
class SelectionAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scanner by lazy { OnDeviceScanner() }
    private var captureJob: Job? = null
    private var overlay: SelectionOverlay? = null

    /** The feature's accessibility shortcut: its floating button or gesture. */
    private val shortcut = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            // A gesture or volume-key shortcut can be used again while selecting, so it closes
            // the selection. The floating button is under the selection and can't be pressed.
            val open = overlay
            if (open != null) open.close() else startScan(fromShade = false)
        }
    }

    override fun onServiceConnected() {
        instance = this
        accessibilityButtonController.registerAccessibilityButtonCallback(shortcut)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        accessibilityButtonController.unregisterAccessibilityButtonCallback(shortcut)
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

    /** [fromShade] is true when started from the tile, so the shade has to close first. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun startScan(fromShade: Boolean = true) {
        if (captureJob?.isActive == true || overlay != null) return
        captureJob = scope.launch {
            if (fromShade) {
                val shadeClosed = SelectOnScreenTileService.expectShadeClosed()
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                // A fixed delay would catch the closing animation on slower phones. The tile
                // stops listening as the shade finishes closing; the timeout covers phones that don't say.
                withTimeoutOrNull(SHADE_CLOSE_TIMEOUT_MS) { shadeClosed.await() }
                delay(SHADE_SETTLE_MS)
            } else {
                // Lets the button's press effect or the gesture's animation finish first.
                delay(SHORTCUT_SETTLE_MS)
            }

            val bitmap = captureScreen()
            if (bitmap == null) {
                Toast.makeText(this@SelectionAccessibilityService, R.string.selection_capture_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            showOverlay(bitmap, barInsets())
        }
    }

    /**
     * Where the status and navigation bars were when the screenshot was taken, if they were
     * showing. The overlay covers them with the screenshot's own, so they aren't scanned.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun barInsets(): Insets = try {
        val insets = getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
        Insets.toCompatInsets(insets.getInsets(WindowInsets.Type.systemBars()))
    } catch (_: RuntimeException) {
        // Scanning the bars too is better than not opening at all.
        Insets.NONE
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun showOverlay(bitmap: Bitmap, bars: Insets) {
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
            // The keyboard mustn't resize or shift the frozen screen; the sheet moves up by itself.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        overlay = SelectionOverlay(
            // Wrapped each time, because the service outlives a change of language.
            context = UiLanguage.wrap(this),
            image = bitmap,
            scanner = scanner,
            source = ImageSource.SCREEN,
            attach = { windows.addView(it, params) },
            detach = { windows.removeViewImmediate(it) },
            onClosed = { overlay = null },
            inOverlayWindow = true,
            barInsets = bars,
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
        private const val SHORTCUT_SETTLE_MS = 150L

        @Volatile
        var instance: SelectionAccessibilityService? = null
            private set

        /** Whether the service is turned on and can take over from the tile. */
        val isRunning: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && instance != null
    }
}
