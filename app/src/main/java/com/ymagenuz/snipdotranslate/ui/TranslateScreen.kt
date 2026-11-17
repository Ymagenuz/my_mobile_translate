package com.ymagenuz.snipdotranslate.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.core.HistoryEntry
import com.ymagenuz.snipdotranslate.core.LanguageOption
import com.ymagenuz.snipdotranslate.core.SOURCE_AUTO
import com.ymagenuz.snipdotranslate.core.SOURCE_LANGUAGES
import com.ymagenuz.snipdotranslate.core.TARGET_DEFAULT
import com.ymagenuz.snipdotranslate.core.TARGET_LANGUAGES
import com.ymagenuz.snipdotranslate.core.TranslationMode
import com.ymagenuz.snipdotranslate.data.AppSettings
import com.ymagenuz.snipdotranslate.domain.RunStatus
import com.ymagenuz.snipdotranslate.entry.SelectOnScreenTileService
import kotlinx.coroutines.launch

private const val RECENT_ITEMS = 3

@Composable
fun TranslateScreen(
    viewModel: MainViewModel,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onApiSettings: () -> Unit,
    onSelectionSettings: () -> Unit,
    onInterpret: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(scroll)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(updateAvailable = viewModel.update != null, onHistory = onHistory, onSettings = onSettings)
        LanguageBar(settings, viewModel)
        InputCard(viewModel, running = state.status == RunStatus.RUNNING, mode = state.mode)

        if (state.status != RunStatus.IDLE) {
            AppCard {
                ResultPane(
                    state = state,
                    actions = ResultActions(
                        onStop = viewModel.session::stop,
                        onRetry = viewModel.session::retry,
                        onStopBack = viewModel.session::stopBackTranslation,
                        onOpenSettings = onApiSettings,
                        backTranslationOn = settings.backTranslation,
                        onToggleBack = viewModel::setBackTranslation,
                        onShare = { shareText(context, it) },
                    ),
                    modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(AppIcons.Camera, stringResource(R.string.action_camera), onTakePhoto, Modifier.weight(1f))
            QuickAction(AppIcons.Image, stringResource(R.string.action_photo), onPickPhoto, Modifier.weight(1f))
            QuickAction(AppIcons.Mic, stringResource(R.string.action_interpret), onInterpret, Modifier.weight(1f))
        }

        SetupCard(viewModel, settings, onApiSettings, onSelectionSettings)

        if (history.isNotEmpty()) {
            Recent(
                entries = history.take(RECENT_ITEMS),
                onOpen = { entry ->
                    viewModel.openHistoryEntry(entry)
                    scope.launch { scroll.animateScrollTo(0) }
                },
                onSeeAll = onHistory,
            )
        }
    }
}

@Composable
private fun Header(updateAvailable: Boolean, onHistory: () -> Unit, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 52.dp)) {
        Image(AppIcons.Brand, contentDescription = null)
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        IconButton(onClick = onHistory) {
            Icon(AppIcons.History, contentDescription = stringResource(R.string.title_history))
        }
        Box {
            val label = stringResource(if (updateAvailable) R.string.settings_update_available else R.string.title_settings)
            IconButton(onClick = onSettings) { Icon(AppIcons.Settings, contentDescription = label) }
            if (updateAvailable) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 9.dp, end = 9.dp)
                        .size(9.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

/** The source in blue and the target in teal, as everywhere else, with a swap between them. */
@Composable
private fun LanguageBar(settings: AppSettings, viewModel: MainViewModel) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            LanguagePicker(
                SOURCE_LANGUAGES,
                settings.sourceLanguage,
                MaterialTheme.colorScheme.primary,
                viewModel::setSourceLanguage,
                Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = viewModel::swapLanguages,
                enabled = viewModel.canSwapLanguages(settings),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    AppIcons.Swap,
                    contentDescription = stringResource(R.string.action_swap_languages),
                    modifier = Modifier.size(20.dp),
                )
            }
            LanguagePicker(
                TARGET_LANGUAGES,
                settings.targetLanguage,
                MaterialTheme.colorScheme.tertiary,
                viewModel::setTargetLanguage,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LanguagePicker(
    options: List<LanguageOption>,
    selected: String,
    color: Color,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(
                pickerLabel(selected),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(AppIcons.ChevronDown, contentDescription = null, tint = color, modifier = Modifier.padding(start = 2.dp).size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(pickerLabel(option.code)) },
                    trailingIcon = {
                        if (option.code == selected) {
                            Icon(AppIcons.Check, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                    },
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

/** The text box with everything that acts on it inside the same card. */
@Composable
private fun InputCard(viewModel: MainViewModel, running: Boolean, mode: String) {
    val context = LocalContext.current
    val hint = stringResource(R.string.input_label)
    AppCard {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp)) {
            BasicTextField(
                value = viewModel.input,
                onValueChange = { viewModel.input = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.125f,
                    lineHeight = MaterialTheme.typography.titleMedium.lineHeight * 1.1f,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                minLines = 3,
                maxLines = 12,
                decorationBox = { field ->
                    Box {
                        if (viewModel.input.isEmpty()) {
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.125f,
                                ),
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        field()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 12.dp)
                    .semantics { contentDescription = hint },
            )
            if (viewModel.input.isNotEmpty()) {
                IconButton(onClick = viewModel::clearInput) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = stringResource(R.string.action_clear),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        ) {
            FilledTonalIconButton(onClick = { clipboardText(context)?.let(viewModel::paste) }) {
                Icon(AppIcons.Paste, contentDescription = stringResource(R.string.action_paste), modifier = Modifier.size(20.dp))
            }
            FilterChip(
                selected = viewModel.forceDictionary,
                onClick = { viewModel.forceDictionary = !viewModel.forceDictionary },
                label = { Text(stringResource(R.string.mode_dictionary), maxLines = 1) },
                leadingIcon = { Icon(AppIcons.Book, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = viewModel::translate,
                enabled = viewModel.input.isNotBlank() && !running,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 14.dp),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            !running -> R.string.action_translate
                            mode == TranslationMode.DICTIONARY -> R.string.action_looking_up
                            else -> R.string.action_translating
                        },
                    ),
                    maxLines = 1,
                )
                if (!running) {
                    Icon(AppIcons.ArrowRight, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    AppCard(modifier = modifier.height(84.dp), onClick = onClick) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The two things a new user has to do before the app is any use: connect a translation
 * service, and add the tile that starts a selection on any screen. Gone once both are done
 * or it's been closed.
 */
@Composable
private fun SetupCard(
    viewModel: MainViewModel,
    settings: AppSettings,
    onApiSettings: () -> Unit,
    onSelectionSettings: () -> Unit,
) {
    // Not shown until the saved key has been looked up, so it doesn't flash by.
    val service = viewModel.service ?: return
    val done = listOf(service.ready, settings.selectTileAdded).count { it }
    if (settings.setupDismissed || done == 2) return
    val context = LocalContext.current
    var tileNote by remember { mutableStateOf("") }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Text(
                stringResource(R.string.setup_title, done, 2),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::dismissSetup) {
                Icon(
                    AppIcons.Close,
                    contentDescription = stringResource(R.string.setup_hide),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (service.ready) {
            DoneRow(stringResource(R.string.setup_service_done), "${service.providerName} · ${service.model}")
        } else {
            SettingRow(
                icon = AppIcons.Key,
                title = stringResource(R.string.setup_service_todo),
                subtitle = stringResource(R.string.setup_service_todo_hint),
                trailing = { SetupButton(stringResource(R.string.setup_service_action), onApiSettings) },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
        if (settings.selectTileAdded) {
            DoneRow(stringResource(R.string.setup_tile_done), stringResource(R.string.setup_tile_done_hint))
        } else {
            val tileName = stringResource(R.string.tile_select_on_screen)
            val notAdded = stringResource(R.string.settings_tile_not_added, tileName)
            SettingRow(
                icon = AppIcons.ScreenSelect,
                title = stringResource(R.string.setup_tile_todo),
                subtitle = stringResource(R.string.setup_tile_todo_hint),
                trailing = {
                    SetupButton(stringResource(R.string.action_add)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            tileNote = ""
                            requestTile(context, SelectOnScreenTileService::class.java, tileName, R.drawable.ic_tile_select) {
                                if (tileIsAdded(it)) viewModel.noteTile(selectOnScreen = true, added = true) else tileNote = notAdded
                            }
                        } else {
                            // Older Android has no way to ask; the page says how to add it by hand.
                            onSelectionSettings()
                        }
                    }
                },
            )
        }
        if (tileNote.isNotEmpty()) {
            Text(
                tileNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun DoneRow(title: String, subtitle: String) {
    SettingRow(
        icon = AppIcons.Check,
        title = title,
        subtitle = subtitle,
        badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
        badgeContent = MaterialTheme.colorScheme.tertiary,
    )
}

@Composable
private fun SetupButton(text: String, onClick: () -> Unit) {
    PillButton(
        text,
        onClick,
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/** The latest translations, one tap from being shown again. */
@Composable
private fun Recent(entries: List<HistoryEntry>, onOpen: (HistoryEntry) -> Unit, onSeeAll: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_recent),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.home_see_all)) }
        }
        AppCard(modifier = Modifier.clip(MaterialTheme.shapes.large)) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(entry) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(entry.source, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        plainPreview(entry.result),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}
