package com.ymagenuz.snipdotranslate.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelListAndHistoryTest {
    private fun parse(json: String) = parseModelIds(Json.parseToJsonElement(json))

    @Test
    fun modelsAreTrimmedDeduplicatedAndSorted() {
        val ids = parse(
            """{"data":[{"id":"provider/model:free"},{"id":" custom-model "},{"id":"Model-A"},{"id":"model-a"},{"id":"custom-model"}]}""",
        )
        assertEquals(listOf("Model-A", "custom-model", "model-a", "provider/model:free"), ids)
    }

    @Test
    fun malformedAndControlCharacterIdsAreIgnored() {
        val ids = parse(
            """{"data":[{"id":" valid/model "},{"id":""},{"id":"   "},{"id":null},{"id":42},{"id":[]},{"id":{}},
            {"id":"bad\nmodel"},{"id":"bad\u0000model"},{"id":"bad\u007fmodel"},{"id":"bad\u0085model"},
            {"id":"bad​model"},{"id":"\tbad-model"},{},null,"not-a-model"]}""",
        )
        assertEquals(listOf("valid/model"), ids)
    }

    @Test
    fun unusableListsExplainWhatToDo() {
        listOf("""{"data":[]}""", """{"data":[{"id":""},{"id":null}]}""", """{"data":null}""", "{}").forEach { json ->
            val error = assertThrows(IllegalArgumentException::class.java) { parse(json) }
            assertTrue("no available models" in error.message.orEmpty())
            assertTrue("Enter a model ID manually" in error.message.orEmpty())
        }
        val error = assertThrows(IllegalArgumentException::class.java) { parse("""{"data":123}""") }
        assertTrue("invalid model list" in error.message.orEmpty())
    }

    @Test
    fun historyPutsNewestFirstAndRemovesDuplicates() {
        var history = withHistoryEntry(emptyList(), " a ", " b ", "auto", "t1")
        history = withHistoryEntry(history, "c", "d", "en2zh", "t2")
        history = withHistoryEntry(history, "a", "b", "zh2en", "t3")

        assertEquals(listOf("a", "c"), history.map { it.source })
        assertEquals("t3", history[0].time)
        assertEquals("zh2en", history[0].mode)
    }

    @Test
    fun historyIgnoresEmptyEntriesAndIsCapped() {
        assertEquals(emptyList<HistoryEntry>(), withHistoryEntry(emptyList(), "  ", "b", "auto", "t"))
        assertEquals(emptyList<HistoryEntry>(), withHistoryEntry(emptyList(), "a", "  ", "auto", "t"))

        val full = (1..60).fold(emptyList<HistoryEntry>()) { history, n ->
            withHistoryEntry(history, "s$n", "r$n", "auto", "t$n")
        }
        assertEquals(MAX_HISTORY_ITEMS, full.size)
        assertEquals("s60", full.first().source)
    }
}
