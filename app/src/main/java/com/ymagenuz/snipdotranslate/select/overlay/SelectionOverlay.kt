package com.ymagenuz.snipdotranslate.select.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.SnipDoApp
import com.ymagenuz.snipdotranslate.data.AppSettings
import com.ymagenuz.snipdotranslate.domain.RunStatus
import com.ymagenuz.snipdotranslate.domain.TranslationRequest
import com.ymagenuz.snipdotranslate.ocr.OnDeviceScanner
import com.ymagenuz.snipdotranslate.ocr.ScanScript
import com.ymagenuz.snipdotranslate.ocr.VisionResult
import com.ymagenuz.snipdotranslate.ocr.readImageWithVision
import com.ymagenuz.snipdotranslate.select.core.AreaRect
import com.ymagenuz.snipdotranslate.select.core.Pen
import com.ymagenuz.snipdotranslate.select.core.SelectionModel
import com.ymagenuz.snipdotranslate.select.core.WordOrder
import com.ymagenuz.snipdotranslate.select.core.center
import com.ymagenuz.snipdotranslate.select.core.inReadingOrder
import com.ymagenuz.snipdotranslate.ui.MainActivity
import com.ymagenuz.snipdotranslate.ui.ResultActions
import com.ymagenuz.snipdotranslate.ui.ResultPane
import com.ymagenuz.snipdotranslate.ui.SheetFrame
import com.ymagenuz.snipdotranslate.ui.SnipDoTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAP_TOLERANCE_DP = 10f
private const val DRAG_SLOP_DP = 8f
private const val BLACK_LEVEL = 8
private const val BLACK_SHARE = 0.98f
private const val AREA_PADDING_PX = 8

/**
 * A still image with the pens and result sheet: a frozen screen shown over everything
 * else, or a photo in its own screen. [attach] and [detach] put it wherever it belongs.
 */
