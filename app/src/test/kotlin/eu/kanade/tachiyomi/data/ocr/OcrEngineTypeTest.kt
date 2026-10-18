package eu.kanade.tachiyomi.data.ocr

import chimahon.ocr.PaddleDetector
import chimahon.ocr.PaddleRecognizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class OcrEngineTypeTest {

    @Test
    fun `local preference selects local OCR`() {
        assertEquals(OcrEngineType.LOCAL, OcrEngineType.fromPreference("local"))
    }

    @Test
    fun `old Paddle preference still uses Manga and new preferences resolve every recognizer`() {
        assertEquals(PaddleRecognizer.MANGA, OcrEngineType.fromPreference("paddle").paddleRecognizer)
        for (recognizer in PaddleRecognizer.entries) {
            assertEquals(recognizer, OcrEngineType.fromPreference(recognizer.preference).paddleRecognizer)
        }
        assertNotEquals(
            OcrSelection(OcrEngineType.PADDLE_KOREAN, PaddleDetector.TINY).cacheVariant,
            OcrSelection(OcrEngineType.PADDLE_KOREAN, PaddleDetector.SMALL).cacheVariant,
        )
    }

    @Test
    fun `other preferences select Google Lens`() {
        assertEquals(OcrEngineType.CLOUD, OcrEngineType.fromPreference("cloud"))
        assertEquals(OcrEngineType.CLOUD, OcrEngineType.fromPreference("unexpected"))
    }
}
