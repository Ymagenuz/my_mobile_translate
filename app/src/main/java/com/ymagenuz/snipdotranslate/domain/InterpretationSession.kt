package com.ymagenuz.snipdotranslate.domain

import com.ymagenuz.snipdotranslate.core.ContextLine
import com.ymagenuz.snipdotranslate.core.SpeakerSetup
import com.ymagenuz.snipdotranslate.core.buildInterpretationPrompt
import com.ymagenuz.snipdotranslate.core.interpretationTargets
import com.ymagenuz.snipdotranslate.core.speechLanguageTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Tap-to-talk stops by itself after this long without hearing anyone. */
const val IDLE_LISTEN_LIMIT_MS = 60_000L

/** How long to wait for the last words after asking speech recognition to stop. */
const val FINISH_TIMEOUT_MS = 3_000L

/** A pause after reading aloud, so recognition doesn't hear the end of it. */
const val RESUME_AFTER_READING_MS = 300L

private const val TRANSIENT_RETRY_MS = 300L
private const val MAX_TRANSIENT_RETRIES = 3

enum class SpeechError {
    NO_SPEECH,
    PERMISSION,
    AUDIO,
    LANGUAGE_UNSUPPORTED,
    NETWORK,
    UNAVAILABLE,
    TRANSIENT,

    /** The app doing the recognition may not use the microphone itself. */
    ENGINE_MICROPHONE,

    /** Hearing through the model needs an API key, a model and Supports audio input. */
    CLOUD_NOT_READY,

    /** The model couldn't write down what was said; see [SpeechInput.lastErrorDetail]. */
    CLOUD_FAILED,
    OTHER,
}

/** The app that provides the phone's speech recognition. */
data class SpeechEngine(val label: String, val packageName: String)

/** Events from one recognition, which ends with either [onResult] or [onError]. */
interface SpeechListener {
    fun onReady()

    fun onSpeechBegin()

    /** From 0 for silence to 1 for loud speech. */
    fun onLevel(level: Float)

    fun onPartial(text: String)

    fun onResult(text: String)

    fun onError(error: SpeechError)
}

/** The phone's speech recognition, one recognition at a time. */
interface SpeechInput {
    val isAvailable: Boolean

    fun start(languageTag: String, listener: SpeechListener)

    /** Stops listening; the words heard so far still arrive in [SpeechListener.onResult]. */
    fun stop()

    /**
     * Stops at once. The phone's recognition sends nothing more; the model may still hand
     * over a sentence it was already writing down, which arrives as a late result.
     */
    fun cancel()

    fun release()

    /** How long to wait after [stop] for the last words. */
    val finishTimeoutMs: Long get() = FINISH_TIMEOUT_MS

    /** Says more about the last [SpeechError.CLOUD_FAILED]. */
    fun lastErrorDetail(): String? = null

    /**
     * The recognition app, when it isn't allowed to use the microphone itself. Some, such as
     * Xiaomi's, record under their own name rather than the app that asked.
     */
    fun engineWithoutMicrophone(): SpeechEngine? = null

    /** The app's languages the engine can hear, or null when it can't be told. */
    suspend fun recognizedLanguages(): Set<String>? = null
}

enum class VoiceResult { SPOKEN, NO_VOICE, FAILED }

/** The phone's text-to-speech. After [stop], the stopped text's callback isn't called. */
interface SpeechOutput {
    fun speak(text: String, languageTag: String, onDone: (VoiceResult) -> Unit)

    fun stop()

    fun release()
}

enum class ListenPhase {
    /** Recognition was asked to start and isn't ready yet. */
    STARTING,

    /** Ready, and nobody has started speaking. */
    READY,

    /** Someone is speaking. */
    HEARING,

    /** Not listening for a moment while a translation is read aloud. */
    PAUSED,

    /** Asked to stop and waiting for the last words. */
    FINISHING,
}

