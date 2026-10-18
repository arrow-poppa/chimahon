package eu.kanade.tachiyomi.ui.dictionary

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import chimahon.anki.AnkiProfile
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class AiExplanationContent(
    val lookupId: String,
    val text: String,
    val loading: Boolean,
    val error: String?,
    val initiallyCollapsed: Boolean,
    val onAction: (String) -> Unit,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", lookupId)
        put("text", text)
        put("loading", loading)
        put("error", error ?: "")
        put("initiallyCollapsed", initiallyCollapsed)
    }
}

@Composable
fun rememberAiExplanation(
    lookupId: String,
    profile: AnkiProfile,
    target: String,
    sentence: String,
    hasDictionaryResults: Boolean,
    active: Boolean,
    onExplanationChanged: (String) -> Unit = {},
): AiExplanationContent? {
    val canShow = profile.aiEnabled &&
        target.isNotBlank() &&
        (hasDictionaryResults || profile.aiUnknownWordFallback)
    if (!canShow) return null

    return key(lookupId) {
        val repository = remember { Injekt.get<AiExplanationRepository>() }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val activeModel = profile.aiModelForProvider()
        var explanation by remember(profile.aiProvider, activeModel, target, sentence) { mutableStateOf("") }
        var error by remember(profile.aiProvider, activeModel, target, sentence) { mutableStateOf<String?>(null) }
        var loading by remember(profile.aiProvider, activeModel, target, sentence) { mutableStateOf(false) }
        var requestJob by remember { mutableStateOf<Job?>(null) }

        fun generate(bypassCache: Boolean) {
            if (bypassCache || profile.aiCancelPendingRequests) {
                requestJob?.cancel()
            }
            if (bypassCache) explanation = ""
            requestJob = scope.launch {
                loading = true
                error = null
                try {
                    explanation = repository.explain(
                        profile = profile,
                        target = target,
                        sentence = sentence,
                        bypassCache = bypassCache,
                        onPartial = { partial ->
                            withContext(Dispatchers.Main.immediate) {
                                explanation = partial
                            }
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "AI explanation failed"
                } finally {
                    loading = false
                }
            }
        }

        LaunchedEffect(
            active,
            profile.aiAutoGenerate,
            profile.aiStreamResponse,
            profile.aiCancelPendingRequests,
            profile.id,
            target,
            sentence,
        ) {
            if (!active) {
                if (profile.aiCancelPendingRequests) {
                    requestJob?.cancel()
                    loading = false
                }
            } else if (profile.aiAutoGenerate && explanation.isBlank() && !loading) {
                generate(false)
            }
        }
        LaunchedEffect(explanation) {
            onExplanationChanged(explanation)
        }
        DisposableEffect(profile.aiCancelPendingRequests) {
            onDispose {
                if (profile.aiCancelPendingRequests) requestJob?.cancel()
            }
        }

        fun copyExplanation() {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("AI explanation", explanation))
            context.toast("AI explanation copied")
        }

        AiExplanationContent(
            lookupId = lookupId,
            text = explanation,
            loading = loading,
            error = error,
            initiallyCollapsed = profile.aiExplanationCollapsed ||
                profile.dictionaryCollapseMode == AnkiProfile.DICTIONARY_COLLAPSE_COLLAPSE_ALL,
            onAction = { action ->
                when (action) {
                    "copy" -> copyExplanation()
                    "generate" -> if (!loading) generate(explanation.isNotBlank() || error != null)
                }
            },
        )
    }
}
