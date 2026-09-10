package chimahon.ai

import chimahon.anki.AnkiProfile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AiExplanationService(httpClient: OkHttpClient) {
    private val client = httpClient.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val streamingClient = httpClient.newBuilder()
        // Streaming calls may legitimately last longer than 30 seconds. The
        // read timeout is a stall timeout and restarts whenever another SSE
        // chunk arrives, matching the Yomitan implementation.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generate(
        profile: AnkiProfile,
        apiKey: String,
        target: String,
        sentence: String,
    ): String {
        validateConfiguration(profile, apiKey)

        val prompt = renderPrompt(profile.aiPrompt, target, sentence)
        val renderedProfile = profile.copy(
            aiSystemPrompt = renderPrompt(profile.aiSystemPrompt, target, sentence),
        )
        val request = when (profile.aiProvider) {
            AnkiProfile.AI_PROVIDER_GEMINI -> buildGeminiRequest(renderedProfile, apiKey, prompt)
            AnkiProfile.AI_PROVIDER_DEEPSEEK -> buildDeepSeekRequest(renderedProfile, apiKey, prompt)
            AnkiProfile.AI_PROVIDER_CUSTOM,
            AnkiProfile.AI_PROVIDER_OPENAI_COMPATIBLE -> buildCustomRequest(renderedProfile, apiKey, prompt)
            else -> buildOpenAiChatRequest(renderedProfile, apiKey, prompt)
        }
        val responseBody = client.newCall(request).awaitBody()
        return when (profile.aiProvider) {
            AnkiProfile.AI_PROVIDER_GEMINI -> parseGeminiResponse(responseBody)
            AnkiProfile.AI_PROVIDER_CUSTOM,
            AnkiProfile.AI_PROVIDER_OPENAI_COMPATIBLE -> parseCustomChatCompletionsResponse(responseBody)
            else -> parseChatCompletionsResponse(responseBody)
        }.trim().ifBlank { "No explanation available." }
    }

    /**
     * Streams cumulative explanation text from providers which expose an SSE
     * endpoint. Cancelling collection closes the underlying OkHttp call.
     */
    fun generateStream(
        profile: AnkiProfile,
        apiKey: String,
        target: String,
        sentence: String,
    ): Flow<String> = callbackFlow {
        try {
            validateConfiguration(profile, apiKey)
        } catch (error: Exception) {
            close(error)
            return@callbackFlow
        }

        val prompt = renderPrompt(profile.aiPrompt, target, sentence)
        val renderedProfile = profile.copy(
            aiSystemPrompt = renderPrompt(profile.aiSystemPrompt, target, sentence),
        )
        val request = try {
            buildStreamingRequest(renderedProfile, apiKey, prompt)
        } catch (error: Exception) {
            close(error)
            return@callbackFlow
        }
        val call = streamingClient.newCall(request)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    close(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            val responseBody = it.body.string()
                            close(IOException("AI request failed (${it.code})${providerError(responseBody)}"))
                            return
                        }

                        try {
                            val source = it.body.source()
                            val dataLines = mutableListOf<String>()
                            var full = ""
                            var finished = false

                            fun emitEvent(): Boolean {
                                if (dataLines.isEmpty()) return true
                                val payload = dataLines.joinToString("\n")
                                dataLines.clear()
                                if (payload == "[DONE]") return false
                                val delta = when (profile.aiProvider) {
                                    AnkiProfile.AI_PROVIDER_GEMINI -> parseGeminiStreamDelta(payload)
                                    else -> parseOpenAiStreamDelta(payload)
                                }
                                if (delta.isNotEmpty()) {
                                    full += delta
                                    if (trySend(full).isFailure) return false
                                }
                                return true
                            }

                            while (!finished) {
                                val line = source.readUtf8Line()
                                if (line == null) {
                                    emitEvent()
                                    break
                                }
                                when {
                                    line.isBlank() -> finished = !emitEvent()
                                    line.startsWith("data:") -> {
                                        dataLines += line.removePrefix("data:").removePrefix(" ")
                                    }
                                }
                            }

                            if (full.isBlank()) {
                                close(IOException("AI streaming returned no text"))
                            } else {
                                close()
                            }
                        } catch (error: Exception) {
                            close(error)
                        }
                    }
                }
            },
        )
        awaitClose { call.cancel() }
    }.buffer(Channel.CONFLATED)

    private fun validateConfiguration(profile: AnkiProfile, apiKey: String) {
        require(apiKey.isNotBlank()) { "Add an API key in Dictionary settings" }
        if (profile.aiProvider != AnkiProfile.AI_PROVIDER_CUSTOM &&
            profile.aiProvider != AnkiProfile.AI_PROVIDER_OPENAI_COMPATIBLE
        ) {
            require(profile.aiModelForProvider().isNotBlank()) { "Choose an AI model" }
        }
        if (profile.aiProvider == AnkiProfile.AI_PROVIDER_CUSTOM ||
            profile.aiProvider == AnkiProfile.AI_PROVIDER_OPENAI_COMPATIBLE
        ) {
            require(profile.aiCustomEndpoint.isNotBlank()) { "Add the full Custom API endpoint" }
        }
    }

    private fun buildStreamingRequest(profile: AnkiProfile, apiKey: String, prompt: String): Request {
        return when (profile.aiProvider) {
            AnkiProfile.AI_PROVIDER_GEMINI -> {
                val route = resolveGeminiRoute(profile.aiGeminiModel, profile.aiGeminiThinkingLevel)
                val baseUrl = if (route.useVertexExpressRoute) VERTEX_GEMINI_BASE_URL else GEMINI_BASE_URL
                jsonRequest(
                    url = "$baseUrl/${route.model}:streamGenerateContent?alt=sse&key=${apiKey.trim()}",
                    body = buildGeminiRequestBody(profile, prompt, route),
                    headers = emptyMap(),
                    accept = SSE_MEDIA_TYPE,
                )
            }
            AnkiProfile.AI_PROVIDER_DEEPSEEK -> jsonRequest(
                url = DEEPSEEK_CHAT_URL,
                body = buildDeepSeekStreamingRequestBody(profile, prompt),
                headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
                accept = SSE_MEDIA_TYPE,
            )
            AnkiProfile.AI_PROVIDER_CUSTOM,
            AnkiProfile.AI_PROVIDER_OPENAI_COMPATIBLE -> {
                val endpoint = profile.aiCustomEndpoint.trim()
                val headers = buildMap {
                    put("Authorization", "Bearer ${apiKey.trim()}")
                    if (isOpenRouterEndpoint(endpoint)) {
                        put("HTTP-Referer", "https://github.com/arrow-poppa/chimahon")
                        put("X-OpenRouter-Title", "Chimahon")
                    }
                }
                jsonRequest(
                    url = endpoint,
                    body = buildCustomStreamingRequestBody(profile, prompt),
                    headers = headers,
                    accept = SSE_MEDIA_TYPE,
                )
            }
            else -> jsonRequest(
                url = OPENAI_CHAT_URL,
                body = buildOpenAiStreamingRequestBody(profile, prompt),
                headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
                accept = SSE_MEDIA_TYPE,
            )
        }
    }

    private fun buildOpenAiChatRequest(profile: AnkiProfile, apiKey: String, prompt: String): Request {
        return jsonRequest(
            url = OPENAI_CHAT_URL,
            body = buildOpenAiRequestBody(profile, prompt),
            headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
        )
    }

    private fun buildCustomRequest(profile: AnkiProfile, apiKey: String, prompt: String): Request {
        val endpoint = profile.aiCustomEndpoint.trim()
        val headers = buildMap {
            put("Authorization", "Bearer ${apiKey.trim()}")
            if (isOpenRouterEndpoint(endpoint)) {
                put("HTTP-Referer", "https://github.com/arrow-poppa/chimahon")
                put("X-OpenRouter-Title", "Chimahon")
            }
        }
        return jsonRequest(endpoint, buildCustomRequestBody(profile, prompt), headers)
    }

    private fun buildDeepSeekRequest(profile: AnkiProfile, apiKey: String, prompt: String): Request {
        return jsonRequest(
            url = DEEPSEEK_CHAT_URL,
            body = buildDeepSeekRequestBody(profile, prompt),
            headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
        )
    }

    private fun buildGeminiRequest(profile: AnkiProfile, apiKey: String, prompt: String): Request {
        val route = resolveGeminiRoute(profile.aiGeminiModel, profile.aiGeminiThinkingLevel)
        val body = buildGeminiRequestBody(profile, prompt, route)
        val url = if (route.useVertexExpressRoute) {
            "$VERTEX_GEMINI_BASE_URL/${route.model}:generateContent?key=${apiKey.trim()}"
        } else {
            "$GEMINI_BASE_URL/${route.model}:generateContent"
        }
        val headers = if (route.useVertexExpressRoute) emptyMap() else mapOf("x-goog-api-key" to apiKey.trim())
        return jsonRequest(url, body, headers)
    }

    private fun jsonRequest(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        accept: String = "application/json",
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", accept)
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
        private const val SSE_MEDIA_TYPE = "text/event-stream"
        private const val OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"
        private const val DEEPSEEK_CHAT_URL = "https://api.deepseek.com/chat/completions"
        private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val VERTEX_GEMINI_BASE_URL = "https://aiplatform.googleapis.com/v1/publishers/google/models"

        fun renderPrompt(template: String, target: String, sentence: String): String {
            return template
                .replace("{{target}}", target)
                .replace("{{sentence}}", sentence)
        }

        internal fun buildOpenAiRequestBody(profile: AnkiProfile, prompt: String): JSONObject {
            return buildChatCompletionsBody(
                model = profile.aiOpenAiModel,
                systemPrompt = profile.aiSystemPrompt,
                prompt = prompt,
                temperature = profile.aiTemperature,
            )
        }

        internal fun buildOpenAiStreamingRequestBody(profile: AnkiProfile, prompt: String): JSONObject {
            return buildOpenAiRequestBody(profile, prompt).apply { put("stream", true) }
        }

        internal fun buildCustomRequestBody(profile: AnkiProfile, prompt: String): JSONObject {
            val body = buildChatCompletionsBody(
                model = profile.aiCustomModel.ifBlank { "gpt-4o-mini" },
                systemPrompt = profile.aiSystemPrompt,
                prompt = prompt,
                temperature = profile.aiTemperature,
            )
            applyReasoningOptions(
                requestBody = body,
                endpoint = profile.aiCustomEndpoint,
                thinkingMode = profile.aiCustomThinkingMode,
                thinkingIntensity = profile.aiCustomThinkingIntensity,
                customThinkingIntensity = profile.aiCustomThinkingIntensityValue,
            )
            applyOpenRouterProviderOptions(body, profile)
            applyCustomRequestBodyJson(body, profile.aiCustomRequestBodyJson)
            return body
        }

        internal fun buildCustomStreamingRequestBody(profile: AnkiProfile, prompt: String): JSONObject {
            // Apply this last so custom request JSON cannot accidentally turn
            // streaming back off while the Real Time Response switch is on.
            return buildCustomRequestBody(profile, prompt).apply { put("stream", true) }
        }

        internal fun buildDeepSeekRequestBody(profile: AnkiProfile, prompt: String): JSONObject {
            return buildChatCompletionsBody(
                model = profile.aiDeepSeekModel,
                systemPrompt = profile.aiSystemPrompt,
                prompt = prompt,
                temperature = profile.aiTemperature,
            ).apply {
                put("stream", false)
                applyReasoningOptions(
                    requestBody = this,
                    endpoint = DEEPSEEK_CHAT_URL,
                    thinkingMode = profile.aiDeepSeekThinkingMode,
                    thinkingIntensity = profile.aiDeepSeekThinkingIntensity,
                    customThinkingIntensity = "",
                )
            }
        }

        internal fun buildDeepSeekStreamingRequestBody(profile: AnkiProfile, prompt: String): JSONObject {
            return buildDeepSeekRequestBody(profile, prompt).apply { put("stream", true) }
        }

        private fun buildChatCompletionsBody(
            model: String,
            systemPrompt: String,
            prompt: String,
            temperature: Float,
        ): JSONObject {
            val messages = JSONArray().apply {
                if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", prompt))
            }
            return JSONObject()
                .put("model", model.trim())
                .put("messages", messages)
                .put("temperature", temperature.toDouble().coerceIn(0.0, 2.0))
        }

        private fun applyReasoningOptions(
            requestBody: JSONObject,
            endpoint: String,
            thinkingMode: String,
            thinkingIntensity: String,
            customThinkingIntensity: String,
        ) {
            val customValue = customThinkingIntensity.trim()
            val effectiveIntensity = if (thinkingIntensity == AnkiProfile.THINKING_INTENSITY_CUSTOM) {
                customValue
            } else {
                thinkingIntensity
            }
            val hasThinkingMode = thinkingMode == AnkiProfile.THINKING_ENABLED ||
                thinkingMode == AnkiProfile.THINKING_DISABLED
            val hasThinkingIntensity = effectiveIntensity.isNotBlank()
            if (!hasThinkingMode && !hasThinkingIntensity) return

            if (isOpenRouterEndpoint(endpoint)) {
                val reasoning = JSONObject()
                when {
                    thinkingMode == AnkiProfile.THINKING_DISABLED -> reasoning.put("effort", "none")
                    thinkingIntensity == AnkiProfile.THINKING_INTENSITY_CUSTOM && customValue.isNotBlank() -> {
                        mergeJsonObject(reasoning, parseCustomReasoningValue(customValue))
                    }
                    hasThinkingIntensity -> reasoning.put(
                        "effort",
                        if (effectiveIntensity == AnkiProfile.THINKING_INTENSITY_MAX) "xhigh" else effectiveIntensity,
                    )
                    hasThinkingMode -> reasoning.put("enabled", true)
                }
                reasoning.put("exclude", true)
                requestBody.put("reasoning", reasoning)
                return
            }

            if (hasThinkingMode) requestBody.put("thinking", JSONObject().put("type", thinkingMode))
            if (thinkingMode != AnkiProfile.THINKING_DISABLED && hasThinkingIntensity) {
                requestBody.put("reasoning_effort", effectiveIntensity)
            }
        }

        private fun parseCustomReasoningValue(value: String): JSONObject {
            val trimmed = value.trim()
            if (trimmed.all(Char::isDigit) && trimmed.isNotEmpty()) {
                return JSONObject().put("max_tokens", trimmed.toLong())
            }
            if (trimmed.startsWith("{")) {
                runCatching { return JSONObject(trimmed) }
            }
            return JSONObject().put(
                "effort",
                if (trimmed == AnkiProfile.THINKING_INTENSITY_MAX) "xhigh" else trimmed,
            )
        }

        private fun applyOpenRouterProviderOptions(requestBody: JSONObject, profile: AnkiProfile) {
            if (!isOpenRouterEndpoint(profile.aiCustomEndpoint)) return
            val slugs = profile.aiCustomProviderSlugs
                .split(Regex("[\\n,]+"))
                .map(String::trim)
                .filter(String::isNotEmpty)
            val provider = JSONObject()
            val slugArray = JSONArray(slugs)
            when (profile.aiCustomProviderRoutingMode) {
                AnkiProfile.ROUTING_ORDER -> if (slugs.isNotEmpty()) provider.put("order", slugArray)
                AnkiProfile.ROUTING_ONLY -> if (slugs.isNotEmpty()) provider.put("only", slugArray)
                AnkiProfile.ROUTING_IGNORE -> if (slugs.isNotEmpty()) provider.put("ignore", slugArray)
            }
            provider.put("allow_fallbacks", profile.aiCustomProviderAllowFallbacks)
            requestBody.put("provider", provider)
        }

        private fun applyCustomRequestBodyJson(requestBody: JSONObject, rawValue: String) {
            val raw = rawValue.trim()
            if (raw.isBlank()) return
            val parsed = try {
                JSONObject(raw)
            } catch (error: JSONException) {
                throw IllegalArgumentException("Invalid Custom Request Body JSON: ${error.message}", error)
            }
            mergeJsonObject(requestBody, parsed)
        }

        private fun mergeJsonObject(target: JSONObject, source: JSONObject) {
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val sourceValue = source.get(key)
                val targetValue = target.opt(key)
                if (sourceValue is JSONObject && targetValue is JSONObject) {
                    mergeJsonObject(targetValue, sourceValue)
                } else {
                    target.put(key, sourceValue)
                }
            }
        }

        private fun isOpenRouterEndpoint(endpoint: String): Boolean {
            val host = runCatching { URI(endpoint.trim()).host?.lowercase() }.getOrNull() ?: return false
            return host == "openrouter.ai" || host.endsWith(".openrouter.ai")
        }

        internal data class GeminiRoute(
            val model: String,
            val thinkingLevel: String,
            val useVertexExpressRoute: Boolean,
        )

        internal fun resolveGeminiRoute(modelValue: String, thinkingLevelValue: String): GeminiRoute {
            var model = modelValue.trim().ifBlank { "gemini-2.5-flash" }.removePrefix("models/")
            if (model.endsWith("-latest")) model = model.removeSuffix("-latest")
            var useVertex = false
            if (model.endsWith("-vertex")) {
                model = model.removeSuffix("-vertex")
                useVertex = true
            }
            val isLowThinking = model.contains("low-thinking")
            if (isLowThinking) {
                model = "gemini-3-flash-preview"
                useVertex = true
            }
            if (model == "gemini-3-pro-preview" || model == "gemini-3-pro-image-preview") useVertex = true

            var thinkingLevel = thinkingLevelValue.uppercase()
            if (isLowThinking && thinkingLevel.isBlank()) thinkingLevel = "LOW"
            if (model == "gemini-3-pro-preview" || model == "gemini-3.1-pro-preview") {
                thinkingLevel = when (thinkingLevel) {
                    "MINIMAL" -> "LOW"
                    "MEDIUM" -> "HIGH"
                    "LOW", "HIGH" -> thinkingLevel
                    else -> ""
                }
            }
            if (!model.contains("gemini-3") || thinkingLevel !in setOf("MINIMAL", "LOW", "MEDIUM", "HIGH")) {
                thinkingLevel = ""
            }
            return GeminiRoute(model, thinkingLevel, useVertex)
        }

        internal fun buildGeminiRequestBody(
            profile: AnkiProfile,
            prompt: String,
            route: GeminiRoute = resolveGeminiRoute(profile.aiGeminiModel, profile.aiGeminiThinkingLevel),
        ): JSONObject {
            val generationConfig = JSONObject().put("temperature", profile.aiTemperature.toDouble().coerceIn(0.0, 2.0))
            if (route.thinkingLevel.isNotBlank()) {
                generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", route.thinkingLevel))
            }
            return JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                    ),
                )
                if (profile.aiSystemPrompt.isNotBlank()) {
                    put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", profile.aiSystemPrompt))),
                    )
                }
                put("generationConfig", generationConfig)
            }
        }

        internal fun parseChatCompletionsResponse(raw: String): String {
            val json = Json.parseToJsonElement(raw) as? JsonObject ?: return ""
            val choices = json["choices"] as? JsonArray ?: return ""
            val first = choices.firstOrNull() as? JsonObject ?: return ""
            val message = first["message"] as? JsonObject
            return stringifyMessageContent(message?.get("content") ?: first["text"])
        }

        internal fun parseCustomChatCompletionsResponse(raw: String): String {
            val json = Json.parseToJsonElement(raw) as? JsonObject ?: return ""
            val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val error = choice?.get("error") as? JsonObject
            if (error != null) {
                val detail = error.string("message").ifBlank { error.toString() }
                throw IOException("Custom API error: $detail")
            }
            return parseChatCompletionsResponse(raw)
        }

        internal fun parseOpenAiStreamDelta(raw: String): String {
            val json = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return ""
            val topLevelError = json["error"] as? JsonObject
            if (topLevelError != null) {
                val detail = topLevelError.string("message").ifBlank { topLevelError.toString() }
                throw IOException("AI stream error: $detail")
            }
            val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return ""
            val choiceError = choice["error"] as? JsonObject
            if (choiceError != null) {
                val detail = choiceError.string("message").ifBlank { choiceError.toString() }
                throw IOException("AI stream error: $detail")
            }
            val delta = choice["delta"] as? JsonObject
            return stringifyMessageContent(delta?.get("content") ?: choice["text"])
        }

        internal fun parseGeminiStreamDelta(raw: String): String {
            val json = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return ""
            val error = json["error"] as? JsonObject
            if (error != null) {
                val detail = error.string("message").ifBlank { error.toString() }
                throw IOException("AI stream error: $detail")
            }
            val candidate = (json["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return ""
            val content = candidate["content"] as? JsonObject ?: return ""
            val parts = content["parts"] as? JsonArray ?: return ""
            return buildString {
                for (partElement in parts) {
                    val part = partElement as? JsonObject ?: continue
                    val isThought = (part["thought"] as? JsonPrimitive)?.contentOrNull == "true"
                    if (!isThought) append(part.string("text"))
                }
            }
        }

        private fun stringifyMessageContent(content: JsonElement?): String = when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("") { stringifyMessageContent(it) }
            is JsonObject -> stringifyMessageContent(content["text"] ?: content["content"])
            else -> ""
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