/** Whose button is on: [held] while pressed and held, otherwise until tapped again. */
data class Listening(val letter: Char, val held: Boolean, val phase: ListenPhase)

enum class RenditionStatus { RUNNING, DONE, FAILED }

/** One utterance translated into one language. */
data class Rendition(
    val language: String,
    val text: String = "",
    val status: RenditionStatus = RenditionStatus.RUNNING,
    val error: RunError? = null,
)

/** What a speaker said; [final] once recognition has finished hearing it. */
data class Utterance(
    val id: Long,
    val letter: Char,
    val language: String,
    val text: String,
    val final: Boolean,
    val renditions: List<Rendition> = emptyList(),
)

sealed interface InterpretNotice {
    data object NoRecognizer : InterpretNotice

    data object NeedMicrophone : InterpretNotice

    data object MicrophoneBusy : InterpretNotice

    data class EngineNeedsMicrophone(val engine: SpeechEngine) : InterpretNotice

    data class LanguageUnsupported(val language: String) : InterpretNotice

    data class CloudNotReady(val language: String) : InterpretNotice

    data class CloudFailed(val message: String) : InterpretNotice

    data object Network : InterpretNotice

    data object RecognitionFailed : InterpretNotice

    data object IdleStopped : InterpretNotice

    data class NoVoice(val language: String) : InterpretNotice
}

/** The translation being read aloud. */
data class Reading(val utteranceId: Long, val language: String)

data class InterpretState(
    val speakers: List<SpeakerSetup> = emptyList(),
    val utterances: List<Utterance> = emptyList(),
    val listening: Listening? = null,
    val readAloud: Boolean = true,
    val reading: Reading? = null,
    val notice: InterpretNotice? = null,
    /** Languages the phone's speech recognition can't hear; their speakers can only listen. */
    val unheard: Set<String> = emptySet(),
    /** Languages the phone can't hear that the translation model hears instead. */
    val throughModel: Set<String> = emptySet(),
)

/**
 * A conversation between speakers of different languages. Each speaker has a button:
 * tapping it listens until it's tapped again, and pressing and holding it listens until
 * it's released. Every sentence is translated as soon as recognition finishes hearing it,
 * while listening carries on, and translations can be read aloud between sentences.
 */
