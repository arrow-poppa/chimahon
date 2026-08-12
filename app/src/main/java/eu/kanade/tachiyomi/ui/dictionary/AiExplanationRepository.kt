package eu.kanade.tachiyomi.ui.dictionary

import chimahon.ai.AiExplanationService
import chimahon.anki.AnkiProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class AiExplanationRepository(
    httpClient: OkHttpClient,
    private val secretStore: AiSecretStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val service = AiExplanationService(httpClient)
    private val cache = LinkedHashMap<CacheKey, CacheEntry>(16, 0.75f, true)

    suspend fun explain(
        profile: AnkiProfile,
        target: String,
        sentence: String,
        bypassCache: Boolean = false,
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
        val text = withContext(Dispatchers.IO) {
            service.generate(
                profile = profile,
                apiKey = secretStore.get(profile.aiProvider),
                target = target,
                sentence = sentence,
            )
        }
        synchronized(cache) {
            cache[key] = CacheEntry(text, now())
            while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.entries.first().key)
        }
        return text
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
