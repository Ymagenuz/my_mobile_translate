package com.ymagenuz.snipdotranslate

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ymagenuz.snipdotranslate.api.ChatClient
import com.ymagenuz.snipdotranslate.api.createHttpClient
import com.ymagenuz.snipdotranslate.data.AndroidKeystoreCipher
import com.ymagenuz.snipdotranslate.data.HistoryStore
import com.ymagenuz.snipdotranslate.data.KeyVault
import com.ymagenuz.snipdotranslate.data.SettingsStore
import com.ymagenuz.snipdotranslate.domain.ChatStreamer
import com.ymagenuz.snipdotranslate.domain.ProviderAccess
import com.ymagenuz.snipdotranslate.domain.SpeechInput
import com.ymagenuz.snipdotranslate.domain.SpeechOutput
import com.ymagenuz.snipdotranslate.domain.TranslationSession
import com.ymagenuz.snipdotranslate.speech.ModelSpeechInput
import com.ymagenuz.snipdotranslate.speech.SystemSpeechInput
import com.ymagenuz.snipdotranslate.speech.transcribeWithModel
import com.ymagenuz.snipdotranslate.speech.SystemSpeechOutput
import com.ymagenuz.snipdotranslate.update.UpdateChecker
import com.ymagenuz.snipdotranslate.update.UpdateDownloader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SnipDoApp : Application() {
    // For small writes that must finish after the screen or service that asked has gone.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http by lazy { createHttpClient() }
    val chatClient by lazy { ChatClient(http) }
    val updateChecker by lazy { UpdateChecker(http, BuildConfig.VERSION_NAME) }
    val updateDownloader by lazy { UpdateDownloader(http, BuildConfig.VERSION_NAME) }
    val settingsStore by lazy {
        SettingsStore(
            PreferenceDataStoreFactory.create { File(filesDir, "datastore/settings.preferences_pb") },
            allowLocalHttp = BuildConfig.ALLOW_LOCAL_HTTP,
        )
    }
    val keyVault by lazy { KeyVault(File(noBackupFilesDir, "keys"), AndroidKeystoreCipher()) }
    val historyStore by lazy { HistoryStore(File(filesDir, "translation_history.json")) }

    // Debug builds can swap these to script a conversation on an emulator without a microphone.
    var speechInputFactory: () -> SpeechInput = { SystemSpeechInput(this) }
    var speechOutputFactory: () -> SpeechOutput = { SystemSpeechOutput(this) }

    /** Hears languages the phone can't, through the translation model, when it takes audio. */
    var modelSpeechInputFactory: () -> SpeechInput = {
        ModelSpeechInput(this, transcribe = { wav, language -> transcribeWithModel(this, wav, language) })
    }

    fun newSession(scope: CoroutineScope) = TranslationSession(
        scope = scope,
        streamer = ChatStreamer(chatClient::streamChat),
        providerAccess = ::providerAccess,
        backTranslationEnabled = { settingsStore.current().backTranslation },
        onFinished = historyStore::add,
    )

    /** Remembers whether a Quick Settings tile is added, for the setup card and Settings. */
    fun noteTile(selectOnScreen: Boolean, added: Boolean) {
        appScope.launch {
            settingsStore.update {
                when {
                    selectOnScreen && it.selectTileAdded != added -> it.copy(selectTileAdded = added)
                    !selectOnScreen && it.clipboardTileAdded != added -> it.copy(clipboardTileAdded = added)
                    else -> it
                }
            }
        }
    }

    suspend fun providerAccess(): ProviderAccess {
        val provider = settingsStore.current().resolvedProvider()
        if (provider.model.isEmpty()) return ProviderAccess.MissingModel
        val key = keyVault.read(provider.providerId)
        return if (key.isEmpty()) ProviderAccess.MissingKey else ProviderAccess.Ready(provider, key)
    }
}
