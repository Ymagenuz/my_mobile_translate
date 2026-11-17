package com.ymagenuz.snipdotranslate.ui

import android.app.Application
import android.content.res.Resources
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ymagenuz.snipdotranslate.SnipDoApp
import com.ymagenuz.snipdotranslate.core.INTERPRET_LANGUAGES
import com.ymagenuz.snipdotranslate.core.SpeakerSetup
import com.ymagenuz.snipdotranslate.core.defaultSpeakers
import com.ymagenuz.snipdotranslate.core.withAddedSpeaker
import com.ymagenuz.snipdotranslate.core.withSpeakerLanguage
import com.ymagenuz.snipdotranslate.core.withoutSpeaker
import com.ymagenuz.snipdotranslate.domain.ChatStreamer
import com.ymagenuz.snipdotranslate.domain.InterpretNotice
import com.ymagenuz.snipdotranslate.domain.InterpretationSession
import com.ymagenuz.snipdotranslate.speech.CombinedSpeechInput
import kotlinx.coroutines.launch

/** The interpretation page's conversation, kept while the app is open but never saved. */
class InterpretViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SnipDoApp
    private val phone = app.speechInputFactory()

    // What the phone says it can hear (null until known, or if it can't tell), and whether
    // the translation model takes audio, for the languages the phone can't hear.
    private var phoneLanguages: Set<String>? = null
    private var modelHears = false
    private val input = CombinedSpeechInput(
        phone = phone,
        model = app.modelSpeechInputFactory(),
        phoneLanguages = { phoneLanguages },
        modelHears = { modelHears },
    )
    val session = InterpretationSession(
        scope = viewModelScope,
        input = input,
        output = app.speechOutputFactory(),
        streamer = ChatStreamer(app.chatClient::streamChat),
        providerAccess = app::providerAccess,
    )
    val state = session.state
    val level = session.level

    init {
        viewModelScope.launch {
            phoneLanguages = phone.recognizedLanguages()
            updateLanguageSupport()
        }
        viewModelScope.launch {
            app.settingsStore.settings.collect { settings ->
                // The phone's own language, not the app's interface language.
                val deviceLanguage = Resources.getSystem().configuration.locales[0].language
                session.setSpeakers(settings.interpretSpeakers.ifEmpty { defaultSpeakers(deviceLanguage) })
                session.setReadAloud(settings.interpretReadAloud)
                modelHears = settings.resolvedProvider().supportsAudio
                updateLanguageSupport()
            }
        }
    }

    private fun updateLanguageSupport() {
        val notByPhone = INTERPRET_LANGUAGES.filterNot(input::phoneHears).toSet()
        if (modelHears) {
            session.setLanguageSupport(unheard = emptySet(), throughModel = notByPhone)
        } else {
            session.setLanguageSupport(unheard = notByPhone)
        }
        // A phone without recognition can still hear everything through the model.
        if (input.isAvailable && state.value.notice == InterpretNotice.NoRecognizer) session.dismissNotice()
    }

    fun addSpeaker() = changeSpeakers { withAddedSpeaker(it) }

    fun removeSpeaker(letter: Char) = changeSpeakers { withoutSpeaker(it, letter) }

    fun setLanguage(letter: Char, language: String) = changeSpeakers { withSpeakerLanguage(it, letter, language) }

    fun setReadAloud(enabled: Boolean) {
        session.setReadAloud(enabled)
        viewModelScope.launch { app.settingsStore.update { it.copy(interpretReadAloud = enabled) } }
    }

    private fun changeSpeakers(change: (List<SpeakerSetup>) -> List<SpeakerSetup>?) {
        val changed = change(state.value.speakers) ?: return
        session.setSpeakers(changed)
        viewModelScope.launch { app.settingsStore.update { it.copy(interpretSpeakers = changed) } }
    }

    override fun onCleared() {
        session.close()
    }
}
