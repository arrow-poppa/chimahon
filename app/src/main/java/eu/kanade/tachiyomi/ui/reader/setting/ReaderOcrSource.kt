package eu.kanade.tachiyomi.ui.reader.setting

import chimahon.ocr.PaddleRecognizer
import eu.kanade.tachiyomi.data.ocr.OcrEngineType

enum class ReaderOcrSource(
    val usesMokuro: Boolean,
    val usesPersistentCache: Boolean,
    val persistentCacheVariant: String? = null,
    val recognitionEngine: OcrEngineType?,
) {
    AUTOMATIC(
        usesMokuro = true,
        usesPersistentCache = true,
        recognitionEngine = null,
    ),
    MOKURO(
        usesMokuro = true,
        usesPersistentCache = false,
        recognitionEngine = null,
    ),
    GOOGLE_LENS(
        usesMokuro = false,
        usesPersistentCache = false,
        persistentCacheVariant = "google_lens",
        recognitionEngine = OcrEngineType.CLOUD,
    ),
    LOCAL(
        usesMokuro = false,
        usesPersistentCache = false,
        recognitionEngine = OcrEngineType.LOCAL,
    ),
    PADDLE(
        usesMokuro = false,
        usesPersistentCache = false,
        persistentCacheVariant = "paddle",
        recognitionEngine = OcrEngineType.PADDLE,
    ),
    PADDLE_LATIN(usesMokuro = false, usesPersistentCache = false, persistentCacheVariant = "paddle_latin", recognitionEngine = OcrEngineType.PADDLE_LATIN),
    PADDLE_MULTILINGUAL(usesMokuro = false, usesPersistentCache = false, persistentCacheVariant = "paddle_multilingual", recognitionEngine = OcrEngineType.PADDLE_MULTILINGUAL),
    PADDLE_CYRILLIC(usesMokuro = false, usesPersistentCache = false, persistentCacheVariant = "paddle_cyrillic", recognitionEngine = OcrEngineType.PADDLE_CYRILLIC),
    PADDLE_ARABIC(usesMokuro = false, usesPersistentCache = false, persistentCacheVariant = "paddle_arabic", recognitionEngine = OcrEngineType.PADDLE_ARABIC),
    PADDLE_GREEK(usesMokuro = false, usesPersistentCache = false, persistentCacheVariant = "paddle_greek", recognitionEngine = OcrEngineType.PADDLE_GREEK),
    PADDLE_KOREAN(usesMokuro = false, usesPersistentCache = false, persistentCacheVariant = "paddle_korean", recognitionEngine = OcrEngineType.PADDLE_KOREAN),
    ;

    val persistsOcrResults: Boolean
        get() = usesPersistentCache || persistentCacheVariant != null

    fun cacheVariant(selection: eu.kanade.tachiyomi.data.ocr.OcrSelection): String? = selection.cacheVariant ?: persistentCacheVariant

    fun usesGenericPaddleDetector(defaultEngine: OcrEngineType): Boolean {
        if (this == MOKURO) return false
        val recognizer = (recognitionEngine ?: defaultEngine).paddleRecognizer
        return recognizer != null && recognizer != PaddleRecognizer.MANGA
    }

    companion object {
        fun availableSources(localOcrAvailable: Boolean, mokuroAvailable: Boolean): List<ReaderOcrSource> {
            return entries.filter { source ->
                when (source) {
                    LOCAL, PADDLE, PADDLE_LATIN, PADDLE_MULTILINGUAL, PADDLE_CYRILLIC, PADDLE_ARABIC, PADDLE_GREEK, PADDLE_KOREAN -> localOcrAvailable
                    MOKURO -> mokuroAvailable
                    else -> true
                }
            }
        }
    }
}
