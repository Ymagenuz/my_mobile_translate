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
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.ymagenuz.snipdotranslate.select.core.Collected
import com.ymagenuz.snipdotranslate.select.core.Junction
import com.ymagenuz.snipdotranslate.select.core.Pen
import com.ymagenuz.snipdotranslate.select.core.Quad
import com.ymagenuz.snipdotranslate.select.core.ScreenWord
import com.ymagenuz.snipdotranslate.select.core.SelectionModel
import com.ymagenuz.snipdotranslate.select.core.WordOrder
import com.ymagenuz.snipdotranslate.select.core.bottom
import com.ymagenuz.snipdotranslate.select.core.center
import com.ymagenuz.snipdotranslate.select.core.inReadingOrder
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
import com.ymagenuz.snipdotranslate.ui.shareText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAP_TOLERANCE_DP = 10f
private const val DRAG_SLOP_DP = 8f
private const val BLACK_LEVEL = 8
private const val BLACK_SHARE = 0.98f
private const val AREA_PADDING_PX = 8

// A selection ending in this share of the screen's height may go on below it.
private const val CONTINUE_ZONE = 0.25f

// The space the pill at the bottom edge takes, with the navigation bar under it.
private val PILL_ZONE_HEIGHT = 120.dp
private val PILL_HALF_WIDTH = 110.dp

/** Where the image came from: the screen just captured, or a photo or screenshot file. */
enum class ImageSource { SCREEN, PHOTO }

/**
 * What a selection hands on when it continues on the next screen: the words so far, and the
 * pen, order and scan language it was using, so the next screen carries on the same way.
 * [marks] are where this screen's kept words are, to keep them marked over the live app.
 * [keptOnScreen] are where the app's scrolling took them by the time the next screen was
 * captured, when it could be followed.
 */
data class Continuation(
    val collected: Collected,
    val pen: Pen,
    val order: WordOrder,
    val script: ScanScript,
    val toolbarCollapsed: Boolean,
    val marks: List<Quad> = emptyList(),
    val keptOnScreen: List<Quad> = emptyList(),
)

/**
 * A still image with the pens and result sheet. [attach] and [detach] put it wherever it
 * belongs. In an activity, the host handles Back by calling [onBack]. [inOverlayWindow] is
 * true in the accessibility service's overlay, which handles Back itself, and where Android
 * would hide toasts and can't open its text toolbar, so both are drawn inside it instead.
 * [barInsets] are the parts of a screenshot under the status and navigation bars; words there,
 * such as the clock, are left out of the scan. With [onContinue], a selection that reaches the
 * bottom can keep its words while the user scrolls to the rest; the next screen's selection
 * then gets them as [continuation] and picks up where they end.
 */