class SelectionOverlay(
    private val context: Context,
    private val image: Bitmap,
    private val scanner: OnDeviceScanner,
    private val isPhoto: Boolean,
    private val attach: (View) -> Unit,
    private val detach: (View) -> Unit,
    private val onClosed: () -> Unit,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val app = context.applicationContext as SnipDoApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density = context.resources.displayMetrics.density

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val toolbar = ToolbarState()
    private val canvas = SelectionCanvasView(context, image)
    private val session = app.newSession(scope)
    private val settings = app.settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private var model: SelectionModel? = null
    private var scanJob: Job? = null
    private var sheetVisible by mutableStateOf(false)
    private var readingImage by mutableStateOf(false)
    private var fromImage by mutableStateOf(false)
    private var closed = false

    private val root = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            // In a photo's own screen, the activity's back handling calls onBack instead.
            if (!isPhoto && event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    fun show() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeViewModelStoreOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)

        canvas.onStrokeActive = { toolbar.strokeActive = it }
        canvas.onSelectionChanged = ::syncToolbar
        canvas.onArea = ::readArea

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(canvas, match, match)
        root.addView(ComposeView(context).apply { setContent { OverlayUi() } }, match, match)
        root.isFocusableInTouchMode = true
        if (isPhoto) root.setBackgroundColor(AndroidColor.BLACK)

        attach(root)
        root.requestFocus()

        scope.launch {
            val saved = app.settingsStore.current()
            toolbar.plusDp = saved.plusBrushDp
            toolbar.minusDp = saved.minusBrushDp
            toolbar.x = saved.toolbarX
            toolbar.y = saved.toolbarY
            applyBrush()
        }
        startScan(toolbar.script)
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        detach(root)
        // The screenshot exists only in memory and is freed once nothing refers to it.
        // Recycling it here would crash a frame or scan that is still using it.
        onClosed()
    }

    fun onBack() {
        if (sheetVisible) closeSheet() else close()
    }

    private fun startScan(script: ScanScript) {
        toolbar.script = script
        toolbar.scanning = true
        scanJob?.cancel()
        scanJob = scope.launch {
            val words = try {
                inReadingOrder(scanner.scan(image, script))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The area pen still works without the on-device scan.
                Toast.makeText(context, R.string.selection_scan_failed, Toast.LENGTH_LONG).show()
                emptyList()
            }
            // The status bar is still drawn over a blocked app, so its clock may be read.
            val contentWords = words.filter { it.quad.center.y in contentTop(image)..contentBottom(image) }
            if (!isPhoto && contentWords.isEmpty() && withContext(Dispatchers.Default) { isBlackedOut(image) }) {
                Toast.makeText(context, R.string.selection_blocked, Toast.LENGTH_LONG).show()
                close()
                return@launch
            }
            toolbar.scanning = false
            model = SelectionModel(words, TAP_TOLERANCE_DP * density, DRAG_SLOP_DP * density).also(canvas::setModel)
            if (words.isEmpty()) Toast.makeText(context, R.string.selection_no_text, Toast.LENGTH_LONG).show()
        }
    }

    private fun syncToolbar() {
        val current = model
        toolbar.canUndo = current?.canUndo == true
        toolbar.canRedo = current?.canRedo == true
        toolbar.hasSelection = current?.hasSelection == true
    }

    private fun applyBrush() {
        val widthDp = if (toolbar.pen == Pen.MINUS) toolbar.minusDp else toolbar.plusDp
        canvas.brushRadiusPx = density * widthDp / 2f
    }

    private fun selectPen(pen: Pen) {
        // Tapping the current brush again shows its width control.
        toolbar.showWidth = toolbar.pen == pen && !toolbar.showWidth
        toolbar.pen = pen
        canvas.pen = pen
        applyBrush()
    }

    private fun act(action: SelectionModel.() -> Unit) {
        model?.action()
        syncToolbar()
        canvas.refresh()
    }

    private fun savePrefs() {
        scope.launch {
            app.settingsStore.update {
                it.copy(
                    plusBrushDp = toolbar.plusDp,
                    minusBrushDp = toolbar.minusDp,
                    toolbarX = toolbar.x,
                    toolbarY = toolbar.y,
                )
            }
        }
    }

    private fun selectedText(): String = model?.selectedText(toolbar.order).orEmpty()

    private fun copySelection() {
        val text = selectedText()
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("selection", text))
        Toast.makeText(context, R.string.action_copied, Toast.LENGTH_SHORT).show()
    }

    private fun translate(text: String) {
        if (text.isEmpty()) return
        val current = settings.value
        sheetVisible = true
        session.translate(TranslationRequest(text, false, current.sourceLanguage, current.targetLanguage))
    }

    private fun closeSheet() {
        session.stop()
        sheetVisible = false
        readingImage = false
        fromImage = false
    }

    /** Hands the text to the main screen, where it can be edited and translated again. */
    private fun openInApp() {
        val state = session.state.value
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_INPUT, state.request?.text)
        if (state.status == RunStatus.DONE) {
            intent.putExtra(MainActivity.EXTRA_RESULT, state.text).putExtra(MainActivity.EXTRA_MODE, state.mode)
        }
        context.startActivity(intent)
        close()
    }

    /** Sends only the drawn rectangle to the vision model, for what the scan can't read. */
    private fun readArea(area: AreaRect) {
        val left = (area.left.toInt() - AREA_PADDING_PX).coerceAtLeast(0)
        val top = (area.top.toInt() - AREA_PADDING_PX).coerceAtLeast(0)
        val right = (area.right.toInt() + AREA_PADDING_PX).coerceAtMost(image.width)
        val bottom = (area.bottom.toInt() + AREA_PADDING_PX).coerceAtMost(image.height)
        if (right <= left || bottom <= top) return

        val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        readWithVision(crop) { crop.recycle() }
    }

    /** Reads the whole photo with the provider's vision model, as the desktop OCR does. */
    private fun readWholeImage() = readWithVision(image) { }

    private fun readWithVision(source: Bitmap, afterRead: () -> Unit) {
        sheetVisible = true
        readingImage = true
        scope.launch {
            val result = readImageWithVision(app, source)
            afterRead()
            readingImage = false
            when (result) {
                is VisionResult.Text -> {
                    fromImage = true
                    translate(result.text)
                }
                VisionResult.NoKey -> session.showFailure(context.getString(R.string.error_no_key))
                VisionResult.NoModel -> session.showFailure(context.getString(R.string.error_no_model))
                is VisionResult.Unsupported ->
                    session.showFailure(context.getString(R.string.error_ocr_unsupported, result.providerName))
                VisionResult.NoTextFound -> session.showFailure(context.getString(R.string.error_no_text_found))
                is VisionResult.Failed -> session.showFailure(result.message)
            }
        }
    }

    private fun openSettings() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
        )
        close()
    }

    private fun setBackTranslation(enabled: Boolean) {
        scope.launch {
            app.settingsStore.update { it.copy(backTranslation = enabled) }
            session.onBackTranslationToggled(enabled)
        }
    }

    @Composable
    private fun OverlayUi() {
        SnipDoTheme {
            Box(Modifier.fillMaxSize()) {
                if (sheetVisible) ResultSheet() else Toolbar()
            }
        }
    }

    @Composable
    private fun Toolbar() {
        FloatingToolbar(
            toolbar,
            ToolbarActions(
                onPen = ::selectPen,
                onBrushChanged = {
                    applyBrush()
                    savePrefs()
                },
                onUndo = { act { undo() } },
                onRedo = { act { redo() } },
                onSelectAll = { act { selectAll() } },
                onClear = { act { clear() } },
                onToggleOrder = {
                    toolbar.order = if (toolbar.order == WordOrder.READING) WordOrder.SELECTION else WordOrder.READING
                },
                onNextScript = { startScan(ScanScript.entries[(toolbar.script.ordinal + 1) % ScanScript.entries.size]) },
                onCopy = ::copySelection,
                onTranslate = { translate(selectedText()) },
                onClose = ::close,
                onMoved = ::savePrefs,
                onReadWithAi = if (isPhoto) ::readWholeImage else null,
            ),
        )
    }

    @Composable
    private fun ResultSheet() {
        val state by session.state.collectAsStateWithLifecycle()
        val current by settings.collectAsStateWithLifecycle()
        SheetFrame(onDismiss = ::closeSheet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (readingImage) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.selection_reading_image))
                } else {
                    state.request?.text?.takeIf { it.isNotEmpty() }?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(if (fromImage) R.string.label_from_image else R.string.label_original),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = ::openInApp) { Text(stringResource(R.string.action_open_in_app)) }
                        }
                        Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.status != RunStatus.IDLE) {
                        ResultPane(
                            state = state,
                            selectable = false,
                            actions = ResultActions(
                                onStop = session::stop,
                                onRetry = session::retry,
                                onStopBack = session::stopBackTranslation,
                                onOpenSettings = ::openSettings,
                                backTranslationOn = current.backTranslation,
                                onToggleBack = ::setBackTranslation,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun contentTop(image: Bitmap): Float = image.height / 8f

private fun contentBottom(image: Bitmap): Float = image.height * 7f / 8f

/** True when an app has blocked capture, which Android reports as a black image. */
private fun isBlackedOut(image: Bitmap): Boolean {
    val columns = 24
    val rows = 48
    var black = 0
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            // Stay clear of the status and navigation bars, which are still drawn.
            val x = image.width * (column + 1) / (columns + 1)
            val y = image.height / 8 + (image.height * 3 / 4) * row / rows
            val pixel = image.getPixel(x, y)
            if (AndroidColor.red(pixel) <= BLACK_LEVEL && AndroidColor.green(pixel) <= BLACK_LEVEL && AndroidColor.blue(pixel) <= BLACK_LEVEL) {
                black++
            }
        }
    }
    return black >= columns * rows * BLACK_SHARE
}
