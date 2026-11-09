package com.ymagenuz.snipdotranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.core.LanguageOption
import com.ymagenuz.snipdotranslate.core.SOURCE_AUTO
import com.ymagenuz.snipdotranslate.core.SOURCE_LANGUAGES
import com.ymagenuz.snipdotranslate.core.TARGET_DEFAULT
import com.ymagenuz.snipdotranslate.core.TARGET_LANGUAGES
import com.ymagenuz.snipdotranslate.core.TranslationMode
import com.ymagenuz.snipdotranslate.domain.RunStatus

@Composable
fun TranslateScreen(
    viewModel: MainViewModel,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val running = state.status == RunStatus.RUNNING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onHistory) { Text(stringResource(R.string.title_history)) }
            TextButton(onClick = onSettings) {
                Text(stringResource(if (viewModel.update != null) R.string.settings_update_badge else R.string.title_settings))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguagePicker(SOURCE_LANGUAGES, settings.sourceLanguage, viewModel::setSourceLanguage)
            Text(stringResource(R.string.language_to), style = MaterialTheme.typography.bodySmall)
            LanguagePicker(TARGET_LANGUAGES, settings.targetLanguage, viewModel::setTargetLanguage)
        }

        OutlinedTextField(
            value = viewModel.input,
            onValueChange = { viewModel.input = it },
            label = { Text(stringResource(R.string.input_label)) },
            minLines = 4,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::translate, enabled = viewModel.input.isNotBlank() && !running) {
                Text(
                    stringResource(
                        when {
                            !running -> R.string.action_translate
                            state.mode == TranslationMode.DICTIONARY -> R.string.action_looking_up
                            else -> R.string.action_translating
                        },
                    ),
                )
            }
            FilterChip(
                selected = viewModel.forceDictionary,
                onClick = { viewModel.forceDictionary = !viewModel.forceDictionary },
                label = { Text(stringResource(R.string.mode_dictionary)) },
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::clearInput) { Text(stringResource(R.string.action_clear)) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTakePhoto) { Text(stringResource(R.string.action_camera)) }
            OutlinedButton(onClick = onPickPhoto) { Text(stringResource(R.string.action_photo)) }
        }

        if (state.status != RunStatus.IDLE) {
            ResultPane(
                state = state,
                actions = ResultActions(
                    onStop = viewModel.session::stop,
                    onRetry = viewModel.session::retry,
                    onStopBack = viewModel.session::stopBackTranslation,
                    onOpenSettings = onSettings,
                    backTranslationOn = settings.backTranslation,
                    onToggleBack = viewModel::setBackTranslation,
                ),
            )
        }
    }
}

@Composable
private fun LanguagePicker(options: List<LanguageOption>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(pickerLabel(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(pickerLabel(option.code)) },
                    onClick = {
                        expanded = false
                        onSelect(option.code)
                    },
                )
            }
        }
    }
}

@Composable
private fun pickerLabel(code: String): String = when (code) {
    SOURCE_AUTO -> stringResource(R.string.language_auto)
    TARGET_DEFAULT -> stringResource(R.string.language_default)
    else -> languageName(code)
}
