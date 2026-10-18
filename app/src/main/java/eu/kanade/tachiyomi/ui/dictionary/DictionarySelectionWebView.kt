package eu.kanade.tachiyomi.ui.dictionary

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.os.Bundle
import android.view.ActionMode
import android.view.ViewGroup
import android.webkit.WebView

/** Compose Popup roots have no DecorView to create WebView's floating selection menu. */
internal class DictionarySelectionWebView(context: Context) : WebView(DictionaryWebViewContext(context)) {
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        val mode = super.startActionMode(callback, type)
        if (mode != null || type != ActionMode.TYPE_FLOATING) return mode
        val decor = this.context.dictionaryActivity()?.window?.decorView as? ViewGroup ?: return null
        if (!decor.isAttachedToWindow) return null
        return decor.startActionModeForChild(this, callback, type)
    }
}

internal fun Context.dictionaryActivity(): Activity? {
    var candidate = this
    while (candidate is ContextWrapper) {
        if (candidate is Activity) return candidate
        val base = candidate.baseContext
        if (base === candidate) break
        candidate = base
    }
    return null
}

private class DictionaryWebViewContext(context: Context) : MutableContextWrapper(context) {
    override fun startActivity(intent: Intent) = startActivity(intent, null)

    override fun startActivity(intent: Intent, options: Bundle?) {
        // PROCESS_TEXT also works from the screen-capture service, which has no Activity.
        val launchIntent = if (dictionaryActivity() == null) Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent
        super.startActivity(launchIntent, options)
    }
}
