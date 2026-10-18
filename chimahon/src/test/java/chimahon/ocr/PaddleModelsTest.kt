package chimahon.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaddleModelsTest {
    @Test
    fun `Arabic visual output is restored without reversing Latin or numeric runs`() {
        assertEquals("مرحبا بالعالم", restorePaddleArabicText("ملاعلاب ابحرم"))
        assertEquals("123مرحبا", restorePaddleArabicText("ابحرم123"))
        assertEquals("Hello 123", restorePaddleArabicText("Hello 123"))
        assertEquals("😀مرحبا", restorePaddleArabicText("ابحرم😀"))
    }

    @Test
    fun `manga preserves its preference and cache independently of generic detector`() {
        assertEquals("paddle", PaddleRecognizer.MANGA.preference)
        assertEquals("paddle", PaddleModelSelection(PaddleRecognizer.MANGA, PaddleDetector.SMALL).cacheVariant)
        assertTrue(PaddleModelSelection(PaddleRecognizer.MANGA).bundles.isEmpty())
    }

    @Test
    fun `all six Lookitan recognizers and both detectors have independent cache identities`() {
        val selections = PaddleRecognizer.entries.filter { it != PaddleRecognizer.MANGA }.flatMap { recognizer ->
            PaddleDetector.entries.map { PaddleModelSelection(recognizer, it) }
        }
        assertEquals(12, selections.size)
        assertEquals(12, selections.map { it.cacheVariant }.toSet().size)
        assertTrue(selections.all { PADDLE_MODELS_VERSION in it.cacheVariant })
        assertEquals(9, paddleComponentBundles.size)
    }

    @Test
    fun `recognizers share detector components without sharing their dictionaries`() {
        val latin = PaddleModelSelection(PaddleRecognizer.LATIN)
        val korean = PaddleModelSelection(PaddleRecognizer.KOREAN)
        assertEquals(latin.bundles.first(), korean.bundles.first())
        assertNotEquals(latin.bundles.last(), korean.bundles.last())
        assertEquals(PaddleDetector.TINY, PaddleDetector.fromPreference("unknown"))
    }
}
