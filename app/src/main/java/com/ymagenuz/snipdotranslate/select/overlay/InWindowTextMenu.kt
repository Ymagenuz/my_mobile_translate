package com.ymagenuz.snipdotranslate.select.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred

private val MENU_GAP = 12.dp
private val MENU_MARGIN = 8.dp

// Opening other apps' text actions from the overlay isn't possible, so only these are offered.
private val MENU_KEYS = setOf(
    TextContextMenuKeys.CutKey,
    TextContextMenuKeys.CopyKey,
    TextContextMenuKeys.PasteKey,
    TextContextMenuKeys.SelectAllKey,
)

/**
 * The cut, copy, paste and select-all menu for selected text, drawn inside [content]'s own
 * window. Android's floating text toolbar only opens in an activity's window, so in the
 * accessibility overlay selected text would otherwise have no menu.
 */
@Composable
fun InWindowTextMenu(content: @Composable () -> Unit) {
    val menu = remember { InWindowMenu() }
    CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides menu) {
        Box {
            content()
            menu.open?.let { MenuBar(it) }
        }
    }
}

private class OpenMenu(val session: TextContextMenuSession, val data: TextContextMenuDataProvider)

/** Text fields and selections ask this to show their menu, and wait until it closes. */
private class InWindowMenu : TextContextMenuProvider {
    var open by mutableStateOf<OpenMenu?>(null)
        private set

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val closed = CompletableDeferred<Unit>()
        val shown = OpenMenu(
            object : TextContextMenuSession {
                override fun close() {
                    closed.complete(Unit)
                }
            },
            dataProvider,
        )
        open = shown
        try {
            closed.await()
        } finally {
            // Also when the selection is cleared, which cancels the wait.
            if (open === shown) open = null
        }
    }
}

/** Sits above the selected text, or below it when there's no room, and stays on screen. */
@Composable
private fun MenuBar(menu: OpenMenu) {
    val items = menu.data.data().components.filterIsInstance<TextContextMenuItem>().filter { it.key in MENU_KEYS }
    if (items.isEmpty()) return
    Layout(
        content = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 4.dp)) {
                    for (item in items) {
                        TextButton(onClick = { item.onClick(menu.session) }) { Text(item.label) }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val bar = measurables.single().measure(Constraints(maxWidth = width, maxHeight = height))
        layout(width, height) {
            // Read while placing, so the bar follows the selection as the sheet scrolls.
            val here = coordinates ?: return@layout
            val selection = menu.data.contentBounds(here)
            val gap = MENU_GAP.roundToPx()
            val margin = MENU_MARGIN.roundToPx()
            val above = selection.top.roundToInt() - gap - bar.height
            val y = if (above >= margin) above else selection.bottom.roundToInt() + gap
            val x = selection.center.x.roundToInt() - bar.width / 2
            bar.place(
                x.coerceIn(margin, (width - bar.width - margin).coerceAtLeast(margin)),
                y.coerceIn(margin, (height - bar.height - margin).coerceAtLeast(margin)),
            )
        }
    }
}
