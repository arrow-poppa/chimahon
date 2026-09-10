package chimahon.dictionary

import chimahon.GlossaryEntry
import chimahon.TermResult
import chimahon.anki.AnkiProfile
import chimahon.dictionary.english.EnglishDeinflector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LookupSourceCandidatesTest {

    @Test
    fun `word resolution never shortens one word to a character prefix`() {
        assertEquals(
            listOf("BikBik"),
            lookupSourceCandidates("BikBik", AnkiProfile.SEARCH_RESOLUTION_WORD, "en"),
        )
    }

    @Test
    fun `letter resolution retains longest to shortest prefix lookup`() {
        assertEquals(
            listOf("BikBik", "BikBi", "BikB", "Bik", "Bi", "B"),
            lookupSourceCandidates("BikBik", AnkiProfile.SEARCH_RESOLUTION_LETTER, "en"),
        )
    }

    @Test
    fun `word resolution removes complete trailing words like Yomitan`() {
        assertEquals(
            listOf("complete this task", "complete this", "complete"),
            lookupSourceCandidates("complete this task", AnkiProfile.SEARCH_RESOLUTION_WORD, "en"),
        )
    }

    @Test
    fun `bi dictionary entry cannot satisfy BikBik in word mode`() {
        val sources = lookupSourceCandidates("BikBik", AnkiProfile.SEARCH_RESOLUTION_WORD, "en")
        val results = lookupExactSources(sources, "en", EnglishDeinflector, 20, ::fakeExactQuery)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `bi dictionary entry can satisfy BikBik in letter mode`() {
        val sources = lookupSourceCandidates("BikBik", AnkiProfile.SEARCH_RESOLUTION_LETTER, "en")
        val results = lookupExactSources(sources, "en", EnglishDeinflector, 20, ::fakeExactQuery)

        assertEquals("Bi", results.single().matched)
        assertEquals("bi", results.single().deinflected)
    }

    private fun fakeExactQuery(candidate: String): List<TermResult> {
        if (candidate != "bi") return emptyList()
        return listOf(
            TermResult(
                expression = "bi",
                reading = "bi",
                rules = "",
                glossaries = arrayOf(GlossaryEntry("Test", "prefix", "", "")),
                frequencies = emptyArray(),
                pitches = emptyArray(),
            ),
        )
    }
}
