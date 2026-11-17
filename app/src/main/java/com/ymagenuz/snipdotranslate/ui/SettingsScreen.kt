package com.ymagenuz.snipdotranslate.ui

import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ymagenuz.snipdotranslate.BuildConfig
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.core.API_PROVIDERS
import com.ymagenuz.snipdotranslate.core.apiProvider
import com.ymagenuz.snipdotranslate.data.AppSettings
import com.ymagenuz.snipdotranslate.data.UI_LANGUAGE_CHINESE
import com.ymagenuz.snipdotranslate.data.UI_LANGUAGE_ENGLISH
import com.ymagenuz.snipdotranslate.entry.SelectOnScreenTileService
import com.ymagenuz.snipdotranslate.entry.TranslateClipboardTileService
import com.ymagenuz.snipdotranslate.select.overlay.SelectionAccessibilityService
import com.ymagenuz.snipdotranslate.update.UpdateInstaller
import java.io.IOException
import kotlinx.coroutines.launch

/** Each group as a few rows that say how things stand; the details are a page further in. */
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit, onApi: () -> Unit, onSelection: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTopBar(stringResource(R.string.title_settings), onBack, stringResource(R.string.action_back))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (viewModel.update != null) UpdateCard(viewModel)

            SectionLabel(stringResource(R.string.settings_service))
            ServiceCard(viewModel.service, onApi)

            SectionLabel(stringResource(R.string.settings_selection))
            SelectionCard(viewModel, settings, onSelection)

            SectionLabel(stringResource(R.string.settings_translation))
            AppCard {
                SettingRow(
                    icon = AppIcons.BackTranslate,
                    title = stringResource(R.string.back_translate),
                    subtitle = stringResource(R.string.settings_back_translation),
                    trailing = { Switch(checked = settings.backTranslation, onCheckedChange = viewModel::setBackTranslation) },
                )
            }

            SectionLabel(stringResource(R.string.settings_app))
            AppCard {
                SettingRow(
                    icon = AppIcons.Globe,
                    title = stringResource(R.string.settings_language),
                    badgeContainer = MaterialTheme.colorScheme.surfaceContainer,
                    badgeContent = MaterialTheme.colorScheme.onSurfaceVariant,
                    trailing = { UiLanguageSwitch(settings.uiLanguage, viewModel::setUiLanguage) },
                )
                RowDivider()
                VersionRow(viewModel)
            }
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 62.dp))
}

@Composable
private fun Chevron() {
    Icon(
        AppIcons.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(end = 4.dp),
    )
}

@Composable
private fun ServiceCard(service: ServiceStatus?, onApi: () -> Unit) {
    AppCard(onClick = onApi) {
        val problem = service != null && !service.ready
        SettingRow(
            icon = AppIcons.Key,
            title = service?.providerName ?: stringResource(R.string.settings_service),
            subtitle = when {
                service == null -> null
                !service.hasKey -> stringResource(R.string.settings_service_no_key)
                service.model.isEmpty() -> stringResource(R.string.settings_service_no_model)
                else -> service.model + " · " + stringResource(R.string.settings_service_key_saved)
            },
            subtitleColor = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            trailing = { Chevron() },
        )
    }
}

