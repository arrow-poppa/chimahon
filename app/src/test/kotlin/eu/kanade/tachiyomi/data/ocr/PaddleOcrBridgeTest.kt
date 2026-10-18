package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import chimahon.ocr.EngineLine
import chimahon.ocr.OcrEngine
import chimahon.ocr.OcrLanguage
import chimahon.ocr.PaddleModelSelection
import chimahon.ocr.PaddleRecognizer
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.Closeable

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PaddleOcrBridgeTest {
    @Test
    fun `model switch waits for inference before closing and replacing the native engine`() = runTest {
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val bridge = PaddleOcrBridge(mockk<Context>()) { selection ->
            events += "create:${selection.recognizer.id}"
            object : OcrEngine, Closeable {
                override val name = selection.recognizer.id
                override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
                    events += "start:$name"
                    if (selection.recognizer == PaddleRecognizer.LATIN) release.await()
                    events += "finish:$name"
                    return emptyList()
                }
                override fun close() {
                    events += "close:$name"
                }
            }
        }
        val first = async { bridge.recognize(byteArrayOf(), OcrLanguage.PORTUGUESE, PaddleModelSelection(PaddleRecognizer.LATIN)) }
        runCurrent()
        val second = async { bridge.recognize(byteArrayOf(), OcrLanguage.KOREAN, PaddleModelSelection(PaddleRecognizer.KOREAN)) }
        runCurrent()
        assertEquals(listOf("create:rec_tiny", "start:rec_tiny"), events)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("create:rec_tiny", "start:rec_tiny", "finish:rec_tiny", "close:rec_tiny", "create:rec_korean", "start:rec_korean", "finish:rec_korean"), events)
        bridge.invalidateModels()
        assertEquals("close:rec_korean", events.last())
        assertFalse(bridge.isInitialized)
    }
}
