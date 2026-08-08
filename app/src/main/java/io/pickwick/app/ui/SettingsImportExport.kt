package io.pickwick.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistParser
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The list's way in and out: save or share the current list (including unsaved
 * edits) as a whitelist.txt, read one back in, or offer the whole list to the
 * community directory. [current] is a provider rather than a value so the file
 * written is whatever is on screen at the moment the button is pressed.
 * [onImport] merges parsed links into the form and returns how many were new.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ExportSection(current: () -> Whitelist, onImport: (Whitelist) -> Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var askingLang by remember { mutableStateOf(false) }

    fun exportText(): String {
        val today = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
            .format(java.util.Date())
        return io.pickwick.app.data.WhitelistExporter.toText(current(), today)
    }

    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(exportText().toByteArray())
            } ?: error("couldn't open the file")
            "Saved ✓"
        }.getOrElse { "Save failed: ${it.message}" }
    }

    val openLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = "Reading…"
            runCatching {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        // Bounded like every other read from something we don't
                        // control: the picker can hand back any file at all, and
                        // a multi-gigabyte one must not be pulled into memory.
                        val bytes = ByteArray(MAX_IMPORT_BYTES + 1)
                        var n = 0
                        while (n < bytes.size) {
                            val r = input.read(bytes, n, bytes.size - n)
                            if (r <= 0) break
                            n += r
                        }
                        check(n <= MAX_IMPORT_BYTES) { "that file is too big" }
                        String(bytes, 0, n)
                    } ?: error("couldn't open the file")
                    // Parsing regexes a megabyte line-by-line — that stays off
                    // main too; only the form merge lands there.
                    WhitelistParser.parse(text)
                }
            }.onSuccess { parsed ->
                val added = onImport(parsed)
                message = if (added > 0) {
                    "Added $added new channel(s)/playlist(s) — review them below (tagged NEW)"
                } else "Nothing new in that file"
            }.onFailure { message = "Import failed: ${it.message}" }
        }
    }

    if (askingLang) {
        SubmitToDirectoryDialog(
            count = current().sources.size,
            onDismiss = { askingLang = false },
            onConfirm = { lang ->
                askingLang = false
                submitting = true
                message = "Submitting…"
                val sources = current().sources
                val appContext = context.applicationContext
                // LanPushScope, not the composition scope: a long list outlives
                // Save & close, and half-sent-then-silently-dropped is the one
                // outcome a parent can't diagnose. The toast survives the
                // screen; the inline message covers it while it's still open.
                io.pickwick.app.data.LanPushScope.scope.launch {
                    val result = runCatching {
                        io.pickwick.app.data.DirectorySubmitter.submit(sources, lang)
                    }.map { io.pickwick.app.data.DirectorySubmitter.summarize(it) }
                        .getOrElse { "Couldn't reach the directory — check the connection and try again." }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        submitting = false
                        message = result
                        android.widget.Toast.makeText(
                            appContext, result, android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    Text(
        "Save the channel list as a file — keep it as a backup, or send it to " +
            "another parent. Import a saved file to add its channels here. Or offer " +
            "the whole list to the shared Pickwick directory, where other families " +
            "can find it after review.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // Four buttons never fit one phone row.
    androidx.compose.foundation.layout.FlowRow {
        TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Pickwick whitelist")
                putExtra(android.content.Intent.EXTRA_TEXT, exportText())
            }
            runCatching {
                context.startActivity(
                    android.content.Intent.createChooser(send, "Share whitelist")
                )
            }.onFailure { message = "No app available to share with" }
        }) { Text("Share…") }
        Spacer(Modifier.width(8.dp))
        TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
            runCatching { saveLauncher.launch("pickwick-whitelist.txt") }
                .onFailure { message = "No file picker on this device" }
        }) { Text("Save to file…") }
        Spacer(Modifier.width(8.dp))
        TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
            // Exports are text/plain, but pickers on some devices type a .txt
            // from a share or a download as octet-stream and would hide it.
            runCatching {
                openLauncher.launch(
                    arrayOf("text/plain", "text/*", "application/octet-stream")
                )
            }.onFailure { message = "No file picker on this device" }
        }) { Text("Import from file…") }
        Spacer(Modifier.width(8.dp))
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !submitting && current().sources.isNotEmpty(),
            onClick = { askingLang = true }
        ) { Text(if (submitting) "Submitting…" else "Submit list to directory…") }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A whitelist.txt is a few kB; anything near this is not one. */
private const val MAX_IMPORT_BYTES = 1024 * 1024

/**
 * Consent gate for the directory submission: the list stops being private the
 * moment it is sent, so say plainly what leaves and what happens next before
 * asking for the one thing the worker can't infer — the channels' language.
 */
@Composable
private fun SubmitToDirectoryDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var lang by remember { mutableStateOf(java.util.Locale.getDefault().language) }
    val valid = io.pickwick.app.data.DirectorySubmitter.isValidLang(lang.trim().lowercase())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Submit your list to the directory?") },
        text = {
            Column {
                Text(
                    "All $count channel(s) and playlist(s) in your list are sent for " +
                        "review. Duplicates are weeded out before anything is published: " +
                        "anything already in the directory — or already waiting for " +
                        "review — is skipped, so if you submitted last week, only what " +
                        "you've added since goes through. Each new one becomes a public " +
                        "suggestion: checked by AI, then by a person, before it appears " +
                        "in the shared directory where other families can add it. The " +
                        "review queue holds about 50 suggestions at a time — if your " +
                        "list is longer, just submit again later to send the rest. " +
                        "Nothing about you or your kids is sent — only the links."
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = lang,
                    onValueChange = { lang = it },
                    label = { Text("Language of these channels") },
                    placeholder = { Text("2-letter language code (en, es, …)") },
                    singleLine = true,
                    isError = !valid
                )
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { onConfirm(lang.trim().lowercase()) }) {
                Text("Submit")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
