package com.ymagenuz.snipdotranslate.entry

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.SnipDoApp
import com.ymagenuz.snipdotranslate.core.incomingText
import com.ymagenuz.snipdotranslate.data.AppSettings
import com.ymagenuz.snipdotranslate.domain.RunStatus
import com.ymagenuz.snipdotranslate.domain.TranslationRequest
import com.ymagenuz.snipdotranslate.ui.AppIcons
import com.ymagenuz.snipdotranslate.ui.CaptionAction
import com.ymagenuz.snipdotranslate.ui.MainActivity
import com.ymagenuz.snipdotranslate.ui.ResultActionBar
import com.ymagenuz.snipdotranslate.ui.ResultActions
import com.ymagenuz.snipdotranslate.ui.ResultPane
import com.ymagenuz.snipdotranslate.ui.SheetFrame
import com.ymagenuz.snipdotranslate.ui.SnipDoTheme
import com.ymagenuz.snipdotranslate.ui.SourceEditor
import com.ymagenuz.snipdotranslate.ui.SourcePreview
import com.ymagenuz.snipdotranslate.ui.UiLanguage
import com.ymagenuz.snipdotranslate.ui.shareText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PopupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SnipDoApp
    val session = app.newSession(viewModelScope)
    val settings = app.settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun translate(text: String) {
        viewModelScope.launch {
            val current = app.settingsStore.current()
            session.translate(TranslationRequest(text, false, current.sourceLanguage, current.targetLanguage))
        }
    }

    fun setBackTranslation(enabled: Boolean) {
        viewModelScope.launch {
            app.settingsStore.update { it.copy(backTranslation = enabled) }
            session.onBackTranslationToggled(enabled)
        }
    }
}

/** Shows a translation in a sheet over whichever app asked for it. */
class PopupActivity : ComponentActivity() {
    private val viewModel: PopupViewModel by viewModels()
    private var awaitingClipboard = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { SnipDoTheme { Sheet() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && awaitingClipboard) readClipboard()
    }

    /** Android only lets an app read the clipboard once its window has focus. */
    private fun readClipboard() {
        awaitingClipboard = false
        val clipboard = getSystemService(ClipboardManager::class.java)
        start(clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this))
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_TRANSLATE_CLIPBOARD -> if (hasWindowFocus()) readClipboard() else awaitingClipboard = true
            Intent.ACTION_PROCESS_TEXT -> start(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
            Intent.ACTION_SEND ->
                if (intent.type?.startsWith("image/") == true) openSharedImage(intent) else start(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            else -> finish()
        }
    }

    private fun openSharedImage(shared: Intent) {
        val image = IntentCompat.getParcelableExtra(shared, Intent.EXTRA_STREAM, Uri::class.java)
        if (image == null) {
            finish()
            return
        }
        startActivity(
            Intent(this, PhotoSelectActivity::class.java)
                .setData(image)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        finish()
    }

    private fun start(raw: CharSequence?) {
        val text = incomingText(raw)
        if (text == null) {
            openApp()
        } else {
            viewModel.translate(text)
        }
    }

    private fun openApp(configure: Intent.() -> Unit = {}) {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(configure))
        finish()
    }

    @Composable
    private fun Sheet() {
        val state by viewModel.session.state.collectAsStateWithLifecycle()
        val settings by viewModel.settings.collectAsStateWithLifecycle()
        if (state.status == RunStatus.IDLE) return
        // The source text being corrected before it's translated again.
        var editing by remember { mutableStateOf(false) }
        var draft by remember { mutableStateOf(TextFieldValue()) }
        val actions = ResultActions(
            onStop = viewModel.session::stop,
            onRetry = viewModel.session::retry,
            onStopBack = viewModel.session::stopBackTranslation,
            onOpenSettings = { openApp { putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true) } },
            backTranslationOn = settings.backTranslation,
            onToggleBack = viewModel::setBackTranslation,
            onShare = { shareText(this, it) },
        )

        BackHandler(onBack = ::finish)
        SheetFrame(
            onDismiss = ::finish,
            bottomBar = {
                ResultActionBar(
                    state = state,
                    actions = actions,
                    onOpenInApp = {
                        val finished = state.status == RunStatus.DONE
                        openApp {
                            putExtra(MainActivity.EXTRA_INPUT, state.request?.text)
                            if (finished) {
                                putExtra(MainActivity.EXTRA_RESULT, state.text)
                                putExtra(MainActivity.EXTRA_MODE, state.mode)
                            }
                        }
                    },
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val label = stringResource(R.string.label_original)
                val source = state.request?.text.orEmpty()
                if (editing) {
                    SourceEditor(
                        label = label,
                        draft = draft,
                        onDraftChange = { draft = it },
                        onTranslate = {
                            editing = false
                            viewModel.translate(draft.text.trim())
                        },
                        onCancel = { editing = false },
                    )
                } else {
                    SourcePreview(label, source) {
                        CaptionAction(AppIcons.Edit, stringResource(R.string.action_edit)) {
                            draft = TextFieldValue(source, TextRange(source.length))
                            editing = true
                        }
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                )
                ResultPane(state = state, actions = actions, showActions = false, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }

    companion object {
        const val ACTION_TRANSLATE_CLIPBOARD = "com.ymagenuz.snipdotranslate.TRANSLATE_CLIPBOARD"
    }
}
