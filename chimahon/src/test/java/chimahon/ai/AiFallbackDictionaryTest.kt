package chimahon.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiFallbackDictionaryTest {

    @Test
    fun `fallback keeps a whole Latin word exportable`() {
        val token = getAiFallbackToken("complete the task", "en")
        val result = createAiFallbackLookupResult(requireNotNull(token))

        assertEquals("complete", result.matched)
        assertEquals("complete", result.term.expression)
        assertEquals("complete", result.term.reading)
        assertEquals(AI_FALLBACK_DICTIONARY_NAME, result.term.glossaries.single().dictName)
        assertTrue(result.isAiFallbackDictionaryEntry())
    }

    @Test
    fun `fallback preserves apostrophes and strips edge separators`() {
        assertEquals("don't", getAiFallbackToken("don't stop", "en")?.term)
        assertEquals("l'homme", getAiFallbackToken("l'homme arrive", "fr")?.term)
        assertEquals("word", getAiFallbackToken("-word- next", "en")?.term)
    }

    @Test
    fun `fallback does not swallow scripts without word boundaries`() {
        assertNull(getAiFallbackToken("日本語の文", "ja"))
        assertNull(getAiFallbackToken("中文句子", "zh-Hans"))
    }

    @Test
    fun `AI explanation is attached only to an export copy`() {
        val original = createAiFallbackLookupResult(requireNotNull(getAiFallbackToken("complete", "en")))
        val exported = original.withAiFallbackExplanation("Adjective meaning finished.")

        assertEquals("", original.term.glossaries.single().glossary)
        assertEquals("Adjective meaning finished.", exported.term.glossaries.single().glossary)
        assertFalse(original === exported)
    }
}
