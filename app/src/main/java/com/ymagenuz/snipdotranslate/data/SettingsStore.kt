package com.ymagenuz.snipdotranslate.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ymagenuz.snipdotranslate.core.API_PROVIDERS
import com.ymagenuz.snipdotranslate.core.ApiProviderConfig
import com.ymagenuz.snipdotranslate.core.ApiProviderSpec
import com.ymagenuz.snipdotranslate.core.DEFAULT_API_PROVIDER
import com.ymagenuz.snipdotranslate.core.SOURCE_AUTO
import com.ymagenuz.snipdotranslate.core.SOURCE_LANGUAGES
import com.ymagenuz.snipdotranslate.core.TARGET_DEFAULT
import com.ymagenuz.snipdotranslate.core.TARGET_LANGUAGES
import com.ymagenuz.snipdotranslate.core.apiProvider
import com.ymagenuz.snipdotranslate.core.providerDefaultConfig
import com.ymagenuz.snipdotranslate.core.validatedProviderConfig
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val UI_LANGUAGE_ENGLISH = "en"
const val UI_LANGUAGE_CHINESE = "zh-CN"
val UI_LANGUAGES = setOf(UI_LANGUAGE_ENGLISH, UI_LANGUAGE_CHINESE)

data class AppSettings(
    val apiProvider: String = DEFAULT_API_PROVIDER,
    val apiConfigs: Map<String, ApiProviderConfig> = emptyMap(),
    val backTranslation: Boolean = false,
    val uiLanguage: String = UI_LANGUAGE_ENGLISH,
    val sourceLanguage: String = SOURCE_AUTO,
    val targetLanguage: String = TARGET_DEFAULT,
    val lastUpdateCheckMs: Long? = null,
    val updateWasAvailable: Boolean = false,
    val plusBrushDp: Float = 16f,
    val minusBrushDp: Float = 20f,
    val toolbarX: Float = -1f,
    val toolbarY: Float = -1f,
) {
    fun configFor(providerId: String): ApiProviderConfig =
        apiConfigs[providerId] ?: providerDefaultConfig(providerId)

    fun resolvedProvider(providerId: String = apiProvider): ApiProviderSpec =
        apiProvider(providerId, configFor(providerId))
}

@Serializable
private data class StoredConfig(
    val baseUrl: String,
    val model: String,
    val supportsVision: Boolean,
    val disableThinking: Boolean = false,
)

/** Saved settings. A bad value falls back to its default without losing the others. */
class SettingsStore(
    private val store: DataStore<Preferences>,
    private val allowLocalHttp: Boolean = false,
) {
    val settings: Flow<AppSettings> = store.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map(::decode)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { preferences -> encode(transform(decode(preferences)), preferences) }
    }

    private fun decode(preferences: Preferences) = AppSettings(
        apiProvider = preferences[API_PROVIDER]
            ?.takeIf { id -> API_PROVIDERS.any { it.providerId == id } }
            ?: DEFAULT_API_PROVIDER,
        apiConfigs = decodeConfigs(preferences[API_CONFIGS]),
        backTranslation = preferences[BACK_TRANSLATION] ?: false,
        uiLanguage = preferences[UI_LANGUAGE]?.takeIf { it in UI_LANGUAGES } ?: UI_LANGUAGE_ENGLISH,
        sourceLanguage = preferences[SOURCE_LANGUAGE]
            ?.takeIf { code -> SOURCE_LANGUAGES.any { it.code == code } }
            ?: SOURCE_AUTO,
        targetLanguage = preferences[TARGET_LANGUAGE]
            ?.takeIf { code -> TARGET_LANGUAGES.any { it.code == code } }
            ?: TARGET_DEFAULT,
        lastUpdateCheckMs = preferences[LAST_UPDATE_CHECK],
        updateWasAvailable = preferences[UPDATE_WAS_AVAILABLE] ?: false,
        plusBrushDp = preferences[PLUS_BRUSH] ?: 16f,
        minusBrushDp = preferences[MINUS_BRUSH] ?: 20f,
        toolbarX = preferences[TOOLBAR_X] ?: -1f,
        toolbarY = preferences[TOOLBAR_Y] ?: -1f,
    )

    private fun decodeConfigs(text: String?): Map<String, ApiProviderConfig> {
        if (text == null) return emptyMap()
        val stored = try {
            JSON.decodeFromString<Map<String, StoredConfig>>(text)
        } catch (_: SerializationException) {
            return emptyMap()
        } catch (_: IllegalArgumentException) {
            return emptyMap()
        }
        return stored.mapNotNull { (providerId, config) ->
            if (API_PROVIDERS.none { it.providerId == providerId }) return@mapNotNull null
            try {
                val validated = validatedProviderConfig(
                    config.baseUrl,
                    config.model,
                    config.supportsVision,
                    config.disableThinking,
                    allowLocalHttp,
                )
                if (validated.model.isEmpty()) null else providerId to validated
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toMap()
    }

    private fun encode(settings: AppSettings, preferences: MutablePreferences) {
        preferences[API_PROVIDER] = settings.apiProvider
        preferences[API_CONFIGS] = JSON.encodeToString(
            settings.apiConfigs.mapValues { (_, config) ->
                StoredConfig(config.baseUrl, config.model, config.supportsVision, config.disableThinking)
            },
        )
        preferences[BACK_TRANSLATION] = settings.backTranslation
        preferences[UI_LANGUAGE] = settings.uiLanguage
        preferences[SOURCE_LANGUAGE] = settings.sourceLanguage
        preferences[TARGET_LANGUAGE] = settings.targetLanguage
        preferences[UPDATE_WAS_AVAILABLE] = settings.updateWasAvailable
        preferences[PLUS_BRUSH] = settings.plusBrushDp
        preferences[MINUS_BRUSH] = settings.minusBrushDp
        preferences[TOOLBAR_X] = settings.toolbarX
        preferences[TOOLBAR_Y] = settings.toolbarY
        val lastCheck = settings.lastUpdateCheckMs
        if (lastCheck == null) preferences.remove(LAST_UPDATE_CHECK) else preferences[LAST_UPDATE_CHECK] = lastCheck
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val API_PROVIDER = stringPreferencesKey("api_provider")
        val API_CONFIGS = stringPreferencesKey("api_configs")
        val BACK_TRANSLATION = booleanPreferencesKey("back_translation")
        val UI_LANGUAGE = stringPreferencesKey("ui_language")
        val SOURCE_LANGUAGE = stringPreferencesKey("source_language")
        val TARGET_LANGUAGE = stringPreferencesKey("target_language")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_ms")
        val UPDATE_WAS_AVAILABLE = booleanPreferencesKey("update_was_available")
        val PLUS_BRUSH = floatPreferencesKey("plus_brush_dp")
        val MINUS_BRUSH = floatPreferencesKey("minus_brush_dp")
        val TOOLBAR_X = floatPreferencesKey("toolbar_x")
        val TOOLBAR_Y = floatPreferencesKey("toolbar_y")
    }
}
