package com.ymagenuz.snipdotranslate.domain

import com.ymagenuz.snipdotranslate.core.ApiProviderSpec
import com.ymagenuz.snipdotranslate.core.BuiltPrompt
import com.ymagenuz.snipdotranslate.core.LatexStreamRestorer
import com.ymagenuz.snipdotranslate.core.SOURCE_AUTO
import com.ymagenuz.snipdotranslate.core.TARGET_DEFAULT
import com.ymagenuz.snipdotranslate.core.TranslationMode
import com.ymagenuz.snipdotranslate.core.backTranslationLanguageLabel
import com.ymagenuz.snipdotranslate.core.buildBackTranslationPrompt
import com.ymagenuz.snipdotranslate.core.buildTranslationPrompt
import com.ymagenuz.snipdotranslate.core.detectTextLanguage
import com.ymagenuz.snipdotranslate.core.resolveEffectiveMode
import com.ymagenuz.snipdotranslate.core.translationTargetLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val LIVE_RENDER_INTERVAL_MS = 120L

enum class RunStatus { IDLE, RUNNING, DONE, STOPPED, FAILED }

enum class BackStatus { HIDDEN, SAME_LANGUAGE, NO_KEY, RUNNING, DONE, STOPPED, FAILED }

sealed interface RunError {
    data object NoKey : RunError

    data object NoModel : RunError

    data class Message(val text: String) : RunError
}

data class BackTranslationState(
    val status: BackStatus = BackStatus.HIDDEN,
    val languageCode: String = "",
    val text: String = "",
    val error: String? = null,
)

data class TranslationRequest(
    val text: String,
    val forceDictionary: Boolean = false,
    val sourceLang: String = SOURCE_AUTO,
    val targetLang: String = TARGET_DEFAULT,
)

data class TranslationState(
    val status: RunStatus = RunStatus.IDLE,
    val request: TranslationRequest? = null,
    val mode: String = TranslationMode.AUTO,
    val text: String = "",
    val error: RunError? = null,
    val back: BackTranslationState = BackTranslationState(),
)

sealed interface ProviderAccess {
    data class Ready(val provider: ApiProviderSpec, val apiKey: String) : ProviderAccess

    data object MissingKey : ProviderAccess

    data object MissingModel : ProviderAccess
}

fun interface ChatStreamer {
    fun stream(provider: ApiProviderSpec, apiKey: String, prompt: String): Flow<String>
}

