package eu.kanade.tachiyomi.ui.dictionary

import chimahon.FrequencyEntry
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.PitchEntry
import chimahon.TermResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DictionaryResultOrderingTest {

    @Test
    fun `longest source match wins even without dictionary priority`() {
        val results = listOf(result("c"), result("complete"), result("com"), result("m"))

        assertEquals(
            listOf("complete", "com", "c", "m"),
            orderLookupResults(results, emptyMap()).map { it.matched },
        )
    }

    private fun result(matched: String): LookupResult = LookupResult(
        matched = matched,
        deinflected = matched,
        process = emptyArray(),
        term = TermResult(
            expression = matched,
            reading = matched,
            rules = "",
            glossaries = arrayOf(GlossaryEntry("Test", matched, "", "")),
            frequencies = emptyArray<FrequencyEntry>(),
            pitches = emptyArray<PitchEntry>(),
        ),
        preprocessorSteps = 0,
    )
}
