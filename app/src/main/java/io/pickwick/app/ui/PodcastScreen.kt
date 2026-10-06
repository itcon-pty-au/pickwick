package io.pickwick.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home-tile artwork for podcasts: the cached feed's cover right away, and a
 * background fetch for feeds never fetched on this device (a fresh push from
 * the parent's phone) so their tiles don't sit on the fallback forever.
 */
@Composable
internal fun rememberPodcastCovers(feeds: List<PodcastFeed>): Map<String, String?> {
    val context = LocalContext.current
    val covers by produceState<Map<String, String?>>(emptyMap(), feeds) {
        val library = PodcastLibrary(context)
        value = withContext(Dispatchers.IO) { feeds.associate { it.id to library.cached(it.id)?.imageUrl } }
        feeds.filter { library.cached(it.id) == null }.forEach { feed ->
            val image = withContext(Dispatchers.IO) { runCatching { library.refresh(feed).imageUrl }.getOrNull() }
            if (image != null) value = value + (feed.id to image)
        }
    }
    return covers
}

@Composable
internal fun PodcastScreen(feedId: String, profileId: String?, vm: MainViewModel? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val isTv = remember {
        (context.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }
    BackHandler { onBack() }
    val library = remember { PodcastLibrary(context) }
    var feed by remember { mutableStateOf<PodcastFeed?>(null) }
    var configLoaded by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf<PodcastChannel?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Map<String, WatchProgress>>(emptyMap()) }
    var resume by remember { mutableIntStateOf(0) }
    val profileSuffix by produceState<String?>(null, profileId) {
        value = withContext(Dispatchers.IO) { ProfileNamespace(context).suffixFor(profileId) }
    }
    LaunchedEffect(feedId, profileId) {
        feed = withContext(Dispatchers.IO) { ConfigStore(context).load().podcastFor(feedId, profileId) }
        configLoaded = true
    }
    LaunchedEffect(feed) {
        val f = feed ?: return@LaunchedEffect
        error = null
        channel = withContext(Dispatchers.IO) { library.cached(f.id) }
        if (withContext(Dispatchers.IO) { library.isFresh(f.id) }) return@LaunchedEffect
        loading = true
        try {
            channel = withContext(Dispatchers.IO) { library.refresh(f) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A stale cached listing still plays; only an empty screen needs the message.
            if (channel == null) error = "This podcast can't be reached right now. Ask a parent to check it."
        } finally { loading = false }
    }
    LaunchedEffect(profileId, resume) {
        progress = withContext(Dispatchers.IO) {
            WatchHistoryStore(context, ProfileNamespace(context).suffixFor(profileId)).all()
        }
    }
    // Re-read progress when coming back from the player.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resume++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val saved = vm?.state?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    val f = feed
    val showName = f?.name?.ifBlank { null } ?: channel?.title.orEmpty()
    val episodes = channel?.episodes.orEmpty()
    // Same tiles, grid and header as a YouTube channel: episodes are VideoItems
    // under their synthetic podcast URL, so progress bars and watched dimming
    // come for free.
    val items = if (f == null) emptyList() else episodes.map { e ->
        val url = PodcastPaths.url(f.id, e.key)
        VideoItem(Video(url, e.title, showName, e.imageUrl, e.durationSeconds), progress[url]?.fraction)
    }
    Surface(Modifier.fillMaxSize()) {
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides
                if (isTv) TvColumnPivot else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current,
            androidx.compose.foundation.LocalOverscrollConfiguration provides
                if (isTv) null else androidx.compose.foundation.LocalOverscrollConfiguration.current
        ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                showName.ifEmpty { "Podcast" },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            if (loading && items.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            VideoGrid(
                items,
                onPlay = { item ->
                    val suffix = profileSuffix ?: return@VideoGrid
                    val feedId = f?.id ?: return@VideoGrid
                    context.startActivity(
                        Intent(context, PodcastPlayerActivity::class.java)
                            .putExtra(PodcastPlayerActivity.EXTRA_FEED_ID, feedId)
                            .putStringArrayListExtra(PodcastPlayerActivity.EXTRA_EPISODES, ArrayList(episodes.map { it.key }))
                            .putExtra(PodcastPlayerActivity.EXTRA_INDEX, items.indexOf(item).coerceAtLeast(0))
                            .putExtra(PodcastPlayerActivity.EXTRA_PROFILE_ID, profileId)
                            .putExtra(PodcastPlayerActivity.EXTRA_PROFILE_SUFFIX, suffix)
                    )
                },
                emptyText = when {
                    configLoaded && f == null -> "This podcast is no longer available."
                    error != null -> error!!
                    channel == null -> "Loading episodes…"
                    else -> "No episodes yet."
                },
                // The hold menu YouTube episodes get, minus downloads
                // (VideoGrid drops that row for podcasts).
                watchlisted = saved?.watchlisted.orEmpty(),
                onToggleWatchlist = vm?.let { it::toggleWatchlist },
                watchLater = saved?.watchLater.orEmpty(),
                onToggleWatchLater = vm?.let { it::toggleWatchLater },
                queued = saved?.queued.orEmpty(),
                onToggleQueue = vm?.let { it::toggleQueue },
                onToggleWatched = { item ->
                    // Same rewind-not-delete rule as MainViewModel.toggleWatched,
                    // which can't refresh this screen's own progress map.
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val history = WatchHistoryStore(context, ProfileNamespace(context).suffixFor(profileId))
                            val existing = history.progress(item.video.url)
                            val duration = existing?.durationMs?.takeIf { it > 0 }
                                ?: (item.video.durationSeconds * 1000).takeIf { it > 0 } ?: 1_000L
                            history.save(item.video.url, if (existing?.isFinished == true) 0L else duration, duration)
                        }
                        resume++
                        vm?.syncWatchState()
                    }
                },
                grabFocus = isTv
            )
        }
        }
    }
}
