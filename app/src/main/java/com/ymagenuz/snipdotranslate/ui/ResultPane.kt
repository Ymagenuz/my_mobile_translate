package com.ymagenuz.snipdotranslate.ui

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.core.SOURCE_LANGUAGES
import com.ymagenuz.snipdotranslate.core.TARGET_DEFAULT
import com.ymagenuz.snipdotranslate.core.TARGET_LANGUAGES
import com.ymagenuz.snipdotranslate.core.TranslationMode
import com.ymagenuz.snipdotranslate.domain.BackStatus
import com.ymagenuz.snipdotranslate.domain.BackTranslationState
import com.ymagenuz.snipdotranslate.domain.RunError
import com.ymagenuz.snipdotranslate.domain.RunStatus
import com.ymagenuz.snipdotranslate.domain.TranslationState
import com.ymagenuz.snipdotranslate.ui.reveal.LocalTextReveal
import com.ymagenuz.snipdotranslate.ui.reveal.rememberRevealComponents
import java.util.Locale
import kotlinx.coroutines.delay

private const val COPIED_NOTE_MS = 1500L

data class ResultActions(
    val onStop: () -> Unit,
    val onRetry: () -> Unit,
    val onStopBack: () -> Unit,
    val onOpenSettings: () -> Unit,
    val backTranslationOn: Boolean,
    val onToggleBack: (Boolean) -> Unit,
)

/** The translation and its back-translation; shared by the app, the popup and the overlay. */
@Composable
fun ResultPane(
    state: TranslationState,
    actions: ResultActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                resultLabel(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (state.status == RunStatus.RUNNING) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = actions.onStop) { Text(stringResource(R.string.action_stop)) }
            }
            Spacer(Modifier.weight(1f))
            if (state.mode != TranslationMode.DICTIONARY) {
                FilterChip(
                    selected = actions.backTranslationOn,
                    onClick = { actions.onToggleBack(!actions.backTranslationOn) },
                    label = { Text(stringResource(R.string.back_translate)) },
                )
            }
        }

        if (state.text.isNotEmpty()) {
            MarkdownText(state.text, running = state.status == RunStatus.RUNNING)
        } else if (state.status == RunStatus.RUNNING) {
            val waiting = if (state.mode == TranslationMode.DICTIONARY) R.string.action_looking_up else R.string.action_translating
            PendingNote(stringResource(waiting), MaterialTheme.colorScheme.tertiary)
        }

        when (state.status) {
            RunStatus.STOPPED -> Text(
                stringResource(R.string.note_stopped),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
            )
            RunStatus.FAILED -> ErrorNote(state.error, actions)
            else -> Unit
        }

        if (state.text.isNotBlank() && state.status != RunStatus.RUNNING) CopyButton(state.text)
        BackTranslationPanel(state.back, actions.onStopBack)
    }
}

/** Says what an empty result is waiting for, as the desktop's empty result box does. */
@Composable
private fun PendingNote(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        // With Android's "Remove animations" the spinner would stand still and look stuck.
        if (ValueAnimator.areAnimatorsEnabled()) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
}

/** Set once the text has been seen streaming in; plain, since [running] changing recomposes anyway. */
private class StreamedFlag {
    var value = false
}

/**
 * Selectable, so part of a result can be copied. Text fades in while [running], and for the last chunk, which arrives as the run ends.
 * A finished result shown later, such as after rotating, appears at once.
 */
@Composable
private fun MarkdownText(text: String, running: Boolean) {
    val type = MaterialTheme.typography
    val streamed = remember { StreamedFlag() }
    if (running) streamed.value = true
    // Off with Android's "Remove animations", as the desktop follows Windows' setting.
    val reveal = streamed.value && ValueAnimator.areAnimatorsEnabled()
    // Keeps showing the previous text while the next is parsed, instead of a blank frame.
    val markdown = rememberMarkdownState(text, retainState = true)
    val components = rememberRevealComponents()
    val body: @Composable () -> Unit = {
        CompositionLocalProvider(LocalTextReveal provides reveal) {
            Markdown(
                markdownState = markdown,
                // The renderer's default headings are display-sized, too large for a phone.
                typography = markdownTypography(
                    h1 = type.titleLarge,
                    h2 = type.titleMedium,
                    h3 = type.titleSmall,
                    h4 = type.titleSmall,
                    h5 = type.bodyLarge,
                    h6 = type.bodyLarge,
                ),
                components = components,
            )
        }
    }
    SelectionContainer { body() }
}

@Composable
private fun ErrorNote(error: RunError?, actions: ResultActions) {
    val message = when (error) {
        RunError.NoKey -> stringResource(R.string.error_no_key)
        RunError.NoModel -> stringResource(R.string.error_no_model)
        is RunError.Message -> stringResource(R.string.error_translation, error.text)
        null -> ""
    }
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.onRetry) { Text(stringResource(R.string.action_try_again)) }
        if (error !is RunError.Message) {
            TextButton(onClick = actions.onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
        }
    }
}

@Composable
private fun CopyButton(text: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_NOTE_MS)
            copied = false
        }
    }
    OutlinedButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
            copied = true
        },
    ) {
        Text(stringResource(if (copied) R.string.action_copied else R.string.action_copy))
    }
}

@Composable
private fun BackTranslationPanel(back: BackTranslationState, onStop: () -> Unit) {
    if (back.status == BackStatus.HIDDEN) return

    Spacer(Modifier.width(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.label_back_translation, languageName(back.languageCode)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (back.status == BackStatus.RUNNING) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
        }
    }
    if (back.text.isNotEmpty()) {
        MarkdownText(back.text, running = back.status == BackStatus.RUNNING)
    } else if (back.status == BackStatus.RUNNING) {
        PendingNote(stringResource(R.string.back_running), MaterialTheme.colorScheme.primary)
    }

    val note = when (back.status) {
        BackStatus.STOPPED -> stringResource(R.string.back_stopped)
        BackStatus.NO_KEY -> stringResource(R.string.back_no_key)
        BackStatus.SAME_LANGUAGE -> stringResource(R.string.back_same_language)
        BackStatus.FAILED -> stringResource(R.string.back_error, back.error.orEmpty())
        else -> null
    }
    note?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (back.status == BackStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun resultLabel(state: TranslationState): String {
    val target = state.request?.targetLang ?: TARGET_DEFAULT
    return when {
        state.mode == TranslationMode.DICTIONARY -> stringResource(R.string.label_dictionary)
        state.mode == TranslationMode.ZH_TO_EN -> stringResource(R.string.label_english_translation)
        state.mode == TranslationMode.EN_TO_ZH -> stringResource(R.string.label_chinese_translation)
        target != TARGET_DEFAULT ->
            stringResource(R.string.label_language_translation, languageName(target).uppercase(Locale.ROOT))
        else -> stringResource(R.string.label_auto_translation)
    }
}

/** Each language is named in its own language, as on the desktop. */
fun languageName(code: String): String =
    (SOURCE_LANGUAGES + TARGET_LANGUAGES).firstOrNull { it.code == code }?.promptLabel ?: code
