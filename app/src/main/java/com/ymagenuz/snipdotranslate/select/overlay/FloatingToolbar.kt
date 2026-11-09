package com.ymagenuz.snipdotranslate.select.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.ocr.ScanScript
import com.ymagenuz.snipdotranslate.select.core.Pen
import com.ymagenuz.snipdotranslate.select.core.WordOrder
import kotlin.math.roundToInt

const val MIN_BRUSH_DP = 8f
const val MAX_BRUSH_DP = 96f

/** What the toolbar shows, updated from the canvas and the scan. */
class ToolbarState {
    var pen by mutableStateOf(Pen.TEXT)
    var scanning by mutableStateOf(true)
    var strokeActive by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var hasSelection by mutableStateOf(false)
    var order by mutableStateOf(WordOrder.READING)
    var script by mutableStateOf(ScanScript.CHINESE)
    var plusDp by mutableFloatStateOf(16f)
    var minusDp by mutableFloatStateOf(20f)
    var collapsed by mutableStateOf(false)
    var showWidth by mutableStateOf(false)
    var x by mutableFloatStateOf(-1f)
    var y by mutableFloatStateOf(-1f)
}

data class ToolbarActions(
    val onPen: (Pen) -> Unit,
    val onBrushChanged: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onSelectAll: () -> Unit,
    val onClear: () -> Unit,
    val onToggleOrder: () -> Unit,
    val onNextScript: () -> Unit,
    val onCopy: () -> Unit,
    val onTranslate: () -> Unit,
    val onClose: () -> Unit,
    val onMoved: () -> Unit,
    val onReadWithAi: (() -> Unit)? = null,
)

@Composable
fun FloatingToolbar(state: ToolbarState, actions: ToolbarActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val areaWidth = with(density) { maxWidth.toPx() }
        val areaHeight = with(density) { maxHeight.toPx() }
        var boxWidth by remember { mutableFloatStateOf(0f) }
        var boxHeight by remember { mutableFloatStateOf(0f) }
        val margin = with(density) { 8.dp.toPx() }

        if (state.x < 0f || state.y < 0f) {
            state.x = margin
            state.y = with(density) { 96.dp.toPx() }
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 6.dp,
            tonalElevation = 3.dp,
            modifier = Modifier
                .offset {
                    IntOffset(
                        state.x.coerceIn(0f, (areaWidth - boxWidth).coerceAtLeast(0f)).roundToInt(),
                        state.y.coerceIn(0f, (areaHeight - boxHeight).coerceAtLeast(0f)).roundToInt(),
                    )
                }
                .alpha(if (state.strokeActive) 0.2f else 1f)
                .onSizeChanged {
                    boxWidth = it.width.toFloat()
                    boxHeight = it.height.toFloat()
                },
        ) {
            val dragModifier = Modifier.pointerInput(areaWidth, areaHeight) {
                detectDragGestures(
                    onDragEnd = {
                        // Rests against the nearer side, as a drawing app's palette does.
                        val middle = state.x + boxWidth / 2f
                        state.x = if (middle < areaWidth / 2f) margin else areaWidth - boxWidth - margin
                        state.y = state.y.coerceIn(0f, areaHeight - boxHeight)
                        actions.onMoved()
                    },
                ) { change, amount ->
                    change.consume()
                    state.x = (state.x + amount.x).coerceIn(0f, (areaWidth - boxWidth).coerceAtLeast(0f))
                    state.y = (state.y + amount.y).coerceIn(0f, (areaHeight - boxHeight).coerceAtLeast(0f))
                }
            }
            if (state.collapsed) Pill(state, dragModifier) else Expanded(state, actions, dragModifier)
        }
    }
}

@Composable
private fun Pill(state: ToolbarState, dragModifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = dragModifier
            .size(52.dp)
            .clickable { state.collapsed = false },
    ) {
        if (state.scanning) ScanRing() else Text("···", fontSize = 18.sp)
    }
}

@Composable
private fun ScanRing() {
    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
}

