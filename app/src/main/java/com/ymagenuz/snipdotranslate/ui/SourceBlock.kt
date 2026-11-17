package com.ymagenuz.snipdotranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ymagenuz.snipdotranslate.R

/** The source text at the top of a result sheet: its label with [actions], then a few lines of it. */
@Composable
fun SourcePreview(label: String, text: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
        SectionCaption(label, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        actions()
    }
    Text(
        text,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A quiet action beside a label: an icon and a word. */
@Composable
fun CaptionAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
        Text(text, maxLines = 1)
    }
}

/** The source text in an editable field, translated again with the corrections. */
@Composable
fun SourceEditor(
    label: String,
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    onTranslate: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SectionCaption(label, MaterialTheme.colorScheme.primary, Modifier.padding(top = 12.dp))
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        minLines = 2,
        maxLines = 8,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = MaterialTheme.shapes.medium,
        colors = fieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onTranslate, enabled = draft.text.isNotBlank()) {
            Icon(AppIcons.Translate, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
            Text(stringResource(R.string.action_translate))
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}
