package com.ymagenuz.snipdotranslate.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ymagenuz.snipdotranslate.R
import com.ymagenuz.snipdotranslate.core.INTERPRET_LANGUAGES
import com.ymagenuz.snipdotranslate.core.MAX_SPEAKERS
import com.ymagenuz.snipdotranslate.core.MIN_SPEAKERS
import com.ymagenuz.snipdotranslate.core.SpeakerSetup
import com.ymagenuz.snipdotranslate.domain.InterpretNotice
import com.ymagenuz.snipdotranslate.domain.InterpretState
import com.ymagenuz.snipdotranslate.domain.InterpretationSession
import com.ymagenuz.snipdotranslate.domain.ListenPhase
import com.ymagenuz.snipdotranslate.domain.Listening
import com.ymagenuz.snipdotranslate.domain.Reading
import com.ymagenuz.snipdotranslate.domain.Rendition
import com.ymagenuz.snipdotranslate.domain.RenditionStatus
import com.ymagenuz.snipdotranslate.domain.RunError
import com.ymagenuz.snipdotranslate.domain.Utterance
import kotlinx.coroutines.flow.StateFlow

private val TALK_BUTTON_HEIGHT = 84.dp
private val ADD_BUTTON_WIDTH = 56.dp
// Three speakers and + fit side by side on a phone about 410 dp wide; more scroll.
private val MIN_TALK_BUTTON_WIDTH = 96.dp
private val TALK_BUTTON_SHAPE = RoundedCornerShape(24.dp)
private val BUBBLE_MAX_WIDTH = 320.dp
private val WAVE_HEIGHT = 22.dp

// One colour per speaker letter, readable with white text on top. The first two are the
// app's own blue and teal.
private val SPEAKER_COLORS = listOf(
    Color(0xFF2A64E6),
    Color(0xFF00796B),
    Color(0xFFE8710A),
    Color(0xFF8E44AD),
    Color(0xFFC2185B),
    Color(0xFF558B2F),
)

private fun speakerColor(letter: Char): Color = SPEAKER_COLORS[(letter - 'A').coerceIn(0, SPEAKER_COLORS.lastIndex)]

/** The speaker's colour for text and marks on the page, lighter or darker so it stays readable there. */
@Composable
private fun speakerAccent(letter: Char): Color {
    val color = speakerColor(letter)
    return if (isSystemInDarkTheme()) lerp(color, Color.White, 0.4f) else lerp(color, Color.Black, 0.18f)
}

@Composable
private fun speakerName(letter: Char): String = stringResource(R.string.interpret_speaker, letter.toString())

@Composable
fun InterpretScreen(viewModel: InterpretViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session = viewModel.session
    val context = LocalContext.current

    var waitingForMicrophone by remember { mutableStateOf<Char?>(null) }
    val askForMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val letter = waitingForMicrophone
        waitingForMicrophone = null
        if (!granted) {
            session.showNotice(InterpretNotice.NeedMicrophone)
        } else if (letter != null) {
            // The press that asked is long over, so start as a tap would.
            session.toggle(letter)
        }
    }
    val microphoneAllowed = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val askFor = { letter: Char ->
        waitingForMicrophone = letter
        askForMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }
    val talk = TalkActions(
        session = session,
        press = { letter ->
            if (microphoneAllowed()) {
                session.press(letter)
                true
            } else {
                askFor(letter)
                false
            }
        },
        toggle = { letter -> if (microphoneAllowed()) session.toggle(letter) else askFor(letter) },
    )

    ActiveWhileShown(session)
    KeepScreenOn()

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTopBar(stringResource(R.string.title_interpret), onBack, stringResource(R.string.action_back)) {
            FilledTonalIconToggleButton(
                checked = state.readAloud,
                onCheckedChange = viewModel::setReadAloud,
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = Color.Transparent,
                    checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Icon(
                    if (state.readAloud) AppIcons.Speaker else AppIcons.SpeakerOff,
                    contentDescription = stringResource(R.string.interpret_read_aloud),
                )
            }
            if (state.utterances.isNotEmpty()) {
                IconButton(onClick = session::clear) {
                    Icon(AppIcons.Trash, contentDescription = stringResource(R.string.action_clear))
                }
            }
        }

        state.notice?.let { notice ->
            NoticeBanner(notice, onDismiss = session::dismissNotice, onOpenSettings = onOpenSettings)
        }

        Conversation(
            state = state,
            onReplay = session::replay,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        SpeakerBar(
            state = state,
            level = viewModel.level,
            talk = talk,
            onLanguage = viewModel::setLanguage,
            onRemove = viewModel::removeSpeaker,
            onAdd = viewModel::addSpeaker,
        )
    }
}

