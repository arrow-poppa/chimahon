package eu.kanade.tachiyomi.ui.dictionary

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Lives in the test APK, so PROCESS_TEXT crosses a real application boundary. */
class SelectionProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(
            Intent("app.chimahon.selection.TEST_PROCESS_TEXT")
                .setPackage("app.chimahon.dev")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()),
        )
        finish()
    }
}
