package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import eu.kanade.domain.ui.UiPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference

/**
 * Keeps a dictionary popup WebView warmed without attaching it to a reader view tree.
 *
 * Android's ViewGroup attach pass assumes its child array is stable while dispatching
 * attached state. Warming the popup by adding a hidden WebView to a reader root can race
 * with that pass on Android 16, so the warm shell lives here until a popup actually mounts.
 */
internal object DictionaryPopupWebViewWarmup {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var warmedWebView: WebView? = null
    private var warmedOwner: WeakReference<Context>? = null

    fun warm(context: Context, languageCode: String = "") {
        val appContext = context.applicationContext
        runOnMain {
            val owner = context.dictionaryActivity() ?: context
            val cached = warmedWebView?.takeIf { it.parent == null }
            val webView = if (cached != null && warmedOwner?.get() === owner) {
                cached
            } else {
                cached?.destroy()
                createWebView(context)
            }
            warmedWebView = webView
            warmedOwner = WeakReference(owner)
            webView.setBaseContext(context)
            try {
                val shell = buildWarmShell(appContext, languageCode)
                prepareDictionaryWebViewShell(appContext, webView, shell.bootstrapHtml, shell.backgroundColor)
            } finally {
                // The provider keeps a weak window owner; the pooled view must not retain the Activity.
                webView.setBaseContext(appContext)
            }
        }
    }

    fun acquire(context: Context, languageCode: String = ""): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Dictionary popup WebView must be acquired on the main thread"
        }

        val appContext = context.applicationContext
        val owner = context.dictionaryActivity() ?: context
        val cached = warmedWebView?.takeIf { it.parent == null }
        val sameOwner = warmedOwner?.get() === owner
        warmedWebView = null
        warmedOwner = null
        // Chromium binds its WindowAndroid when WebView is constructed. Rebinding only
        // MutableContextWrapper later leaves native selection actions on the old window.
        val webView = if (cached != null && sameOwner) {
            cached
        } else {
            cached?.destroy()
            createWebView(context)
        }

        webView.setBaseContext(context)
        val shell = buildWarmShell(appContext, languageCode)
        prepareDictionaryWebViewShell(appContext, webView, shell.bootstrapHtml, shell.backgroundColor)
        return webView
    }

    fun recycle(context: Context, webView: WebView?) {
        if (webView == null) return
        val appContext = context.applicationContext
        runOnMain {
            (webView.parent as? ViewGroup)?.removeView(webView)
            stopDictionaryAudio(webView)
            webView.alpha = 0f
            webView.isClickable = false
            webView.isLongClickable = false
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
            webView.setOnTouchListener(null)
            webView.setOnKeyListener(null)
            webView.setOnLongClickListener(null)
            webView.resetDictionaryCallbacks()
            webView.setBaseContext(appContext)

            if (warmedWebView == null || warmedWebView === webView) {
                warmedWebView = webView
                warmedOwner = WeakReference(context.dictionaryActivity() ?: context)
            } else {
                webView.destroy()
            }
        }
    }

    private fun createWebView(context: Context): WebView {
        return DictionarySelectionWebView(context)
    }

    private fun WebView.setBaseContext(context: Context) {
        (this.context as? MutableContextWrapper)?.baseContext = context
    }

    private fun buildWarmShell(context: Context, languageCode: String): WarmShell {
        val dictionaryPreferences = Injekt.get<DictionaryPreferences>()
        val uiPreferences = Injekt.get<UiPreferences>()
        val themeMode = dictionaryPreferences.themeMode().get()
        val customColor = dictionaryPreferences.customColor().get()
        val eInkMode = dictionaryPreferences.eInkMode().get()
        val resolved = resolveDictionaryTheme(
            themeMode = themeMode,
            customColor = customColor,
            eInkMode = eInkMode,
            forceDefaultTheme = false,
            systemIsDark = context.isSystemDark(),
            appThemeMode = uiPreferences.themeMode().get(),
            appAmoled = uiPreferences.themeDarkAmoled().get(),
            appColorTheme = uiPreferences.colorTheme().get(),
        )
        val colorScheme = if (resolved.useAppTheme) {
            getAppThemeColorScheme(context, resolved.isDark, resolved.isAmoled)
        } else {
            getDictionaryColorScheme(resolved.isDark, resolved.isAmoled, resolved.seedColor)
        }
        val backgroundColor = if (resolved.isAmoled && resolved.isDark) Color.Black else colorScheme.surface
        val bootstrapHtml = getDictionaryBootstrapHtml(
            context = context,
            colorScheme = colorScheme,
            isDark = resolved.isDark,
            isAmoled = resolved.isAmoled,
            seedColor = resolved.seedColor,
            fontFamily = dictionaryPreferences.fontFamily().get(),
            eInkMode = eInkMode,
            paginatedScrolling = dictionaryPreferences.paginatedScrolling().get(),
            paginatedScrollStepSize = dictionaryPreferences.paginatedScrollStepSize().get(),
            languageCode = languageCode,
        )

        return WarmShell(bootstrapHtml, backgroundColor.toArgb())
    }

    private fun Context.isSystemDark(): Boolean {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun WebView.resetDictionaryCallbacks() {
        val state = tag as? DictionaryWebViewState ?: return
        state.onAnkiLookup = null
        state.ankiJsBridge.onAnkiLookup = null
        state.onRecursiveLookup = null
        state.onTabSelect = null
        state.onBack = null
        state.onContentInvalidated = null
        state.onContentReady = null
        state.lastPayload = null
        state.lastResults = null
        state.lastExistingExpressions = null
        state.lastMediaDataUris = null
        state.lastRenderSignature = null
        state.pendingPayload = null
        state.pendingEntryJsons = null
        state.pendingResults = null
        state.pendingExistingExpressions = null
        state.pendingMediaDataUris = null
        state.pendingRenderSignature = null
        state.payloadBridge.rawPayloadJson = ""
        state.payloadBridge.rawEntryJsons = emptyList()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private data class WarmShell(
        val bootstrapHtml: String,
        val backgroundColor: Int,
    )
}
