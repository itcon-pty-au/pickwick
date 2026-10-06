package io.pickwick.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parent-side list of RSS podcasts. Each feed becomes its own tile on the
 * kids' home screens. No time chip: podcasts don't count against screen time.
 */
@Composable
internal fun PodcastSettings(feeds: List<PodcastFeed>, profiles: List<Profile>, onChanged: (List<PodcastFeed>) -> Unit) {
    var editing by remember { mutableStateOf<PodcastFeed?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PodcastFeed?>(null) }
    Text(
        "Add a podcast by its RSS feed address. Podcasts are audio-only and don't count against screen time.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    if (feeds.isEmpty()) Text("No podcasts yet.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    feeds.forEach { feed ->
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadgeIcon(SourceBadge.PODCAST)
                    Spacer(Modifier.width(8.dp))
                    Text(feed.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editing = feed }, modifier = Modifier.tvFocusHighlight()) { Icon(Icons.Default.Edit, "Edit ${feed.name}") }
                    IconButton(onClick = { deleting = feed }, modifier = Modifier.tvFocusHighlight()) { Icon(Icons.Default.Delete, "Remove ${feed.name}") }
                }
                Text(feed.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (profiles.size >= 2) {
                    KidToggleChips(profiles = profiles, selectedIds = feed.profileIds, onChanged = { selected ->
                        onChanged(feeds.map { if (it.id == feed.id) it.copy(profileIds = selected) else it })
                    })
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { adding = true }, modifier = Modifier.tvFocusHighlight()) { Icon(Icons.Default.Add, null); Text("Add RSS podcast") }
    if (adding || editing != null) PodcastFeedDialog(editing, feeds,
        onDismiss = { adding = false; editing = null },
        onSave = { f ->
            onChanged(if (feeds.any { it.id == f.id }) feeds.map { if (it.id == f.id) f else it } else feeds + f)
            adding = false; editing = null
        })
    deleting?.let { f -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove ${f.name}?") },
        text = { Text("Kids will no longer see this podcast.") },
        confirmButton = { TextButton(onClick = { onChanged(feeds.filterNot { it.id == f.id }); deleting = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun PodcastFeedDialog(initial: PodcastFeed?, existing: List<PodcastFeed>, onDismiss: () -> Unit, onSave: (PodcastFeed) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    // The address that last fetched cleanly; saving requires the field to still match it.
    var checkedUrl by remember { mutableStateOf(initial?.url) }
    var summary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val trimmed = url.trim()
    val duplicate = existing.any { it.id != initial?.id && it.url.equals(trimmed, ignoreCase = true) }

    fun check() {
        if (!PodcastFeed.isFeedUrl(trimmed)) { error = "Enter an address starting with http:// or https://"; return }
        busy = true; error = null; summary = null
        scope.launch {
            try {
                val channel = withContext(Dispatchers.IO) { PodcastLibrary.fetch(trimmed) }
                checkedUrl = trimmed
                if (name.isBlank() || initial == null) name = channel.title.ifBlank { name }
                summary = "${channel.episodes.size} episodes found"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Our own messages are parent-readable; a SAX parse error is not.
                error = (if (e is IllegalArgumentException || e is java.io.IOException) e.message else null)
                    ?.takeIf { it.isNotBlank() } ?: "Couldn't read a podcast feed at that address."
            } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add RSS podcast" else "Edit podcast") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it; summary = null; error = null }, label = { Text("RSS feed address") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedButton(onClick = { check() }, enabled = !busy && trimmed.isNotEmpty(),
                    modifier = Modifier.tvFocusHighlight()) { Text(if (busy) "Checking…" else "Check feed") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (duplicate) Text("This podcast is already added.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(name, { name = it }, label = { Text("Name shown to kids") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = checkedUrl != null)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val base = initial ?: PodcastFeed(url = trimmed, name = name.trim())
                    onSave(base.copy(url = trimmed, name = name.trim()))
                },
                enabled = !busy && !duplicate && checkedUrl == trimmed && name.isNotBlank(),
                modifier = Modifier.tvFocusHighlight()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.tvFocusHighlight()) { Text("Cancel") } }
    )
}
