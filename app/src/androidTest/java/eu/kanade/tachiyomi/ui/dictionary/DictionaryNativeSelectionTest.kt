package eu.kanade.tachiyomi.ui.dictionary

import android.content.ClipboardManager
import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import chimahon.DictionaryRepository
import chimahon.anki.AnkiProfile
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLookupPopup
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real touch input: never creates a DOM selection or substitutes an HTML menu. */
class DictionaryNativeSelectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var webView: WebView
    private var dismissed = false

    @After
    fun captureFailureEvidenceAndRelease() {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "selection-tests").apply { mkdirs() }
        device.takeScreenshot(File(directory, "screen.png"))
        device.dumpWindowHierarchy(File(directory, "windows.xml"))
        if (::webView.isInitialized) compose.runOnUiThread { webView.destroy() }
    }

    @Test
    fun inlinePopupLongPressOpensNativeCopyAndPreservesPopup() {
        mountPopup(windowPopup = false)
        selectWord()
        assertNativeSelectionAndCopy()
    }

    @Test
    fun recursiveWindowPopupLongPressOpensNativeCopyAndPreservesPopup() {
        mountPopup(windowPopup = true)
        selectWord()
        assertNativeSelectionAndCopy()
    }

    @Test
    fun heldDragCanExpandSelectionWithoutDismissingPopup() {
        mountPopup(windowPopup = false)
        val start = wordPoint(2)
        val end = wordPoint(21)
        val downTime = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, start, downTime)
        SystemClock.sleep(900)
        for (step in 1..20) {
            touch(MotionEvent.ACTION_MOVE, PointF(start.x + (end.x - start.x) * step / 20, start.y), downTime)
            SystemClock.sleep(25)
        }
        touch(MotionEvent.ACTION_UP, end, downTime)
        assertTrue("Native selection menu did not open", device.wait(Until.hasObject(By.text("Copy")), 5_000))
        assertFalse("Selecting text dismissed the dictionary", dismissed)
        assertTrue("Held drag must expand beyond one word: ${selection()}", selection().trim().contains(' '))
    }

    private fun mountPopup(windowPopup: Boolean) {
        compose.runOnUiThread {
            val preferences = Injekt.get<DictionaryPreferences>()
            preferences.popupSwipeToDismiss().set(true)
            preferences.popupWidth().set(360)
            preferences.popupHeight().set(420)
            preferences.popupMode().set("floating")
            DictionaryPopupWebViewWarmup.warm(compose.activity)
            webView = DictionaryPopupWebViewWarmup.acquire(compose.activity)
        }
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier.fillMaxSize().background(Color.White).clickable {
                        dismissed = true
                        visible = false
                    },
                ) {
                    OcrLookupPopup(
                        visible = visible,
                        lookupString = "Selectable",
                        fullText = "Selectable definition for precise native selection.",
                        charOffset = 0,
                        onDismiss = {
                            dismissed = true
                            visible = false
                        },
                        webView = webView,
                        repository = DictionaryRepository(compose.activity.getExternalFilesDir(null)),
                        anchorX = 20f,
                        anchorY = 30f,
                        isVertical = false,
                        activeProfile = AnkiProfile(id = "selection-test", name = "Selection", languageCode = "en"),
                        usePopup = windowPopup,
                        initialEntryJsons = listOf(ENTRY),
                    )
                }
            }
        }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (js("!!document.querySelector('.gloss-content')") == true) return
            SystemClock.sleep(100)
        }
        error("Dictionary renderer did not become ready")
    }

    private fun selectWord() {
        val point = wordPoint(2)
        val downTime = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, point, downTime)
        SystemClock.sleep(900)
        touch(MotionEvent.ACTION_UP, point, downTime)
    }

    private fun assertNativeSelectionAndCopy() {
        assertTrue(
            "Native Android Copy menu did not open; selection=${selection()}; dismissed=$dismissed",
            device.wait(Until.hasObject(By.text("Copy")), 5_000),
        )
        assertFalse("Long press dismissed the dictionary", dismissed)
        val selected = selection().trim()
        assertEquals("Selectable", selected)
        device.findObject(By.text("Copy")).click()
        SystemClock.sleep(200)
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(selected, clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity)?.toString())
        }
        assertFalse("Copy dismissed the dictionary", dismissed)
    }

    private fun wordPoint(offset: Int): PointF {
        val result = js(
            """(() => {
            const node = document.createTreeWalker(document.querySelector('.gloss-content'), NodeFilter.SHOW_TEXT).nextNode();
            const range = document.createRange();
            range.setStart(node, $offset); range.setEnd(node, ${offset + 1});
            const r = range.getBoundingClientRect();
            return [r.x + r.width / 2, r.y + r.height / 2, devicePixelRatio];
        })()""",
        ) as JSONArray
        val location = IntArray(2)
        compose.runOnUiThread { webView.getLocationOnScreen(location) }
        return PointF(
            location[0] + (result.getDouble(0) * result.getDouble(2)).toFloat(),
            location[1] + (result.getDouble(1) * result.getDouble(2)).toFloat(),
        )
    }

    private fun selection() = js("getSelection().toString()") as String

    private fun js(expression: String): Any? {
        val latch = CountDownLatch(1)
        var result: Any? = null
        compose.runOnUiThread {
            webView.evaluateJavascript(expression) {
                result = JSONTokener(it).nextValue()
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "WebView JavaScript callback timed out" }
        return result
    }

    private fun touch(action: Int, point: PointF, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    companion object {
        private const val ENTRY = """{"index":0,"matched":"Selectable","process":[],"term":{"expression":"Selectable","reading":"","glossaries":[{"dictName":"Native Selection Test","glossary":"Selectable definition for precise native selection.","definitionTags":"","termTags":""}],"frequencies":[],"pitches":[]}}"""
    }
}
