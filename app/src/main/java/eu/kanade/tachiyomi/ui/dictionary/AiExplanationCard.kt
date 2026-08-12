package eu.kanade.tachiyomi.ui.dictionary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import chimahon.anki.AnkiProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun AiExplanationCard(
    profile: AnkiProfile,
    target: String,
    sentence: String,
    hasDictionaryResults: Boolean,
    active: Boolean,
    onExplanationChanged: (String) -> Unit = {},
    onSelectedTextChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val canShow = profile.aiEnabled &&
        target.isNotBlank() &&
        (hasDictionaryResults || profile.aiUnknownWordFallback)
    if (!canShow) return

    val repository = remember { Injekt.get<AiExplanationRepository>() }
    val scope = rememberCoroutineScope()
    val activeModel = profile.aiModelForProvider()
    var explanation by remember(profile.aiProvider, activeModel, target, sentence) { mutableStateOf("") }
    var error by remember(profile.aiProvider, activeModel, target, sentence) { mutableStateOf<String?>(null) }
    var loading by remember(profile.aiProvider, activeModel, target, sentence) { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var explanationField by remember(explanation) {
        mutableStateOf(TextFieldValue(text = explanation, selection = TextRange.Zero))
    }

    fun generate(bypassCache: Boolean) {
        requestJob?.cancel()
        requestJob = scope.launch {
            loading = true
            error = null
            try {
                explanation = repository.explain(
                    profile = profile,
                    target = target,
                    sentence = sentence,
                    bypassCache = bypassCache,
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

    LaunchedEffect(active, profile.aiAutoGenerate, profile.id, target, sentence) {
        if (!active) {
            requestJob?.cancel()
            loading = false
        } else if (profile.aiAutoGenerate && explanation.isBlank() && !loading) {
            generate(false)
        }
    }
    LaunchedEffect(explanation) {
        onExplanationChanged(explanation)
        onSelectedTextChanged("")
    }
    DisposableEffect(Unit) {
        onDispose { requestJob?.cancel() }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("AI explanation", style = MaterialTheme.typography.titleSmall)
                if (explanation.isNotBlank() && !loading) {
                    TextButton(onClick = { generate(true) }) { Text("Regenerate") }
                }
            }
            when {
                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Text("Generating contextual explanation…")
                }
                error != null -> {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { generate(true) }) { Text("Try again") }
                }
                explanation.isNotBlank() -> BasicTextField(
                    value = explanationField,
                    onValueChange = { value ->
                        if (value.text == explanation) {
                            explanationField = value
                            val selection = value.selection
                            if (!selection.collapsed) {
                                // Keep the last non-empty selection pending when focus
                                // moves to the WebView's Anki button, like Yomitan does.
                                onSelectedTextChanged(
                                    explanation.substring(selection.min, selection.max),
                                )
                            }
                        }
                    },
                    readOnly = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    cursorBrush = SolidColor(Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                )
                else -> OutlinedButton(onClick = { generate(false) }) {
                    Text("Generate explanation")
                }
            }
        }
    }
}