/** Runs one translation at a time, followed by its optional back-translation. */
class TranslationSession(
    private val scope: CoroutineScope,
    private val streamer: ChatStreamer,
    private val providerAccess: suspend () -> ProviderAccess,
    private val backTranslationEnabled: suspend () -> Boolean,
    private val onFinished: suspend (source: String, result: String, mode: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(TranslationState())
    val state: StateFlow<TranslationState> = mutableState.asStateFlow()

    private var translationJob: Job? = null
    private var backJob: Job? = null
    private var currentRun: Any? = null
    private var stoppedRun: Any? = null
    private var currentBackRun: Any? = null
    private var stoppedBackRun: Any? = null

    fun translate(request: TranslationRequest) {
        cancelRuns()
        val text = request.text.trim()
        if (text.isEmpty()) return

        val mode = resolveEffectiveMode(text, request.forceDictionary, request.sourceLang, request.targetLang)
        val run = Any()
        currentRun = run
        mutableState.value = TranslationState(status = RunStatus.RUNNING, request = request.copy(text = text), mode = mode)
        translationJob = scope.launch { runTranslation(run, text, mode, request) }
    }

    fun retry() {
        mutableState.value.request?.let(::translate)
    }

    /** Stops receiving and keeps the text that has arrived. */
    fun stop() {
        if (mutableState.value.status != RunStatus.RUNNING) return
        stoppedRun = currentRun
        translationJob?.cancel()
    }

    fun stopBackTranslation() {
        if (mutableState.value.back.status != BackStatus.RUNNING) return
        stoppedBackRun = currentBackRun
        backJob?.cancel()
    }

    /** Shows a saved result, back-translating it too if that is switched on. */
    fun showFinished(request: TranslationRequest, mode: String, result: String) {
        cancelRuns()
        mutableState.value = TranslationState(status = RunStatus.DONE, request = request, mode = mode, text = result)
        scope.launch { startBackTranslation() }
    }

    /** Shows a problem that happened before a translation could start. */
    fun showFailure(message: String) {
        cancelRuns()
        mutableState.value = TranslationState(status = RunStatus.FAILED, error = RunError.Message(message))
    }

    fun clear() {
        cancelRuns()
        mutableState.value = TranslationState()
    }

    /** Applies a change of the back-translation setting to the result on screen. */
    fun onBackTranslationToggled(enabled: Boolean) {
        val current = mutableState.value
        if (!enabled) {
            cancelBackRun()
            mutableState.update { it.copy(back = BackTranslationState()) }
        } else if (current.status == RunStatus.DONE && current.back.status == BackStatus.HIDDEN) {
            scope.launch { startBackTranslation() }
        }
    }

    private fun cancelRuns() {
        currentRun = null
        translationJob?.cancel()
        cancelBackRun()
    }

    private fun cancelBackRun() {
        currentBackRun = null
        backJob?.cancel()
    }

    private suspend fun runTranslation(run: Any, text: String, mode: String, request: TranslationRequest) {
        val access = providerAccess()
        if (run !== currentRun) return
        if (access !is ProviderAccess.Ready) {
            val error = if (access is ProviderAccess.MissingModel) RunError.NoModel else RunError.NoKey
            mutableState.update { it.copy(status = RunStatus.FAILED, error = error) }
            return
        }

        val built = buildTranslationPrompt(text, mode, request.sourceLang, request.targetLang)
        val received = Receiver(built) { partial ->
            if (run === currentRun) mutableState.update { it.copy(text = partial) }
        }
        try {
            streamer.stream(access.provider, access.apiKey, built.prompt).collect(received::accept)
        } catch (cancelled: CancellationException) {
            if (stoppedRun === run) {
                val partial = received.finish()
                mutableState.update { it.copy(status = RunStatus.STOPPED, text = partial) }
            }
            throw cancelled
        } catch (failure: Exception) {
            if (run === currentRun) {
                val partial = received.finish()
                val message = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
                mutableState.update {
                    it.copy(status = RunStatus.FAILED, text = partial, error = RunError.Message(message))
                }
            }
            return
        }

        if (run !== currentRun) return
        val result = received.finish()
        mutableState.update { it.copy(status = RunStatus.DONE, text = result) }
        try {
            onFinished(text, result, mode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A history that can't be saved must not take the translation away.
        }
        startBackTranslation()
    }

    private suspend fun startBackTranslation() {
        val current = mutableState.value
        val request = current.request ?: return
        val translation = current.text.trim()
        if (translation.isEmpty() || current.mode == TranslationMode.DICTIONARY || !backTranslationEnabled()) return

        val language = if (request.sourceLang != SOURCE_AUTO) request.sourceLang else detectTextLanguage(request.text)
        if (language == translationTargetLanguage(current.mode, request.targetLang)) {
            setBack(BackTranslationState(BackStatus.SAME_LANGUAGE, language))
            return
        }
        val access = providerAccess() as? ProviderAccess.Ready
        if (access == null) {
            setBack(BackTranslationState(BackStatus.NO_KEY, language))
            return
        }

        val run = Any()
        currentBackRun = run
        setBack(BackTranslationState(BackStatus.RUNNING, language))
        backJob = scope.launch { runBackTranslation(run, translation, language, access) }
    }

    private suspend fun runBackTranslation(
        run: Any,
        translation: String,
        language: String,
        access: ProviderAccess.Ready,
    ) {
        val built = buildBackTranslationPrompt(translation, backTranslationLanguageLabel(language))
        val received = Receiver(built) { partial ->
            if (run === currentBackRun) updateBack { it.copy(text = partial) }
        }
        try {
            streamer.stream(access.provider, access.apiKey, built.prompt).collect(received::accept)
        } catch (cancelled: CancellationException) {
            if (stoppedBackRun === run) {
                val partial = received.finish()
                updateBack { it.copy(status = BackStatus.STOPPED, text = partial) }
            }
            throw cancelled
        } catch (failure: Exception) {
            if (run === currentBackRun) {
                val partial = received.finish()
                updateBack { it.copy(status = BackStatus.FAILED, text = partial, error = failure.message) }
            }
            return
        }
        if (run === currentBackRun) {
            val result = received.finish()
            updateBack { it.copy(status = BackStatus.DONE, text = result) }
        }
    }

    private fun setBack(back: BackTranslationState) = mutableState.update { it.copy(back = back) }

    private fun updateBack(change: (BackTranslationState) -> BackTranslationState) =
        mutableState.update { it.copy(back = change(it.back)) }

    /** Restores LaTeX as text arrives and limits how often partial text is published. */
    private inner class Receiver(built: BuiltPrompt, private val publish: (String) -> Unit) {
        private val restorer = LatexStreamRestorer(built.replacements)
        private val text = StringBuilder()
        private var lastPublish = 0L

        fun accept(chunk: String) {
            text.append(restorer.feed(chunk))
            val now = nowMs()
            if (now - lastPublish >= LIVE_RENDER_INTERVAL_MS) {
                lastPublish = now
                publish(text.toString())
            }
        }

        fun finish(): String {
            text.append(restorer.finish())
            return text.toString()
        }
    }
}
