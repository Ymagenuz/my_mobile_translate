package com.ymagenuz.snipdotranslate.ui

import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as PlatformIcon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The row at the top of a page: Back, the page's name, and its own actions. */
@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit, backLabel: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = backLabel) }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        actions()
    }
}

/** A white card with a hairline border, the frame for each group of content. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    if (onClick == null) {
        Surface(shape = MaterialTheme.shapes.large, color = color, border = border, modifier = modifier) {
            Column(content = content)
        }
    } else {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = color, border = border, modifier = modifier) {
            Column(content = content)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** An icon in a small tinted circle, at the start of a row. */
@Composable
fun IconBadge(
    icon: ImageVector,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.primary,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(container, CircleShape)) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}

/** A row in a card: what it is, how it stands, and something to do about it at the end. */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.outline,
    badgeContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    badgeContent: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        IconBadge(icon, badgeContainer, badgeContent)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
        }
        trailing?.invoke()
    }
}

/** A rounded button in a soft colour, with an optional icon before its label. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(start = if (icon == null) 16.dp else 12.dp, end = 16.dp),
        modifier = modifier.heightIn(min = 44.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(20.dp))
        }
        Text(text, maxLines = 1)
    }
}

/** A text field on a card-coloured ground, with a border no heavier than the cards around it. */
@Composable
fun fieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
)

/** A result as plain words for a one- or two-line preview, without the Markdown it was written in. */
fun plainPreview(markdown: String): String =
    markdown.lineSequence()
        .map { it.trim().trimStart('#', '>', '*', '-', ' ').replace("**", "").replace("`", "") }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The first thing on the clipboard as text, or null when there is none or it can't be read. */
fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}

/** Opens Android's share sheet for [text]. From a service, pass [newTask]. */
fun shareText(context: Context, text: String, newTask: Boolean = false) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, null)
    if (newTask) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // Nothing on this phone takes shared text.
    }
}

/** Asks Android to add a Quick Settings tile; [onResult] gets a StatusBarManager.TILE_ADD_REQUEST_RESULT value. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun requestTile(context: Context, service: Class<*>, label: String, icon: Int, onResult: (Int) -> Unit) {
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, service),
        label,
        PlatformIcon.createWithResource(context, icon),
        context.mainExecutor,
    ) { result -> onResult(result) }
}

/** Whether the tile is in Quick Settings after a request that ended with [result]. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun tileIsAdded(result: Int): Boolean =
    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
