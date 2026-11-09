package com.ymagenuz.snipdotranslate.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.core.content.FileProvider
import com.ymagenuz.snipdotranslate.entry.PhotoSelectActivity
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var route: Route
        get() = viewModel.route
        set(value) {
            viewModel.route = value
        }
    private var pendingCapture: Uri? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLanguage.wrap(newBase))
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::openPhoto)
    }

    private val capturePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = pendingCapture
        pendingCapture = null
        if (taken && uri != null) openPhoto(uri)
    }

    /** The system camera app takes the picture, so this app needs no camera permission. */
    private fun takePhoto() {
        val directory = File(cacheDir, "photos").apply { mkdirs() }
        val file = File.createTempFile("capture", ".jpg", directory)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        pendingCapture = uri
        capturePhoto.launch(uri)
    }

    private fun openPhoto(uri: Uri) {
        startActivity(
            Intent(this, PhotoSelectActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyIntent(intent)
        setContent {
            SnipDoTheme {
                Surface {
                    BackHandler(enabled = route != Route.TRANSLATE) { route = Route.TRANSLATE }
                    when (route) {
                        Route.TRANSLATE -> TranslateScreen(
                            viewModel,
                            onHistory = { route = Route.HISTORY },
                            onSettings = { route = Route.SETTINGS },
                            onTakePhoto = ::takePhoto,
                            onPickPhoto = {
                                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        Route.HISTORY -> HistoryScreen(viewModel) { route = Route.TRANSLATE }
                        Route.SETTINGS -> SettingsScreen(viewModel) { route = Route.TRANSLATE }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            route = Route.SETTINGS
            intent.removeExtra(EXTRA_OPEN_SETTINGS)
        }
        val input = intent.getStringExtra(EXTRA_INPUT) ?: return
        route = Route.TRANSLATE
        viewModel.receive(input, intent.getStringExtra(EXTRA_RESULT), intent.getStringExtra(EXTRA_MODE))
        intent.removeExtra(EXTRA_INPUT)
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
        const val EXTRA_INPUT = "input"
        const val EXTRA_RESULT = "result"
        const val EXTRA_MODE = "mode"
    }
}
