package chimahon.ocr

import chimahon.anki.AnkiProfile

/**
 * Text selected for a dictionary lookup.
 *
 * Offsets use Kotlin/Java UTF-16 indices so they can be passed directly to
 * Compose text layouts and the EPUB WebView bridge.
 */
data class LookupSelection(
    val query: String,
    val start: Int,
    val end: Int,
    val tappedOffset: Int,
)

/** Returns whether [char] can begin a dictionary lookup. */
fun isOcrLookupStartChar(char: Char): Boolean {
    if (char.isWhitespace()) return false
    val type = Character.getType(char)
    return type != Character.CONNECTOR_PUNCTUATION.toInt() &&
        type != Character.DASH_PUNCTUATION.toInt() &&
        type != Character.START_PUNCTUATION.toInt() &&
        type != Character.END_PUNCTUATION.toInt() &&
        type != Character.INITIAL_QUOTE_PUNCTUATION.toInt() &&
        type != Character.FINAL_QUOTE_PUNCTUATION.toInt() &&
        type != Character.OTHER_PUNCTUATION.toInt() &&
        type != Character.MATH_SYMBOL.toInt() &&
        type != Character.CURRENCY_SYMBOL.toInt() &&
        type != Character.MODIFIER_SYMBOL.toInt() &&
        type != Character.OTHER_SYMBOL.toInt()
}

/** Returns the lookup suffix beginning at [start], matching manga OCR behavior. */
fun extractOcrLookupText(text: String, start: Int): String {
    val result = StringBuilder()
    var index = start.coerceIn(0, text.length)
    while (index < text.length && isOcrLookupStartChar(text[index])) {
        result.append(text[index])
        index++
    }
    return result.toString()
}

private fun isInternalWordConnector(char: Char): Boolean {
    return char == '\'' || char == '\u2019' || char == '-' || char == '\u2010' || char == '\u2011'
}

private fun isWholeWordCharAt(text: String, index: Int, lineStart: Int, lineEnd: Int): Boolean {
    if (index !in lineStart until lineEnd) return false
    if (isOcrLookupStartChar(text[index])) return true
    if (!isInternalWordConnector(text[index])) return false
    return index > lineStart &&
        index + 1 < lineEnd &&
        isOcrLookupStartChar(text[index - 1]) &&
        isOcrLookupStartChar(text[index + 1])
}

/**
 * Builds the lookup query and its source range for a tap.
 *
 * Character scanning deliberately keeps the existing Yomitan/hoshidicts
 * behavior: the query begins at the tapped character and extends to the next
 * delimiter so the backend can try successively shorter prefixes. Word
 * scanning expands in both directions to the token containing the tap.
 */
fun buildLookupSelection(
    text: String,
    tapOffset: Int,
    wholeWord: Boolean,
    lineStart: Int = 0,
    lineEnd: Int = text.length,
): LookupSelection? {
    if (text.isEmpty()) return null
    val safeLineStart = lineStart.coerceIn(0, text.length)
    val safeLineEnd = lineEnd.coerceIn(safeLineStart, text.length)
    if (safeLineStart == safeLineEnd) return null
    val tap = tapOffset.coerceIn(safeLineStart, safeLineEnd - 1)

    if (!wholeWord) {
        if (!isOcrLookupStartChar(text[tap])) return null
        val query = extractOcrLookupText(text, tap)
            .take((safeLineEnd - tap).coerceAtLeast(0))
        if (query.isBlank()) return null
        return LookupSelection(
            query = query,
            start = tap,
            end = tap + query.length,
            tappedOffset = tap,
        )
    }

    if (!isWholeWordCharAt(text, tap, safeLineStart, safeLineEnd)) return null
    var start = tap
    while (start > safeLineStart && isWholeWordCharAt(text, start - 1, safeLineStart, safeLineEnd)) {
        start--
    }
    var end = tap + 1
    while (end < safeLineEnd && isWholeWordCharAt(text, end, safeLineStart, safeLineEnd)) {
        end++
    }
    if (start >= end) return null
    return LookupSelection(
        query = text.substring(start, end),
        start = start,
        end = end,
        tappedOffset = tap,
    )
}

/** Resolves [resolution] and delegates to [buildLookupSelection]. */
fun buildLookupSelection(
    text: String,
    tapOffset: Int,
    resolution: String,
    languageCode: String?,
    lineStart: Int = 0,
    lineEnd: Int = text.length,
): LookupSelection? {
    return buildLookupSelection(
        text = text,
        tapOffset = tapOffset,
        wholeWord = shouldScanWholeWord(resolution, languageCode),
        lineStart = lineStart,
        lineEnd = lineEnd,
    )
}

private val CJK_OcrLanguageCodes = setOf("ja", "zh", "yue", "ko")
private val mixedOrUnknownOcrLanguageCodes = setOf("all", "other", "multi", "unknown")

