package com.ymagenuz.snipdotranslate.entry

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.select.overlay.CapturedScreen
import com.ymagenuz.snipdotranslate.ui.UiLanguage
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CAPTURE_TIMEOUT_MS = 5000L

/**
 * Asks Android for permission to capture the screen, takes one screenshot of whatever is
 * below, and opens it with the pens. It has no window of its own on screen, so it neither
 * appears in the screenshot nor changes the status bar of the app below.
 */
class ScreenCaptureActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val answer = result.data
        if (result.resultCode == RESULT_OK && answer != null) capture(result.resultCode, answer) else finish()
    }
    private var ownsCapture = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(1, 1)
        window.setGravity(Gravity.START or Gravity.TOP)
        if (savedInstanceState != null) {
            // Recreated while already waiting for the answer.
            ownsCapture = true
            return
        }
        // A second tap while Android's dialog is open leaves that one alone.
        if (busy) {
            finish()
            return
        }
        busy = true
        ownsCapture = true

        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The whole screen only, so Android doesn't first ask which app to share.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        try {
            consent.launch(request)
        } catch (_: ActivityNotFoundException) {
            failed()
        }
    }

    private fun capture(resultCode: Int, answer: Intent) {
        val screenshot = CapturedScreen.expect()
        val (width, height) = screenSize()
        ScreenCaptureService.start(this, resultCode, answer, width, height, resources.displayMetrics.densityDpi)
        lifecycleScope.launch {
            val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { screenshot.await() }
            if (bitmap == null) {
                failed()
                return@launch
            }
            CapturedScreen.hold(bitmap)
            val select = Intent(this@ScreenCaptureActivity, PhotoSelectActivity::class.java)
                .putExtra(PhotoSelectActivity.EXTRA_SCREEN, true)
                // A task of its own, so Back returns to the app that was captured.
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
            // No opening animation, so the screen looks as if it has simply frozen.
            startActivity(select, ActivityOptions.makeCustomAnimation(this@ScreenCaptureActivity, 0, 0).toBundle())
            finish()
        }
    }

    override fun onDestroy() {
        if (ownsCapture && isFinishing) busy = false
        super.onDestroy()
    }

    private fun failed() {
        Toast.makeText(this, R.string.selection_capture_failed, Toast.LENGTH_LONG).show()
        finish()
    }

    /** The whole display in its current rotation, system bars included. */
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private companion object {
        // Whether a capture screen is already asking or capturing.
        @Volatile
        var busy = false
    }
}
