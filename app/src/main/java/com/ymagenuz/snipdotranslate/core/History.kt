package com.ymagenuz.snipdotranslate.core

import kotlinx.serialization.Serializable

const val MAX_HISTORY_ITEMS = 50

@Serializable
data class HistoryEntry(
    val time: String,
    val mode: String,
    val source: String,
    val result: String,
)

/** Puts a finished translation first, removing any identical earlier entry. */
fun withHistoryEntry(
    history: List<HistoryEntry>,
    source: String,
    result: String,
    mode: String,
    time: String,
): List<HistoryEntry> {
    val cleanSource = source.pyStrip()
    val cleanResult = result.pyStrip()
    if (cleanSource.isEmpty() || cleanResult.isEmpty()) return history

    val others = history.filter { it.source != cleanSource || it.result != cleanResult }
    val entry = HistoryEntry(time, mode.ifEmpty { TranslationMode.AUTO }, cleanSource, cleanResult)
    return (listOf(entry) + others).take(MAX_HISTORY_ITEMS)
}
