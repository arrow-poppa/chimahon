package eu.kanade.tachiyomi.ui.player.controls

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeSubtitleGeometryTest {

    @Test
    fun `middle of a latin word selects the complete token`() {
        val request = lookup("complete this", tapOffset = 4, languageCode = "en")

        assertNotNull(request)
        assertEquals("complete", request?.lookupString)
        assertEquals(0, request?.charOffset)
        assertEquals(4, request?.tapCharOffset)
    }

    @Test
    fun `explicit character scanning starts at the tapped character`() {
        val request = lookup("complete this", tapOffset = 4, languageCode = "en", scanResolution = "character")

        assertEquals("lete", request?.lookupString)
        assertEquals(4, request?.charOffset)
    }

    @Test
    fun `explicit word scanning overrides automatic CJK scanning`() {
        val request = lookup("complete this", tapOffset = 4, languageCode = "ja", scanResolution = "word")

        assertEquals("complete", request?.lookupString)
        assertEquals(0, request?.charOffset)
    }

    @Test
    fun `automatic CJK scanning preserves native character lookup`() {
        val request = lookup("日本語", tapOffset = 1, languageCode = "ja")

        assertEquals("本語", request?.lookupString)
        assertEquals(1, request?.charOffset)
    }

    @Test
    fun `word scanning preserves internal apostrophes and hyphens`() {
        assertEquals("l'esprit", lookup("l'esprit", tapOffset = 1, languageCode = "fr")?.lookupString)
        assertEquals("bem-estar", lookup("bem-estar", tapOffset = 3, languageCode = "pt-BR")?.lookupString)
    }

    @Test
    fun `word selection preserves native timing track identity and glyph offsets`() {
        val request = lookup("say complete", tapOffset = 8, languageCode = "en")

        assertEquals("complete", request?.lookupString)
        assertEquals(4, request?.charOffset)
        assertEquals(8, request?.tapCharOffset)
        assertEquals(2.0, request?.cueStartSeconds)
        assertEquals(3.5, request?.cueEndSeconds)
        assertEquals("secondary-sub-text-geometry:7:2.0:3.5", request?.nativeCueKey)
        val highlights = request?.nativeRectangles?.mergedHighlightBounds(4, 12)
        assertEquals(1, highlights?.size)
        assertEquals(40f, highlights?.single()?.left)
        assertEquals(120f, highlights?.single()?.right)
    }

    @Test
    fun `spaces punctuation and taps outside glyphs do not trigger lookup`() {
        assertNull(lookup("a b", tapOffset = 1, languageCode = "en"))
        assertNull(lookup("a,b", tapOffset = 1, languageCode = "en"))
        assertNull(geometry("hello").lookupAt(-5f, 5f, 1000, 100, "sub-text-geometry"))
    }

    @Test
    fun `scaled geometry keeps word selection aligned with the tapped glyph`() {
        val request = geometry("complete").lookupAt(
            x = 90f,
            y = 10f,
            width = 2000,
            height = 200,
            track = "sub-text-geometry",
            languageCode = "en",
        )

        assertEquals("complete", request?.lookupString)
        assertEquals(4, request?.tapCharOffset)
        assertEquals(160f, request?.anchorWidth)
    }

    private fun lookup(
        text: String,
        tapOffset: Int,
        languageCode: String,
        scanResolution: String = "",
    ): SubtitleLookupRequest? = geometry(text).lookupAt(
        x = tapOffset * 10f + 5f,
        y = 5f,
        width = 1000,
        height = 100,
        track = "secondary-sub-text-geometry",
        scanResolution = scanResolution,
        languageCode = languageCode,
    )

    private fun geometry(text: String) = NativeSubtitleGeometry(
        version = 1,
        w = 1000,
        h = 100,
        events = listOf(
            NativeSubtitleEvent(
                id = 7,
                text = text,
                start = 2.0,
                end = 3.5,
                spans = text.indices.map { NativeSubtitleSpan(it, it + 1, it * 10, 0, 10, 10) },
            ),
        ),
    )
}
