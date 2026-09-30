package io.pickwick.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NetworkCatalogSettings(shares: List<SmbShare>, catalogs: List<SmbCatalog>, profiles: List<Profile>,
    onChanged: (List<SmbShare>, List<SmbCatalog>) -> Unit) {
    var editing by remember { mutableStateOf<SmbCatalog?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SmbCatalog?>(null) }
    var editingShare by remember { mutableStateOf<SmbShare?>(null) }
    var addingFolderTo by remember { mutableStateOf<SmbShare?>(null) }
    var deletingShare by remember { mutableStateOf<SmbShare?>(null) }
    if (shares.isEmpty()) Text("No network shares yet.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    shares.forEach { share ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(share.name, style = MaterialTheme.typography.titleMedium)
                Text("${share.host}/${share.share}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { editingShare = share }) { Icon(Icons.Default.Edit, "Edit connection ${share.name}") }
            IconButton(onClick = { deletingShare = share }) { Icon(Icons.Default.Delete, "Remove share ${share.name}") }
        }
        catalogs.filter { it.shareId == share.id }.forEach { c ->
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
          Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeMultiplierChip(c.timePercent) { percent ->
                    onChanged(shares, catalogs.map { if (it.id == c.id) it.copy(timePercent = percent) else it })
                }
                Spacer(Modifier.width(8.dp))
                var marquee by remember(c.id) { mutableStateOf(false) }
                if (marquee) LaunchedEffect(c.id) {
                    kotlinx.coroutines.delay(6_000)
                    marquee = false
                }
                Box(Modifier.weight(1f).clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { marquee = !marquee }) {
                    MarqueeTitle(c.name, marquee)
                }
                IconButton(onClick = { editing = c }, modifier = Modifier.tvFocusHighlight()) { Icon(Icons.Default.Edit, "Edit ${c.name}") }
                IconButton(onClick = { deleting = c }, modifier = Modifier.tvFocusHighlight()) { Icon(Icons.Default.Delete, "Remove ${c.name}") }
            }
            Text(c.root.ifBlank { "/" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (profiles.size >= 2) {
                KidToggleChips(profiles = profiles, selectedIds = c.profileIds, onChanged = { selected ->
                    onChanged(shares, catalogs.map { if (it.id == c.id) it.copy(profileIds = selected) else it })
                })
            }
          }
        }
        }
        TextButton(onClick = { addingFolderTo = share }, modifier = Modifier.tvFocusHighlight()) {
            Icon(Icons.Default.Add, null); Text("Add catalog folder")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { adding = true }, modifier = Modifier.tvFocusHighlight()) { Icon(Icons.Default.Add, null); Text("Add network share") }
    if (adding || editingShare != null) NetworkShareDialog(editingShare?.catalog(),
        onDismiss = { adding = false; editingShare = null },
        onSave = { c ->
            val s = SmbShare.fromCatalog(c, c.id, c.name)
            onChanged(if (shares.any { it.id == s.id }) shares.map { if (it.id == s.id) s else it } else shares + s,
                catalogs.map { if (it.shareId == s.id) s.applyTo(it) else it })
            if (adding) addingFolderTo = s
            adding = false; editingShare = null
        })
    val folderShare = addingFolderTo ?: editing?.let { c -> shares.firstOrNull { it.id == c.shareId } }
    folderShare?.let { share -> CatalogFolderDialog(share, editing,
        onDismiss = { addingFolderTo = null; editing = null },
        onSave = { c ->
            onChanged(shares, if (catalogs.any { it.id == c.id }) catalogs.map { if (it.id == c.id) c else it } else catalogs + c)
            addingFolderTo = null; editing = null
        }) }
    deleting?.let { c -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove ${c.name}?") },
        text = { Text("The files on the network drive will be kept.") },
        confirmButton = { TextButton(onClick = { onChanged(shares, catalogs.filterNot { it.id == c.id }); deleting = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    deletingShare?.let { s -> AlertDialog(onDismissRequest = { deletingShare = null }, title = { Text("Remove ${s.name}?") },
        text = { Text("This removes the connection and its ${catalogs.count { it.shareId == s.id }} catalogs from Pickwick. Files on the drive will be kept.") },
        confirmButton = { TextButton(onClick = {
            onChanged(shares.filterNot { it.id == s.id }, catalogs.filterNot { it.shareId == s.id }); deletingShare = null
        }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { deletingShare = null }) { Text("Cancel") } }) }
}

@Composable
private fun NetworkShareDialog(initial: SmbCatalog?, onDismiss: () -> Unit, onSave: (SmbCatalog) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: 445).toString()) }
    var share by remember { mutableStateOf(initial?.share.orEmpty()) }
    val connectionId = remember { initial?.id ?: java.util.UUID.randomUUID().toString() }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var domain by remember { mutableStateOf(initial?.domain.orEmpty()) }
    var guest by remember { mutableStateOf(initial?.guest ?: true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var servers by remember { mutableStateOf<List<String>?>(null) }
    var shares by remember { mutableStateOf<List<String>?>(null) }
    fun draft() = SmbCatalog(
        id = connectionId,
        name = name.trim().ifBlank { share.trim() },
        host = host.trim(), share = share.trim(), username = user.trim(), domain = domain.trim(),
        password = if (guest) "" else password, guest = guest,
        credentialVersion = if (initial != null && initial.password == password && initial.username == user && initial.guest == guest)
            initial.credentialVersion else java.util.UUID.randomUUID().toString(),
        profileIds = initial?.profileIds.orEmpty(), timePercent = initial?.timePercent ?: 100,
        port = port.toIntOrNull() ?: throw IllegalArgumentException("Invalid port"))
    fun saveConnection() {
        busy = true; error = null
        scope.launch {
            try {
                val c = draft()
                withContext(Dispatchers.IO) { SmbLibrary(context).list(c, "") }
                onSave(c)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = if (e is IllegalArgumentException) "Enter a server address and share name, with a valid folder path." else SmbLibrary.message(e)
            } finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (initial == null) "Add network share" else "Edit network share") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Display name") }, enabled = !busy, singleLine = true)
            OutlinedTextField(host, { host = it; shares = null }, label = { Text("Server hostname or IP") }, enabled = !busy, singleLine = true)
            OutlinedTextField(port, { port = it }, label = { Text("Port") }, enabled = !busy, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            TextButton(onClick = {
                busy = true; error = null
                scope.launch {
                    try { servers = SmbDiscovery.findServers() }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = "Could not search this network. Enter the server address." }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("Find servers") }
            servers?.let { found ->
                if (found.isEmpty()) Text("No servers found")
                found.forEach { server -> TextButton(onClick = { host = server; servers = null; shares = null }, enabled = !busy) { Text(server) } }
            }
            OutlinedTextField(share, { share = it }, label = { Text("Share name") }, enabled = !busy, singleLine = true)
            Row { Checkbox(guest, { guest = it }, enabled = !busy); Text("Guest access", Modifier.padding(top = 12.dp)) }
            if (!guest) {
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, enabled = !busy, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy, singleLine = true)
                OutlinedTextField(domain, { domain = it }, label = { Text("Domain (optional)") }, enabled = !busy, singleLine = true)
            }
            TextButton(onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val c = SmbCatalog(name = "Browse", host = host.trim(), share = "IPC$", username = user.trim(), password = password, domain = domain.trim(), guest = guest, port = port.toInt())
                        shares = withContext(Dispatchers.IO) { SmbLibrary.shares(c) }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        error = "Could not list shares. Check sign-in details or enter the share name directly."
                    } finally { busy = false }
                }
            }, enabled = !busy && host.isNotBlank()) { Text("Find shares") }
            shares?.forEach { s -> TextButton(onClick = { share = s; shares = null }, enabled = !busy) { Text(s) } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { saveConnection() }, enabled = !busy && host.isNotBlank() && share.isNotBlank()) { Text("Save connection") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
private fun CatalogFolderDialog(share: SmbShare, initial: SmbCatalog?, onDismiss: () -> Unit, onSave: (SmbCatalog) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = remember { initial?.id ?: java.util.UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var path by remember { mutableStateOf(initial?.root.orEmpty()) }
    var folders by remember { mutableStateOf<List<NetworkFile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun browse(target: String, save: Boolean = false) {
        busy = true; error = null; folders = emptyList()
        scope.launch {
            try {
                val clean = SmbPaths.clean(target)
                val catalog = share.catalog(id, name.trim().ifBlank { clean.substringAfterLast('/').ifBlank { share.name } }, clean)
                    .copy(profileIds = initial?.profileIds.orEmpty(), timePercent = initial?.timePercent ?: 100)
                val rows = withContext(Dispatchers.IO) { SmbLibrary(context).list(catalog, "") }
                path = clean
                if (save) onSave(catalog) else folders = rows.filter { it.directory }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = if (e is IllegalArgumentException) "Enter a valid folder path." else SmbLibrary.message(e)
            } finally { busy = false }
        }
    }
    LaunchedEffect(share.id) { browse(path) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial == null) "Add catalog folder" else "Edit catalog folder") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(share.name, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(name, { name = it }, label = { Text("Catalog name") }, enabled = !busy, singleLine = true)
            OutlinedTextField(path, { path = it; folders = emptyList() }, label = { Text("Folder") }, enabled = !busy, singleLine = true)
            Row {
                TextButton(onClick = { browse(path) }, enabled = !busy) { Text("Browse") }
                TextButton(onClick = { browse(path.substringBeforeLast('/', "")) }, enabled = !busy && path.isNotEmpty()) { Text("Parent folder") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            folders.forEach { f -> TextButton(onClick = { browse(SmbPaths.join(path, f.path)) }, enabled = !busy) { Text(f.name) } }
            if (!busy && error == null && folders.isEmpty()) Text("No subfolders")
        } },
        confirmButton = { TextButton(onClick = { browse(path, save = true) }, enabled = !busy) { Text("Save catalog") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}
