package eu.kanade.tachiyomi.ui.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiExplanationContentTest {

    @Test
    fun `renderer payload preserves plain text and collapse state`() {
        val text = "<script>alert('test')</script>\nQuoted \"word\" and 日本語"
        val payload = AiExplanationContent(
            lookupId = "lookup",
            text = text,
            loading = true,
            error = null,
            initiallyCollapsed = true,
            onAction = {},
        ).toJson()

        assertEquals("lookup", payload.getString("key"))
        assertEquals(text, payload.getString("text"))
        assertTrue(payload.getBoolean("loading"))
        assertTrue(payload.getBoolean("initiallyCollapsed"))
        assertEquals("", payload.getString("error"))
        assertFalse(payload.has("onAction"))
    }

    @Test
    fun `renderer payload exposes errors for retry`() {
        val payload = AiExplanationContent(
            lookupId = "lookup",
            text = "",
            loading = false,
            error = "Connection failed",
            initiallyCollapsed = false,
            onAction = {},
        ).toJson()

        assertEquals("Connection failed", payload.getString("error"))
        assertFalse(payload.getBoolean("loading"))
        assertFalse(payload.getBoolean("initiallyCollapsed"))
    }
}