class SelectionOverlay(
    private val context: Context,
    private val image: Bitmap,
    private val scanner: OnDeviceScanner,
    private val source: ImageSource,
    private val attach: (View) -> Unit,
    private val detach: (View) -> Unit,
    private val onClosed: () -> Unit,
    private val inOverlayWindow: Boolean = false,
    private val barInsets: Insets = Insets.NONE,
    private val continuation: Continuation? = null,
    private val onContinue: ((Continuation) -> Unit)? = null,
) {
    private val app = context.applicationContext as SnipDoApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density = context.resources.displayMetrics.density
    private val owner = ComposeWindowOwner()

    private val toolbar = ToolbarState()
    private val canvas = SelectionCanvasView(context, image)
    private val session = app.newSession(scope)
    private val settings = app.settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private var model: SelectionModel? = null
    // Words taken from earlier screens, and where this screen picks up after them.
    private val collected = continuation?.collected ?: Collected.EMPTY
    private var junction = Junction.NONE
    private var canContinue by mutableStateOf(false)
    private var scanJob: Job? = null
    private var sheetVisible by mutableStateOf(false)
    private var readingImage by mutableStateOf(false)
    private var fromImage by mutableStateOf(false)
    // The source text being corrected before it's translated, for what the scan misread.
    private var editing by mutableStateOf(false)
    private var draft by mutableStateOf(TextFieldValue())
    // Off while editing a fresh selection, so an earlier result doesn't show under it.
    private var showResult by mutableStateOf(false)
    // A message shown inside the overlay window, where a toast would be hidden under it.
    private var notice by mutableStateOf<Notice?>(null)
    private var closed = false

    private val root = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            // An activity calls onBack from its own back handling instead.
            if (inOverlayWindow && event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    fun show() {
        owner.attachTo(root)

        canvas.onStrokeActive = { toolbar.strokeActive = it }
        canvas.onSelectionChanged = ::syncToolbar
        canvas.onArea = ::readArea

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(canvas, match, match)
        root.addView(ComposeView(context).apply { setContent { OverlayUi() } }, match, match)
        if (source == ImageSource.PHOTO) root.setBackgroundColor(AndroidColor.BLACK)
        // Receives the Back key in the overlay window.
        if (inOverlayWindow) root.isFocusableInTouchMode = true

        attach(root)
        if (inOverlayWindow) root.requestFocus()

        scope.launch {
            val saved = app.settingsStore.current()
            toolbar.plusDp = saved.plusBrushDp
            toolbar.minusDp = saved.minusBrushDp
            toolbar.x = saved.toolbarX
            toolbar.y = saved.toolbarY
            applyBrush()
        }
        continuation?.let {
            toolbar.pen = it.pen
            canvas.pen = it.pen
            toolbar.order = it.order
            toolbar.collapsed = it.toolbarCollapsed
        }
        syncToolbar()
        startScan(continuation?.script ?: toolbar.script)
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        owner.destroy()
        detach(root)
        // The screenshot exists only in memory and is freed once nothing refers to it.
        // Recycling it here would crash a frame or scan that is still using it.
        onClosed()
    }

    fun onBack() {
        if (sheetVisible) dismissSheet() else close()
    }

    private fun startScan(script: ScanScript) {
        toolbar.script = script
        toolbar.scanning = true
        scanJob?.cancel()
        scanJob = scope.launch {
            val words = try {
                inReadingOrder(scanner.scan(image, script).filterNot(::underBars))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The area pen still works without the on-device scan.
                tell(R.string.selection_scan_failed, long = true)
                emptyList()
            }
            // The status bar is still drawn over a blocked app, so its clock may be read.
            val contentWords = words.filter { it.quad.center.y in contentTop(image)..contentBottom(image) }
            if (source == ImageSource.SCREEN && contentWords.isEmpty() && withContext(Dispatchers.Default) { isBlackedOut(image) }) {
                // A toast, which shows once the selection has closed, even over the overlay window.
                Toast.makeText(context, R.string.selection_blocked, Toast.LENGTH_LONG).show()
                // Words kept from earlier screens wait in the bar for a screen that can be captured.
                // The marks belong to a screen that has gone.
                continuation?.let { onContinue?.invoke(it.copy(marks = emptyList())) }
                close()
                return@launch
            }
            toolbar.scanning = false
            junction = collected.junctionWith(words, continuation?.keptOnScreen.orEmpty())
            val scanned = SelectionModel(
                words,
                TAP_TOLERANCE_DP * density,
                DRAG_SLOP_DP * density,
                locked = junction.carried,
                initial = junction.replacement,
            )
            // Set first, since applying strokes drawn during the scan updates the toolbar from it.
            model = scanned
            canvas.setModel(scanned)
            when {
                words.isEmpty() -> tell(R.string.selection_no_text, long = true)
                !collected.isEmpty && !junction.found -> tell(R.string.continue_not_found, long = true)
            }
        }
    }

    /** Whether a word is in the captured status or navigation bar, like the clock or a carrier name. */
    private fun underBars(word: ScreenWord): Boolean {
        val center = word.quad.center
        return center.y < barInsets.top || center.y > image.height - barInsets.bottom ||
            center.x < barInsets.left || center.x > image.width - barInsets.right
    }

    /** A short message while the selection stays open, as a toast or inside the overlay window. */
    private fun tell(@StringRes text: Int, long: Boolean = false) {
        if (inOverlayWindow) {
            notice = Notice(context.getString(text), if (long) NOTICE_LONG_MS else NOTICE_SHORT_MS)
        } else {
            Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncToolbar() {
        val current = model
        toolbar.canUndo = current?.canUndo == true
        toolbar.canRedo = current?.canRedo == true
        toolbar.hasSelection = current?.hasSelection == true
        toolbar.hasText = toolbar.hasSelection || !collected.isEmpty
        toolbar.count = (current?.selectedIds?.size ?: 0) + collected.words.size
        // Also offered with nothing new selected yet, to scroll further before capturing again.
        canContinue = onContinue != null && (reachesBottom(current) || (!collected.isEmpty && !toolbar.hasSelection))
    }

    /** Whether the selection runs into the bottom of the screen, where the text may go on. */
    private fun reachesBottom(current: SelectionModel?): Boolean {
        if (current == null || !current.hasSelection) return false
        val top = barInsets.top.toFloat()
        val bottom = (image.height - barInsets.bottom).toFloat()
        val lowest = current.selectedIds.maxOf { current.words[it].quad.bottom }
        return lowest >= bottom - (bottom - top) * CONTINUE_ZONE
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

    /** Everything selected so far: the earlier screens' words, then this screen's. */
    private fun collectedSoFar(): Collected =
        collected.plus(model?.selectedWords(toolbar.order).orEmpty(), junction, (image.height - barInsets.bottom).toFloat())

    private fun selectedText(): String = collectedSoFar().text

    /** Keeps what's selected and leaves the frozen screen, so the user can scroll to the rest. */
    private fun continueBelow() {
        val handOn = onContinue ?: return
        // Everything kept on this screen, including what was taken from the one before.
        val marks = model?.let { kept -> (kept.locked + kept.selectedIds).map { kept.words[it].quad } }.orEmpty()
        handOn(Continuation(collectedSoFar(), toolbar.pen, toolbar.order, toolbar.script, toolbar.collapsed, marks))
        close()
    }

    private fun copySelection() {
        val text = selectedText()
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("selection", text))
        tell(R.string.action_copied)
    }

    private fun translate(text: String) {
        if (text.isEmpty()) return
        val current = settings.value
        sheetVisible = true
        editing = false
        showResult = true
        session.translate(TranslationRequest(text, false, current.sourceLanguage, current.targetLanguage))
    }

    /** Opens the selected words for correcting before anything is sent. */
    private fun editSelection() {
        val text = selectedText()
        if (text.isEmpty()) return
        session.stop()
        fromImage = false
        showResult = false
        startEditing(text)
        sheetVisible = true
    }

    /** Opens the text that was just translated, to correct it and translate again. */
    private fun editSource() {
        startEditing(session.state.value.request?.text.orEmpty())
    }

    private fun startEditing(text: String) {
        draft = TextFieldValue(text, TextRange(text.length))
        editing = true
    }

    private fun cancelEditing() {
        // Nothing was translated yet, so there's nothing to go back to in the sheet.
        if (showResult) editing = false else closeSheet()
    }

    /**
     * Swiping the sheet away, tapping outside it or Back. Once something has been translated
     * that means done, so the selection closes too; before that, it returns to the pens.
     */
    private fun dismissSheet() {
        if (showResult) close() else closeSheet()
    }

    /** Back to the pens with the same words selected, to change them and translate again. */
    private fun selectAgain() = closeSheet()

    private fun closeSheet() {
        session.stop()
        sheetVisible = false
        readingImage = false
        fromImage = false
        editing = false
        showResult = false
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
        editing = false
        showResult = true
        readingImage = true
        scope.launch {
            val result = readImageWithVision(app, source)
            afterRead()
            readingImage = false
            when (result) {
                is VisionResult.Text -> {
                    fromImage = true
                    // Follows the words taken from earlier screens, as selected words would.
                    translate(if (collected.isEmpty) result.text else collected.text + "\n\n" + result.text)
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

    /** Hands the translation to Android's share sheet, which would open under a selection left up. */
    private fun share(text: String) {
        shareText(context, text, newTask = true)
        close()
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
            val screen: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize()) {
                    if (sheetVisible) {
                        ResultSheet()
                    } else {
                        ContinuePill()
                        Toolbar()
                    }
                    notice?.let { NoticeBubble(it) }
                }
            }
            if (inOverlayWindow) InWindowTextMenu(screen) else screen()
        }
    }

    /** Looks like a toast, and like one lets touches through to whatever is under it. */
    @Composable
    private fun BoxScope.NoticeBubble(shown: Notice) {
        LaunchedEffect(shown) {
            delay(shown.durationMs)
            if (notice === shown) notice = null
        }
        Text(
            shown.text,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 72.dp)
                .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                // Read out by screen readers, as a toast would be.
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    /**
     * At the bottom edge: which screen this is, once a selection has continued, and Continue
     * when the selection reaches the bottom. Just above the toolbar when that sits along the
     * bottom, where it would cover the pill.
     */
    @Composable
    private fun ContinuePill() {
        val part = if (collected.isEmpty) null else collected.parts + 1
        if (part == null && !canContinue) return
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val areaWidth = constraints.maxWidth.toFloat()
            val areaHeight = constraints.maxHeight.toFloat()
            // Where the toolbar is drawn, kept on screen as it keeps itself.
            val toolbarLeft = toolbar.x.coerceIn(0f, (areaWidth - toolbar.width).coerceAtLeast(0f))
            val toolbarTop = toolbar.y.coerceIn(0f, (areaHeight - toolbar.height).coerceAtLeast(0f))
            val halfPill = with(density) { PILL_HALF_WIDTH.toPx() }
            val coversPill = toolbar.height > 0f &&
                toolbarTop + toolbar.height > areaHeight - with(density) { PILL_ZONE_HEIGHT.toPx() } &&
                toolbarLeft < areaWidth / 2f + halfPill && toolbarLeft + toolbar.width > areaWidth / 2f - halfPill
            val placement = if (coversPill) {
                Modifier.padding(bottom = with(density) { (areaHeight - toolbarTop).toDp() } + 8.dp)
            } else {
                Modifier.navigationBarsPadding().padding(bottom = 16.dp)
            }
            Pill(part, Modifier.align(Alignment.BottomCenter).then(placement))
        }
    }

    @Composable
    private fun Pill(part: Int?, modifier: Modifier) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 6.dp,
            modifier = modifier.alpha(if (toolbar.strokeActive) 0.2f else 1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                part?.let {
                    Text(
                        stringResource(R.string.continue_part, it),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                    )
                }
                if (canContinue) {
                    TextButton(onClick = ::continueBelow, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.continue_below))
                        Icon(AppIcons.ArrowDown, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(20.dp))
                    }
                }
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
                onEdit = ::editSelection,
                onClose = ::close,
                onMoved = ::savePrefs,
                onReadWithAi = if (source == ImageSource.PHOTO) ::readWholeImage else null,
            ),
        )
    }

    @Composable
    private fun ResultSheet() {
        val state by session.state.collectAsStateWithLifecycle()
        val current by settings.collectAsStateWithLifecycle()
        val actions = ResultActions(
            onStop = session::stop,
            onRetry = session::retry,
            onStopBack = session::stopBackTranslation,
            onOpenSettings = ::openSettings,
            backTranslationOn = current.backTranslation,
            onToggleBack = ::setBackTranslation,
            onShare = ::share,
        )
        val resultShown = !readingImage && showResult && state.status != RunStatus.IDLE
        val bar: (@Composable () -> Unit)? = if (resultShown) {
            {
                ResultActionBar(
                    state = state,
                    actions = actions,
                    onOpenInApp = if (!editing && state.request != null) ::openInApp else null,
                )
            }
        } else {
            null
        }
        SheetFrame(onDismiss = ::dismissSheet, bottomBar = bar) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Without the bar below, the content itself keeps clear of the navigation bar.
                    .then(if (resultShown) Modifier else Modifier.navigationBarsPadding())
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (readingImage) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Text(stringResource(R.string.selection_reading_image))
                    }
                } else {
                    val label = stringResource(if (fromImage) R.string.label_from_image else R.string.label_original)
                    if (editing) {
                        SourceEditor(
                            label = label,
                            draft = draft,
                            onDraftChange = { draft = it },
                            onTranslate = { translate(draft.text.trim()) },
                            onCancel = ::cancelEditing,
                        )
                    } else {
                        state.request?.text?.takeIf { it.isNotEmpty() }?.let { source ->
                            SourcePreview(label, source) {
                                CaptionAction(AppIcons.Edit, stringResource(R.string.action_edit), ::editSource)
                                CaptionAction(AppIcons.ScreenSelect, stringResource(R.string.action_select_again), ::selectAgain)
                            }
                        }
                    }
                    if (resultShown) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                        )
                        ResultPane(state = state, actions = actions, showActions = false, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
    }
}

/** A new instance for each message, so the same text shown again starts its time again. */
private class Notice(val text: String, val durationMs: Long)

// As long as Android shows a short or long toast.
private const val NOTICE_SHORT_MS = 2000L
private const val NOTICE_LONG_MS = 3500L

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
