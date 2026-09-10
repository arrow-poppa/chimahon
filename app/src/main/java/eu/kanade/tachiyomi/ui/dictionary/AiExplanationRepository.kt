package eu.kanade.tachiyomi.ui.dictionary

import chimahon.ai.AiExplanationService
import chimahon.anki.AnkiProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class AiExplanationRepository(
    httpClient: OkHttpClient,
    private val secretStore: AiSecretStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val service = AiExplanationService(httpClient)
    private val cache = LinkedHashMap<CacheKey, CacheEntry>(16, 0.75f, true)
    private val inFlight = mutableMapOf<CacheKey, CompletableDeferred<String>>()

    suspend fun explain(
        profile: AnkiProfile,
        target: String,
        sentence: String,
        bypassCache: Boolean = false,
        onPartial: suspend (String) -> Unit = {},
    ): String {
        val key = CacheKey(
            profileId = profile.id,
            provider = profile.aiProvider,
            providerSettings = profile.aiProviderSettingsCacheKey(),
            systemPrompt = profile.aiSystemPrompt,
            prompt = profile.aiPrompt,
            temperature = profile.aiTemperature,
            target = target,
            sentence = sentence,
        )
        if (!bypassCache) {
            synchronized(cache) {
                cache[key]?.takeIf { now() - it.createdAt < CACHE_TTL_MS }?.let { return it.text }
            }
        }
        val (pendingResult, ownsRequest) = if (bypassCache) {
            null to true
        } else {
            synchronized(inFlight) {
                val existing = inFlight[key]
                if (existing != null) {
                    existing to false
                } else {
                    CompletableDeferred<String>().also { inFlight[key] = it } to true
                }
            }
        }
        if (!ownsRequest) return checkNotNull(pendingResult).await()

        val apiKey = secretStore.get(profile.aiProvider)
        val requestContext = if (profile.aiCancelPendingRequests) {
            Dispatchers.IO
        } else {
            Dispatchers.IO + NonCancellable
        }
        return withContext(requestContext) {
            try {
                val text = if (profile.aiStreamResponse) {
                    var streamedText = ""
                    try {
                        service.generateStream(
                            profile = profile,
                            apiKey = apiKey,
                            target = target,
                            sentence = sentence,
                        ).collect { partial ->
                            streamedText = partial
                            onPartial(partial)
                        }
                        streamedText.trim().ifBlank { "No explanation available." }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // Match Yomitan: if the endpoint rejects streaming before
                        // sending any text, transparently retry once normally.
                        if (streamedText.isNotEmpty()) throw error
                        service.generate(
                            profile = profile,
                            apiKey = apiKey,
                            target = target,
                            sentence = sentence,
                        )
                    }
                } else {
                    service.generate(
                        profile = profile,
                        apiKey = apiKey,
                        target = target,
                        sentence = sentence,
                    )
                }
                synchronized(cache) {
                    cache[key] = CacheEntry(text, now())
                    while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.entries.first().key)
                }
                // Complete before leaving a NonCancellable context: the original
                // UI coroutine may already be cancelled, but another popup can
                // immediately reuse the completed request just like Yomitan.
                pendingResult?.complete(text)
                text
            } catch (error: Throwable) {
                pendingResult?.completeExceptionally(error)
                throw error
            } finally {
                if (pendingResult != null) {
                    synchronized(inFlight) {
                        if (inFlight[key] === pendingResult) inFlight.remove(key)
                    }
                }
            }
        }
    }

    suspend fun testConnection(profile: AnkiProfile): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            service.generate(
                profile = profile,
                apiKey = secretStore.get(profile.aiProvider),
                target = "test",
                sentence = "This is a connection test.",
            )
            Unit
        }
    }

    private data class CacheKey(
        val profileId: String,
        val provider: String,
        val providerSettings: String,
        val systemPrompt: String,
        val prompt: String,
        val temperature: Float,
        val target: String,
        val sentence: String,
    )

    private data class CacheEntry(val text: String, val createdAt: Long)

    companion object {
        private const val CACHE_TTL_MS = 60_000L
        private const val MAX_CACHE_ENTRIES = 32
    }
}

private fun AnkiProfile.aiProviderSettingsCacheKey(): String = when (aiProvider) {
    AnkiProfile.AI_PROVIDER_GEMINI -> listOf(aiGeminiModel, aiGeminiThinkingLevel).joinToString("\u0000")
    AnkiProfile.AI_PROVIDER_DEEPSEEK -> listOf(
        aiDeepSeekModel,
        aiDeepSeekThinkingMode,
        aiDeepSeekThinkingIntensity,
    ).joinToString("\u0000")
    AnkiProfile.AI_PROVIDER_CUSTOM,
    AnkiProfile.AI_PROVIDER_OPENAI_COMPATIBLE -> listOf(
        aiCustomEndpoint,
        aiCustomModel,
        aiCustomProviderRoutingMode,
        aiCustomProviderSlugs,
        aiCustomProviderAllowFallbacks.toString(),
        aiCustomThinkingMode,
        aiCustomThinkingIntensity,
        aiCustomThinkingIntensityValue,
        aiCustomRequestBodyJson,
    ).joinToString("\u0000")
    else -> aiOpenAiModel
}