/** What a speaker's button does; [press] returns false when it had to ask for the microphone. */
private class TalkActions(
    val session: InterpretationSession,
    val press: (Char) -> Boolean,
    val toggle: (Char) -> Unit,
)

/** Listens and reads aloud only while the page is on screen. */
@Composable
private fun ActiveWhileShown(session: InterpretationSession) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.setActive(true)
                Lifecycle.Event.ON_STOP -> session.setActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.setActive(false)
        }
    }
}

/** A conversation shouldn't stop because the screen timed out. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun NoticeBanner(notice: InterpretNotice, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val message = when (notice) {
        InterpretNotice.NoRecognizer -> stringResource(R.string.interpret_no_recognizer)
        InterpretNotice.NeedMicrophone -> stringResource(R.string.interpret_need_microphone)
        InterpretNotice.MicrophoneBusy -> stringResource(R.string.interpret_microphone_busy)
        is InterpretNotice.EngineNeedsMicrophone ->
            stringResource(R.string.interpret_engine_microphone, notice.engine.label)
        is InterpretNotice.LanguageUnsupported ->
            stringResource(R.string.interpret_language_unsupported, languageName(notice.language))
        is InterpretNotice.CloudNotReady ->
            stringResource(R.string.interpret_cloud_not_ready, languageName(notice.language))
        is InterpretNotice.CloudFailed -> stringResource(R.string.interpret_cloud_failed, notice.message)
        InterpretNotice.Network -> stringResource(R.string.interpret_network)
        InterpretNotice.RecognitionFailed -> stringResource(R.string.interpret_recognition_failed)
        InterpretNotice.IdleStopped -> stringResource(R.string.interpret_idle_stopped)
        is InterpretNotice.NoVoice -> stringResource(R.string.interpret_no_voice, languageName(notice.language))
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = 12.dp)) {
                Icon(AppIcons.Info, contentDescription = null, modifier = Modifier.padding(top = 12.dp).size(18.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp, top = 12.dp, bottom = 8.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_close), modifier = Modifier.size(18.dp))
                }
            }
            // Microphone problems are fixed in App info: SnipDo's own, or the recognition app's.
            val appInfoFor = when (notice) {
                InterpretNotice.NeedMicrophone -> context.packageName
                is InterpretNotice.EngineNeedsMicrophone -> notice.engine.packageName
                else -> null
            }
            // Hearing a language through the model is set up on the translation service's page.
            val needsSettings = notice is InterpretNotice.CloudNotReady ||
                notice is InterpretNotice.LanguageUnsupported ||
                notice == InterpretNotice.NoRecognizer
            if (needsSettings || appInfoFor != null) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(end = 4.dp)) {
                    if (needsSettings) {
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.title_settings)) }
                    }
                    if (appInfoFor != null) {
                        TextButton(
                            onClick = {
                                openSettingsPage(
                                    context,
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$appInfoFor")),
                                )
                            },
                        ) { Text(stringResource(R.string.settings_open_app_info)) }
                    }
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
private fun Conversation(
    state: InterpretState,
    onReplay: (Long, String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    if (state.utterances.isEmpty()) {
        EmptyGuide(modifier)
        return
    }
    val listState = rememberLazyListState()
    val newest = state.utterances.last().id
    // Newest at the bottom; a growing translation stays in view there.
    LaunchedEffect(newest) { listState.animateScrollToItem(0) }
    // Two speakers read as a conversation: the first on the left, the second on the right.
    val second = state.speakers.takeIf { it.size == 2 }?.get(1)?.letter
    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(16.dp),
        // A short conversation sits at the bottom, next to the buttons that continue it.
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        modifier = modifier,
    ) {
        items(state.utterances.asReversed(), key = { it.id }) { utterance ->
            UtteranceBubble(utterance, atEnd = utterance.letter == second, state.reading, onReplay, onOpenSettings)
        }
    }
}

/** How the page works, in three steps, until someone has spoken. */
@Composable
private fun EmptyGuide(modifier: Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 340.dp)) {
            GuideStep(AppIcons.Globe, stringResource(R.string.interpret_step_languages))
            GuideStep(AppIcons.Mic, stringResource(R.string.interpret_step_talk))
            GuideStep(AppIcons.Speaker, stringResource(R.string.interpret_step_result))
        }
    }
}