@Composable
private fun Expanded(state: ToolbarState, actions: ToolbarActions, dragModifier: Modifier) {
    Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tool("⠿", stringResource(R.string.tool_move), modifier = dragModifier) { }
            PenTool(state, actions, Pen.TEXT, "✎", R.string.pen_text)
            PenTool(state, actions, Pen.PLUS, "+", R.string.pen_plus)
            PenTool(state, actions, Pen.MINUS, "−", R.string.pen_minus)
            PenTool(state, actions, Pen.LASSO, "◯", R.string.pen_lasso)
            PenTool(state, actions, Pen.PARAGRAPH, "¶", R.string.pen_paragraph)
            PenTool(state, actions, Pen.AREA, "▭", R.string.pen_area)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tool("↶", stringResource(R.string.tool_undo), enabled = state.canUndo, onClick = actions.onUndo)
            Tool("↷", stringResource(R.string.tool_redo), enabled = state.canRedo, onClick = actions.onRedo)
            Tool(stringResource(R.string.tool_all_short), stringResource(R.string.tool_all), onClick = actions.onSelectAll)
            Tool("⌫", stringResource(R.string.tool_clear), enabled = state.hasSelection, onClick = actions.onClear)
            Tool(
                if (state.order == WordOrder.READING) "≡" else "⇅",
                stringResource(if (state.order == WordOrder.READING) R.string.order_reading else R.string.order_selection),
                onClick = actions.onToggleOrder,
            )
            Tool(scriptLabel(state.script), stringResource(R.string.tool_script), onClick = actions.onNextScript)
            Tool("–", stringResource(R.string.tool_collapse)) { state.collapsed = true }
        }
        if (state.showWidth && (state.pen == Pen.PLUS || state.pen == Pen.MINUS)) WidthSlider(state, actions)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = actions.onTranslate, enabled = state.hasSelection) {
                Text(stringResource(R.string.action_translate))
            }
            OutlinedButton(onClick = actions.onCopy, enabled = state.hasSelection) {
                Text(stringResource(R.string.action_copy))
            }
            actions.onReadWithAi?.let {
                Tool(stringResource(R.string.tool_ai_short), stringResource(R.string.tool_read_with_ai), onClick = it)
            }
            Spacer(Modifier.width(2.dp))
            if (state.scanning) ScanRing()
            Tool("✕", stringResource(R.string.action_close), onClick = actions.onClose)
        }
    }
}

@Composable
private fun WidthSlider(state: ToolbarState, actions: ToolbarActions) {
    val isPlus = state.pen == Pen.PLUS
    Slider(
        value = if (isPlus) state.plusDp else state.minusDp,
        onValueChange = {
            if (isPlus) state.plusDp = it else state.minusDp = it
            actions.onBrushChanged()
        },
        valueRange = MIN_BRUSH_DP..MAX_BRUSH_DP,
        modifier = Modifier
            .width(300.dp)
            .semantics { contentDescription = "brush width" },
    )
}

@Composable
private fun PenTool(state: ToolbarState, actions: ToolbarActions, pen: Pen, glyph: String, description: Int) {
    Tool(glyph, stringResource(description), selected = state.pen == pen) { actions.onPen(pen) }
}

@Composable
private fun Tool(
    glyph: String,
    description: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent
    ToolBox(modifier, background, enabled, description, onClick) {
        Text(glyph, fontSize = 18.sp, modifier = Modifier.alpha(if (enabled) 1f else 0.35f))
    }
}

@Composable
private fun ToolBox(
    modifier: Modifier,
    background: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        content = content,
        modifier = modifier
            .size(44.dp)
            .background(background, RoundedCornerShape(10.dp))
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClick = onClick),
    )
}

private fun scriptLabel(script: ScanScript): String = when (script) {
    ScanScript.CHINESE -> "中"
    ScanScript.LATIN -> "A"
    ScanScript.JAPANESE -> "あ"
    ScanScript.KOREAN -> "한"
}
