package chimahon.dictionary

import chimahon.LookupResult
import chimahon.TermResult
import chimahon.anki.AnkiProfile
import chimahon.ocr.effectiveSearchResolution
import chimahon.ocr.nextWordBoundarySubstring

/**
 * Returns the source strings that may be queried for one forward lookup.
 *
 * Yomitan's `word` resolution removes complete trailing words only. A query
 * containing a single word is therefore never shortened to a character
 * prefix. `letter` resolution preserves the traditional longest-to-shortest
 * prefix scan, while taking care not to split a Unicode surrogate pair.
 */
fun lookupSourceCandidates(
    query: String,
    searchResolution: String,
    languageCode: String,
): List<String> {
    if (query.isEmpty()) return emptyList()

    val wordResolution = effectiveSearchResolution(searchResolution, languageCode) ==
        AnkiProfile.SEARCH_RESOLUTION_WORD
    return buildList {
        var current = query
        while (current.isNotEmpty()) {
            add(current)
            current = if (wordResolution) {
                nextWordBoundarySubstring(current)
            } else {
                current.substring(0, current.offsetByCodePoints(current.length, -1))
            }
        }
    }
}

/** Queries each source/deinflection exactly, without native character trimming. */
fun lookupExactSources(
    sources: List<String>,
    languageCode: String,
    deinflector: Deinflector?,
    maxResults: Int,
    queryExact: (String) -> List<TermResult>,
): List<LookupResult> {
    val results = mutableListOf<LookupResult>()
    for (source in sources) {
        val candidates = if (deinflector == null) {
            listOf(source)
        } else {
            buildSet {
                for (preprocessed in deinflector.preProcess(source).distinct()) {
                    deinflector.deinflect(preprocessed, languageCode)
                        .mapTo(this) { it.text }
                }
            }
        }
        for (candidate in candidates) {
            queryExact(candidate).mapTo(results) { term ->
                LookupResult(
                    matched = source,
                    deinflected = candidate,
                    process = emptyArray(),
                    term = term,
                    preprocessorSteps = 0,
                )
            }
        }
    }
    return results.distinctBy { it.term.expression to it.term.reading }.take(maxResults)
}
