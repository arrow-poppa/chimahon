package eu.kanade.tachiyomi.ui.dictionary

import eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.orderedFullText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScreenLookupOcrTest {
    @Test
    fun `second line lookup uses reading order and the same sentence offset`() {
        val block = OcrTextBlock(
            0.1f,
            0.2f,
            0.7f,
            0.6f,
            listOf("second", "first"),
            lineGeometries = listOf(line(0.5f), line(0.2f)),
        )
        val selection = block.screenLookupSelection(0.45f, 0.525f, 1000f, 500f, "", "en")
        assertEquals("firstsecond", block.orderedFullText)
        assertEquals("second", selection?.query)
        assertEquals(5, selection?.start)
        assertEquals("second", block.orderedFullText.substring(selection!!.start, selection.end))
    }

    @Test
    fun `character lookup continues across lines without shifting its context`() {
        val block = OcrTextBlock(
            0.1f,
            0.2f,
            0.7f,
            0.6f,
            listOf("日本", "語学"),
            lineGeometries = listOf(line(0.2f), line(0.5f)),
        )
        val selection = block.screenLookupSelection(0.55f, 0.225f, 1000f, 500f, "", "ja")
        assertEquals("日本語学", block.orderedFullText)
        assertEquals("本語学", selection?.query)
        assertEquals(1, selection?.start)
    }

    @Test
    fun `rotated text uses screen dimensions to find the touched character`() {
        val block = OcrTextBlock(
            0.2f,
            0.4f,
            0.6f,
            0.48f,
            listOf("alpha"),
            lineGeometries = listOf(OcrLineGeometry(0.2f, 0.4f, 0.6f, 0.48f, 90f)),
        )
        assertEquals(0, block.screenLookupCharOffset(0.4f, 0.12f, 1000f, 500f))
        assertEquals("alpha", block.screenLookupSelection(0.4f, 0.12f, 1000f, 500f, "", "en")?.query)
    }

    @Test
    fun `whitespace does not query the neighboring word`() {
        val block = OcrTextBlock(
            0.1f,
            0.2f,
            0.7f,
            0.25f,
            listOf("one two"),
            lineGeometries = listOf(line(0.2f)),
        )
        assertNull(block.screenLookupSelection(0.4f, 0.225f, 1000f, 500f, "", "en"))
    }

    private fun line(y: Float) = OcrLineGeometry(0.1f, y, 0.7f, y + 0.05f)
}
