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

/** Parent-side list of the home's rows. List order is the order on screen. */
@Composable
internal fun HomeRowSettings(rows: List<HomeRow>, sources: List<WhitelistEntry>, profiles: List<Profile>,
    onChanged: (List<HomeRow>) -> Unit) {
    var editing by remember { mutableStateOf<HomeRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<HomeRow?>(null) }
    fun move(from: Int, to: Int) = onChanged(rows.toMutableList().apply { add(to, removeAt(from)) })
    Text("Sort channels into named rows on the home screen. Channels in no row appear under More channels.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    rows.forEachIndexed { index, row ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    // material-icons-core has no up or down arrow; a glyph does the job.
                    IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) { Text("↑") }
                    IconButton(onClick = { move(index, index + 1) }, enabled = index < rows.lastIndex) { Text("↓") }
                    IconButton(onClick = { editing = row }) { Icon(Icons.Default.Edit, "Edit ${row.name}") }
                    IconButton(onClick = { removing = row }) { Icon(Icons.Default.Delete, "Remove ${row.name}") }
                }
                Text("${row.sourceUrls.size} channels", style = MaterialTheme.typography.bodySmall)
                if (profiles.size >= 2) {
                    Text(
                        if (row.profileIds.isEmpty()) "Everyone"
                        else profiles.filter { it.id in row.profileIds }.joinToString { it.name },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    Button(onClick = { adding = true }, enabled = rows.size < HomeRow.MAX_ROWS) { Icon(Icons.Default.Add, null); Text("Add row") }
    if (adding || editing != null) HomeRowDialog(editing, rows, sources, profiles,
        onDismiss = { adding = false; editing = null }, onSave = { row ->
            onChanged(if (rows.any { it.id == row.id }) rows.map { if (it.id == row.id) row else it } else rows + row)
            adding = false; editing = null
        })
    removing?.let { row -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${row.name}?") },
        text = { Text("Its channels stay on the home screen, under More channels.") },
        confirmButton = { TextButton(onClick = { onChanged(rows.filterNot { it.id == row.id }); removing = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}

@Composable
private fun HomeRowDialog(initial: HomeRow?, rows: List<HomeRow>, sources: List<WhitelistEntry>, profiles: List<Profile>,
    onDismiss: () -> Unit, onSave: (HomeRow) -> Unit) {
    val id = remember { initial?.id ?: java.util.UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var picked by remember { mutableStateOf(initial?.sourceUrls.orEmpty()) }
    var forKids by remember { mutableStateOf(initial?.profileIds.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "Add row" else "Edit row") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(HomeRow.MAX_NAME) }, label = { Text("Row name") }, singleLine = true)
            if (profiles.size >= 2) {
                Text("Show for", style = MaterialTheme.typography.titleSmall)
                KidToggleChips(profiles = profiles, selectedIds = forKids, onChanged = { forKids = it })
            }
            Text("Channels", style = MaterialTheme.typography.titleSmall)
            sources.forEach { source ->
                // Allowed on purpose: two kids' rows can share a channel. A kid who gets both sees it in the higher one.
                val elsewhere = rows.filter { it.id != id && source.url in it.sourceUrls }.joinToString { it.name }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(source.url in picked, { checked -> picked = if (checked) picked + source.url else picked - source.url })
                    Column(Modifier.weight(1f)) {
                        Text(source.label ?: source.id)
                        if (elsewhere.isNotEmpty()) Text("Also in $elsewhere", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && picked.isNotEmpty(),
            onClick = { onSave(HomeRow(id, name.trim(), picked, forKids)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
