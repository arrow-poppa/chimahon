package chimahon.ocr

/** Content version stays unchanged when hosting moves, preserving installed models and caches. */
const val PADDLE_MODELS_VERSION = "lookitan-ocr-v1"
const val PADDLE_MODELS_RELEASE_TAG = "ocr-models-v1"

enum class PaddleRecognizer(val id: String, val label: String) {
    MANGA("manga", "PaddleOCR v6 — Manga"),
    LATIN("rec_tiny", "PaddleOCR v6 — Latin (Tiny)"),
    MULTILINGUAL("rec_small", "PaddleOCR v6 — Multilingual (Chinese / Japanese)"),
    CYRILLIC("rec_cyrillic", "PaddleOCR v5 — Cyrillic"),
    ARABIC("rec_arabic", "PaddleOCR v5 — Arabic"),
    GREEK("rec_el", "PaddleOCR v5 — Greek"),
    KOREAN("rec_korean", "PaddleOCR v5 — Korean"),
    ;

    val bundle: String get() = "paddle_$id"
    val preference: String get() = if (this == MANGA) "paddle" else "paddle_$id"
}

enum class PaddleDetector(val id: String, val label: String) {
    TINY("det_tiny", "PP-OCRv6 Tiny (CPU, default)"),
    SMALL("det_small", "PP-OCRv6 Small (CPU)"),
    ;

    val bundle: String get() = "paddle_$id"

    companion object {
        fun fromPreference(value: String): PaddleDetector = entries.find { it.id == value } ?: TINY
    }
}

data class PaddleModelSelection(
    val recognizer: PaddleRecognizer,
    val detector: PaddleDetector = PaddleDetector.TINY,
) {
    val cacheVariant: String
        get() = if (recognizer == PaddleRecognizer.MANGA) "paddle" else "${recognizer.bundle}_${detector.id}_$PADDLE_MODELS_VERSION"

    val bundles: List<String>
        get() = if (recognizer == PaddleRecognizer.MANGA) emptyList() else listOf(detector.bundle, recognizer.bundle)
}

/** Only catalogued directories may be installed or deleted. */
val paddleComponentBundles: Set<String> = PaddleDetector.entries.map { it.bundle }.toSet() +
    PaddleRecognizer.entries.filter { it != PaddleRecognizer.MANGA }.map { it.bundle } + "paddle_runtime"

/** Arabic CTC emits visual order; preserve Latin/number runs as PaddleOCR's pred_reverse does. */
fun restorePaddleArabicText(text: String): String {
    val groups = mutableListOf<String>()
    val latin = StringBuilder()
    fun flushLatin() {
        if (latin.isNotEmpty()) {
            groups += latin.toString()
            latin.setLength(0)
        }
    }
    for (codePoint in text.codePoints().toArray()) {
        if (codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code ||
            codePoint in '0'.code..'9'.code || (codePoint <= 127 && codePoint.toChar() in " :*./%+-")
        ) {
            latin.appendCodePoint(codePoint)
        } else {
            flushLatin()
            groups += String(Character.toChars(codePoint))
        }
    }
    flushLatin()
    return groups.asReversed().joinToString("")
}