/** Normalizes a BCP-47-style language code ("es-419", "zh_Hant") to its base. */
fun normalizeOcrLanguageCode(code: String): String {
    return code.trim().substringBefore('-').substringBefore('_').lowercase()
}

/**
 * Whether whole-word scan expansion applies for [languageCode]: true for
 * space-delimited languages, false for CJK script and for mixed / unknown
 * ("all languages") codes.
 */
fun isLanguageWholeWordScan(languageCode: String?): Boolean {
    if (languageCode == null) return false
    val lang = normalizeOcrLanguageCode(languageCode)
    if (lang.isBlank() || lang in mixedOrUnknownOcrLanguageCodes) return false
    return lang !in CJK_OcrLanguageCodes
}

/**
 * Resolves whether the scan should expand to whole words given an optional
 * explicit [resolution] ("word" / "character") and a [languageCode].
 * An explicit resolution always wins; otherwise the value is derived from the
 * language (word for space-delimited languages, character for CJK /
 * "all languages").
 */
fun shouldScanWholeWord(resolution: String, languageCode: String?): Boolean {
    return when (resolution.trim().lowercase()) {
        AnkiProfile.SCAN_RESOLUTION_WORD -> true
        AnkiProfile.SCAN_RESOLUTION_CHARACTER -> false
        else -> isLanguageWholeWordScan(languageCode)
    }
}

/**
 * Resolves a profile's effective scan resolution ("word"/"character") for
 * [languageCode]. An explicit stored value wins; "auto" (empty) derives from
 * the language (word for space-delimited, character for CJK / "all languages").
 */
fun effectiveScanResolution(stored: String, languageCode: String?): String {
    val value = stored.trim().lowercase()
    if (value == AnkiProfile.SCAN_RESOLUTION_WORD ||
        value == AnkiProfile.SCAN_RESOLUTION_CHARACTER
    ) {
        return value
    }
    return if (isLanguageWholeWordScan(languageCode)) {
        AnkiProfile.SCAN_RESOLUTION_WORD
    } else {
        AnkiProfile.SCAN_RESOLUTION_CHARACTER
    }
}

/**
 * Resolves a profile's effective search resolution: an explicit value wins,
 * otherwise it derives from the language like [effectiveScanResolution]
 * ("word" vs "letter" for CJK / "all languages").
 */
fun effectiveSearchResolution(stored: String, languageCode: String?): String {
    val value = stored.trim().lowercase()
    if (value == AnkiProfile.SEARCH_RESOLUTION_WORD ||
        value == AnkiProfile.SEARCH_RESOLUTION_LETTER
    ) {
        return value
    }
    return if (isLanguageWholeWordScan(languageCode)) {
        AnkiProfile.SEARCH_RESOLUTION_WORD
    } else {
        AnkiProfile.SEARCH_RESOLUTION_LETTER
    }
}

/**
 * Whether the source and profile languages can share a dictionary profile:
 * mixed/unknown source or profile always matches; otherwise the resolved
 * base language codes must be equal.
 */
fun isOcrAllowedForLanguage(sourceLanguage: String, profileLanguage: String): Boolean {
    val sourceLang = normalizeOcrLanguageCode(sourceLanguage)
    if (sourceLang.isBlank() || sourceLang in mixedOrUnknownOcrLanguageCodes) return true

    val profileLang = normalizeOcrLanguageCode(profileLanguage)
    if (profileLang.isBlank() || profileLang in mixedOrUnknownOcrLanguageCodes) return true

    return sourceLang == profileLang
}

/**
 * Returns the whole word containing [tapOffset]; characters, expanding
 * backward to [lineStart] and forward to [lineEnd] while [isOcrLookupStartChar]
 * holds. The clamp prevents crossing into a neighboring OCR line (block text
 * concatenates lines without separators).
 */
fun extractWholeWord(text: String, tapOffset: Int, lineStart: Int, lineEnd: Int): String {
    return buildLookupSelection(
        text = text,
        tapOffset = tapOffset,
        wholeWord = true,
        lineStart = lineStart,
        lineEnd = lineEnd,
    )?.query.orEmpty()
}

private val wordBoundaryRegex = Regex("""[^\p{L}][\p{L}\p{N}]*$""")

/**
 * Yomitan's `translation.searchResolution='word'` iteration step: returns
 * [current] cut at the last word boundary (dropping the trailing word), or ""
 * when there are no boundaries to cut.
 */
fun nextWordBoundarySubstring(current: String): String {
    if (current.isEmpty()) return ""
    val match = wordBoundaryRegex.find(current)
    val nextLength = match?.range?.first ?: -1
    return if (nextLength > 0) current.substring(0, nextLength) else ""
}
