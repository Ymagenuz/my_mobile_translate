package com.ymagenuz.snipdotranslate.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ymagenuz.snipdotranslate.core.ApiProviderConfig
import com.ymagenuz.snipdotranslate.core.DEEPSEEK_API_PROVIDER
import com.ymagenuz.snipdotranslate.core.DEFAULT_API_PROVIDER
import com.ymagenuz.snipdotranslate.core.MAX_HISTORY_ITEMS
import java.io.File
import java.time.LocalDateTime
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoresTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() {
        scope.cancel()
    }

    private class SoftwareCipher : KeyCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun seal(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return sealWith(cipher, plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            val (iv, body) = splitSealed(sealed)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            return cipher.doFinal(body)
        }
    }

    private fun vault(cipher: KeyCipher = SoftwareCipher()) = KeyVault(folder.newFolder(), cipher)

    private fun dataStore(name: String = "settings.preferences_pb") =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, name) }

    @Test
    fun keysRoundTripPerProviderAndAreNotStoredInPlainText() = runBlocking {
        val directory = folder.newFolder()
        val keys = KeyVault(directory, SoftwareCipher())

        assertTrue(keys.write(DEFAULT_API_PROVIDER, "  sk-gptsapi-secret\n"))
        assertTrue(keys.write(DEEPSEEK_API_PROVIDER, "sk-deepseek-secret"))

        assertEquals("sk-gptsapi-secret", keys.read(DEFAULT_API_PROVIDER))
        assertEquals("sk-deepseek-secret", keys.read(DEEPSEEK_API_PROVIDER))
        assertFalse("sk-gptsapi-secret" in File(directory, DEFAULT_API_PROVIDER).readText(Charsets.ISO_8859_1))
    }

    @Test
    fun unusableKeysAreNotSaved() = runBlocking {
        val keys = vault()
        assertFalse(keys.write(DEFAULT_API_PROVIDER, "   "))
        assertFalse(keys.write(DEFAULT_API_PROVIDER, "your_api_key"))
        assertFalse(keys.write(DEFAULT_API_PROVIDER, "sk\u0000x"))
        assertEquals("", keys.read(DEFAULT_API_PROVIDER))
    }

    @Test
    fun missingOrDamagedKeysReadAsEmpty() = runBlocking {
        val directory = folder.newFolder()
        val keys = KeyVault(directory, SoftwareCipher())
        assertEquals("", keys.read(DEFAULT_API_PROVIDER))

        File(directory, DEFAULT_API_PROVIDER).writeBytes(byteArrayOf(12, 1, 2, 3))
        assertEquals("", keys.read(DEFAULT_API_PROVIDER))
    }

    @Test
    fun failedWritesDoNotExposeTheKey() {
        val broken = object : KeyCipher {
            override fun seal(plain: ByteArray): ByteArray = throw IllegalStateException(plain.decodeToString())

            override fun open(sealed: ByteArray): ByteArray = sealed
        }

        val error = assertThrows(KeyVaultException::class.java) {
            runBlocking { vault(broken).write(DEFAULT_API_PROVIDER, "sk-secret") }
        }

        assertFalse("sk-secret" in error.message.orEmpty())
    }

    @Test
    fun deletedKeysAreGone() = runBlocking {
        val keys = vault()
        keys.write(DEFAULT_API_PROVIDER, "sk-secret")
        keys.delete(DEFAULT_API_PROVIDER)
        assertEquals("", keys.read(DEFAULT_API_PROVIDER))
    }

    @Test
    fun sealedLayoutRejectsDamage() {
        assertThrows(IllegalArgumentException::class.java) { splitSealed(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { splitSealed(byteArrayOf(5, 1, 2)) }
        val (iv, body) = splitSealed(byteArrayOf(2, 7, 8, 9))
        assertArrayEquals(byteArrayOf(7, 8), iv)
        assertArrayEquals(byteArrayOf(9), body)
    }

    @Test
    fun settingsDefaultToTheDesktopDefaults() = runBlocking {
        val settings = SettingsStore(dataStore()).current()

        assertEquals(DEFAULT_API_PROVIDER, settings.apiProvider)
        assertFalse(settings.backTranslation)
        assertEquals(UI_LANGUAGE_ENGLISH, settings.uiLanguage)
        assertEquals("gpt-5.4-nano", settings.resolvedProvider().model)
    }

    @Test
    fun settingsPersistAndKeepPerProviderConfigs() = runBlocking {
        val store = SettingsStore(dataStore())
        val custom = ApiProviderConfig("https://example.org/v1", "my-model", true, false)

        store.update {
            it.copy(
                apiProvider = DEEPSEEK_API_PROVIDER,
                apiConfigs = mapOf(DEEPSEEK_API_PROVIDER to custom),
                backTranslation = true,
                uiLanguage = UI_LANGUAGE_CHINESE,
                sourceLanguage = "ja",
                targetLanguage = "en",
                lastUpdateCheckMs = 1234L,
            )
        }

        val saved = store.current()
        assertEquals(DEEPSEEK_API_PROVIDER, saved.apiProvider)
        assertEquals("my-model", saved.resolvedProvider().model)
        assertTrue(saved.resolvedProvider().supportsVision)
        assertEquals("gpt-5.4-nano", saved.resolvedProvider(DEFAULT_API_PROVIDER).model)
        assertTrue(saved.backTranslation)
        assertEquals(UI_LANGUAGE_CHINESE, saved.uiLanguage)
        assertEquals("ja", saved.sourceLanguage)
        assertEquals("en", saved.targetLanguage)
        assertEquals(1234L, saved.lastUpdateCheckMs)
    }

    @Test
    fun aBadSavedValueDoesNotDiscardTheOthers() = runBlocking {
        // One write only: DataStore's rename-over can't replace an existing file on the Windows test JVM.
        val raw = dataStore()
        val store = SettingsStore(raw)

        raw.edit {
            it[booleanPreferencesKey("back_translation")] = true
            it[stringPreferencesKey("api_provider")] = "from-a-newer-release"
            it[stringPreferencesKey("ui_language")] = "fr"
            it[stringPreferencesKey("source_language")] = "xx"
            it[stringPreferencesKey("api_configs")] = "{not json"
        }

        val settings = store.current()
        assertEquals(DEFAULT_API_PROVIDER, settings.apiProvider)
        assertEquals(UI_LANGUAGE_ENGLISH, settings.uiLanguage)
        assertEquals("auto", settings.sourceLanguage)
        assertTrue(settings.apiConfigs.isEmpty())
        assertTrue(settings.backTranslation)
    }

    @Test
    fun invalidProviderConfigsAreDroppedIndividually() = runBlocking {
        val raw = dataStore()
        raw.edit {
            it[stringPreferencesKey("api_configs")] = """{
                "gptsapi":{"baseUrl":"https://a.example/v1","model":"good","supportsVision":true},
                "deepseek":{"baseUrl":"http://insecure.example/v1","model":"m","supportsVision":false},
                "openrouter":{"baseUrl":"https://b.example/v1","model":"  ","supportsVision":false},
                "unknown":{"baseUrl":"https://c.example/v1","model":"m","supportsVision":false}
            }"""
        }

        assertEquals(setOf(DEFAULT_API_PROVIDER), SettingsStore(raw).current().apiConfigs.keys)
    }

    private fun history(file: File) = HistoryStore(file) { LocalDateTime.of(2026, 11, 9, 8, 5, 3) }

    @Test
    fun historyPersistsInTheDesktopShape() = runBlocking {
        val file = File(folder.root, "history.json")
        history(file).add("source", "result", "auto")

        val reloaded = history(file)
        reloaded.load()

        assertEquals("source", reloaded.history.value.single().source)
        assertEquals("2026-11-09 08:05:03", reloaded.history.value.single().time)
        val text = file.readText()
        listOf("\"time\"", "\"mode\"", "\"source\"", "\"result\"").forEach { assertTrue(it in text) }
    }

    @Test
    fun historyIsCappedOnLoadAndAdd() = runBlocking {
        val file = File(folder.root, "history.json")
        val store = history(file)
        (1..60).forEach { store.add("s$it", "r$it", "auto") }
        assertEquals(MAX_HISTORY_ITEMS, store.history.value.size)
        assertEquals("s60", store.history.value.first().source)
    }

    @Test
    fun damagedHistoryIsSetAsideNotOverwritten() = runBlocking {
        val file = File(folder.root, "history.json")
        file.writeText("{damaged")

        val store = history(file)
        store.add("new", "entry", "auto")

        assertEquals("{damaged", File(file.path + ".damaged").readText())
        assertEquals(listOf("new"), store.history.value.map { it.source })
    }

    @Test
    fun clearingHistoryPersists() = runBlocking {
        val file = File(folder.root, "history.json")
        val store = history(file)
        store.add("a", "b", "auto")
        store.clear()

        val reloaded = history(file)
        reloaded.load()
        assertTrue(reloaded.history.value.isEmpty())
    }
}
