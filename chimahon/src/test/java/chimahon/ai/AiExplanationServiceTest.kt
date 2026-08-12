package chimahon.ai

import chimahon.anki.AnkiProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class AiExplanationServiceTest {

    @Test
    fun `prompt placeholders are replaced literally`() {
        assertEquals(
            "Explain escolher in Eu preciso escolher agora.",
            AiExplanationService.renderPrompt(
                "Explain {{target}} in {{sentence}}",
                "escolher",
                "Eu preciso escolher agora.",
            ),
        )
    }

    @Test
    fun `all placeholders are replaced for both user and system prompt templates`() {
        assertEquals(
            "Target escolher; again escolher; context Eu preciso escolher agora.",
            AiExplanationService.renderPrompt(
                "Target {{target}}; again {{target}}; context {{sentence}}",
                "escolher",
                "Eu preciso escolher agora.",
            ),
        )
    }

    @Test
    fun `openrouter custom settings are applied and request json overrides generated values`() {
        val profile = AnkiProfile(
            id = "test",
            name = "Test",
            aiProvider = AnkiProfile.AI_PROVIDER_CUSTOM,
            aiSystemPrompt = "Tutor",
            aiCustomEndpoint = "https://openrouter.ai/api/v1/chat/completions",
            aiCustomModel = "provider/model",
            aiCustomProviderRoutingMode = AnkiProfile.ROUTING_ONLY,
            aiCustomProviderSlugs = "deepinfra/turbo, fireworks",
            aiCustomProviderAllowFallbacks = false,
            aiCustomThinkingMode = AnkiProfile.THINKING_ENABLED,
            aiCustomThinkingIntensity = AnkiProfile.THINKING_INTENSITY_CUSTOM,
            aiCustomThinkingIntensityValue = "{\"max_tokens\":2000}",
            aiCustomRequestBodyJson = "{\"temperature\":0.4,\"reasoning\":{\"exclude\":false}}",
        )

        val body = AiExplanationService.buildCustomRequestBody(profile, "Explain it")
        val reasoning = body.getJSONObject("reasoning")
        val provider = body.getJSONObject("provider")
        val messages = body.getJSONArray("messages")

        assertEquals("provider/model", body.getString("model"))
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Tutor", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("Explain it", messages.getJSONObject(1).getString("content"))
        assertEquals(0.4, body.getDouble("temperature"))
        assertEquals(2000, reasoning.getInt("max_tokens"))
        assertFalse(reasoning.getBoolean("exclude"))
        assertEquals("deepinfra/turbo", provider.getJSONArray("only").getString(0))
        assertEquals("fireworks", provider.getJSONArray("only").getString(1))
        assertFalse(provider.getBoolean("allow_fallbacks"))
    }

    @Test
    fun `empty system prompt sends only the user message like Yomitan`() {
        val profile = AnkiProfile(
            id = "test",
            name = "Test",
            aiProvider = AnkiProfile.AI_PROVIDER_CUSTOM,
            aiSystemPrompt = "",
            aiCustomEndpoint = "https://example.test/v1/chat/completions",
            aiCustomModel = "model",
        )

        val messages = AiExplanationService.buildCustomRequestBody(profile, "Explain it").getJSONArray("messages")

        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("Explain it", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun `custom blank model falls back to Yomitan default`() {
        val profile = AnkiProfile(
            id = "test",
            name = "Test",
            aiProvider = AnkiProfile.AI_PROVIDER_CUSTOM,
            aiCustomEndpoint = "https://openrouter.ai/api/v1/chat/completions",
            aiCustomModel = "",
        )

        assertEquals(
            "gpt-4o-mini",
            AiExplanationService.buildCustomRequestBody(profile, "Explain it").getString("model"),
        )
    }

    @Test
    fun `non openrouter thinking can be explicitly disabled`() {
        val profile = AnkiProfile(
            id = "test",
            name = "Test",
            aiProvider = AnkiProfile.AI_PROVIDER_CUSTOM,
            aiCustomEndpoint = "https://example.test/v1/chat/completions",
            aiCustomModel = "model",
            aiCustomThinkingMode = AnkiProfile.THINKING_DISABLED,
            aiCustomThinkingIntensity = AnkiProfile.THINKING_INTENSITY_HIGH,
        )

        val body = AiExplanationService.buildCustomRequestBody(profile, "Explain it")

        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("provider"))
    }

    @Test
    fun `invalid custom request body json is rejected before networking`() {
        val profile = AnkiProfile(
            id = "test",
            name = "Test",
            aiCustomModel = "model",
            aiCustomRequestBodyJson = "not-json",
        )

        assertThrows(IllegalArgumentException::class.java) {
            AiExplanationService.buildCustomRequestBody(profile, "Explain it")
        }
    }

    @Test
    fun `gemini 3 pro normalizes unsupported thinking levels`() {
        val route = AiExplanationService.resolveGeminiRoute("gemini-3.1-pro-preview-vertex", "MEDIUM")

        assertEquals("gemini-3.1-pro-preview", route.model)
        assertEquals("HIGH", route.thinkingLevel)
        assertTrue(route.useVertexExpressRoute)
    }

    @Test
    fun `openai uses chat completions messages like Yomitan`() {
        val profile = AnkiProfile(
            id = "test",
            name = "Test",
            aiOpenAiModel = "gpt-4o-mini",
            aiSystemPrompt = "Tutor for {{target}}",
            aiTemperature = 0.7f,
        )

        val body = AiExplanationService.buildOpenAiRequestBody(profile, "Explain it")
        val messages = body.getJSONArray("messages")

        assertEquals("gpt-4o-mini", body.getString("model"))
        assertFalse(body.has("input"))
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Tutor for {{target}}", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("Explain it", messages.getJSONObject(1).getString("content"))
        assertEquals(0.7, body.getDouble("temperature"), 0.0001)
    }

    @Test
    fun `chat completions content is parsed`() {
        assertEquals(
            "Contextual explanation.",
            AiExplanationService.parseChatCompletionsResponse(
                """{"choices":[{"message":{"role":"assistant","content":"Contextual explanation."}}]}""",
            ),
        )
    }

    @Test
    fun `chat completions structured content is parsed`() {
        assertEquals(
            "First second",
            AiExplanationService.parseChatCompletionsResponse(
                """{"choices":[{"message":{"content":[{"type":"text","text":"First "},{"content":"second"}]}}]}""",
            ),
        )
    }

    @Test
    fun `custom choice error is surfaced like Yomitan`() {
        val error = assertThrows(IOException::class.java) {
            AiExplanationService.parseCustomChatCompletionsResponse(
                """{"choices":[{"error":{"message":"Provider unavailable"}}]}""",
            )
        }

        assertEquals("Custom API error: Provider unavailable", error.message)
    }

    @Test
    fun `gemini response parts are parsed`() {
        assertEquals(
            "First part.\nSecond part.",
            AiExplanationService.parseGeminiResponse(
                """{"candidates":[{"content":{"parts":[{"text":"First part."},{"text":"Second part."}]}}]}""",
            ),
        )
    }
}
