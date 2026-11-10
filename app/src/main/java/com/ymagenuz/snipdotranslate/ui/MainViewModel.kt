package com.ymagenuz.snipdotranslate.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ymagenuz.snipdotranslate.BuildConfig
import com.ymagenuz.snipdotranslate.SnipDoApp
import com.ymagenuz.snipdotranslate.core.API_PROVIDERS
import com.ymagenuz.snipdotranslate.core.ApiProviderConfig
import com.ymagenuz.snipdotranslate.core.HistoryEntry
import com.ymagenuz.snipdotranslate.core.ReleaseInfo
import com.ymagenuz.snipdotranslate.core.TranslationMode
import com.ymagenuz.snipdotranslate.core.apiProvider
import com.ymagenuz.snipdotranslate.core.isDownloadableRelease
import com.ymagenuz.snipdotranslate.core.isPlaceholderApiKey
import com.ymagenuz.snipdotranslate.core.isUpdateCheckDue
import com.ymagenuz.snipdotranslate.core.prepareSecretForWrite
import com.ymagenuz.snipdotranslate.core.providerDefaultConfig
import com.ymagenuz.snipdotranslate.core.validatedProviderConfig
import com.ymagenuz.snipdotranslate.data.AppSettings
import com.ymagenuz.snipdotranslate.data.KeyVaultException
import com.ymagenuz.snipdotranslate.domain.TranslationRequest
import com.ymagenuz.snipdotranslate.update.UpdateInstaller
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the user is editing for one provider, kept when switching between providers. */
data class ApiDraft(
    val baseUrl: String,
    val model: String,
    val supportsVision: Boolean,
    val disableThinking: Boolean,
    val apiKey: String,
)

enum class Route { TRANSLATE, HISTORY, SETTINGS }

sealed interface UpdateStatus {
    data object Idle : UpdateStatus

    data object Checking : UpdateStatus

    data object Finished : UpdateStatus

    data class Failed(val message: String) : UpdateStatus
}

/** An in-app update download, from tapping Update now to handing it to Android's installer. */
sealed interface DownloadState {
    data object Idle : DownloadState

    data class Downloading(val percent: Int) : DownloadState

    data class Ready(val file: File) : DownloadState

    data class Failed(val message: String) : DownloadState

    // The file downloaded and matched its checksum but isn't a newer build of this app.
    data object Rejected : DownloadState
}

sealed interface SaveResult {
    data object Saved : SaveResult

    data class InvalidConfig(val providerName: String) : SaveResult

    data class InvalidKey(val providerName: String) : SaveResult

    data object KeyNotSaved : SaveResult
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SnipDoApp
    val session = app.newSession(viewModelScope)
    val state = session.state
    val history = app.historyStore.history
    val settings = app.settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    var input by mutableStateOf("")
    var forceDictionary by mutableStateOf(false)

