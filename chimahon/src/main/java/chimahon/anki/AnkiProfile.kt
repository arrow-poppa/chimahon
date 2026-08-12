package chimahon.anki

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named mining configuration that bundles together:
 * - AnkiDroid deck/model/field-map/tag settings
 * - The ordered list of dictionaries to use
 * - Which of those dictionaries are enabled for lookup
 *
 * The list [dictionaryOrder] defines priority (index 0 = highest).
 * [enabledDictionaries] is a subset of [dictionaryOrder]; when empty every
 * dictionary in [dictionaryOrder] is treated as enabled (backwards-compatible
 * default for the "Default" profile created during migration).
 */
data class AnkiProfile(
    val id: String,
    val name: String,
    // Anki settings
    val ankiEnabled: Boolean = false,
    val ankiDeck: String = "",
    val ankiModel: String = LapisPreset.MODEL_NAME,
    val ankiFieldMap: String = LapisPreset.defaultFieldMapJson,
    val ankiTags: String = "chimahon",
    val ankiDupCheck: Boolean = true,
    val ankiDupScope: String = "deck",
    val ankiDupAction: String = "prevent",
    val ankiCropMode: String = "full",
    val ankiCropPreset: String = "full",
    val ankiSyncOnCreate: Boolean = false,
    // Dictionary configuration
    val dictionaryOrder: List<String> = emptyList(),
    val enabledDictionaries: Set<String> = emptySet(), // empty = all enabled
    val dictionaryCollapseMode: String = DICTIONARY_COLLAPSE_EXPAND_ALL,
    val dictionaryDisplayModes: Map<String, String> = emptyMap(),

    /**
     * BCP-47-style language code for this profile, e.g. "ja", "ko", "ar", "en".
     * Used by [chimahon.dictionary.DictionaryProfileResolver] to auto-select a
     * matching profile when a source declares a specific language.
     * Empty string means "any / not language-specific".
     */
    val languageCode: String = "",

    /**
     * OCR scan resolution (yomitan "scanning.scanResolution"). Controls whether
     * the tapped position expands to the whole word ("word", default for
     * space-delimited languages) or stays character-based ("character", default
     * for CJK). Empty means auto-derive from [languageCode].
     */
    val scanResolution: String = "",

    /**
     * Lookup search resolution (yomitan "translation.searchResolution"). When
     * no dictionary entry matches the full source, "word" retries cutting at
     * word boundaries while "letter" retries one character at a time.
     * Empty means auto (derive from [languageCode]).
     */
    val searchResolution: String = "",

    // Contextual AI explanation settings. API keys are deliberately stored
    // separately by the Android host and never serialized with the profile.
    val aiEnabled: Boolean = false,
    val aiProvider: String = AI_PROVIDER_OPENAI,
    val aiSystemPrompt: String = DEFAULT_AI_SYSTEM_PROMPT,
    val aiPrompt: String = DEFAULT_AI_PROMPT,
    val aiTemperature: Float = 0.2f,
    val aiAutoGenerate: Boolean = false,
    val aiUnknownWordFallback: Boolean = false,
    val aiOpenAiModel: String = "gpt-5-mini",
    val aiGeminiModel: String = "gemini-2.5-flash",
    val aiGeminiThinkingLevel: String = THINKING_DEFAULT,
    val aiDeepSeekModel: String = "",
    val aiDeepSeekThinkingMode: String = THINKING_DEFAULT,
    val aiDeepSeekThinkingIntensity: String = THINKING_DEFAULT,
    val aiCustomEndpoint: String = "",
    val aiCustomModel: String = "",
    val aiCustomProviderRoutingMode: String = ROUTING_DEFAULT,
    val aiCustomProviderSlugs: String = "",
    val aiCustomProviderAllowFallbacks: Boolean = true,
    val aiCustomThinkingMode: String = THINKING_DEFAULT,
    val aiCustomThinkingIntensity: String = THINKING_DEFAULT,
    val aiCustomThinkingIntensityValue: String = "",
    val aiCustomRequestBodyJson: String = "",
) {

    fun aiModelForProvider(): String = when (aiProvider) {
        AI_PROVIDER_GEMINI -> aiGeminiModel
        AI_PROVIDER_DEEPSEEK -> aiDeepSeekModel
        AI_PROVIDER_CUSTOM, AI_PROVIDER_OPENAI_COMPATIBLE -> aiCustomModel
        else -> aiOpenAiModel
    }

    fun aiEndpointForProvider(): String = when (aiProvider) {
        AI_PROVIDER_CUSTOM, AI_PROVIDER_OPENAI_COMPATIBLE -> aiCustomEndpoint
        else -> ""
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("ankiEnabled", ankiEnabled)
        put("ankiDeck", ankiDeck)
        put("ankiModel", ankiModel)
        put("ankiFieldMap", ankiFieldMap)
        put("ankiTags", ankiTags)
        put("ankiDupCheck", ankiDupCheck)
        put("ankiDupScope", ankiDupScope)
        put("ankiDupAction", ankiDupAction)
        put("ankiCropMode", ankiCropMode)
        put("ankiCropPreset", ankiCropPreset)
        put("ankiSyncOnCreate", ankiSyncOnCreate)
        put("dictionaryOrder", JSONArray(dictionaryOrder))
        put("enabledDictionaries", JSONArray(enabledDictionaries.toList()))
        put("dictionaryCollapseMode", dictionaryCollapseMode)
        put("dictionaryDisplayModes", JSONObject(dictionaryDisplayModes))
        put("languageCode", languageCode)
        put("scanResolution", scanResolution)
        put("searchResolution", searchResolution)
        put("aiEnabled", aiEnabled)
        put("aiProvider", aiProvider)
        // Keep the legacy active-provider aliases so profiles remain readable
        // by builds created before provider-specific settings were introduced.
        put("aiEndpoint", aiEndpointForProvider())
        put("aiModel", aiModelForProvider())
        put("aiSystemPrompt", aiSystemPrompt)
        put("aiPrompt", aiPrompt)
        put("aiTemperature", aiTemperature.toDouble())
        put("aiAutoGenerate", aiAutoGenerate)
        put("aiUnknownWordFallback", aiUnknownWordFallback)
        put("aiOpenAiModel", aiOpenAiModel)
        put("aiGeminiModel", aiGeminiModel)
        put("aiGeminiThinkingLevel", aiGeminiThinkingLevel)
        put("aiDeepSeekModel", aiDeepSeekModel)
        put("aiDeepSeekThinkingMode", aiDeepSeekThinkingMode)
        put("aiDeepSeekThinkingIntensity", aiDeepSeekThinkingIntensity)
        put("aiCustomEndpoint", aiCustomEndpoint)
        put("aiCustomModel", aiCustomModel)
        put("aiCustomProviderRoutingMode", aiCustomProviderRoutingMode)
        put("aiCustomProviderSlugs", aiCustomProviderSlugs)
        put("aiCustomProviderAllowFallbacks", aiCustomProviderAllowFallbacks)
        put("aiCustomThinkingMode", aiCustomThinkingMode)
        put("aiCustomThinkingIntensity", aiCustomThinkingIntensity)
        put("aiCustomThinkingIntensityValue", aiCustomThinkingIntensityValue)
        put("aiCustomRequestBodyJson", aiCustomRequestBodyJson)
    }

    companion object {
        val EMPTY = AnkiProfile(id = "", name = "")
        const val DICTIONARY_COLLAPSE_EXPAND_ALL = "expand_all"
        const val DICTIONARY_COLLAPSE_EXPAND_FIRST_AVAILABLE = "expand_first_available"
        const val DICTIONARY_COLLAPSE_COLLAPSE_ALL = "collapse_all"
        const val DICTIONARY_COLLAPSE_CUSTOM = "custom"

        const val DICTIONARY_DISPLAY_ALWAYS_EXPANDED = "always_expanded"
        const val DICTIONARY_DISPLAY_FALLBACK = "fallback"
        const val DICTIONARY_DISPLAY_ALWAYS_COLLAPSED = "always_collapsed"

        const val SCAN_RESOLUTION_AUTO = ""
        const val SCAN_RESOLUTION_CHARACTER = "character"
        const val SCAN_RESOLUTION_WORD = "word"

        const val SEARCH_RESOLUTION_AUTO = ""
        const val SEARCH_RESOLUTION_LETTER = "letter"
        const val SEARCH_RESOLUTION_WORD = "word"

        const val AI_PROVIDER_OPENAI = "openai"
        const val AI_PROVIDER_CUSTOM = "custom"
        const val AI_PROVIDER_DEEPSEEK = "deepseek"
        // Value written by the first Chimahon BYOK implementation.
        const val AI_PROVIDER_OPENAI_COMPATIBLE = "openai_compatible"
        const val AI_PROVIDER_GEMINI = "gemini"

        const val THINKING_DEFAULT = ""
        const val THINKING_ENABLED = "enabled"
        const val THINKING_DISABLED = "disabled"
        const val THINKING_INTENSITY_HIGH = "high"
        const val THINKING_INTENSITY_MAX = "max"
        const val THINKING_INTENSITY_CUSTOM = "custom"

        const val ROUTING_DEFAULT = ""
        const val ROUTING_ORDER = "order"
        const val ROUTING_ONLY = "only"
        const val ROUTING_IGNORE = "ignore"

        const val DEFAULT_AI_SYSTEM_PROMPT = ""
        const val DEFAULT_AI_PROMPT = "Explain the meaning of '{{target}}' in the following sentence: '{{sentence}}'. Provide a concise explanation focusing on the word's usage and meaning in this specific context."
        private const val LEGACY_AI_SYSTEM_PROMPT = "You are a concise language tutor. Explain the selected term using the supplied sentence context."
        private const val LEGACY_AI_PROMPT = "Explain {{target}} in this context:\n{{sentence}}"

        fun fromJson(json: JSONObject): AnkiProfile {
            val storedProvider = json.optString("aiProvider", AI_PROVIDER_OPENAI)
            val legacyModel = json.optString("aiModel", "")
            val legacyEndpoint = json.optString("aiEndpoint", "")
            val provider = when {
                storedProvider == AI_PROVIDER_OPENAI_COMPATIBLE -> AI_PROVIDER_CUSTOM
                storedProvider == AI_PROVIDER_OPENAI && legacyEndpoint.isNotBlank() &&
                    !legacyEndpoint.contains("api.openai.com", ignoreCase = true) -> AI_PROVIDER_CUSTOM
                else -> storedProvider
            }
            return AnkiProfile(
            id = json.getString("id"),
            name = json.getString("name"),
            ankiEnabled = json.optBoolean("ankiEnabled", false),
            ankiDeck = json.optString("ankiDeck", ""),
            ankiModel = json.optString("ankiModel", "").ifBlank { LapisPreset.MODEL_NAME },
            ankiFieldMap = json.optString("ankiFieldMap", "{}").let { fieldMap ->
                val model = json.optString("ankiModel", "")
                when {
                    model.isBlank() && LapisPreset.isBlankFieldMap(fieldMap) -> LapisPreset.defaultFieldMapJson
                    LapisPreset.isBundledModelName(model) && LapisPreset.isBlankFieldMap(fieldMap) -> LapisPreset.defaultFieldMapJson
                    else -> fieldMap
                }
            },
            ankiTags = json.optString("ankiTags", "chimahon"),
            ankiDupCheck = json.optBoolean("ankiDupCheck", true),
            ankiDupScope = json.optString("ankiDupScope", "deck"),
            ankiDupAction = json.optString("ankiDupAction", "prevent"),
            ankiCropMode = json.optString("ankiCropMode", "full"),
            ankiCropPreset = json.optString("ankiCropPreset", "full"),
            ankiSyncOnCreate = json.optBoolean("ankiSyncOnCreate", false),
            dictionaryOrder = json.optJSONArray("dictionaryOrder")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList(),
            enabledDictionaries = json.optJSONArray("enabledDictionaries")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                ?: emptySet(),
            dictionaryCollapseMode = json.optString("dictionaryCollapseMode", DICTIONARY_COLLAPSE_EXPAND_ALL),
            dictionaryDisplayModes = json.optJSONObject("dictionaryDisplayModes")
                ?.let { obj ->
                    buildMap {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, obj.optString(key, DICTIONARY_DISPLAY_FALLBACK))
                        }
                    }
                }
                ?: emptyMap(),
            languageCode = json.optString("languageCode", ""),
            scanResolution = json.optString("scanResolution", ""),
            searchResolution = json.optString("searchResolution", ""),
            aiEnabled = json.optBoolean("aiEnabled", false),
            aiProvider = provider,
            aiSystemPrompt = json.optString("aiSystemPrompt", DEFAULT_AI_SYSTEM_PROMPT).let {
                if (it == LEGACY_AI_SYSTEM_PROMPT) DEFAULT_AI_SYSTEM_PROMPT else it
            },
            aiPrompt = json.optString("aiPrompt", DEFAULT_AI_PROMPT).let {
                if (it == LEGACY_AI_PROMPT) DEFAULT_AI_PROMPT else it
            },
            aiTemperature = json.optDouble("aiTemperature", 0.2).toFloat().coerceIn(0f, 2f),
            aiAutoGenerate = json.optBoolean("aiAutoGenerate", false),
            aiUnknownWordFallback = json.optBoolean("aiUnknownWordFallback", false),
            aiOpenAiModel = json.optString(
                "aiOpenAiModel",
                legacyModel.takeIf { provider == AI_PROVIDER_OPENAI }.orEmpty().ifBlank { "gpt-5-mini" },
            ),
            aiGeminiModel = json.optString(
                "aiGeminiModel",
                legacyModel.takeIf { provider == AI_PROVIDER_GEMINI }.orEmpty().ifBlank { "gemini-2.5-flash" },
            ),
            aiGeminiThinkingLevel = json.optString("aiGeminiThinkingLevel", THINKING_DEFAULT),
            aiDeepSeekModel = json.optString(
                "aiDeepSeekModel",
                legacyModel.takeIf { provider == AI_PROVIDER_DEEPSEEK }.orEmpty(),
            ),
            aiDeepSeekThinkingMode = json.optString("aiDeepSeekThinkingMode", THINKING_DEFAULT),
            aiDeepSeekThinkingIntensity = json.optString("aiDeepSeekThinkingIntensity", THINKING_DEFAULT),
            aiCustomEndpoint = json.optString(
                "aiCustomEndpoint",
                legacyEndpoint.takeIf { provider == AI_PROVIDER_CUSTOM }.orEmpty(),
            ),
            aiCustomModel = json.optString(
                "aiCustomModel",
                legacyModel.takeIf { provider == AI_PROVIDER_CUSTOM }.orEmpty(),
            ),
            aiCustomProviderRoutingMode = json.optString("aiCustomProviderRoutingMode", ROUTING_DEFAULT),
            aiCustomProviderSlugs = json.optString("aiCustomProviderSlugs", ""),
            aiCustomProviderAllowFallbacks = json.optBoolean("aiCustomProviderAllowFallbacks", true),
            aiCustomThinkingMode = json.optString("aiCustomThinkingMode", THINKING_DEFAULT),
            aiCustomThinkingIntensity = json.optString("aiCustomThinkingIntensity", THINKING_DEFAULT),
            aiCustomThinkingIntensityValue = json.optString("aiCustomThinkingIntensityValue", ""),
            aiCustomRequestBodyJson = json.optString("aiCustomRequestBodyJson", ""),
        )
        }

        /**
         * Migrate legacy flat-key values (passed in from the UI/prefs layer,
         * so this class itself stays free of Android/Prefs dependencies)
         * into a brand-new Default profile.
         */
        fun createDefault(
            defaultName: String = "Default",
            ankiEnabled: Boolean = false,
            ankiDeck: String = "",
            ankiModel: String = LapisPreset.MODEL_NAME,
            ankiFieldMap: String = LapisPreset.defaultFieldMapJson,
            ankiTags: String = "chimahon",
            ankiDupCheck: Boolean = true,
            ankiDupScope: String = "deck",
            ankiDupAction: String = "prevent",
            ankiCropMode: String = "full",
            ankiCropPreset: String = "full",
            ankiSyncOnCreate: Boolean = false,
            dictionaryOrder: List<String> = emptyList(),
        ): AnkiProfile = AnkiProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = defaultName,
            ankiEnabled = ankiEnabled,
            ankiDeck = ankiDeck,
            ankiModel = ankiModel.ifBlank { LapisPreset.MODEL_NAME },
            ankiFieldMap = when {
                ankiModel.isBlank() && LapisPreset.isBlankFieldMap(ankiFieldMap) -> LapisPreset.defaultFieldMapJson
                LapisPreset.isBundledModelName(ankiModel) && LapisPreset.isBlankFieldMap(ankiFieldMap) -> LapisPreset.defaultFieldMapJson
                else -> ankiFieldMap
            },
            ankiTags = ankiTags,
            ankiDupCheck = ankiDupCheck,
            ankiDupScope = ankiDupScope,
            ankiDupAction = ankiDupAction,
            ankiCropMode = ankiCropMode,
            ankiCropPreset = ankiCropPreset,
            ankiSyncOnCreate = ankiSyncOnCreate,
            dictionaryOrder = dictionaryOrder,
            enabledDictionaries = emptySet(),
        )
    }
}
