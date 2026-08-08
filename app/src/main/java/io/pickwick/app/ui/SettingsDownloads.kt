package io.pickwick.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.data.DownloadEvents
import io.pickwick.app.data.DownloadService
import io.pickwick.app.data.DownloadStatus
import io.pickwick.app.data.DownloadStore
import io.pickwick.app.data.LocalLibrary
import kotlinx.coroutines.launch

// --- Offline downloads ------------------------------------------------------

/**
 * The parent's side of offline downloads: approve or decline the kid's
 * requests, watch active downloads, pick the quality, and free up space.
 * No storage cap — usage is shown and cleanup is a deliberate delete here.
 */
@Composable
internal fun DownloadsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { DownloadStore(context.applicationContext) }
    // Any change from the kid's taps or the service re-reads the queue live.
    val changes by DownloadEvents.changes.collectAsState()
    val entries = remember(changes) { store.entries() }
    val activeProgress by DownloadEvents.progress.collectAsState()
    var quality by remember { mutableIntStateOf(store.maxHeight) }

    // Android 13+: the first approval asks to show the progress notification.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    fun approve(url: String) {
        store.approve(url)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        DownloadService.start(context)
    }

    Text(
        "The kid taps ⬇️ on any video to ask for it offline. Approved videos are " +
            "saved to this device and play without internet — perfect for car trips. " +
            "Watching them still uses screen time as usual.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Download quality", modifier = Modifier.weight(1f))
        listOf(480, 720, 1080).forEach { h ->
            FilterChip(
                selected = quality == h,
                onClick = { quality = h; store.maxHeight = h },
                label = { Text("${h}p") },
                modifier = Modifier.padding(start = 6.dp).tvFocusHighlight()
            )
        }
    }

    val requested = entries.filter { it.status == DownloadStatus.REQUESTED }
    val active = entries.filter {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
    }
    val failed = entries.filter { it.status == DownloadStatus.FAILED }
    val done = entries.filter { it.status == DownloadStatus.DONE }

    if (requested.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Waiting for your OK", style = MaterialTheme.typography.titleSmall)
        requested.forEach { e ->
            DownloadRow(e, subtitle = e.video.channelName) {
                TextButton(
                    modifier = Modifier.tvFocusHighlight(),
                    onClick = { approve(e.video.url) }
                ) { Text("Approve") }
                TextButton(
                    modifier = Modifier.tvFocusHighlight(),
                    onClick = { store.remove(e.video.url) }
                ) { Text("No") }
            }
        }
    }

    if (active.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Downloading", style = MaterialTheme.typography.titleSmall)
        active.forEach { e ->
            val fraction = activeProgress?.takeIf { it.first == e.video.url }?.second
            val label = when {
                fraction != null -> "${(fraction * 100).toInt()}%"
                else -> "queued"
            }
            DownloadRow(e, subtitle = label) {
                IconButton(onClick = { store.remove(e.video.url) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Cancel download")
                }
            }
        }
    }

    if (failed.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Didn't finish", style = MaterialTheme.typography.titleSmall)
        failed.forEach { e ->
            DownloadRow(e, subtitle = e.error ?: "Download failed") {
                TextButton(
                    modifier = Modifier.tvFocusHighlight(),
                    onClick = { approve(e.video.url) }
                ) { Text("Retry") }
                IconButton(onClick = { store.remove(e.video.url) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                }
            }
        }
    }

    if (done.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("On this device", style = MaterialTheme.typography.titleSmall)
        done.forEach { e ->
            val size = remember(changes, e.video.url) {
                e.video.videoId?.let { store.sizeOf(it) } ?: 0L
            }
            DownloadRow(e, subtitle = formatBytes(size)) {
                IconButton(onClick = { store.remove(e.video.url) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete download")
                }
            }
        }
        Text(
            "Total: ${formatBytes(remember(changes) { store.totalSize() })}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (entries.isEmpty()) {
        Text(
            "No requests yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One video in the downloads lists: thumb, title/subtitle, trailing actions. */
@Composable
private fun DownloadRow(
    entry: DownloadStore.Entry,
    subtitle: String,
    actions: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        AsyncImage(
            model = entry.video.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.width(72.dp).height(40.dp).clip(MaterialTheme.shapes.small)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.video.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.0f kB".format(bytes / 1_000.0)
}

// --- Videos from this phone --------------------------------------------------

/**
 * Parent links videos already on this phone into the kid's Downloads shelf.
 * SAF only: the files stay where they are, Pickwick stores links. Folders are
 * the recommended path (one permission grant covers everything inside, and a
 * rescan picks up new files); single-file picking exists for one-offs.
 */
@Composable
internal fun LocalVideosSection(profiles: List<io.pickwick.app.data.Profile> = emptyList()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = remember { LocalLibrary(context.applicationContext) }
    // Local edits ride the downloads change signal — same shelf, same refresh.
    val changes by DownloadEvents.changes.collectAsState()
    val trees = remember(changes) { library.trees() }
    val items = remember(changes) { library.items() }
    val scope = rememberCoroutineScope()
    // Thumbnail/metadata extraction is ~0.1s per file: a folder of episodes
    // needs a narrated scan, not a frozen section.
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /** A picked folder awaiting the "who can watch these?" answer. */
    var pendingTree by remember { mutableStateOf<android.net.Uri?>(null) }

    fun rescan() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            library.rescan { done, total -> if (total > 0) progress = done to total }
            progress = null
        }
    }

    fun linkTree(uri: android.net.Uri, forKids: Set<String>) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            library.addTree(uri, forKids)
            library.rescan { done, total -> if (total > 0) progress = done to total }
            progress = null
        }
    }

    pendingTree?.let { uri ->
        WhoForDialog(
            title = "Who can watch this folder?",
            profiles = profiles,
            initialIds = emptySet(),
            confirmLabel = "Link folder",
            onDismiss = { pendingTree = null },
            onConfirm = { forKids ->
                pendingTree = null
                linkTree(uri, forKids)
            }
        )
    }

    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            if (profiles.size >= 2) pendingTree = uri
            else linkTree(uri, emptySet())
        }
    }
    val pickVideos = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            library.addFiles(uris) { done, total -> progress = done to total }
            progress = null
        }
    }

    Text(
        "Add videos already on this phone — home videos, rips, purchases. " +
            "Pickwick links to the files where they are (nothing is copied or " +
            "uploaded) and shows them on the kid's Downloads shelf, with the " +
            "folder name where the channel name usually goes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            modifier = Modifier.tvFocusHighlight(),
            onClick = { pickFolder.launch(null) }
        ) { Text("Add folder") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            modifier = Modifier.tvFocusHighlight(),
            onClick = { pickVideos.launch(arrayOf("video/*")) }
        ) { Text("Add videos") }
        if (trees.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { rescan() }
            ) { Text("Rescan") }
        }
    }

    progress?.let { (done, total) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text("Adding $done of $total", style = MaterialTheme.typography.bodySmall)
        }
    }

    trees.forEach { tree ->
        val count = items.count { it.treeUri == tree.uri && it.available }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "📁 ${tree.name}  ·  $count video(s)",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (profiles.size >= 2) {
                KidToggleChips(
                    profiles = profiles,
                    selectedIds = tree.profileIds,
                    onChanged = { forKids ->
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            library.setTreeProfiles(tree.uri, forKids)
                        }
                    }
                )
                Spacer(Modifier.width(4.dp))
            }
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { library.forgetTree(tree.uri) } }
            ) { Text("Forget") }
        }
    }

    val sorted = items.sortedWith(compareBy({ it.video.channelName }, { it.video.title }))
    sorted.forEach { item ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            AsyncImage(
                model = item.video.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.width(72.dp).height(40.dp).clip(MaterialTheme.shapes.small)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.video.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    if (item.available) {
                        "${item.video.channelName} · ${formatClock(item.video.durationSeconds)}"
                    } else "File missing — rescan, or forget its folder",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Folder videos are managed by their folder — a removed row would
            // silently come back on the next rescan.
            if (item.treeUri.isEmpty()) {
                IconButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { library.remove(item.video.url) }
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove video")
                }
            }
        }
    }

    if (items.isEmpty() && trees.isEmpty()) {
        Text(
            "Nothing linked yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
