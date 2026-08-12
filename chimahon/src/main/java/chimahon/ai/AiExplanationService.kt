package chimahon.ai

import chimahon.anki.AnkiProfile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AiExplanationService(httpClient: OkHttpClient) {
    private val client = httpClient.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generate(
        profile: AnkiProfile,
        apiKey: String,
        target: String,
        sentence: String,
    ): String {
        require(apiKey.isNotBlank()) { "Add an API key in Dictionary settings" }
        require(profile.aiModel.isNotBlank()) { "Choose an AI model" }
        val prompt = renderPrompt(profile.aiPrompt, target, sentence)
        val request = when (profile.aiProvider) {
            AnkiProfile.AI_PROVIDER_GEMINI -> buildGeminiRequest(profile, apiKey, prompt)
            AnkiProfile.AI_PROVIDER_OPENAI_COMPATIBLE -> buildOpenAiCompatibleRequest(profile, apiKey, prompt)
            else -> buildOpenAiResponsesRequest(profile, apiKey, prompt)
        }
        val responseBody = client.newCall(request).awaitBody()
        return when (profile.aiProvider) {
            AnkiProfile.AI_PROVIDER_GEMINI -> parseGeminiResponse(responseBody)
            AnkiProfile.AI_PROVIDER_OPENAI_COMPATIBLE -> parseChatCompletionsResponse(responseBody)
            else -> parseOpenAiResponsesResponse(responseBody)
        }.trim().ifBlank { throw IOException("The AI provider returned an empty response") }
    }

    private fun buildOpenAiResponsesRequest(profile: AnkiProfile, apiKey: String, prompt: String): Request {
        val body = JSONObject().apply {
            put("model", profile.aiModel)
            put("instructions", profile.aiSystemPrompt)
            put("input", prompt)
            put("temperature", profile.aiTemperature.toDouble())
        }
        return jsonRequest(
            url = resolveEndpoint(profile.aiEndpoint, OPENAI_RESPONSES_URL, "responses"),
            body = body,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
        )
    }

    private fun buildOpenAiCompatibleRequest(profile: AnkiProfile, apiKey: String, prompt: String): Request {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", profile.aiSystemPrompt))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().apply {
            put("model", profile.aiModel)
            put("messages", messages)
            put("temperature", profile.aiTemperature.toDouble())
        }
        return jsonRequest(
            url = resolveEndpoint(profile.aiEndpoint, OPENAI_CHAT_URL, "chat/completions"),
            body = body,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
        )
    }

    private fun buildGeminiRequest(profile: AnkiProfile, apiKey: String, prompt: String): Request {
        val contents = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
        )
        val body = JSONObject().apply {
            if (profile.aiSystemPrompt.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", profile.aiSystemPrompt))),
                )
            }
            put("contents", contents)
            put("generationConfig", JSONObject().put("temperature", profile.aiTemperature.toDouble()))
        }
        val defaultUrl = "$GEMINI_BASE_URL/${profile.aiModel}:generateContent"
        val url = profile.aiEndpoint.trim()
            .takeIf { it.isNotBlank() }
            ?.replace("{{model}}", profile.aiModel)
            ?: defaultUrl
        return jsonRequest(url, body, mapOf("x-goog-api-key" to apiKey))
    }

    private fun jsonRequest(url: String, body: JSONObject, headers: Map<String, String>): Request {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body.string()
                        if (!it.isSuccessful) {
                            val detail = providerError(body)
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("AI request failed (${it.code})$detail"))
                            }
                        } else if (continuation.isActive) {
                            continuation.resume(body)
                        }
                    }
                }
            },
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
        private const val OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"
        private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        fun renderPrompt(template: String, target: String, sentence: String): String {
            return template
                .replace("{{target}}", target)
                .replace("{{sentence}}", sentence)
        }

        internal fun resolveEndpoint(configured: String, defaultUrl: String, suffix: String): String {
            val value = configured.trim().trimEnd('/')
            if (value.isBlank()) return defaultUrl
            return if (value.endsWith("/v1") || value.endsWith("/api/v1")) "$value/$suffix" else value
        }

        internal fun parseOpenAiResponsesResponse(raw: String): String {
            val json = Json.parseToJsonElement(raw) as? JsonObject ?: return ""
            json.string("output_text").takeIf { it.isNotBlank() }?.let { return it }
            val output = json["output"] as? JsonArray ?: return ""
            return buildString {
                for (item in output) {
                    val content = (item as? JsonObject)?.get("content") as? JsonArray ?: continue
                    for (contentItem in content) {
                        val text = (contentItem as? JsonObject)?.string("text").orEmpty()
                        if (text.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(text)
                        }
                    }
                }
            }
        }

        internal fun parseChatCompletionsResponse(raw: String): String {
            val json = Json.parseToJsonElement(raw) as? JsonObject ?: return ""
            val choices = json["choices"] as? JsonArray ?: return ""
            val first = choices.firstOrNull() as? JsonObject ?: return ""
            val message = first["message"] as? JsonObject ?: return ""
            return message.string("content")
        }

        internal fun parseGeminiResponse(raw: String): String {
            val json = Json.parseToJsonElement(raw) as? JsonObject ?: return ""
            val candidates = json["candidates"] as? JsonArray ?: return ""
            val first = candidates.firstOrNull() as? JsonObject ?: return ""
            val content = first["content"] as? JsonObject ?: return ""
            val parts = content["parts"] as? JsonArray ?: return ""
            return buildString {
                for (part in parts) {
                    val text = (part as? JsonObject)?.string("text").orEmpty()
                    if (text.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(text)
                    }
                }
            }
        }

        private fun providerError(raw: String): String {
            val message = runCatching {
                val json = Json.parseToJsonElement(raw) as? JsonObject
                val error = json?.get("error") as? JsonObject
                error?.string("message")
            }.getOrNull().orEmpty().replace(Regex("[\\r\\n]+"), " ").take(240)
            return if (message.isBlank()) "" else ": $message"
        }

        private fun JsonObject.string(name: String): String {
            return (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
    }
}
