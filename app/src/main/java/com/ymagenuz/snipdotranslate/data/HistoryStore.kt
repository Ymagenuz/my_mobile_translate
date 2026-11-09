package com.ymagenuz.snipdotranslate.data

import com.ymagenuz.snipdotranslate.core.HistoryEntry
import com.ymagenuz.snipdotranslate.core.MAX_HISTORY_ITEMS
import com.ymagenuz.snipdotranslate.core.withHistoryEntry
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/** Finished translations, newest first, in the same JSON shape as the desktop app. */
class HistoryStore(
    private val file: File,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private var loaded = false

    val history: StateFlow<List<HistoryEntry>> = entries.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock { ensureLoaded() }
    }

    suspend fun add(source: String, result: String, mode: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoaded()
            save(withHistoryEntry(entries.value, source, result, mode, clock().format(TIME_FORMAT)))
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoaded()
            save(emptyList())
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try {
            entries.value = json.decodeFromString<List<HistoryEntry>>(file.readText()).take(MAX_HISTORY_ITEMS)
        } catch (_: SerializationException) {
            setAside()
        } catch (_: IllegalArgumentException) {
            setAside()
        }
    }

    /** A damaged history is kept beside the new one rather than being overwritten. */
    private fun setAside() {
        file.renameTo(File(file.path + ".damaged"))
    }

    private fun save(updated: List<HistoryEntry>) {
        writeAtomically(file, json.encodeToString(updated).encodeToByteArray())
        entries.value = updated
    }
}
