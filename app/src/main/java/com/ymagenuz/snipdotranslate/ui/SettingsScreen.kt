package com.ymagenuz.snipdotranslate.ui

import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ymagenuz.snipdotranslate.BuildConfig
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.core.API_PROVIDERS
import com.ymagenuz.snipdotranslate.core.apiProvider
import com.ymagenuz.snipdotranslate.data.UI_LANGUAGE_CHINESE
import com.ymagenuz.snipdotranslate.data.UI_LANGUAGE_ENGLISH
import com.ymagenuz.snipdotranslate.entry.SelectOnScreenTileService
import com.ymagenuz.snipdotranslate.entry.TranslateClipboardTileService
import com.ymagenuz.snipdotranslate.select.overlay.SelectionAccessibilityService
import java.io.IOException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val drafts = remember { mutableStateMapOf<String, ApiDraft>() }
    var selected by remember { mutableStateOf(settings.apiProvider) }
    var status by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(Unit) {
        drafts.putAll(viewModel.loadDrafts())
        selected = viewModel.settings.value.apiProvider
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(stringResource(R.string.title_settings), style = MaterialTheme.typography.titleLarge)
        }

        LanguagePicker(settings.uiLanguage, viewModel::setUiLanguage)
        AboutSection(viewModel)
        ScreenSelectionSetup()

        Text(stringResource(R.string.settings_translation), style = MaterialTheme.typography.titleMedium)
        SwitchRow(stringResource(R.string.settings_back_translation), settings.backTranslation, viewModel::setBackTranslation)

        Text(stringResource(R.string.settings_api), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_api_hint), style = MaterialTheme.typography.bodySmall)
        ProviderPicker(selected) {
            selected = it
            status = ""
        }

        val draft = drafts[selected] ?: return@Column
        val provider = apiProvider(selected)
        fun change(update: (ApiDraft) -> ApiDraft) {
            drafts[selected] = update(drafts.getValue(selected))
            status = ""
        }

        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = { value -> change { it.copy(baseUrl = value) } },
            label = { Text(stringResource(R.string.settings_base_url)) },
            readOnly = !provider.baseUrlEditable,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { value -> change { it.copy(apiKey = value) } },
            label = { Text(stringResource(R.string.settings_api_key)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.model,
            onValueChange = { value -> change { it.copy(model = value) } },
            label = { Text(stringResource(R.string.settings_model)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = !fetching,
            onClick = {
                if (draft.apiKey.isBlank()) {
                    status = resources.getString(R.string.settings_need_key_for_models)
                    return@OutlinedButton
                }
                fetching = true
                scope.launch {
                    try {
                        models = viewModel.fetchModels(selected, draft)
                    } catch (failure: IOException) {
                        status = failure.message.orEmpty()
                    } catch (failure: IllegalArgumentException) {
                        status = failure.message.orEmpty()
                    }
                    fetching = false
                }
            },
        ) {
            Text(stringResource(if (fetching) R.string.settings_fetching else R.string.settings_fetch_models))
        }
        SwitchRow(stringResource(R.string.settings_vision), draft.supportsVision) { value ->
            change { it.copy(supportsVision = value) }
        }
        SwitchRow(stringResource(R.string.settings_disable_thinking), draft.disableThinking) { value ->
            change { it.copy(disableThinking = value) }
        }

        Button(
            onClick = {
                scope.launch {
                    status = when (val result = viewModel.saveApi(selected, drafts.toMap())) {
                        SaveResult.Saved -> resources.getString(R.string.settings_saved)
                        is SaveResult.InvalidConfig ->
                            resources.getString(R.string.settings_invalid_config, result.providerName)
                        is SaveResult.InvalidKey ->
                            resources.getString(R.string.settings_invalid_key, result.providerName)
                        SaveResult.KeyNotSaved -> resources.getString(R.string.settings_key_not_saved)
                    }
                }
            },
        ) { Text(stringResource(R.string.action_save)) }

        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
    }

    models?.let { list ->
        ModelDialog(
            models = list,
            onDismiss = { models = null },
            onSelect = { model ->
                drafts[selected] = drafts.getValue(selected).copy(model = model)
                models = null
            },
        )
    }
}

@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    val activity = LocalActivity.current
    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Each language is named in its own language.
        listOf(UI_LANGUAGE_ENGLISH to "English", UI_LANGUAGE_CHINESE to "简体中文").forEach { (code, name) ->
            FilterChip(
                selected = selected == code,
                label = { Text(name) },
                onClick = {
                    if (selected != code) {
                        onSelect(code)
                        // From Android 13 the system restarts the screen itself.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) activity?.recreate()
                    }
                },
            )
        }
    }
}

@Composable
private fun AboutSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val release = viewModel.update
    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
    if (release != null) {
        Text(stringResource(R.string.about_available, release.version), color = MaterialTheme.colorScheme.primary)
        if (release.notes.isNotEmpty()) Text(release.notes, style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)))
                } catch (_: ActivityNotFoundException) {
                    // No browser to open it with; the version shown above still says what's new.
                }
            },
        ) { Text(stringResource(R.string.about_download)) }
    }
    val status = viewModel.updateStatus
    OutlinedButton(enabled = status != UpdateStatus.Checking, onClick = viewModel::checkForUpdates) {
        Text(stringResource(if (status == UpdateStatus.Checking) R.string.about_checking else R.string.about_check))
    }
    when {
        status is UpdateStatus.Failed ->
            Text(stringResource(R.string.about_check_failed, status.message), color = MaterialTheme.colorScheme.error)
        status == UpdateStatus.Finished && release == null -> Text(stringResource(R.string.about_up_to_date))
    }
}

@Composable
private fun ScreenSelectionSetup() {
    val context = LocalContext.current
    Text(stringResource(R.string.settings_selection), style = MaterialTheme.typography.titleMedium)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        Text(stringResource(R.string.settings_selection_unsupported), style = MaterialTheme.typography.bodySmall)
        return
    }

    var running by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        running = SelectionAccessibilityService.instance != null
        onPauseOrDispose { }
    }

    Text(stringResource(if (running) R.string.settings_selection_on else R.string.settings_selection_off))
    if (!running) {
        Text(stringResource(R.string.settings_selection_steps), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
            Text(stringResource(R.string.settings_open_accessibility))
        }
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                )
            },
        ) { Text(stringResource(R.string.settings_open_app_info)) }
    }
    Text(stringResource(R.string.settings_selection_privacy), style = MaterialTheme.typography.bodySmall)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        OutlinedButton(
            onClick = {
                requestTile(context, SelectOnScreenTileService::class.java, R.string.tile_select_on_screen, R.drawable.ic_tile_select)
            },
        ) { Text(stringResource(R.string.settings_add_select_tile)) }
        OutlinedButton(
            onClick = {
                requestTile(context, TranslateClipboardTileService::class.java, R.string.tile_translate_clipboard, R.drawable.ic_tile_translate)
            },
        ) { Text(stringResource(R.string.settings_add_clipboard_tile)) }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestTile(context: Context, service: Class<*>, label: Int, icon: Int) {
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, service),
        context.getString(label),
        Icon.createWithResource(context, icon),
        context.mainExecutor,
    ) { }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.padding(4.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProviderPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.settings_provider) + ": " + apiProvider(selected).displayName)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            API_PROVIDERS.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    onClick = {
                        expanded = false
                        onSelect(provider.providerId)
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelDialog(models: List<String>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.settings_model)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.settings_search_models)) },
                    singleLine = true,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(models.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }) { model ->
                        Text(
                            model,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(model) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
    )
}