    /** Kept here so that rotating or changing language stays on the same page. */
    var route by mutableStateOf(Route.TRANSLATE)
    var update by mutableStateOf<ReleaseInfo?>(null)
        private set
    var updateStatus by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        private set
    var download by mutableStateOf<DownloadState>(DownloadState.Idle)
        private set
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch { app.historyStore.load() }
        viewModelScope.launch(Dispatchers.IO) { UpdateInstaller.removeStale(app, BuildConfig.VERSION_NAME) }
        viewModelScope.launch {
            val saved = app.settingsStore.current()
            if (saved.updateWasAvailable || isUpdateCheckDue(saved.lastUpdateCheckMs, System.currentTimeMillis())) {
                runUpdateCheck(showStatus = false)
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch { runUpdateCheck(showStatus = true) }
    }

    private suspend fun runUpdateCheck(showStatus: Boolean) {
        if (showStatus) updateStatus = UpdateStatus.Checking
        try {
            val release = app.updateChecker.check()
            update = release
            app.settingsStore.update {
                it.copy(lastUpdateCheckMs = System.currentTimeMillis(), updateWasAvailable = release != null)
            }
            if (showStatus) updateStatus = UpdateStatus.Finished
        } catch (failure: IOException) {
            // Background checks stay silent; only a manual check reports a problem.
            if (showStatus) updateStatus = UpdateStatus.Failed(failure.message.orEmpty())
        }
    }

    /** Downloads the available update and checks it, ready for Android's installer. */
    fun downloadUpdate() {
        val release = update ?: return
        if (download is DownloadState.Downloading) return
        if (!isDownloadableRelease(release)) {
            download = DownloadState.Rejected
            return
        }
        downloadJob?.cancel()
        download = DownloadState.Downloading(0)
        downloadJob = viewModelScope.launch {
            val job = coroutineContext.job
            val file = UpdateInstaller.fileFor(app, release.version)
            val result = try {
                app.updateDownloader.download(release, file) { received, total ->
                    val percent = (received * 100 / total).toInt()
                    val shown = download
                    // From the download thread; a cancelled download mustn't come back.
                    if (job.isActive && (shown !is DownloadState.Downloading || shown.percent != percent)) {
                        download = DownloadState.Downloading(percent)
                    }
                }
                if (withContext(Dispatchers.IO) { UpdateInstaller.isUpdateFor(app, file) }) {
                    DownloadState.Ready(file)
                } else {
                    file.delete()
                    DownloadState.Rejected
                }
            } catch (failure: IOException) {
                DownloadState.Failed(failure.message.orEmpty())
            }
            // Cancel already showed the plain button again.
            if (job.isActive) download = result
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        download = DownloadState.Idle
    }

    fun setUiLanguage(code: String) {
        UiLanguage.save(app, code)
        updateSettings { it.copy(uiLanguage = code) }
    }

    fun translate() {
        val current = settings.value
        session.translate(TranslationRequest(input, forceDictionary, current.sourceLanguage, current.targetLanguage))
    }

    fun clearInput() {
        input = ""
        session.clear()
    }

    fun setSourceLanguage(code: String) = updateSettings { it.copy(sourceLanguage = code) }

    fun setTargetLanguage(code: String) = updateSettings { it.copy(targetLanguage = code) }

    fun setBackTranslation(enabled: Boolean) {
        viewModelScope.launch {
            app.settingsStore.update { it.copy(backTranslation = enabled) }
            session.onBackTranslationToggled(enabled)
        }
    }

    fun openHistoryEntry(entry: HistoryEntry) {
        val current = settings.value
        input = entry.source
        forceDictionary = entry.mode == TranslationMode.DICTIONARY
        session.showFinished(
            TranslationRequest(entry.source, forceDictionary, current.sourceLanguage, current.targetLanguage),
            entry.mode,
            entry.result,
        )
    }

    /** Takes over text from the popup, showing its result if it had finished. */
    fun receive(source: String, result: String?, mode: String?) {
        if (result == null || mode == null) {
            input = source
            session.clear()
        } else {
            openHistoryEntry(HistoryEntry(time = "", mode = mode, source = source, result = result))
        }
    }

    fun clearHistory() {
        viewModelScope.launch { app.historyStore.clear() }
    }

    suspend fun loadDrafts(): Map<String, ApiDraft> {
        val current = app.settingsStore.current()
        return API_PROVIDERS.associate { provider ->
            val config = current.configFor(provider.providerId)
            provider.providerId to ApiDraft(
                config.baseUrl,
                config.model,
                config.supportsVision,
                config.disableThinking,
                app.keyVault.read(provider.providerId),
            )
        }
    }

    suspend fun fetchModels(providerId: String, draft: ApiDraft): List<String> {
        val config = validatedProviderConfig(
            draft.baseUrl,
            draft.model,
            draft.supportsVision,
            draft.disableThinking,
            BuildConfig.ALLOW_LOCAL_HTTP,
        )
        return app.chatClient.listModels(apiProvider(providerId, config), draft.apiKey.trim())
    }

    suspend fun saveApi(selected: String, drafts: Map<String, ApiDraft>): SaveResult {
        val configs = mutableMapOf<String, ApiProviderConfig>()
        for (provider in API_PROVIDERS) {
            val draft = drafts.getValue(provider.providerId)
            val config = try {
                validatedProviderConfig(
                    draft.baseUrl,
                    draft.model,
                    draft.supportsVision,
                    draft.disableThinking,
                    BuildConfig.ALLOW_LOCAL_HTTP,
                ).takeIf { it.model.isNotEmpty() }
            } catch (_: IllegalArgumentException) {
                null
            }
            when {
                config == null && provider.providerId == selected -> return SaveResult.InvalidConfig(provider.shortName)
                config != null && config != providerDefaultConfig(provider.providerId) ->
                    configs[provider.providerId] = config
            }
        }

        for (provider in API_PROVIDERS) {
            val key = drafts.getValue(provider.providerId).apiKey
            if (key.isNotBlank() && (prepareSecretForWrite(key) == null || isPlaceholderApiKey(key))) {
                return SaveResult.InvalidKey(provider.shortName)
            }
        }
        try {
            for (provider in API_PROVIDERS) {
                val key = drafts.getValue(provider.providerId).apiKey
                if (key.isBlank()) {
                    app.keyVault.delete(provider.providerId)
                } else if (key.trim() != app.keyVault.read(provider.providerId)) {
                    app.keyVault.write(provider.providerId, key)
                }
            }
        } catch (_: KeyVaultException) {
            return SaveResult.KeyNotSaved
        }

        app.settingsStore.update { it.copy(apiProvider = selected, apiConfigs = configs) }
        return SaveResult.Saved
    }

    private fun updateSettings(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { app.settingsStore.update(change) }
    }
}
