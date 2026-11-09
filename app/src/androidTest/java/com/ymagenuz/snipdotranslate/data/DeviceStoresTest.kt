package com.ymagenuz.snipdotranslate.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.ymagenuz.snipdotranslate.core.DEEPSEEK_API_PROVIDER
import com.ymagenuz.snipdotranslate.core.DEFAULT_API_PROVIDER
import com.ymagenuz.snipdotranslate.core.MAX_HISTORY_ITEMS
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Storage behaviour that only the real Android platform can show. */
class DeviceStoresTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "device-tests-${UUID.randomUUID()}").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun keysRoundTripThroughTheRealKeystore() = runBlocking {
        val directory = File(root, "keys")
        val vault = KeyVault(directory, AndroidKeystoreCipher("snipdo_device_test"))

        assertTrue(vault.write(DEFAULT_API_PROVIDER, "sk-device-secret"))
        assertTrue(vault.write(DEEPSEEK_API_PROVIDER, "sk-other-secret"))
        assertTrue(vault.write(DEFAULT_API_PROVIDER, "sk-replaced-secret"))

        assertEquals("sk-replaced-secret", vault.read(DEFAULT_API_PROVIDER))
        assertEquals("sk-other-secret", vault.read(DEEPSEEK_API_PROVIDER))
        assertFalse("sk-replaced" in File(directory, DEFAULT_API_PROVIDER).readText(Charsets.ISO_8859_1))

        val reopened = KeyVault(directory, AndroidKeystoreCipher("snipdo_device_test"))
        assertEquals("sk-replaced-secret", reopened.read(DEFAULT_API_PROVIDER))
    }

    @Test
    fun settingsCanBeSavedRepeatedly() = runBlocking {
        val store = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") },
        )

        store.update { it.copy(backTranslation = true) }
        store.update { it.copy(uiLanguage = UI_LANGUAGE_CHINESE) }
        store.update { it.copy(sourceLanguage = "ja") }

        val saved = store.current()
        assertTrue(saved.backTranslation)
        assertEquals(UI_LANGUAGE_CHINESE, saved.uiLanguage)
        assertEquals("ja", saved.sourceLanguage)
    }

    @Test
    fun historySurvivesRepeatedAtomicWrites() = runBlocking {
        val file = File(root, "history.json")
        val store = HistoryStore(file)
        (1..60).forEach { store.add("s$it", "r$it", "auto") }

        val reloaded = HistoryStore(file)
        reloaded.load()

        assertEquals(MAX_HISTORY_ITEMS, reloaded.history.value.size)
        assertEquals("s60", reloaded.history.value.first().source)
    }
}
