package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import chimahon.ocr.EngineLine
import chimahon.ocr.OcrEngine
import chimahon.ocr.OcrLanguage
import chimahon.ocr.PaddleModelSelection
import chimahon.ocr.PaddleRecognizer
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable

class PaddleOcrBridge(
    private val context: Context,
    private val engineFactory: ((PaddleModelSelection) -> OcrEngine)? = null,
) {

    private var engine: OcrEngine? = null
    private var loadedSelection: PaddleModelSelection? = null
    private val engineMutex = Mutex()

    val isAvailable: Boolean get() = engineFactory != null || BuildConfig.HAS_LOCAL_OCR

    @Volatile
    var isInitialized: Boolean = false
        private set

    private fun init(selection: PaddleModelSelection): Boolean {
        if (!isAvailable) return false
        if (isInitialized && loadedSelection == selection) return true
        destroy()
        try {
            val instance = engineFactory?.invoke(selection) ?: createNativeEngine(selection)
            engine = instance
            loadedSelection = selection
            isInitialized = true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "PaddleOcrBridge: failed to initialize selected models" }
        }
        return isInitialized
    }

    private fun createNativeEngine(selection: PaddleModelSelection): OcrEngine {
        val clazz = Class.forName("chimahon.local.ocr.PaddleOcrEngine")
        val instance = clazz.getConstructor(Context::class.java, String::class.java, String::class.java)
            .newInstance(context, selection.recognizer.id, selection.detector.id) as OcrEngine
        clazz.getMethod("init").invoke(instance)
        if (!(clazz.getMethod("isInitialized").invoke(instance) as Boolean)) {
            (instance as Closeable).close()
            error("Paddle OCR could not initialize selected models")
        }
        return instance
    }

    suspend fun recognize(bytes: ByteArray, language: OcrLanguage, selection: PaddleModelSelection = PaddleModelSelection(PaddleRecognizer.MANGA)): List<EngineLine> = engineMutex.withLock {
        check(init(selection)) { "Paddle OCR could not initialize the selected models" }
        engine?.recognize(bytes, language) ?: emptyList()
    }

    suspend fun invalidateModels() = engineMutex.withLock { destroy() }

    private fun destroy() {
        if (engine != null) {
            try {
                (engine as Closeable).close()
            } catch (_: Exception) {}
            engine = null
            loadedSelection = null
            isInitialized = false
        }
    }
}