@Composable
private fun GuideStep(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SpeakerBadge(letter: Char) {
    val name = speakerName(letter)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .background(speakerColor(letter), CircleShape)
            .semantics { contentDescription = name },
    ) {
        Text(
            letter.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun UtteranceBubble(
    utterance: Utterance,
    atEnd: Boolean,
    reading: Reading?,
    onReplay: (Long, String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val accent = speakerAccent(utterance.letter)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (atEnd) Alignment.End else Alignment.Start),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (!atEnd) SpeakerBadge(utterance.letter)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            // The corner beside the speaker's letter is the square one.
            shape = RoundedCornerShape(
                topStart = if (atEnd) 20.dp else 4.dp,
                topEnd = if (atEnd) 4.dp else 20.dp,
                bottomEnd = 20.dp,
                bottomStart = 20.dp,
            ),
            modifier = Modifier.weight(1f, fill = false).widthIn(max = BUBBLE_MAX_WIDTH),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        utterance.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = if (utterance.final) FontStyle.Normal else FontStyle.Italic,
                        color = if (utterance.final) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                if (!utterance.final) HearingDots(accent)
                utterance.renditions.forEach { rendition ->
                    RenditionView(
                        rendition = rendition,
                        // With one other speaker there's only one language it could be.
                        showLanguage = utterance.renditions.size > 1,
                        beingRead = reading == Reading(utterance.id, rendition.language),
                        accent = accent,
                        onReplay = { onReplay(utterance.id, rendition.language) },
                        onOpenSettings = onOpenSettings,
                    )
                }
                if (utterance.final && utterance.renditions.isEmpty()) {
                    Text(
                        stringResource(R.string.interpret_nothing_to_translate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }
        if (atEnd) SpeakerBadge(utterance.letter)
    }
}

/** Three dots under words that are still being heard. */
@Composable
private fun HearingDots(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        listOf(1f, 0.6f, 0.3f).forEach { strength ->
            Box(Modifier.size(6.dp).background(color.copy(alpha = strength), CircleShape))
        }
    }
}

@Composable
private fun RenditionView(
    rendition: Rendition,
    showLanguage: Boolean,
    beingRead: Boolean,
    accent: Color,
    onReplay: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column {
        if (showLanguage) {
            Text(
                stringResource(R.string.interpret_into, languageName(rendition.language)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.weight(1f, fill = false).padding(top = 6.dp, end = 4.dp)) {
                when {
                    rendition.text.isNotEmpty() -> SelectionContainer {
                        Text(
                            rendition.text,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
                            modifier = if (beingRead) {
                                Modifier
                                    .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 4.dp)
                            } else {
                                Modifier
                            },
                        )
                    }
                    rendition.status == RenditionStatus.RUNNING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            stringResource(R.string.action_translating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            if (rendition.status == RenditionStatus.DONE && rendition.text.isNotBlank()) {
                IconButton(onClick = onReplay, modifier = Modifier.size(40.dp)) {
                    Icon(
                        AppIcons.Speaker,
                        contentDescription = stringResource(R.string.interpret_read_again),
                        tint = if (beingRead) MaterialTheme.colorScheme.tertiary else accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        if (rendition.status == RenditionStatus.FAILED) {
            val error = rendition.error
            Text(
                when (error) {
                    RunError.NoKey -> stringResource(R.string.error_no_key)
                    RunError.NoModel -> stringResource(R.string.error_no_model)
                    is RunError.Message -> stringResource(R.string.error_translation, error.text)
                    null -> stringResource(R.string.error_translation, "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (error == RunError.NoKey || error == RunError.NoModel) {
                TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.action_open_settings))
                }
            }
        }
    }
}

@Composable
private fun SpeakerBar(
    state: InterpretState,
    level: StateFlow<Float>,
    talk: TalkActions,
    onLanguage: (Char, String) -> Unit,
    onRemove: (Char) -> Unit,
    onAdd: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)) {
            val canAdd = state.speakers.size < MAX_SPEAKERS
            val gap = 8.dp
            val count = state.speakers.size.coerceAtLeast(1)
            val gaps = if (canAdd) count else count - 1
            val shared = (maxWidth - (if (canAdd) ADD_BUTTON_WIDTH else 0.dp) - gap * gaps) / count
            // Past three or four speakers the buttons keep a usable size and the row scrolls.
            val width = max(shared, MIN_TALK_BUTTON_WIDTH)
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                state.speakers.forEach { speaker ->
                    SpeakerControl(
                        speaker = speaker,
                        width = width,
                        unheard = state.unheard,
                        throughModel = state.throughModel,
                        listening = state.listening?.takeIf { it.letter == speaker.letter },
                        level = level,
                        canRemove = state.speakers.size > MIN_SPEAKERS,
                        talk = talk,
                        onLanguage = { onLanguage(speaker.letter, it) },
                        onRemove = { onRemove(speaker.letter) },
                    )
                }
                if (canAdd) {
                    val add = stringResource(R.string.interpret_add_speaker)
                    OutlinedButton(
                        onClick = onAdd,
                        shape = TALK_BUTTON_SHAPE,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .width(ADD_BUTTON_WIDTH)
                            .height(TALK_BUTTON_HEIGHT)
                            .semantics { contentDescription = add },
                    ) { Icon(AppIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun SpeakerControl(
    speaker: SpeakerSetup,
    width: Dp,
    unheard: Set<String>,
    throughModel: Set<String>,
    listening: Listening?,
    level: StateFlow<Float>,
    canRemove: Boolean,
    talk: TalkActions,
    onLanguage: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(Modifier.weight(1f, fill = false)) {
                LanguageMenu(speaker, unheard, throughModel, canRemove, onLanguage, onRemove)
            }
            // Removing is right there, not only at the end of the language menu.
            if (canRemove) {
                val remove = stringResource(R.string.interpret_remove_speaker, speaker.letter.toString())
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = remove,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        TalkButton(speaker.letter, speaker.language in unheard, listening, level, talk)
    }
}

@Composable
private fun LanguageMenu(
    speaker: SpeakerSetup,
    unheard: Set<String>,
    throughModel: Set<String>,
    canRemove: Boolean,
    onLanguage: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(start = 10.dp, end = 4.dp)) {
            Text(
                languageName(speaker.language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                AppIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp).size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            INTERPRET_LANGUAGES.forEach { code ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(languageName(code), fontWeight = if (code == speaker.language) FontWeight.Bold else null)
                            val note = when (code) {
                                in unheard -> R.string.interpret_cannot_hear
                                in throughModel -> R.string.interpret_heard_by_model
                                else -> null
                            }
                            if (note != null) {
                                Text(
                                    stringResource(note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onLanguage(code)
                    },
                )
            }
            if (canRemove) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.interpret_remove_speaker, speaker.letter.toString())) },
                    onClick = {
                        expanded = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/** Tap to talk until tapped again, or press and hold to talk until released. */
@Composable
private fun TalkButton(letter: Char, unheard: Boolean, listening: Listening?, level: StateFlow<Float>, talk: TalkActions) {
    val color = speakerColor(letter)
    val active = listening != null
    val haptics = LocalHapticFeedback.current
    val currentTalk by rememberUpdatedState(talk)
    val hint = stringResource(
        when {
            listening == null && unheard -> R.string.interpret_cannot_hear
            listening == null -> R.string.interpret_hint_idle
            listening.phase == ListenPhase.FINISHING -> R.string.interpret_hint_finishing
            listening.held -> R.string.interpret_hint_holding
            listening.phase == ListenPhase.PAUSED -> R.string.interpret_hint_paused
            listening.phase == ListenPhase.STARTING -> R.string.interpret_hint_starting
            else -> R.string.interpret_hint_listening
        },
    )
    val name = speakerName(letter)
    val actionLabel = stringResource(if (active) R.string.interpret_stop_talking else R.string.interpret_start_talking)

    // While the microphone is open the button shows its level as a moving wave. With Android's
    // "Remove animations" there is no wave, and the button swells with the voice instead.
    val microphoneOpen = listening?.phase == ListenPhase.READY || listening?.phase == ListenPhase.HEARING
    val waveShown = microphoneOpen && ValueAnimator.areAnimatorsEnabled()
    // Only the active button follows the voice level, so the rest of the page doesn't redraw.
    val voice = if (active && !waveShown) level.collectAsStateWithLifecycle().value else 0f
    val pulse by animateFloatAsState(voice, label = "voice")

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(TALK_BUTTON_HEIGHT)
            .graphicsLayer {
                val scale = 1f + 0.06f * pulse
                scaleX = scale
                scaleY = scale
                // Still pressable, to explain why it can't listen.
                alpha = if (unheard) 0.6f else 1f
            }
            .background(if (active) color else color.copy(alpha = 0.14f), TALK_BUTTON_SHAPE)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = hint
                onClick(label = actionLabel) {
                    currentTalk.toggle(letter)
                    true
                }
            }
            .pointerInput(letter) {
                awaitEachGesture {
                    awaitFirstDown()
                    val actions = currentTalk
                    if (!actions.press(letter)) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    var cancelled = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation().also { if (it == null) cancelled = true }
                    }
                    when {
                        up != null -> actions.session.release(letter, held = false)
                        cancelled -> actions.session.abandonPress(letter)
                        else -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.session.holdStarted(letter)
                            // Sliding off the button ends the hold as lifting the finger does.
                            waitForUpOrCancellation()
                            actions.session.release(letter, held = true)
                        }
                    }
                }
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        ) {
            val ink = if (active) Color.White else speakerAccent(letter)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(AppIcons.Mic, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            if (waveShown) {
                // Says "listening" by itself; screen readers still get the words, as the button's state.
                VoiceWave(level, color = Color.White, modifier = Modifier.fillMaxWidth().height(WAVE_HEIGHT))
            } else {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
