package io.pickwick.app.ui

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The kid-facing search row: text field with a mic and a Search button.
 * Whitelist-scoped — it only ever searches the family's indexed channels, so
 * there is no "whole of YouTube" to leak into.
 *
 * The mic uses the platform RecognizerIntent. On Google TV it works only where
 * the remote has voice support, so the icon hides when speech recognition
 * isn't available on the device rather than offering a dead button.
 */
@Composable
internal fun SearchBar(
    isTv: Boolean,
    onSearch: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Checked once: recognition support doesn't appear or vanish mid-session.
    val speechAvailable = remember {
        SpeechRecognizer.isRecognitionAvailable(context)
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            query = spoken
            onSearch(spoken)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search your channels") },
            singleLine = true,
            // IME search action submits, so phone kids never hunt for the button.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { if (query.isNotBlank()) onSearch(query.trim()) }
            ),
            trailingIcon = {
                if (speechAvailable) {
                    IconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What are you looking for?")
                        runCatching { voiceLauncher.launch(intent) }
                    }) {
                        Text("🎤", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { if (query.isNotBlank()) onSearch(query.trim()) },
            enabled = query.isNotBlank(),
            modifier = Modifier.tvFocusHighlight()
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Search")
        }
    }
}
