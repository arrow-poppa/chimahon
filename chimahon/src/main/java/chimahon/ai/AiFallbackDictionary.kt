package chimahon.ai

import chimahon.FrequencyEntry
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.PitchEntry
import chimahon.TermResult
import chimahon.TransformGroup

/** Virtual dictionary name used for entries which exist only to support AI fallback. */
const val AI_FALLBACK_DICTIONARY_NAME = "AI Fallback"

private val noWordBoundaryLanguages = setOf(
    "ja", "zh", "yue", "th", "lo", "km", "my", "bo",
)
private val leadingWordPattern = Regex("""^[\p{L}\p{M}\p{N}'\u2019\u2010\u2011-]+""")
private val leadingEdgeSeparators = Regex("""^['\u2019\u2010\u2011-]+""")
private val trailingEdgeSeparators = Regex("""['\u2019\u2010\u2011-]+$""")
private val letterPattern = Regex("""\p{L}""")

data class AiFallbackToken(
    val term: String,
    val textLength: Int,
)

/**
 * Extracts the leading word for the AI fallback using the same token rules as
 * Yomitan's virtual AI dictionary. The returned length remains tied to the
 * original lookup text so highlighting is not based on a one-letter result.
 */
fun getAiFallbackToken(text: String, languageCode: String = ""): AiFallbackToken? {
    val language = languageCode.trim().substringBefore('-').substringBefore('_').lowercase()
    if (language in noWordBoundaryLanguages) return null

    val matched = leadingWordPattern.find(text.replace('\u2019', '\''))?.value ?: return null
    val scanned = matched.replace(trailingEdgeSeparators, "")
    val term = scanned.replace(leadingEdgeSeparators, "")
    if (term.isEmpty() || !letterPattern.containsMatchIn(term)) return null
    return AiFallbackToken(term = term, textLength = scanned.length)
}

/** Creates the minimal, exportable entry used when no installed dictionary matches. */
fun createAiFallbackLookupResult(token: AiFallbackToken): LookupResult = LookupResult(
    matched = token.term,
    deinflected = token.term,
    process = emptyArray<TransformGroup>(),
    term = TermResult(
        expression = token.term,
        reading = token.term,
        rules = "",
        glossaries = arrayOf(
            GlossaryEntry(
                dictName = AI_FALLBACK_DICTIONARY_NAME,
                glossary = "",
                definitionTags = "",
                termTags = "",
            ),
        ),
        frequencies = emptyArray<FrequencyEntry>(),
        pitches = emptyArray<PitchEntry>(),
    ),
    preprocessorSteps = 0,
)

fun LookupResult.isAiFallbackDictionaryEntry(): Boolean =
    term.glossaries.any { it.dictName == AI_FALLBACK_DICTIONARY_NAME }

/** Adds the generated explanation only to the export copy, not to popup rendering. */
fun LookupResult.withAiFallbackExplanation(explanation: String): LookupResult {
    if (!isAiFallbackDictionaryEntry()) return this
    return copy(
        term = term.copy(
            glossaries = term.glossaries.map { glossary ->
                if (glossary.dictName == AI_FALLBACK_DICTIONARY_NAME) {
                    glossary.copy(glossary = explanation)
                } else {
                    glossary
                }
            }.toTypedArray(),
        ),
    )
}
