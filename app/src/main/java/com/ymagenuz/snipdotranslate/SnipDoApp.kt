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
import com.ymagenuz.snipdotranslate.domain.TranslationSession
import com.ymagenuz.snipdotranslate.update.UpdateChecker
import com.ymagenuz.snipdotranslate.update.UpdateDownloader
import java.io.File
import kotlinx.coroutines.CoroutineScope

class SnipDoApp : Application() {
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

    fun newSession(scope: CoroutineScope) = TranslationSession(
        scope = scope,
        streamer = ChatStreamer(chatClient::streamChat),
        providerAccess = ::providerAccess,
        backTranslationEnabled = { settingsStore.current().backTranslation },
        onFinished = historyStore::add,
    )

    suspend fun providerAccess(): ProviderAccess {
        val provider = settingsStore.current().resolvedProvider()
        if (provider.model.isEmpty()) return ProviderAccess.MissingModel
        val key = keyVault.read(provider.providerId)
        return if (key.isEmpty()) ProviderAccess.MissingKey else ProviderAccess.Ready(provider, key)
    }
}
