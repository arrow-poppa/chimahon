package chimahon.anki

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnkiProfileAiTest {

    @Test
    fun `fresh AI defaults match Yomitan`() {
        val profile = AnkiProfile(id = "fresh", name = "Fresh")

        assertEquals("gpt-4o-mini", profile.aiOpenAiModel)
        assertEquals(0.7f, profile.aiTemperature)
        assertEquals(true, profile.aiAutoGenerate)
        assertFalse(profile.aiStreamResponse)
        assertTrue(profile.aiCancelPendingRequests)
    }

    @Test
    fun `custom AI settings survive profile serialization`() {
        val original = AnkiProfile(
            id = "profile",
            name = "Profile",
            aiProvider = AnkiProfile.AI_PROVIDER_CUSTOM,
            aiCustomEndpoint = "https://openrouter.ai/api/v1/chat/completions",
            aiCustomModel = "provider/model",
            aiCustomProviderRoutingMode = AnkiProfile.ROUTING_ONLY,
            aiCustomProviderSlugs = "deepinfra/turbo",
            aiCustomProviderAllowFallbacks = false,
            aiCustomThinkingMode = AnkiProfile.THINKING_ENABLED,
            aiCustomThinkingIntensity = AnkiProfile.THINKING_INTENSITY_CUSTOM,
            aiCustomThinkingIntensityValue = "2000",
            aiCustomRequestBodyJson = "{\"reasoning\":{\"exclude\":false}}",
            aiStreamResponse = true,
            aiCancelPendingRequests = false,
        )

        val restored = AnkiProfile.fromJson(original.toJson())

        assertEquals(AnkiProfile.AI_PROVIDER_CUSTOM, restored.aiProvider)
        assertEquals(original.aiCustomEndpoint, restored.aiCustomEndpoint)
        assertEquals(original.aiCustomModel, restored.aiCustomModel)
        assertEquals(original.aiCustomProviderRoutingMode, restored.aiCustomProviderRoutingMode)
        assertEquals(original.aiCustomProviderSlugs, restored.aiCustomProviderSlugs)
        assertFalse(restored.aiCustomProviderAllowFallbacks)
        assertEquals(original.aiCustomThinkingMode, restored.aiCustomThinkingMode)
        assertEquals(original.aiCustomThinkingIntensity, restored.aiCustomThinkingIntensity)
        assertEquals(original.aiCustomThinkingIntensityValue, restored.aiCustomThinkingIntensityValue)
        assertEquals(original.aiCustomRequestBodyJson, restored.aiCustomRequestBodyJson)
        assertTrue(restored.aiStreamResponse)
        assertFalse(restored.aiCancelPendingRequests)
    }

    @Test
    fun `legacy compatible provider is migrated without losing model or endpoint`() {
        val restored = AnkiProfile.fromJson(
            JSONObject()
                .put("id", "legacy")
                .put("name", "Legacy")
                .put("aiProvider", AnkiProfile.AI_PROVIDER_OPENAI_COMPATIBLE)
                .put("aiModel", "legacy-model")
                .put("aiEndpoint", "https://legacy.test/v1/chat/completions"),
        )

        assertEquals(AnkiProfile.AI_PROVIDER_CUSTOM, restored.aiProvider)
        assertEquals("legacy-model", restored.aiCustomModel)
        assertEquals("https://legacy.test/v1/chat/completions", restored.aiCustomEndpoint)
    }

    @Test
    fun `legacy OpenAI profile with third party endpoint migrates to Custom messages API`() {
        val restored = AnkiProfile.fromJson(
            JSONObject()
                .put("id", "legacy-openai")
                .put("name", "Legacy OpenAI")
                .put("aiProvider", AnkiProfile.AI_PROVIDER_OPENAI)
                .put("aiModel", "provider/model")
                .put("aiEndpoint", "https://openrouter.ai/api/v1/chat/completions"),
        )

        assertEquals(AnkiProfile.AI_PROVIDER_CUSTOM, restored.aiProvider)
        assertEquals("provider/model", restored.aiCustomModel)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", restored.aiCustomEndpoint)
    }

    @Test
    fun `first BYOK prompt defaults migrate to Yomitan defaults`() {
        val restored = AnkiProfile.fromJson(
            JSONObject()
                .put("id", "legacy-prompts")
                .put("name", "Legacy prompts")
                .put(
                    "aiSystemPrompt",
                    "You are a concise language tutor. Explain the selected term using the supplied sentence context.",
                )
                .put("aiPrompt", "Explain {{target}} in this context:\n{{sentence}}"),
        )

        assertEquals(AnkiProfile.DEFAULT_AI_SYSTEM_PROMPT, restored.aiSystemPrompt)
        assertEquals(AnkiProfile.DEFAULT_AI_PROMPT, restored.aiPrompt)
    }
}
