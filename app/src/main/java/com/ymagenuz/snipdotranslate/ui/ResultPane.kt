package com.ymagenuz.snipdotranslate.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.core.CjkFriendlyFlavour
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
    val onShare: (String) -> Unit,
)

/**
 * The translation and its back-translation; shared by the app, the popup and the overlay.
 * With [showActions] off, the host shows [ResultActionBar] instead, fixed under a sheet.
 */
@Composable
fun ResultPane(
    state: TranslationState,
    actions: ResultActions,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
            SectionCaption(resultLabel(state), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            if (state.status == RunStatus.RUNNING) {
                PillButton(stringResource(R.string.action_stop), actions.onStop, icon = AppIcons.Stop)
            }
        }

        if (state.text.isNotEmpty()) {
            MarkdownText(state.text, running = state.status == RunStatus.RUNNING, textSize = 18)
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

        BackTranslationPanel(state.back, actions.onStopBack)
        if (showActions) InlineActions(state, actions)
    }
}

/** The small capital label over a block of text: blue for the source, teal for the translation. */
@Composable
fun SectionCaption(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = color,
        modifier = modifier,
    )
}

/** Copy, Share and the back-translation switch under a result on the main page. */
@Composable
private fun InlineActions(state: TranslationState, actions: ResultActions) {
    val ready = state.status != RunStatus.RUNNING
    val (copied, copy) = rememberCopy(state.text)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Nothing to copy or share from a run that failed before any text came.
        if (state.text.isNotBlank()) {
            PillButton(
                text = stringResource(if (copied) R.string.action_copied else R.string.action_copy),
                onClick = copy,
                icon = if (copied) AppIcons.Check else AppIcons.Copy,
                enabled = ready,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            FilledTonalIconButton(onClick = { actions.onShare(state.text) }, enabled = ready) {
                Icon(AppIcons.Share, contentDescription = stringResource(R.string.action_share), modifier = Modifier.size(20.dp))
            }
        }
        // Takes what the buttons leave, so the switch after it keeps its whole label where there's room.
        Spacer(Modifier.weight(1f))
        if (state.mode != TranslationMode.DICTIONARY) {
            FilterChip(
                selected = actions.backTranslationOn,
                onClick = { actions.onToggleBack(!actions.backTranslationOn) },
                label = { Text(stringResource(R.string.back_translate), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(AppIcons.BackTranslate, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/**
 * The same actions for a sheet, where they stay in reach under the text however long it is.
 * [onOpenInApp] is left out where there's nothing to hand to the main page yet.
 */
@Composable
fun ResultActionBar(
    state: TranslationState,
    actions: ResultActions,
    onOpenInApp: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ready = state.text.isNotBlank() && state.status != RunStatus.RUNNING
    val (copied, copy) = rememberCopy(state.text)
    Column(modifier = modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Button(onClick = copy, enabled = ready, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(
                    if (copied) AppIcons.Check else AppIcons.Copy,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                )
                Text(stringResource(if (copied) R.string.action_copied else R.string.action_copy), maxLines = 1)
            }
            FilledTonalIconButton(
                onClick = { actions.onShare(state.text) },
                enabled = ready,
                modifier = Modifier.size(48.dp),
            ) { Icon(AppIcons.Share, contentDescription = stringResource(R.string.action_share)) }
            if (state.mode != TranslationMode.DICTIONARY) {
                FilledTonalIconToggleButton(
                    checked = actions.backTranslationOn,
                    onCheckedChange = actions.onToggleBack,
                    colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier.size(48.dp),
                ) { Icon(AppIcons.BackTranslate, contentDescription = stringResource(R.string.back_translate)) }
            }
            if (onOpenInApp != null) {
                FilledTonalIconButton(onClick = onOpenInApp, modifier = Modifier.size(48.dp)) {
                    Icon(AppIcons.OpenInApp, contentDescription = stringResource(R.string.action_open_in_app))
                }
            }
        }
    }
}

/** Copies [text] and says so for a moment: whether it was just copied, and the action that copies. */
@Composable
private fun rememberCopy(text: String): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_NOTE_MS)
            copied = false
        }
    }
    return copied to {
        copyToClipboard(context, "translation", text)
        copied = true
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
 * A finished result shown later, such as after rotating, appears at once. [textSize] is the body text's size in sp.
 */
@Composable
private fun MarkdownText(text: String, running: Boolean, textSize: Int) {
    val type = MaterialTheme.typography
    val streamed = remember { StreamedFlag() }
    if (running) streamed.value = true
    // Off with Android's "Remove animations", as the desktop follows Windows' setting.
    val reveal = streamed.value && ValueAnimator.areAnimatorsEnabled()
    // Keeps showing the previous text while the next is parsed, instead of a blank frame. The flavour shows bold beside
    // Chinese punctuation, as in **注意：**请勿关闭, which GitHub's own rules leave as asterisks.
    val markdown = rememberMarkdownState(text, retainState = true, flavour = CjkFriendlyFlavour)
    val components = rememberRevealComponents()
    val body: @Composable () -> Unit = {
        CompositionLocalProvider(LocalTextReveal provides reveal) {
            // The renderer takes its body text from the theme, so the size is set there.
            MaterialTheme(
                typography = type.copy(
                    bodyLarge = type.bodyLarge.copy(fontSize = textSize.sp, lineHeight = (textSize * 1.5f).sp),
                ),
            ) {
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
        PillButton(stringResource(R.string.action_try_again), actions.onRetry, icon = AppIcons.Retry)
        if (error !is RunError.Message) {
            PillButton(
                stringResource(R.string.action_open_settings),
                actions.onOpenSettings,
                icon = AppIcons.Key,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun BackTranslationPanel(back: BackTranslationState, onStop: () -> Unit) {
    if (back.status == BackStatus.HIDDEN) return

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
        SectionCaption(
            stringResource(R.string.label_back_translation, languageName(back.languageCode)),
            MaterialTheme.colorScheme.primary,
            Modifier.weight(1f),
        )
        if (back.status == BackStatus.RUNNING) {
            PillButton(stringResource(R.string.action_stop), onStop, icon = AppIcons.Stop)
        }
    }
    if (back.text.isNotEmpty()) {
        MarkdownText(back.text, running = back.status == BackStatus.RUNNING, textSize = 15)
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
