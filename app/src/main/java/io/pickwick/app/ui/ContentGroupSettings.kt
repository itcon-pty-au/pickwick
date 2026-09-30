package io.pickwick.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.*

@Composable
internal fun ContentGroupSettings(groups: List<ContentGroup>, sources: List<WhitelistEntry>, catalogs: List<SmbCatalog>,
    profiles: List<Profile>, onChanged: (List<ContentGroup>) -> Unit) {
    var editing by remember { mutableStateOf<ContentGroup?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ContentGroup?>(null) }
    if (groups.isEmpty()) Text("No content groups yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
    groups.forEach { group ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { editing = group }) { Icon(Icons.Default.Edit, "Edit ${group.name}") }
                    IconButton(onClick = { removing = group }) { Icon(Icons.Default.Delete, "Remove ${group.name}") }
                }
                Text("${group.youtubeSources.size + group.catalogIds.size} sources", style = MaterialTheme.typography.bodySmall)
                val children = profiles.map { it.id to it.name }.ifEmpty { listOf("" to "Everyone") }
                children.forEach { (id, name) ->
                    Text("$name: " + (group.sessionMinutes[id]?.let { "$it min per session" } ?: "Unlimited"))
                }
            }
        }
    }
    Button(onClick = { adding = true }, enabled = groups.size < 200) { Icon(Icons.Default.Add, null); Text("Add content group") }
    if (adding || editing != null) ContentGroupDialog(editing, sources, catalogs, profiles,
        onDismiss = { adding = false; editing = null }, onSave = { group ->
            onChanged(if (groups.any { it.id == group.id }) groups.map { if (it.id == group.id) group else it } else groups + group)
            adding = false; editing = null
        })
    removing?.let { group -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${group.name}?") },
        text = { Text("Its session limits will be removed. Content sources will be kept.") },
        confirmButton = { TextButton(onClick = { onChanged(groups.filterNot { it.id == group.id }); removing = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}

@Composable
private fun ContentGroupDialog(initial: ContentGroup?, sources: List<WhitelistEntry>, catalogs: List<SmbCatalog>,
    profiles: List<Profile>, onDismiss: () -> Unit, onSave: (ContentGroup) -> Unit) {
    val id = remember { initial?.id ?: java.util.UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var youtube by remember { mutableStateOf(initial?.youtubeSources.orEmpty()) }
    var network by remember { mutableStateOf(initial?.catalogIds.orEmpty()) }
    var minutes by remember { mutableStateOf(initial?.sessionMinutes.orEmpty().mapValues { it.value.toString() }) }
    val children = profiles.map { it.id to it.name }.ifEmpty { listOf("" to "Everyone") }
    val valid = minutes.values.all { it.toIntOrNull() in 0..1440 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "Add content group" else "Edit content group") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(200) }, label = { Text("Group name") }, singleLine = true)
            Text("Minutes per session", style = MaterialTheme.typography.titleSmall)
            children.forEach { (childId, childName) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(childName, Modifier.weight(1f))
                    Text("Unlimited")
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = childId !in minutes, onCheckedChange = { unlimited ->
                        minutes = if (unlimited) minutes - childId else minutes + (childId to "15")
                    })
                }
                minutes[childId]?.let { value ->
                    OutlinedTextField(value, { minutes = minutes + (childId to it) }, label = { Text("$childName minutes") },
                        singleLine = true, isError = value.toIntOrNull() !in 0..1440,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
            }
            Text("YouTube", style = MaterialTheme.typography.titleSmall)
            sources.forEach { source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(source.url in youtube, { checked -> youtube = if (checked) youtube + source.url else youtube - source.url })
                    Text(source.label ?: source.id, Modifier.weight(1f))
                }
            }
            Text("Network catalogs", style = MaterialTheme.typography.titleSmall)
            catalogs.forEach { catalog ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(catalog.id in network, { checked -> network = if (checked) network + catalog.id else network - catalog.id })
                    Text(catalog.name, Modifier.weight(1f))
                }
            }
        } },
        confirmButton = { TextButton(enabled = valid && name.isNotBlank() && (youtube.isNotEmpty() || network.isNotEmpty()),
            onClick = { onSave(ContentGroup(id, name.trim(), youtube, network, minutes.mapValues { it.value.toInt() })) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