class InterpretationSession(
    private val scope: CoroutineScope,
    private val input: SpeechInput,
    private val output: SpeechOutput,
    private val streamer: ChatStreamer,
    private val providerAccess: suspend () -> ProviderAccess,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(InterpretState())
    val state: StateFlow<InterpretState> = mutableState.asStateFlow()

    // Changes many times a second, so it's kept apart from everything else.
    private val mutableLevel = MutableStateFlow(0f)
    val level: StateFlow<Float> = mutableLevel.asStateFlow()

    private val translations = SupervisorJob(scope.coroutineContext[Job])
    private var nextId = 1L

    private var activeSpeaker: SpeakerSetup? = null
    private var recognition: Recognition? = null
    private var liveId: Long? = null
    private var partial = ""
    private var pressStartedListening = false
    private var lastSpeechMs = 0L
    private var transientFailures = 0
    private var pendingJob: Job? = null
    private var active = true

    private val readQueue = ArrayDeque<ReadItem>()
    private var readToken: Any? = null

    private class ReadItem(val utteranceId: Long, val language: String, val text: String)

    private val current get() = mutableState.value

    init {
        if (!input.isAvailable) showNotice(InterpretNotice.NoRecognizer)
    }

    fun setSpeakers(speakers: List<SpeakerSetup>) {
        val speaking = activeSpeaker
        if (speaking != null && speakers.none { it == speaking }) endListening(commitPartial = true)
        mutableState.update { it.copy(speakers = speakers) }
    }

    /** Which languages can't be heard at all, and which are heard through the model. */
    fun setLanguageSupport(unheard: Set<String>, throughModel: Set<String> = emptySet()) {
        if (activeSpeaker?.language in unheard) endListening(commitPartial = true)
        mutableState.update { it.copy(unheard = unheard, throughModel = throughModel) }
    }

    fun setReadAloud(enabled: Boolean) {
        if (enabled == current.readAloud) return
        mutableState.update { it.copy(readAloud = enabled) }
        if (!enabled) {
            stopReading()
            resumeAfterReading()
        }
    }

    /** A finger went down on a speaker's button. */
    fun press(letter: Char) {
        val listening = current.listening
        if (listening?.letter == letter && listening.phase != ListenPhase.FINISHING) {
            // Already on: this press turns it off when the finger lifts.
            pressStartedListening = false
            return
        }
        stopReading()
        if (listening != null) endListening(commitPartial = true)
        pressStartedListening = false
        if (!input.isAvailable) {
            showNotice(InterpretNotice.NoRecognizer)
            return
        }
        val speaker = current.speakers.firstOrNull { it.letter == letter } ?: return
        if (speaker.language in current.unheard) {
            // Listening would only hear silence; say so straight away instead.
            showNotice(InterpretNotice.LanguageUnsupported(speaker.language))
            return
        }
        pressStartedListening = true
        beginListening(speaker)
    }

    /** The finger stayed down long enough to count as press and hold. */
    fun holdStarted(letter: Char) {
        val listening = current.listening ?: return
        if (listening.letter == letter) setListening(listening.copy(held = true))
    }

    /** The finger lifted: a tap leaves listening on if it turned it on, and otherwise stops it. */
    fun release(letter: Char, held: Boolean) {
        val listening = current.listening ?: return
        if (listening.letter != letter) return
        if (!held && pressStartedListening) {
            setListening(listening.copy(held = false))
        } else {
            requestFinish()
        }
        pressStartedListening = false
    }

    /** Another gesture, such as scrolling the buttons, took over the press. */
    fun abandonPress(letter: Char) {
        val listening = current.listening
        if (pressStartedListening && listening?.letter == letter) endListening(commitPartial = false)
        pressStartedListening = false
    }

    /** Turns a speaker's listening on or off, as a tap does; for accessibility services. */
    fun toggle(letter: Char) {
        press(letter)
        release(letter, held = false)
    }

    /**
     * Off while the page is left or hidden: listening and reading aloud stop, what was heard
     * is kept, and translations that finish meanwhile aren't read aloud.
     */
    fun setActive(active: Boolean) {
        this.active = active
        if (!active) {
            stopReading()
            endListening(commitPartial = true)
        }
    }

    fun clear() {
        endListening(commitPartial = false)
        stopReading()
        translations.cancelChildren()
        mutableState.update { it.copy(utterances = emptyList(), notice = null) }
    }

    /** Reads one translation aloud again. */
    fun replay(utteranceId: Long, language: String) {
        val rendition = current.utterances.firstOrNull { it.id == utteranceId }
            ?.renditions?.firstOrNull { it.language == language }
            ?.takeIf { it.status == RenditionStatus.DONE && it.text.isNotBlank() }
            ?: return
        stopReading()
        readQueue.addFirst(ReadItem(utteranceId, language, rendition.text))
        maybeRead()
    }

    fun showNotice(notice: InterpretNotice) = mutableState.update { it.copy(notice = notice) }

    fun dismissNotice() = mutableState.update { it.copy(notice = null) }

    fun close() {
        setActive(false)
        translations.cancel()
        input.release()
        output.release()
    }

    // Listening

    private fun beginListening(speaker: SpeakerSetup) {
        activeSpeaker = speaker
        lastSpeechMs = nowMs()
        transientFailures = 0
        dismissNotice()
        setListening(Listening(speaker.letter, held = false, phase = ListenPhase.STARTING))
        startRecognition()
    }

    private fun startRecognition() {
        val speaker = activeSpeaker ?: return
        pendingJob?.cancel()
        val next = Recognition(speaker)
        recognition = next
        partial = ""
        setPhase(ListenPhase.STARTING)
        input.start(speechLanguageTag(speaker.language), next)
    }

    private fun stopRecognition() {
        pendingJob?.cancel()
        pendingJob = null
        if (recognition != null) {
            recognition = null
            input.cancel()
        }
        mutableLevel.value = 0f
    }

    /** Stops after the words being spoken, or at once if nobody is speaking. */
    private fun requestFinish() {
        val listening = current.listening ?: return
        when {
            listening.phase == ListenPhase.FINISHING -> Unit
            recognition == null || (listening.phase != ListenPhase.HEARING && partial.isEmpty()) ->
                endListening(commitPartial = true)
            else -> {
                setPhase(ListenPhase.FINISHING)
                input.stop()
                pendingJob = scope.launch {
                    delay(input.finishTimeoutMs)
                    endListening(commitPartial = true)
                }
            }
        }
    }

    /** Ends listening, keeping or dropping the words heard but not finished. */
    private fun endListening(commitPartial: Boolean) {
        val speaker = activeSpeaker ?: return
        // Words still on their way only count if nothing of them was kept or thrown away here.
        recognition?.settled = !commitPartial || partial.isNotBlank()
        stopRecognition()
        if (commitPartial) commitLive(speaker, partial) else dropLive()
        activeSpeaker = null
        setListening(null)
        maybeRead()
    }

    /** After a sentence: listen for the next, unless translations are waiting to be read. */
    private fun continueListening() {
        val listening = current.listening ?: return
        if (!listening.held && current.readAloud && readQueue.isNotEmpty()) {
            setPhase(ListenPhase.PAUSED)
            maybeRead()
        } else {
            startRecognition()
        }
    }

    private fun retryLater() {
        setPhase(ListenPhase.STARTING)
        pendingJob = scope.launch {
            delay(TRANSIENT_RETRY_MS)
            startRecognition()
        }
    }

    private fun onRecognitionError(error: SpeechError) {
        val listening = current.listening ?: return
        val speaker = activeSpeaker ?: return
        val finishing = listening.phase == ListenPhase.FINISHING
        when (error) {
            SpeechError.NO_SPEECH -> {
                commitLive(speaker, partial)
                val idle = !listening.held && nowMs() - lastSpeechMs >= IDLE_LISTEN_LIMIT_MS
                when {
                    finishing -> endListening(commitPartial = true)
                    idle -> {
                        endListening(commitPartial = true)
                        showNotice(InterpretNotice.IdleStopped)
                    }
                    else -> continueListening()
                }
            }
            SpeechError.TRANSIENT -> when {
                finishing -> endListening(commitPartial = true)
                ++transientFailures <= MAX_TRANSIENT_RETRIES -> {
                    commitLive(speaker, partial)
                    retryLater()
                }
                else -> failListening(InterpretNotice.RecognitionFailed)
            }
            SpeechError.PERMISSION -> failListening(InterpretNotice.NeedMicrophone)
            SpeechError.AUDIO -> failListening(InterpretNotice.MicrophoneBusy)
            SpeechError.LANGUAGE_UNSUPPORTED -> {
                mutableState.update { it.copy(unheard = it.unheard + speaker.language) }
                failListening(InterpretNotice.LanguageUnsupported(speaker.language))
            }
            SpeechError.NETWORK -> failListening(InterpretNotice.Network)
            SpeechError.UNAVAILABLE -> failListening(InterpretNotice.NoRecognizer)
            SpeechError.CLOUD_NOT_READY -> failListening(InterpretNotice.CloudNotReady(speaker.language))
            SpeechError.CLOUD_FAILED -> failListening(InterpretNotice.CloudFailed(input.lastErrorDetail().orEmpty()))
            SpeechError.ENGINE_MICROPHONE -> failListening(
                input.engineWithoutMicrophone()?.let(InterpretNotice::EngineNeedsMicrophone)
                    ?: InterpretNotice.RecognitionFailed,
            )
            SpeechError.OTHER -> failListening(InterpretNotice.RecognitionFailed)
        }
    }

    private fun failListening(notice: InterpretNotice) {
        endListening(commitPartial = true)
        showNotice(notice)
    }

    /**
     * Events from one recognition; any from an earlier one are ignored, except a sentence
     * the model finished writing down after listening moved on, which still counts.
     */
    private inner class Recognition(private val speaker: SpeakerSetup) : SpeechListener {
        private val isCurrent get() = recognition === this

        /** Ended, keeping or dropping its unfinished words, so later ones aren't wanted. */
        var settled = false

        override fun onReady() {
            if (!isCurrent || current.listening?.phase != ListenPhase.STARTING) return
            setPhase(ListenPhase.READY)
            maybeRead()
        }

        override fun onSpeechBegin() {
            if (!isCurrent) return
            heardSpeech()
        }

        override fun onLevel(level: Float) {
            if (isCurrent) mutableLevel.value = level.coerceIn(0f, 1f)
        }

        override fun onPartial(text: String) {
            if (!isCurrent || text.isBlank()) return
            val speaker = activeSpeaker ?: return
            partial = text
            heardSpeech()
            showLive(speaker, text)
        }

        override fun onResult(text: String) {
            if (!isCurrent) {
                if (!settled) commitLate(speaker, text)
                return
            }
            recognition = null
            mutableLevel.value = 0f
            val speaker = activeSpeaker ?: return
            commitLive(speaker, text.ifBlank { partial })
            if (current.listening?.phase == ListenPhase.FINISHING) {
                endListening(commitPartial = false)
            } else {
                transientFailures = 0
                continueListening()
            }
        }

        override fun onError(error: SpeechError) {
            if (!isCurrent) return
            recognition = null
            mutableLevel.value = 0f
            onRecognitionError(error)
        }
    }

    private fun heardSpeech() {
        lastSpeechMs = nowMs()
        transientFailures = 0
        val phase = current.listening?.phase
        if (phase == ListenPhase.STARTING || phase == ListenPhase.READY) setPhase(ListenPhase.HEARING)
    }

    private fun setListening(listening: Listening?) {
        if (listening == null) mutableLevel.value = 0f
        mutableState.update { it.copy(listening = listening) }
    }

    private fun setPhase(phase: ListenPhase) {
        val listening = current.listening ?: return
        if (listening.phase != phase) setListening(listening.copy(phase = phase))
    }

    // Utterances

    private fun showLive(speaker: SpeakerSetup, text: String) {
        val id = liveId
        if (id == null) {
            val utterance = Utterance(nextId++, speaker.letter, speaker.language, text, final = false)
            liveId = utterance.id
            mutableState.update { it.copy(utterances = it.utterances + utterance) }
        } else {
            updateUtterance(id) { it.copy(text = text) }
        }
    }

    private fun dropLive() {
        val id = liveId ?: return
        liveId = null
        partial = ""
        mutableState.update { state -> state.copy(utterances = state.utterances.filter { it.id != id }) }
    }

    /** Fixes what was heard as one utterance and starts translating it. */
    private fun commitLive(speaker: SpeakerSetup, text: String) {
        val heard = text.trim()
        if (heard.isEmpty()) {
            dropLive()
            return
        }
        val existing = liveId
        liveId = null
        partial = ""
        addFinal(speaker, heard, existing)
    }

    /** A sentence heard before listening moved on, kept after whatever is on screen now. */
    private fun commitLate(speaker: SpeakerSetup, text: String) {
        val heard = text.trim()
        if (heard.isNotEmpty()) addFinal(speaker, heard, existing = null)
    }

    /** Shows a finished utterance, in place of the live one if there is one, and translates it. */
    private fun addFinal(speaker: SpeakerSetup, heard: String, existing: Long?) {
        val id = existing ?: nextId++
        val targets = interpretationTargets(speaker, current.speakers)
        val utterance = Utterance(
            id = id,
            letter = speaker.letter,
            language = speaker.language,
            text = heard,
            final = true,
            renditions = targets.map { Rendition(it) },
        )
        mutableState.update { state ->
            val utterances = if (existing == null) {
                state.utterances + utterance
            } else {
                state.utterances.map { if (it.id == id) utterance else it }
            }
            state.copy(utterances = utterances)
        }
        val context = current.utterances
            .takeWhile { it.id != id }
            .filter { it.final }
            .map { ContextLine(it.letter, it.text) }
        targets.forEach { target ->
            scope.launch(translations) { translate(utterance, target, context) }
        }
    }

    private suspend fun translate(utterance: Utterance, target: String, context: List<ContextLine>) {
        val access = providerAccess()
        if (access !is ProviderAccess.Ready) {
            val error = if (access is ProviderAccess.MissingModel) RunError.NoModel else RunError.NoKey
            updateRendition(utterance.id, target) { it.copy(status = RenditionStatus.FAILED, error = error) }
            return
        }
        val prompt = buildInterpretationPrompt(utterance.text, utterance.language, target, context)
        val received = StringBuilder()
        try {
            streamer.stream(access.provider, access.apiKey, prompt).collect { chunk ->
                received.append(chunk)
                val shown = received.toString().trimStart()
                updateRendition(utterance.id, target) { it.copy(text = shown) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val message = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
            updateRendition(utterance.id, target) {
                it.copy(text = received.toString().trim(), status = RenditionStatus.FAILED, error = RunError.Message(message))
            }
            return
        }
        val result = received.toString().trim()
        updateRendition(utterance.id, target) { it.copy(text = result, status = RenditionStatus.DONE) }
        if (active && current.readAloud && result.isNotEmpty()) {
            readQueue.addLast(ReadItem(utterance.id, target, result))
            maybeRead()
        }
    }

    private fun updateUtterance(id: Long, change: (Utterance) -> Utterance) = mutableState.update { state ->
        state.copy(utterances = state.utterances.map { if (it.id == id) change(it) else it })
    }

    private fun updateRendition(id: Long, language: String, change: (Rendition) -> Rendition) =
        updateUtterance(id) { utterance ->
            utterance.copy(renditions = utterance.renditions.map { if (it.language == language) change(it) else it })
        }

    // Reading aloud

    /**
     * Reads the next translation when nobody is speaking. Listening pauses meanwhile, so the
     * phone doesn't hear itself, and never interrupts someone holding their button.
     */
    private fun maybeRead() {
        if (current.reading != null || readQueue.isEmpty()) return
        val listening = current.listening
        if (listening != null) {
            val quiet = listening.phase == ListenPhase.READY ||
                (listening.phase == ListenPhase.STARTING && partial.isEmpty())
            when {
                listening.held -> return
                listening.phase == ListenPhase.PAUSED -> Unit
                quiet -> {
                    stopRecognition()
                    setPhase(ListenPhase.PAUSED)
                }
                else -> return
            }
        }
        val item = readQueue.removeFirst()
        val token = Any()
        readToken = token
        mutableState.update { it.copy(reading = Reading(item.utteranceId, item.language)) }
        output.speak(item.text, speechLanguageTag(item.language)) { result ->
            if (readToken !== token) return@speak
            readToken = null
            mutableState.update { it.copy(reading = null) }
            if (result == VoiceResult.NO_VOICE) showNotice(InterpretNotice.NoVoice(item.language))
            if (readQueue.isNotEmpty()) maybeRead() else resumeAfterReading()
        }
    }

    private fun stopReading() {
        readQueue.clear()
        if (readToken != null) {
            readToken = null
            output.stop()
            mutableState.update { it.copy(reading = null) }
        }
    }

    private fun resumeAfterReading() {
        if (current.listening?.phase != ListenPhase.PAUSED) return
        // The time spent reading doesn't count as silence.
        lastSpeechMs = nowMs()
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(RESUME_AFTER_READING_MS)
            startRecognition()
        }
    }
}
