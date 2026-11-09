package com.ymagenuz.snipdotranslate.entry

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.ocr.OnDeviceScanner
import com.ymagenuz.snipdotranslate.ocr.loadPhoto
import com.ymagenuz.snipdotranslate.select.overlay.SelectionOverlay
import com.ymagenuz.snipdotranslate.ui.UiLanguage
import java.io.File
import kotlinx.coroutines.launch

/** Opens a photo or screenshot with the same pens as on-screen selection. */
class PhotoSelectActivity : ComponentActivity() {
    private val scanner by lazy { OnDeviceScanner() }
    private var selection: SelectionOverlay? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) { selection?.onBack() ?: finish() }

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
            selection = SelectionOverlay(
                context = this@PhotoSelectActivity,
                image = photo,
                scanner = scanner,
                isPhoto = true,
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
    }

    override fun onDestroy() {
        selection?.close()
        scanner.close()
        super.onDestroy()
    }

    /** A camera shot exists only to be read here, so it isn't kept. */
    private fun deleteCapturedFile(uri: Uri) {
        if (uri.authority == "$packageName.files") {
            File(cacheDir, "photos/${uri.lastPathSegment}").delete()
        }
    }
}