@Composable
private fun SelectionCard(viewModel: MainViewModel, settings: AppSettings, onSelection: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var note by remember { mutableStateOf("") }
    var serviceOn by remember { mutableStateOf(false) }
    // Checked again on return from Accessibility settings.
    LifecycleResumeEffect(Unit) {
        serviceOn = SelectionAccessibilityService.isRunning
        onPauseOrDispose { }
    }

    val add: (Boolean) -> Unit = { selectOnScreen ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val name = resources.getString(if (selectOnScreen) R.string.tile_select_on_screen else R.string.tile_translate_clipboard)
            note = ""
            requestTile(
                context,
                if (selectOnScreen) SelectOnScreenTileService::class.java else TranslateClipboardTileService::class.java,
                name,
                if (selectOnScreen) R.drawable.ic_tile_select else R.drawable.ic_tile_translate,
            ) { result ->
                if (tileIsAdded(result)) viewModel.noteTile(selectOnScreen, added = true)
                // Android often shows nothing itself, so say what happened and where the tile went.
                note = resources.getString(
                    when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.settings_tile_added
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.settings_tile_already_added
                        // Declined, or this phone doesn't let apps ask.
                        else -> R.string.settings_tile_not_added
                    },
                    name,
                )
            }
        }
    }

    AppCard {
        TileRow(AppIcons.ScreenSelect, stringResource(R.string.settings_tile_select), settings.selectTileAdded) { add(true) }
        RowDivider()
        TileRow(AppIcons.Paste, stringResource(R.string.settings_tile_clipboard), settings.clipboardTileAdded) { add(false) }
        if (note.isNotEmpty()) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 62.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        RowDivider()
        // The accessibility service needs Android 12; before that the page only says how selection works.
        val hasService = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        SettingRow(
            icon = AppIcons.Capture,
            title = stringResource(if (hasService) R.string.settings_capture_direct else R.string.settings_selection_how_title),
            subtitle = when {
                !hasService -> null
                serviceOn -> stringResource(R.string.settings_accessibility_on)
                else -> stringResource(R.string.settings_accessibility_off)
            },
            badgeContainer = MaterialTheme.colorScheme.surfaceContainer,
            badgeContent = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onSelection,
            trailing = { Chevron() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FinePrint(stringResource(R.string.settings_selection_privacy_short), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

/** A note in small type after an information mark. */
@Composable
private fun FinePrint(text: String, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Icon(
            AppIcons.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TileRow(icon: ImageVector, title: String, added: Boolean, onAdd: () -> Unit) {
    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    SettingRow(
        icon = icon,
        title = title,
        subtitle = stringResource(
            when {
                added -> R.string.settings_tile_in_qs
                canAsk -> R.string.settings_tile_not_yet
                else -> R.string.settings_tile_manual
            },
        ),
        trailing = {
            when {
                added -> Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                canAsk -> PillButton(
                    stringResource(R.string.action_add),
                    onAdd,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    )
}

@Composable
private fun UiLanguageSwitch(selected: String, onSelect: (String) -> Unit) {
    val activity = LocalActivity.current
    Row(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, CircleShape).padding(2.dp)) {
        // Each language is named in its own language.
        listOf(UI_LANGUAGE_ENGLISH to "English", UI_LANGUAGE_CHINESE to "简体中文").forEach { (code, name) ->
            val chosen = selected == code
            Surface(
                selected = chosen,
                onClick = {
                    if (!chosen) {
                        onSelect(code)
                        // From Android 13 the system restarts the screen itself.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) activity?.recreate()
                    }
                },
                shape = CircleShape,
                color = if (chosen) MaterialTheme.colorScheme.surfaceBright else Color.Transparent,
                shadowElevation = if (chosen) 1.dp else 0.dp,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun VersionRow(viewModel: MainViewModel) {
    val status = viewModel.updateStatus
    val failed = status is UpdateStatus.Failed
    SettingRow(
        icon = AppIcons.Info,
        title = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
        subtitle = when {
            status is UpdateStatus.Failed -> stringResource(R.string.about_check_failed, status.message)
            viewModel.update != null -> stringResource(R.string.about_available, viewModel.update?.version.orEmpty())
            status == UpdateStatus.Finished -> stringResource(R.string.about_up_to_date)
            else -> null
        },
        subtitleColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        badgeContainer = MaterialTheme.colorScheme.surfaceContainer,
        badgeContent = MaterialTheme.colorScheme.onSurfaceVariant,
        trailing = {
            TextButton(enabled = status != UpdateStatus.Checking, onClick = viewModel::checkForUpdates) {
                Text(stringResource(if (status == UpdateStatus.Checking) R.string.about_checking else R.string.about_check))
            }
        },
    )
}

/** A new version: what it brings, and the way to get it without leaving the app. */
@Composable
private fun UpdateCard(viewModel: MainViewModel) {
    val context = LocalContext.current
    val release = viewModel.update ?: return
    AppCard(modifier = Modifier.padding(top = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(AppIcons.Download)
                Text(
                    stringResource(R.string.about_available, release.version),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (release.notes.isNotEmpty()) {
                Text(release.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UpdateActions(viewModel)
            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)))
                    } catch (_: ActivityNotFoundException) {
                        // No browser to open it with; the version shown above still says what's new.
                    }
                },
            ) {
                Icon(AppIcons.OpenInApp, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                Text(stringResource(R.string.about_download))
            }
        }
    }
}

/** Downloads the update inside the app, then hands it to Android's installer. */
@Composable
private fun UpdateActions(viewModel: MainViewModel) {
    val context = LocalContext.current
    var askedPermission by remember { mutableStateOf(false) }
    when (val state = viewModel.download) {
        is DownloadState.Downloading -> {
            Text(stringResource(R.string.about_downloading, state.percent), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = viewModel::cancelDownload) { Text(stringResource(R.string.action_cancel)) }
        }
        is DownloadState.Ready -> {
            Button(
                onClick = {
                    askedPermission = !UpdateInstaller.canInstall(context)
                    val intent = if (askedPermission) {
                        UpdateInstaller.permissionIntent(context)
                    } else {
                        UpdateInstaller.installIntent(context, state.file)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        // Some phones lack the page; App info leads to the same permission.
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                        )
                    }
                },
            ) { Text(stringResource(R.string.about_install)) }
            Text(
                stringResource(if (askedPermission) R.string.about_install_permission else R.string.about_install_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            Button(onClick = viewModel::downloadUpdate) {
                Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                Text(stringResource(R.string.about_update_now))
            }
            val problem = when (state) {
                is DownloadState.Failed -> stringResource(R.string.about_download_failed, state.message)
                DownloadState.Rejected -> stringResource(R.string.about_download_rejected)
                else -> null
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** The provider, its address, key and model, with Save always in reach under them. */
@Composable
fun ApiSettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val drafts = remember { mutableStateMapOf<String, ApiDraft>() }
    var selected by remember { mutableStateOf(settings.apiProvider) }
    var status by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var showKey by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        drafts.putAll(viewModel.loadDrafts())
        selected = viewModel.settings.value.apiProvider
    }

    val save: () -> Unit = {
        // The keyboard would cover the confirmation.
        focus.clearFocus()
        keyboard?.hide()
        status = ""
        scope.launch {
            val result = viewModel.saveApi(selected, drafts.toMap())
            val message = when (result) {
                SaveResult.Saved -> resources.getString(R.string.settings_saved)
                is SaveResult.InvalidConfig -> resources.getString(R.string.settings_invalid_config, result.providerName)
                is SaveResult.InvalidKey -> resources.getString(R.string.settings_invalid_key, result.providerName)
                SaveResult.KeyNotSaved -> resources.getString(R.string.settings_key_not_saved)
            }
            val saved = result == SaveResult.Saved
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                message = message,
                withDismissAction = !saved,
                duration = if (saved) SnackbarDuration.Short else SnackbarDuration.Long,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            ScreenTopBar(stringResource(R.string.settings_service), onBack, stringResource(R.string.action_back))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FinePrint(stringResource(R.string.settings_api_hint), Modifier.padding(horizontal = 4.dp))
                ProviderPicker(selected) {
                    selected = it
                    status = ""
                }

                val draft = drafts[selected]
                if (draft != null) {
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
                        shape = MaterialTheme.shapes.medium,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.apiKey,
                        onValueChange = { value -> change { it.copy(apiKey = value) } },
                        label = { Text(stringResource(R.string.settings_api_key)) },
                        leadingIcon = { Icon(AppIcons.Key, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) AppIcons.EyeOff else AppIcons.Eye,
                                    contentDescription = stringResource(
                                        if (showKey) R.string.settings_hide_key else R.string.settings_show_key,
                                    ),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { value -> change { it.copy(model = value) } },
                        label = { Text(stringResource(R.string.settings_model)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PillButton(
                        text = stringResource(if (fetching) R.string.settings_fetching else R.string.settings_fetch_models),
                        icon = AppIcons.Download,
                        enabled = !fetching,
                        onClick = {
                            if (draft.apiKey.isBlank()) {
                                status = resources.getString(R.string.settings_need_key_for_models)
                            } else {
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
                            }
                        },
                    )
                    if (status.isNotEmpty()) {
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }

                    AppCard {
                        SwitchRow(AppIcons.Image, stringResource(R.string.settings_vision), draft.supportsVision) { value ->
                            change { it.copy(supportsVision = value) }
                        }
                        RowDivider()
                        SwitchRow(AppIcons.Mic, stringResource(R.string.settings_audio), draft.supportsAudio) { value ->
                            change { it.copy(supportsAudio = value) }
                        }
                        RowDivider()
                        SwitchRow(AppIcons.Sparkles, stringResource(R.string.settings_disable_thinking), draft.disableThinking) { value ->
                            change { it.copy(disableThinking = value) }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Button(
                onClick = save,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.action_save)) }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                // Above Save, which it would otherwise cover.
                .padding(start = 16.dp, end = 16.dp, bottom = 80.dp),
        )
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

/** How a selection is started, what is captured, and the optional service that skips Android's question. */
@Composable
fun SelectionSettingsScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTopBar(stringResource(R.string.settings_selection), onBack, stringResource(R.string.action_back))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_selection_how_title))
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_selection_how), style = MaterialTheme.typography.bodyMedium)
                    FinePrint(stringResource(R.string.settings_selection_privacy))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SectionLabel(stringResource(R.string.settings_selection_accessibility))
                AccessibilityCard()
            }
        }
    }
}

/** The optional accessibility service, which captures without asking and adds a shortcut. */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun AccessibilityCard() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    // Checked again on return from Accessibility settings.
    LifecycleResumeEffect(Unit) {
        running = SelectionAccessibilityService.isRunning
        onPauseOrDispose { }
    }

    AppCard {
        SettingRow(
            icon = if (running) AppIcons.Check else AppIcons.Capture,
            title = stringResource(R.string.settings_capture_direct),
            subtitle = stringResource(if (running) R.string.settings_accessibility_on else R.string.settings_accessibility_off),
            badgeContainer = if (running) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            badgeContent = if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Text(
                stringResource(
                    if (running) R.string.settings_selection_accessibility_on else R.string.settings_selection_accessibility_off,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!running) {
                Text(
                    stringResource(R.string.settings_selection_accessibility_restricted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FinePrint(stringResource(R.string.settings_selection_accessibility_privacy))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    stringResource(R.string.settings_open_accessibility),
                    onClick = { openSettingsPage(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (!running) {
                    PillButton(
                        stringResource(R.string.settings_open_app_info),
                        onClick = {
                            openSettingsPage(
                                context,
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun openSettingsPage(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A phone without that settings page; there's nothing else to open.
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(
        icon = icon,
        title = label,
        badgeContainer = MaterialTheme.colorScheme.surfaceContainer,
        badgeContent = MaterialTheme.colorScheme.onSurfaceVariant,
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun ProviderPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AppCard(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                icon = AppIcons.Globe,
                title = apiProvider(selected).displayName,
                subtitle = stringResource(R.string.settings_provider),
                trailing = {
                    Icon(
                        AppIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            API_PROVIDERS.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    trailingIcon = {
                        if (provider.providerId == selected) {
                            Icon(
                                AppIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
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
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(models.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }) { model ->
                        Text(
                            model,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(model) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
    )
}
