package eu.kanade.tachiyomi.ui.dictionary

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat

/** A service overlay needs a DecorView to host native text selection and its toolbar. */
internal class DictionaryOverlayDialog(context: Context, private val onBack: () -> Unit) :
    Dialog(context, android.R.style.Theme_Material_Light_NoActionBar) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
        window!!.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            attributes = attributes.apply {
                gravity = Gravity.TOP or Gravity.START
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                } else {
                    @Suppress("DEPRECATION")
                    flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                }
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    @Deprecated("Used for back dispatch on Android 12 and earlier")
    override fun onBackPressed() = onBack()
}
