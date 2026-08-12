package chimahon.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
    fun `base v1 endpoint gets protocol path`() {
        assertEquals(
            "https://example.test/v1/chat/completions",
            AiExplanationService.resolveEndpoint(
                "https://example.test/v1/",
                "https://unused.test",
                "chat/completions",
            ),
        )
    }

    @Test
    fun `full custom endpoint stays unchanged`() {
        assertEquals(
            "https://example.test/generate",
            AiExplanationService.resolveEndpoint(
                "https://example.test/generate",
                "https://unused.test",
                "responses",
            ),
        )
    }

    @Test
    fun `responses output content is parsed`() {
        assertEquals(
            "First paragraph.\nSecond paragraph.",
            AiExplanationService.parseOpenAiResponsesResponse(
                """{"output":[{"content":[{"type":"output_text","text":"First paragraph."}]},{"content":[{"type":"output_text","text":"Second paragraph."}]}]}""",
            ),
        )
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
    fun `gemini response parts are parsed`() {
        assertEquals(
            "First part.\nSecond part.",
            AiExplanationService.parseGeminiResponse(
                """{"candidates":[{"content":{"parts":[{"text":"First part."},{"text":"Second part."}]}}]}""",
            ),
        )
    }
}
