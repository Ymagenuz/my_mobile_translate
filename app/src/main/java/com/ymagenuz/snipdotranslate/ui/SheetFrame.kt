package com.ymagenuz.snipdotranslate.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val SHEET_MAX_HEIGHT_FRACTION = 0.85f
private val DISMISS_DRAG_DISTANCE = 96.dp

/**
 * A bottom sheet drawn inside the current window, shared by the popup and the selection
 * screen, so it sits over the image or the app below instead of in a separate dialog.
 * Tapping outside or dragging the handle down dismisses it; the host handles Back.
 */
@Composable
fun SheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dismissDistance = with(density) { DISMISS_DRAG_DISTANCE.toPx() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            // Keeps a text field in the sheet above the keyboard.
            .imePadding(),
    ) {
        val offset = remember { Animatable(with(density) { maxHeight.toPx() }) }
        LaunchedEffect(Unit) { offset.animateTo(0f) }

        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight * SHEET_MAX_HEIGHT_FRACTION)
                .offset { IntOffset(0, offset.value.toInt()) }
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .draggable(
                            state = rememberDraggableState { delta ->
                                scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) }
                            },
                            orientation = Orientation.Vertical,
                            onDragStopped = {
                                if (offset.value > dismissDistance) onDismiss() else offset.animateTo(0f)
                            },
                        ),
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
                    )
                }
                content()
            }
        }
    }
}
