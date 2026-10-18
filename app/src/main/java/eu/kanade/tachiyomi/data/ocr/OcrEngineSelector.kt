package eu.kanade.tachiyomi.data.ocr

import android.graphics.Bitmap
import chimahon.ocr.LensClient
import chimahon.ocr.OcrLanguage
import chimahon.ocr.OcrResult
import chimahon.ocr.PaddleDetector
import chimahon.ocr.PaddleModelSelection
import chimahon.ocr.PaddleRecognizer
import chimahon.ocr.processImageWithChunks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream

enum class OcrEngineType(val preference: String, val paddleRecognizer: PaddleRecognizer? = null) {
    CLOUD("cloud"),
    LOCAL("local"),
    PADDLE("paddle", PaddleRecognizer.MANGA),
    PADDLE_LATIN("paddle_rec_tiny", PaddleRecognizer.LATIN),
    PADDLE_MULTILINGUAL("paddle_rec_small", PaddleRecognizer.MULTILINGUAL),
    PADDLE_CYRILLIC("paddle_rec_cyrillic", PaddleRecognizer.CYRILLIC),
    PADDLE_ARABIC("paddle_rec_arabic", PaddleRecognizer.ARABIC),
    PADDLE_GREEK("paddle_rec_el", PaddleRecognizer.GREEK),
    PADDLE_KOREAN("paddle_rec_korean", PaddleRecognizer.KOREAN),
    ;

    val isOnDevice: Boolean get() = this != CLOUD

    companion object {
        fun fromPreference(value: String): OcrEngineType = entries.find { it.preference == value } ?: CLOUD
    }
}

data class OcrSelection(val engine: OcrEngineType, val detector: PaddleDetector = PaddleDetector.TINY) {
    val paddle: PaddleModelSelection? get() = engine.paddleRecognizer?.let { PaddleModelSelection(it, detector) }
    val cacheVariant: String? get() = paddle?.cacheVariant
}

fun currentOcrSelection(engine: OcrEngineType? = null): OcrSelection {
    val preferences = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()
    return OcrSelection(engine ?: OcrEngineType.fromPreference(preferences.ocrEngine().get()), PaddleDetector.fromPreference(preferences.paddleDetector().get()))
}

suspend fun recognizePage(
    bytes: ByteArray,
    language: OcrLanguage,
    engineType: OcrEngineType? = null,
    selection: OcrSelection = currentOcrSelection(engineType),
): List<OcrResult> {
    val resolvedEngineType = selection.engine

    if (resolvedEngineType == OcrEngineType.LOCAL) {
        val localOcrBridge = Injekt.get<LocalOcrBridge>()
        val modelDownloader = Injekt.get<ModelDownloader>()
        if (!modelDownloader.isDownloaded) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local selected but models not downloaded, triggering download" }
            modelDownloader.triggerDownload()
            return emptyList()
        }
        if (!localOcrBridge.isAvailable) return emptyList()
        if (!localOcrBridge.isInitialized) {
            localOcrBridge.init()
        }
        if (!localOcrBridge.isInitialized) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local engine failed to initialize" }
            return emptyList()
        }
        val result = processImageWithChunks(bytes, language) { chunk ->
            val chunkBytes = withContext(Dispatchers.Default) {
                chunk.bitmap.toJpegBytes(85)
            }
            val lines = localOcrBridge.recognize(chunkBytes, language)
            chunk.bitmap.recycle()
            lines
        }
        if (result.isEmpty()) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local engine returned no results" }
            return emptyList()
        }
        return result
    }

    if (selection.paddle != null) {
        val paddleSelection = checkNotNull(selection.paddle)
        val paddleOcrBridge = Injekt.get<PaddleOcrBridge>()
        check(paddleOcrBridge.isAvailable) { "Paddle OCR is unavailable in this build" }
        if (paddleSelection.recognizer == PaddleRecognizer.MANGA) {
            val models = Injekt.get<ModelDownloader>()
            if (!models.isPaddleDownloaded) models.downloadPaddleAndExtract().getOrThrow()
        } else {
            Injekt.get<PaddleModelDownloader>().ensure(paddleSelection)
        }
        val result = processImageWithChunks(bytes, language) { chunk ->
            val chunkBytes = withContext(Dispatchers.Default) {
                chunk.bitmap.toJpegBytes(85)
            }
            try {
                paddleOcrBridge.recognize(chunkBytes, language, paddleSelection)
            } finally {
                chunk.bitmap.recycle()
            }
        }
        if (result.isEmpty()) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "paddle engine returned no results" }
            return emptyList()
        }
        return result
    }

    val lensClient = Injekt.get<LensClient>()
    val debugResult = lensClient.getDebugOcrData(bytes = bytes, language = language)
    return debugResult.mergedResults
}

suspend fun recognizePage(
    bitmap: Bitmap,
    language: OcrLanguage,
    engineType: OcrEngineType? = null,
    selection: OcrSelection = currentOcrSelection(engineType),
): List<OcrResult> {
    val resolvedEngineType = selection.engine

    if (resolvedEngineType == OcrEngineType.LOCAL) {
        val localOcrBridge = Injekt.get<LocalOcrBridge>()
        val modelDownloader = Injekt.get<ModelDownloader>()
        if (!modelDownloader.isDownloaded) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local selected but models not downloaded, triggering download" }
            modelDownloader.triggerDownload()
            return emptyList()
        }
        if (!localOcrBridge.isAvailable) return emptyList()
        if (!localOcrBridge.isInitialized) {
            localOcrBridge.init()
        }
        if (!localOcrBridge.isInitialized) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local engine failed to initialize" }
            return emptyList()
        }
        val result = processImageWithChunks(bitmap, language) { chunk ->
            val chunkBytes = withContext(Dispatchers.Default) {
                chunk.bitmap.toJpegBytes(85)
            }
            val lines = localOcrBridge.recognize(chunkBytes, language)
            if (chunk.bitmap !== bitmap) chunk.bitmap.recycle()
            lines
        }
        if (result.isEmpty()) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local engine returned no results" }
            return emptyList()
        }
        return result
    }

    if (selection.paddle != null) {
        val paddleSelection = checkNotNull(selection.paddle)
        val paddleOcrBridge = Injekt.get<PaddleOcrBridge>()
        check(paddleOcrBridge.isAvailable) { "Paddle OCR is unavailable in this build" }
        if (paddleSelection.recognizer == PaddleRecognizer.MANGA) {
            val models = Injekt.get<ModelDownloader>()
            if (!models.isPaddleDownloaded) models.downloadPaddleAndExtract().getOrThrow()
        } else {
            Injekt.get<PaddleModelDownloader>().ensure(paddleSelection)
        }
        val result = processImageWithChunks(bitmap, language) { chunk ->
            val chunkBytes = withContext(Dispatchers.Default) {
                chunk.bitmap.toJpegBytes(85)
            }
            try {
                paddleOcrBridge.recognize(chunkBytes, language, paddleSelection)
            } finally {
                if (chunk.bitmap !== bitmap) chunk.bitmap.recycle()
            }
        }
        if (result.isEmpty()) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "paddle engine returned no results" }
            return emptyList()
        }
        return result
    }

    val lensClient = Injekt.get<LensClient>()
    val debugResult = lensClient.getDebugOcrData(bitmap = bitmap, language = language)
    return debugResult.mergedResults
}

private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
    return ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
}
