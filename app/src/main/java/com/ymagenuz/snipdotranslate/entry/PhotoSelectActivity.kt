package com.ymagenuz.snipdotranslate.entry

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.ocr.OnDeviceScanner
import com.ymagenuz.snipdotranslate.ocr.loadPhoto
import com.ymagenuz.snipdotranslate.select.overlay.CapturedScreen
import com.ymagenuz.snipdotranslate.select.overlay.ImageSource
import com.ymagenuz.snipdotranslate.select.overlay.SelectionOverlay
import com.ymagenuz.snipdotranslate.ui.UiLanguage
import java.io.File
import kotlinx.coroutines.launch

/** Keeps the screenshot if the screen is recreated, such as when the theme changes. */
class HeldScreenshot : ViewModel() {
    var bitmap: Bitmap? = null
}

/**
 * Opens a photo, or a screenshot just taken with [EXTRA_SCREEN], with the pens. A
 * screenshot fills the whole display, so it looks as if the screen has frozen.
 */
class PhotoSelectActivity : ComponentActivity() {
    private val scanner by lazy { OnDeviceScanner() }
    private val held: HeldScreenshot by viewModels()
    private var selection: SelectionOverlay? = null
    private var isScreen = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) { selection?.onBack() ?: finish() }

        isScreen = intent.getBooleanExtra(EXTRA_SCREEN, false)
        if (isScreen) {
            showScreenshot()
            return
        }
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val photo = loadPhoto(this@PhotoSelectActivity, uri)
            deleteCapturedFile(uri)
            if (photo == null) {
                Toast.makeText(this@PhotoSelectActivity, R.string.photo_unreadable, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            open(photo, ImageSource.PHOTO)
        }
    }

    override fun finish() {
        super.finish()
        // Back to the live screen without an animation, as if it has unfrozen.
        if (isScreen && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        selection?.close()
        scanner.close()
        super.onDestroy()
    }

    private fun showScreenshot() {
        val screenshot = held.bitmap ?: CapturedScreen.take()?.let(::coverSystemBars)?.also { held.bitmap = it }
        if (screenshot == null) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        coverWholeDisplay(screenshot)
        open(screenshot, ImageSource.SCREEN)
    }

    /**
     * Draws under the cutout and the clear system bars, so the screenshot lines up 1:1 with
     * the screen and the live bars sit on its own. Hiding the bars instead would bring up
     * Android's full-screen notice and let edge strokes pull them back.
     */
    private fun coverWholeDisplay(screenshot: Bitmap) {
        // A turned screen would no longer match the screenshot.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No scrim behind three-button navigation, which would tint the screenshot.
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        // Icons that suit what is behind them, which is the covered strip where there is one.
        val fallback = (BAR_SAMPLE_DP * resources.displayMetrics.density).toInt()
        val (statusHeight, navigationHeight) = barHeights() ?: (fallback to fallback)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight(screenshot, 0, statusHeight.coerceAtLeast(1))
            isAppearanceLightNavigationBars = isLight(screenshot, screenshot.height - navigationHeight.coerceAtLeast(1), screenshot.height)
        }
    }

    /** The status bar's height and the navigation bar's at the bottom, where Android says. */
    private fun barHeights(): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val insets = windowManager.currentWindowMetrics.windowInsets
        val status = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
        val navigation = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
        return status.top to navigation.bottom
    }

    /**
     * Paints over the captured status and navigation bars with the colour beside them. They
     * show the clock as it was and Android's screen-sharing indicator, and the live bars are
     * drawn in the same place anyway.
     */
    private fun coverSystemBars(screenshot: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return screenshot
        val insets = windowManager.currentWindowMetrics.windowInsets
        val status = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
        val navigation = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
        val width = screenshot.width
        val height = screenshot.height
        val bars = listOfNotNull(
            Rect(0, 0, width, status.top).takeIf { status.top in 1 until height },
            Rect(0, height - navigation.bottom, width, height).takeIf { navigation.bottom in 1 until height },
            // In landscape, three-button navigation may sit at a side.
            Rect(0, 0, navigation.left, height).takeIf { navigation.left in 1 until width },
            Rect(width - navigation.right, 0, width, height).takeIf { navigation.right in 1 until width },
        )
        if (bars.isEmpty()) return screenshot
        val image = if (screenshot.isMutable) screenshot else screenshot.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(image)
        val paint = Paint()
        for (bar in bars) {
            paint.color = colourBeside(image, bar)
            canvas.drawRect(bar, paint)
        }
        return image
    }

    private fun open(image: Bitmap, source: ImageSource) {
        selection = SelectionOverlay(
            context = this,
            image = image,
            scanner = scanner,
            source = source,
            attach = { root ->
                setContentView(
                    root,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            },
            detach = { },
            onClosed = {
                selection = null
                finish()
            },
        ).also { it.show() }
    }

    /** A camera shot exists only to be read here, so it isn't kept. */
    private fun deleteCapturedFile(uri: Uri) {
        if (uri.authority == "$packageName.files") {
            File(cacheDir, "photos/${uri.lastPathSegment}").delete()
        }
    }

    companion object {
        const val EXTRA_SCREEN = "com.ymagenuz.snipdotranslate.extra.SCREEN"
    }
}

private const val BAR_SAMPLE_DP = 24f
private const val SAMPLES_ACROSS = 32
private const val SAMPLE_ROWS = 4

/** Whether rows [top] to [bottom] of [image] are mostly light, so a bar over them needs dark icons. */
private fun isLight(image: Bitmap, top: Int, bottom: Int): Boolean {
    val from = top.coerceIn(0, image.height - 1)
    val to = bottom.coerceIn(from + 1, image.height)
    var total = 0f
    var count = 0
    for (row in 0 until SAMPLE_ROWS) {
        val y = from + (to - from) * row / SAMPLE_ROWS
        for (column in 0 until SAMPLES_ACROSS) {
            val pixel = image.getPixel(image.width * column / SAMPLES_ACROSS, y)
            total += 0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)
            count++
        }
    }
    return total / count > LIGHT_LEVEL
}

private const val LIGHT_LEVEL = 140f

/** The most common colour just inside the screen from [bar], which the bar usually shares. */
private fun colourBeside(image: Bitmap, bar: Rect): Int {
    val counts = HashMap<Int, Int>()
    val across = bar.width() >= bar.height()
    for (sample in 0 until SAMPLES_ACROSS) {
        val x = if (across) image.width * sample / SAMPLES_ACROSS else if (bar.left == 0) bar.right else bar.left - 1
        val y = if (!across) image.height * sample / SAMPLES_ACROSS else if (bar.top == 0) bar.bottom else bar.top - 1
        val colour = image.getPixel(x.coerceIn(0, image.width - 1), y.coerceIn(0, image.height - 1))
        counts[colour] = (counts[colour] ?: 0) + 1
    }
    return counts.maxBy { it.value }.key
}
