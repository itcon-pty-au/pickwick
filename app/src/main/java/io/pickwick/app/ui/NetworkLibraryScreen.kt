package io.pickwick.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun NetworkLibraryScreen(profileId: String?, vm: MainViewModel? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val isTv = remember {
        (context.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }
    val saved = vm?.state?.collectAsState()?.value
    var menuFor by remember { mutableStateOf<VideoItem?>(null) }
    menuFor?.let { item ->
        val firstAction = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(item) { runCatching { firstAction.requestFocus() } }
        AlertDialog(
            onDismissRequest = { menuFor = null },
            shape = androidx.compose.ui.graphics.RectangleShape,
            title = { MarqueeTitle(item.video.title, focused = false) },
            text = {
                Column(Modifier.ignoreSelectUntilRelease(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm?.toggleQueue(item); menuFor = null },
                        modifier = Modifier.fillMaxWidth().focusRequester(firstAction).tvFocusHighlight()) {
                        Text(if (item.video.url in saved?.queued.orEmpty()) "Remove from Up next" else "Add to Up next")
                    }
                    TextButton(onClick = { vm?.toggleWatchlist(item); menuFor = null },
                        modifier = Modifier.fillMaxWidth().tvFocusHighlight()) {
                        Text(if (item.video.url in saved?.watchlisted.orEmpty()) "Remove from Favorites" else "Add to Favorites")
                    }
                    TextButton(onClick = { vm?.toggleWatchLater(item); menuFor = null },
                        modifier = Modifier.fillMaxWidth().tvFocusHighlight()) {
                        Text(if (item.video.url in saved?.watchLater.orEmpty()) "Remove from Watch later" else "Add to Watch later")
                    }
                }
            },
            confirmButton = {}
        )
    }
    var config by remember { mutableStateOf<Whitelist?>(null) }
    var catalogId by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var resume by remember { mutableIntStateOf(0) }
    var forceRefresh by remember { mutableStateOf(false) }
    var files by remember(catalogId, path) { mutableStateOf<List<NetworkFile>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, WatchProgress>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var readingDetails by remember { mutableStateOf(false) }
    var detailsCompleted by remember { mutableIntStateOf(0) }
    var detailsTotal by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val library = remember { SmbLibrary(context) }
    val browseLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var browsingActive by remember {
        mutableStateOf(browseLifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    DisposableEffect(browseLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            browsingActive = browseLifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        browseLifecycle.lifecycle.addObserver(observer)
        onDispose { browseLifecycle.lifecycle.removeObserver(observer) }
    }
    val profileSuffix by produceState<String?>(null, profileId) {
        value = withContext(Dispatchers.IO) { ProfileNamespace(context).suffixFor(profileId) }
    }
    LaunchedEffect(profileId) {
        while (true) {
            config = withContext(Dispatchers.IO) { ConfigStore(context).load() }
            delay(3000)
        }
    }
    val catalogs = config?.let { w -> w.networkCatalogs.filter { w.networkCatalogFor(it.id, profileId) != null } }.orEmpty()
    val catalog = catalogs.firstOrNull { it.id == catalogId }
    val covers by produceState<Map<String, String?>>(emptyMap(), catalogs, catalogId, path, files) {
        value = withContext(Dispatchers.IO) {
            if (catalog == null) catalogs.associate { it.id to library.cover(it, "") }
            else files.filter { it.directory }.associate { it.path to library.cover(catalog, it.path) }
        }
    }
    fun back() {
        if (catalogId == null) onBack()
        else if (path.isNotEmpty()) path = path.substringBeforeLast('/', "")
        else catalogId = null
    }
    BackHandler { back() }
    LaunchedEffect(catalog, path, refresh, browsingActive) {
        if (!browsingActive) return@LaunchedEffect
        files = emptyList(); error = null
        val c = catalog ?: return@LaunchedEffect
        loading = true
        try {
            files = withContext(Dispatchers.IO) { library.cached(c, path) }
            val shouldFetch = forceRefresh || withContext(Dispatchers.IO) { !library.isFresh(c, path) }
            forceRefresh = false
            if (shouldFetch) files = withContext(Dispatchers.IO) { library.list(c, path) }
            loading = false
            // Listing freshness does not mean thumbnail extraction finished before leaving.
            val pending = files.filter { !it.directory && (it.duration == 0L || it.thumbnail == null) }
            detailsCompleted = 0
            detailsTotal = pending.size
            readingDetails = pending.isNotEmpty()
            var snapshot = files.toList()
            val folderPath = path
            if (pending.isNotEmpty()) try { withContext(Dispatchers.IO) {
                SmbConnection(c).use { smb ->
                    for (f in pending) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val enriched = try { library.metadata(c, f, smb) }
                            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; f }
                        snapshot = snapshot.map { if (it.path == f.path) enriched else it }
                        // Persist completed work even if navigation cancelled the UI meanwhile.
                        if (enriched != f) library.save(c, folderPath, snapshot)
                        withContext(Dispatchers.Main) {
                            files = snapshot
                            detailsCompleted++
                        }
                    }
                }
            } } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // An offline metadata pass must not hide an otherwise usable cached listing.
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = "The network drive is unavailable. Ask a parent to check the connection."
        } finally { loading = false; readingDetails = false }
    }
    LaunchedEffect(profileId, resume, catalogId, path) {
        progress = withContext(Dispatchers.IO) {
            WatchHistoryStore(context, ProfileNamespace(context).suffixFor(profileId)).all()
        }
    }
    // Refresh resume markers when returning from PlayerActivity.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resume++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val minSeconds = (config?.limitsFor(profileId)?.minVideoMinutes ?: 0) * 60L
    val visible = files.filter { it.directory || minSeconds == 0L || it.duration >= minSeconds }
    Surface(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides
                if (isTv) TvColumnPivot else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current,
            androidx.compose.foundation.LocalOverscrollConfiguration provides
                if (isTv) null else androidx.compose.foundation.LocalOverscrollConfiguration.current
        ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isTv) IconButton(onClick = { back() }) { Icon(Icons.Default.ArrowBack, "Back") }
                Text(catalog?.let { if (path.isEmpty()) it.name else path.substringAfterLast('/') } ?: "Network shares",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                IconButton(onClick = { forceRefresh = true; refresh++ }, enabled = !loading,
                    modifier = Modifier.tvFocusHighlight()) { Icon(Icons.Default.Refresh, "Refresh") }
            }
            if (loading || config == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (readingDetails) Text("Reading video details: $detailsCompleted of $detailsTotal", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            if (catalogId != null && catalog == null && config != null) Text("This network share is no longer available.")
            key(catalogId, path) {
            val gridState = rememberLazyGridState()
            val firstContentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            var initialFocusPlaced by remember { mutableStateOf(false) }
            val firstPath = visible.firstOrNull { !it.directory }?.path ?: visible.firstOrNull()?.path
            val focusKey = if (catalogId == null) catalogs.firstOrNull()?.id else firstPath
            LaunchedEffect(focusKey) {
                if (isTv && !initialFocusPlaced && focusKey != null) {
                    val index = if (catalogId == null) 0 else visible.indexOfFirst { it.path == focusKey }.coerceAtLeast(0)
                    gridState.scrollToItem(index)
                    withFrameNanos { }
                    initialFocusPlaced = runCatching { firstContentFocus.requestFocus(); true }.getOrDefault(false)
                }
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(
                    if (catalogId != null && visible.any { !it.directory }) CatalogGridMinWidth
                    else ChannelGridMinWidth
                ),
                modifier = Modifier.fillMaxSize().dpadHeldScrollThrottle(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (catalogId == null) {
                    if (catalogs.isEmpty() && config != null) item(span = { GridItemSpan(maxLineSpan) }) { Text("No network shares yet") }
                    items(catalogs, key = { it.id }) { c ->
                        ArtworkTile(title = c.name, thumbnail = covers[c.id], timePercent = c.timePercent, fallback = "📁",
                            modifier = if (isTv && c.id == focusKey) Modifier.focusRequester(firstContentFocus) else Modifier,
                            onClick = { catalogId = c.id; path = "" })
                    }
                } else if (catalog != null) {
                    if (visible.isEmpty() && !loading && error == null) item(span = { GridItemSpan(maxLineSpan) }) { Text("No videos or folders") }
                    items(visible, key = { it.path }) { f ->
                        val c = catalog
                        if (f.directory) {
                            ArtworkTile(title = f.name, thumbnail = covers[f.path], fallback = "📁",
                                modifier = if (isTv && f.path == focusKey) Modifier.focusRequester(firstContentFocus) else Modifier,
                                onClick = { path = f.path })
                        } else {
                            val watched = progress[c.url(f.path)]
                            val video = VideoItem(Video(c.url(f.path), f.name.substringBeforeLast('.'), c.name, f.thumbnail, f.duration),
                                watched?.fraction)
                            VideoTile(video, firstContentFocus.takeIf { isTv && f.path == focusKey }, onPlay = {
                                if (profileSuffix != null) {
                                val queue = visible.filterNot { it.directory }
                                val intent = Intent(context, PlayerActivity::class.java)
                                    .putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE, ArrayList(queue.map { c.url(it.path) }))
                                    .putExtra(PlayerActivity.EXTRA_INDEX, queue.indexOf(f))
                                    .putExtra(PlayerActivity.EXTRA_CHANNEL, c.name)
                                    .putExtra(PlayerActivity.EXTRA_TIME_PERCENT, c.timePercent)
                                    .putExtra(PlayerActivity.EXTRA_PROFILE_ID, profileId)
                                    .putExtra(PlayerActivity.EXTRA_PROFILE_SUFFIX, profileSuffix)
                                context.startActivity(intent)
                                }
                            }, onOpenMenu = if (vm != null) ({ menuFor = it }) else null,
                                downloadPending = emptySet(), downloaded = emptySet(), showDownloadStatus = false)
                        }
                    }
                }
            }
            }
        }
    }
    }
}
