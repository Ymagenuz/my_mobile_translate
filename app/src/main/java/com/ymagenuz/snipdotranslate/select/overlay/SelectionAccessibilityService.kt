package com.ymagenuz.snipdotranslate.select.overlay

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.graphics.Insets
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.entry.PopupActivity
import com.ymagenuz.snipdotranslate.entry.SelectOnScreenTileService
import com.ymagenuz.snipdotranslate.ocr.OnDeviceScanner
import com.ymagenuz.snipdotranslate.select.core.Quad
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
    // A selection's words, kept while the user scrolls to the rest, and the bar shown meanwhile.
    private var kept: Continuation? = null
    private var bar: ContinueBar? = null
    // The kept words marked over the live app, the app whose scrolling they follow, and the
    // wait after a touch to hear whether that app says how far it scrolls.
    private var marks: SelectionMarks? = null
    private var marksApp: CharSequence? = null
    private var touchCheck: Job? = null
    private var fadingMarks: SelectionMarks? = null

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
        discardKept()
        accessibilityButtonController.unregisterAccessibilityButtonCallback(shortcut)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        overlay?.close()
        discardKept()
        scope.cancel()
        scanner.close()
        super.onDestroy()
    }

    /** Scrolling, heard only while marks are shown, moves them with the text. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || event?.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val shown = marks ?: return
        val app = event.packageName
        if (app == packageName) return
        // Both are unset when an app doesn't say how far it scrolled, and another app isn't the
        // one the words were kept from.
        val unknown = event.scrollDeltaX == UNSET_DELTA && event.scrollDeltaY == UNSET_DELTA
        if (unknown || (marksApp != null && app != marksApp)) {
            dropMarks(fade = true)
            return
        }
        marksApp = app
        touchCheck?.cancel()
        if (event.scrollDeltaY != 0) shown.scrolledBy(event.scrollDeltaY)
    }

    override fun onInterrupt() = Unit

    /** Closes the selection if the screen turns, since the frozen image no longer fits. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.close()
        dropMarks(fade = false)
    }

    /**
     * [fromShade] is true when started from the tile, so the shade has to close first. While
     * words are kept from a selection, this captures the next screen to carry on from them.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun startScan(fromShade: Boolean = true) {
        if (captureJob?.isActive == true || overlay != null) return
        val continuation = kept
        // Out of the screenshot, which the settling delay below gives them time to leave. The
        // marks only hide, still following the scrolling until the capture, so the next screen
        // knows where the kept words ended up.
        removeBar()
        hideMarks()
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
            val keptOnScreen = marks?.keptNow().orEmpty()
            dropMarks(fade = false)
            if (bitmap == null) {
                Toast.makeText(this@SelectionAccessibilityService, R.string.selection_capture_failed, Toast.LENGTH_LONG).show()
                // The kept words wait for another try, without marks, which may have lost the text.
                continuation?.let { showBar(it.copy(marks = emptyList())) }
                return@launch
            }
            kept = null
            showOverlay(bitmap, barInsets(), continuation?.copy(marks = emptyList(), keptOnScreen = keptOnScreen))
        }
    }

    /**
     * Over the live screen while the user scrolls: the kept words marked where they are, and the
     * bar to capture the next screen, translate, or stop.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun showBar(continuation: Continuation) {
        dropMarks(fade = false)
        removeBar()
        kept = continuation
        val windows = getSystemService(WindowManager::class.java)
        if (continuation.marks.isNotEmpty()) showMarks(windows, continuation.marks)
        val wrap = WindowManager.LayoutParams.WRAP_CONTENT
        val params = WindowManager.LayoutParams(
            wrap,
            wrap,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Touches outside the bar, Back and the keyboard stay with the app being scrolled,
            // and the bar hears that a touch happened, to check the marks can follow it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (BAR_MARGIN_DP * resources.displayMetrics.density).toInt()
        }
        bar = ContinueBar(
            context = UiLanguage.wrap(this),
            parts = continuation.collected.parts,
            attach = { windows.addView(it, params) },
            detach = { windows.removeViewImmediate(it) },
            onCapture = { startScan(fromShade = false) },
            onTranslate = ::translateKept,
            onDiscard = ::discardKept,
            onTouchElsewhere = ::touchedElsewhere,
        ).also { it.show() }
    }

    /**
     * Marks the kept words over the live app, placed like the overlay so they sit on the text,
     * in a window between the status and navigation bars, which they'd otherwise cover when
     * scrolled past them.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun showMarks(windows: WindowManager, quads: List<Quad>) {
        val bars = barInsets()
        val screenHeight = windows.currentWindowMetrics.bounds.height()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (screenHeight - bars.top - bars.bottom).coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            gravity = Gravity.TOP or Gravity.START
            y = bars.top
        }
        val view = SelectionMarks(this, quads, bars.top.toFloat())
        windows.addView(view, params)
        marks = view
        marksApp = null
        followScrolling(true)
    }

    /** Takes the marks down: faded where the text has moved out from under them, or at once. */
    private fun dropMarks(fade: Boolean) {
        touchCheck?.cancel()
        if (!fade) removeFadingMarks()
        val view = marks ?: return
        marks = null
        followScrolling(false)
        val windows = getSystemService(WindowManager::class.java)
        if (!fade) {
            windows.removeViewImmediate(view)
            return
        }
        fadingMarks = view
        view.fadeOut {
            if (fadingMarks === view) {
                fadingMarks = null
                windows.removeViewImmediate(view)
            }
        }
    }

    /**
     * Someone touched the app below. If no scrolling is heard of soon after, the app doesn't say
     * how far it scrolls, or the touch changed the screen, so the marks can't stay on the text.
     */
    private fun touchedElsewhere() {
        if (marks == null) return
        touchCheck?.cancel()
        touchCheck = scope.launch {
            delay(SCROLL_WAIT_MS)
            dropMarks(fade = true)
        }
    }

    /** Hears how far apps scroll, only while marks are shown. Every event, since each carries part of the distance. */
    private fun followScrolling(on: Boolean) {
        serviceInfo = serviceInfo.apply {
            eventTypes = if (on) AccessibilityEvent.TYPE_VIEW_SCROLLED else 0
            notificationTimeout = if (on) 0L else NOTIFICATION_TIMEOUT_MS
        }
    }

    /** Out of a screenshot about to be taken, while still following the scrolling. */
    private fun hideMarks() {
        touchCheck?.cancel()
        removeFadingMarks()
        marks?.visibility = View.INVISIBLE
    }

    /** Marks still fading out go at once, since the screen may be about to be captured. */
    private fun removeFadingMarks() {
        val view = fadingMarks ?: return
        fadingMarks = null
        view.animate().cancel()
        getSystemService(WindowManager::class.java).removeViewImmediate(view)
    }

    private fun removeBar() {
        bar?.remove()
        bar = null
    }

    private fun discardKept() {
        kept = null
        dropMarks(fade = false)
        removeBar()
    }

    /** Translates the kept words over the live screen, in the popup that shared text opens. */
    private fun translateKept() {
        val text = kept?.collected?.text.orEmpty()
        discardKept()
        if (text.isEmpty()) return
        startActivity(
            Intent(this, PopupActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
    private fun showOverlay(bitmap: Bitmap, bars: Insets, continuation: Continuation?) {
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
            continuation = continuation,
            onContinue = ::showBar,
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
        private const val BAR_MARGIN_DP = 8
        private const val SCROLL_WAIT_MS = 800L
        private const val UNSET_DELTA = -1
        // As in the service's configuration.
        private const val NOTIFICATION_TIMEOUT_MS = 100L

        @Volatile
        var instance: SelectionAccessibilityService? = null
            private set

        /** Whether the service is turned on and can take over from the tile. */
        val isRunning: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && instance != null
    }
}
