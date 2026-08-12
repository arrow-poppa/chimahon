package eu.kanade.tachiyomi.ui.dictionary

import chimahon.ai.AiExplanationService
import chimahon.anki.AnkiProfile
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
            endpoint = profile.aiEndpoint,
            model = profile.aiModel,
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
        val text = service.generate(
            profile = profile,
            apiKey = secretStore.get(profile.aiProvider),
            target = target,
            sentence = sentence,
        )
        synchronized(cache) {
            cache[key] = CacheEntry(text, now())
            while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.entries.first().key)
        }
        return text
    }

    suspend fun testConnection(profile: AnkiProfile): Result<Unit> = runCatching {
        service.generate(
            profile = profile,
            apiKey = secretStore.get(profile.aiProvider),
            target = "test",
            sentence = "This is a connection test.",
        )
        Unit
    }

    private data class CacheKey(
        val profileId: String,
        val provider: String,
        val endpoint: String,
        val model: String,
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
