package com.ymagenuz.snipdotranslate.select.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.ocr.ScanScript
import com.ymagenuz.snipdotranslate.select.core.Pen
import com.ymagenuz.snipdotranslate.select.core.WordOrder
import com.ymagenuz.snipdotranslate.ui.AppIcons
import com.ymagenuz.snipdotranslate.ui.PillButton
import kotlin.math.roundToInt

const val MIN_BRUSH_DP = 8f
const val MAX_BRUSH_DP = 96f

// Eight of these fit across a phone 360 dp wide, which the pens' row needs.
private val TOOL_WIDTH = 40.dp
private val TOOL_HEIGHT = 44.dp
private val ACTION_HEIGHT = 46.dp

// On narrower screens Edit and Copy show as icons alone, so they stay on one row with Translate.
private val COMPACT_BELOW = 380.dp

/** What the toolbar shows, updated from the canvas and the scan. */
class ToolbarState {
    var pen by mutableStateOf(Pen.TEXT)
    var scanning by mutableStateOf(true)
    var strokeActive by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var hasSelection by mutableStateOf(false)
    // Something to translate: a selection here, or words kept from earlier screens.
    var hasText by mutableStateOf(false)
    // How many words that is, shown on Translate.
    var count by mutableIntStateOf(0)
    var order by mutableStateOf(WordOrder.READING)
    var script by mutableStateOf(ScanScript.CHINESE)
    var plusDp by mutableFloatStateOf(16f)
    var minusDp by mutableFloatStateOf(20f)
    var collapsed by mutableStateOf(false)
    var showWidth by mutableStateOf(false)
    var x by mutableFloatStateOf(-1f)
    var y by mutableFloatStateOf(-1f)
    // Its size on screen, so other controls can keep clear of it.
    var width by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
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
    val onEdit: () -> Unit,
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
        val compact = maxWidth < COMPACT_BELOW
        var boxWidth by remember { mutableFloatStateOf(0f) }
        var boxHeight by remember { mutableFloatStateOf(0f) }
        val margin = with(density) { 8.dp.toPx() }

        if (state.x < 0f || state.y < 0f) {
            state.x = margin
            state.y = with(density) { 96.dp.toPx() }
        }

        Surface(
            shape = if (state.collapsed) CircleShape else RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 8.dp,
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
                    state.width = boxWidth
                    state.height = boxHeight
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
            if (state.collapsed) Pill(state, dragModifier) else Expanded(state, actions, dragModifier, compact)
        }
    }
}

@Composable
private fun Pill(state: ToolbarState, dragModifier: Modifier) {
    val expand = stringResource(R.string.tool_expand)
    Box(
        contentAlignment = Alignment.Center,
        modifier = dragModifier
            .size(52.dp)
            .clickable(role = Role.Button) { state.collapsed = false }
            .semantics { contentDescription = expand },
    ) {
        if (state.scanning) {
            ScanRing(MaterialTheme.colorScheme.primary)
        } else {
            Icon(AppIcons.Expand, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScanRing(color: Color) {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = color)
}

/**
 * The pens, then what changes the selection, then what is done with it. The bar along the top and
 * the gaps in the rows move the toolbar, so either hand can reach somewhere to drag it by.
 */
@Composable
private fun Expanded(state: ToolbarState, actions: ToolbarActions, dragModifier: Modifier, compact: Boolean) {
    // As wide as its widest row, so the controls at the ends sit at the toolbar's own edges.
    Column(
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val move = stringResource(R.string.tool_move)
        Box(
            contentAlignment = Alignment.Center,
            modifier = dragModifier
                .fillMaxWidth()
                .height(20.dp)
                .semantics { contentDescription = move },
        ) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                    .padding(2.dp),
            ) {
                PenTool(state, actions, Pen.TEXT, AppIcons.PenText, R.string.pen_text)
                PenTool(state, actions, Pen.PLUS, AppIcons.PenPlus, R.string.pen_plus)
                PenTool(state, actions, Pen.MINUS, AppIcons.PenMinus, R.string.pen_minus)
                PenTool(state, actions, Pen.LASSO, AppIcons.PenLasso, R.string.pen_lasso)
                PenTool(state, actions, Pen.PARAGRAPH, AppIcons.PenParagraph, R.string.pen_paragraph)
                PenTool(state, actions, Pen.AREA, AppIcons.PenArea, R.string.pen_area)
            }
            Spacer(dragModifier.weight(1f).height(TOOL_HEIGHT))
            Tool(AppIcons.Collapse, stringResource(R.string.tool_collapse)) { state.collapsed = true }
            Tool(AppIcons.Close, stringResource(R.string.action_close), onClick = actions.onClose)
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Tool(AppIcons.Undo, stringResource(R.string.tool_undo), enabled = state.canUndo, onClick = actions.onUndo)
            Tool(AppIcons.Redo, stringResource(R.string.tool_redo), enabled = state.canRedo, onClick = actions.onRedo)
            Tool(AppIcons.SelectAll, stringResource(R.string.tool_all), onClick = actions.onSelectAll)
            Tool(
                AppIcons.ClearSelection,
                stringResource(R.string.tool_clear),
                enabled = state.hasSelection,
                onClick = actions.onClear,
            )
            Spacer(dragModifier.weight(1f).height(TOOL_HEIGHT))
            // A photo's row also holds the vision model's button, which leaves no room for the word.
            OrderToggle(state, actions, showLabel = !compact && actions.onReadWithAi == null)
            ScriptButton(state, actions)
            actions.onReadWithAi?.let {
                Tool(AppIcons.Sparkles, stringResource(R.string.tool_read_with_ai), onClick = it)
            }
        }

        if (state.showWidth && (state.pen == Pen.PLUS || state.pen == Pen.MINUS)) WidthSlider(state, actions)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TranslateButton(state, actions, Modifier.weight(1f))
            // Corrects what the scan misread before anything is sent.
            SecondaryAction(AppIcons.Edit, stringResource(R.string.action_edit), state.hasText, compact, actions.onEdit)
            SecondaryAction(AppIcons.Copy, stringResource(R.string.action_copy), state.hasText, compact, actions.onCopy)
        }
    }
}

/** Shows that the scan is still running, and then how many words Translate will send. */
@Composable
private fun TranslateButton(state: ToolbarState, actions: ToolbarActions, modifier: Modifier) {
    Button(
        onClick = actions.onTranslate,
        enabled = state.hasText,
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = modifier.height(ACTION_HEIGHT),
    ) {
        if (state.scanning) {
            ScanRing(LocalContentColor.current)
        } else {
            Icon(AppIcons.Translate, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(stringResource(R.string.action_translate), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        if (state.count > 0) {
            Text(
                state.count.toString(),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(LocalContentColor.current.copy(alpha = 0.2f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SecondaryAction(icon: ImageVector, label: String, enabled: Boolean, compact: Boolean, onClick: () -> Unit) {
    if (compact) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(ACTION_HEIGHT)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
    } else {
        PillButton(label, onClick, modifier = Modifier.height(ACTION_HEIGHT), icon = icon, enabled = enabled)
    }
}

@Composable
private fun OrderToggle(state: ToolbarState, actions: ToolbarActions, showLabel: Boolean) {
    val reading = state.order == WordOrder.READING
    val icon = if (reading) AppIcons.OrderReading else AppIcons.OrderPicked
    val description = stringResource(if (reading) R.string.order_reading else R.string.order_selection)
    if (!showLabel) {
        Tool(icon, description, onClick = actions.onToggleOrder)
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .height(40.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(role = Role.Button, onClick = actions.onToggleOrder)
            .semantics { contentDescription = description }
            .padding(start = 10.dp, end = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            stringResource(if (reading) R.string.order_reading_short else R.string.order_selection_short),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            // The description above already says it in full.
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** The script the scan reads, shown as a letter of it; tapping moves to the next. */
@Composable
private fun ScriptButton(state: ToolbarState, actions: ToolbarActions) {
    val description = stringResource(R.string.tool_script)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 6.dp)
            .size(width = TOOL_WIDTH, height = 40.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(role = Role.Button, onClick = actions.onNextScript)
            .semantics { contentDescription = description },
    ) {
        Text(
            scriptLabel(state.script),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = "brush width" },
    )
}

@Composable
private fun PenTool(state: ToolbarState, actions: ToolbarActions, pen: Pen, icon: ImageVector, description: Int) {
    Tool(icon, stringResource(description), selected = state.pen == pen) { actions.onPen(pen) }
}

@Composable
private fun Tool(
    icon: ImageVector,
    description: String,
    // Null for a tool that isn't one of a set to choose from.
    selected: Boolean? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val chosen = selected == true
    val container = if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent
    val tint = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = TOOL_WIDTH, height = TOOL_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (selected != null) this.selected = selected
            },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp).alpha(if (enabled) 1f else 0.35f),
        )
    }
}

private fun scriptLabel(script: ScanScript): String = when (script) {
    ScanScript.CHINESE -> "中"
    ScanScript.LATIN -> "A"
    ScanScript.JAPANESE -> "あ"
    ScanScript.KOREAN -> "한"
}
