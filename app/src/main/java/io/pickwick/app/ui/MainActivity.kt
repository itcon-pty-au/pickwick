package io.pickwick.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import io.pickwick.app.BuildConfig
import io.pickwick.app.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class ChannelVideos(val source: Source) : Screen
    /** Random mix across all whitelisted sources. */
    data object Surprise : Screen
    /** The kid's saved-for-later videos. */
    data object Watchlist : Screen
    /** Parent-approved videos stored on the device — the offline shelf. */
    data object Downloads : Screen
}

/** A video plus its local watch progress (0..1), null if never watched. */
data class VideoItem(val video: Video, val progress: Float?)

data class UiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    /** A whitelist re-fetch is in flight (drives the pull-to-refresh spinner). */
    val refreshing: Boolean = false,
    val error: String? = null,
    val channels: List<Source> = emptyList(),
    val screen: Screen = Screen.Home,
    val videos: List<VideoItem> = emptyList(),
    /**
     * Videos on this screen hidden by AI screening (awaiting a verdict or held
     * for parent review). Lets an all-held source explain itself instead of
     * rendering as inexplicably empty.
     */
    val held: Int = 0,
    /** Partially-watched videos, most recent first (home-screen resume row). */
    val keepWatching: List<VideoItem> = emptyList(),
    /** Source ids with uploads the kid hasn't seen yet. */
    val newBadges: Set<String> = emptySet(),
    /** Video URLs the kid has saved for later (drives the ❤️ badge). */
    val watchlisted: Set<String> = emptySet(),
    /** Video URLs requested/approved/fetching — not yet playable offline (⏳). */
    val downloadPending: Set<String> = emptySet(),
    /** Video URLs fully on disk, playable without a network (✅). */
    val downloaded: Set<String> = emptySet()
)

class MainViewModel(
    private val whitelist: WhitelistRepository,
    private val history: WatchHistoryStore,
    private val sourceCache: SourceCache,
    private val videoCache: VideoCache,
    private val usage: UsageStore,
    private val sessionGuard: SessionGuard,
    private val watchlistStore: WatchlistStore,
    private val pairingStore: PairingStore? = null,
    /** Phone role: lets the periodic sync re-push config a device missed while off. */
    private val configStore: ConfigStore? = null,
    /** Offline downloads (phones); null on TV and in tests. */
    private val downloadStore: DownloadStore? = null,
    /** Parent-sideloaded local files (phones); null on TV and in tests. */
    private val localLibrary: LocalLibrary? = null,
    /** True when there's no network at launch — lands the kid on Downloads. */
    private val isOffline: () -> Boolean = { false },
    /** Serializes this device's watch state for cross-device sync. */
    private val exportWatchState: () -> String = { "{}" },
    /** Merges a peer's watch state into this device. Returns true if applied. */
    private val mergeWatchState: (String) -> Boolean = { false },
    /**
     * Warms thumbnails into Coil's disk cache, one at a time, so the trickle
     * never competes with the images currently on screen.
     */
    private val prefetchThumbs: suspend (List<String>) -> Unit = {},
    /** AI screening gate; null in tests. Unscreened/blocked videos never render. */
    private val screener: io.pickwick.app.data.Screener? = null,
    private val yt: YouTubeRepository = YouTubeRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var sources: List<Source> = emptyList()
    private var blockedVideoIds: Set<String> = emptySet()

    /** The unfiltered videos behind the current screen; progress/filtering applied on top. */
    private var rawVideos: List<Video> = emptyList()

    // Pagination state for the currently open source.
    private var feedHandle: YouTubeRepository.FeedHandle? = null
    private var uploadsNextPage: org.schabi.newpipe.extractor.Page? = null
    private var loadingMore = false

    init {
        _state.value = _state.value.copy(
            watchlisted = watchlistStore.urls(),
            downloadPending = downloadStore?.pendingUrls().orEmpty(),
            // Sideloaded local files count as "downloaded": one set drives the
            // ✅ badges, the home tile and the offline auto-open alike.
            downloaded = downloadStore?.downloadedUrls().orEmpty() + localLibrary?.urls().orEmpty()
        )
        // Car trip / flight: no network but saved videos — open the offline shelf.
        if (isOffline() && _state.value.downloaded.isNotEmpty()) openDownloads()
        refresh()
        syncWatchState()
        syncConfigState()
        // Approvals, finished downloads and local-library edits update the
        // badges (and the Downloads screen, if it's open) as they happen —
        // LocalLibrary rides the same change signal since it feeds the same shelf.
        viewModelScope.launch {
            DownloadEvents.changes.collect { refreshDownloadState() }
        }
        // A TV can sit on the home screen for hours — poll for whitelist edits.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5 * 60 * 1000L)
                refreshIfIdle()
                syncWatchState()
                syncConfigState()
            }
        }
    }

    /**
     * Phone-side config reconcile: an Allow/Block (or any settings edit) made
     * while a kid device was off must still land when it wakes — the original
     * push just failed silently. If a paired device answers with a *different,
     * older* config, push ours again; a device carrying a newer config (another
     * admin phone edited meanwhile) is left alone for a deliberate Push/Pull.
     */
    fun syncConfigState() {
        val store = configStore ?: return
        val devices = pairingStore?.paired().orEmpty()
        if (devices.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val localHash = ConfigStore.fingerprint(store.load())
            val localAt = store.updatedAt()
            devices.forEach { device ->
                val (remoteHash, remoteAt) = LanClient.status(device) ?: return@forEach
                if (remoteHash != localHash && remoteAt < localAt) {
                    LanClient.pushConfig(device, store.rawJson())
                }
            }
        }
    }

    private var watchSyncInFlight = false

    /**
     * Exchange watch progress + saved list with every paired device (phone acts
     * as the hub): pull-merge theirs, push the merged state back. LWW converges.
     */
    fun syncWatchState() {
        val devices = pairingStore?.paired().orEmpty()
        if (devices.isEmpty() || watchSyncInFlight) return
        watchSyncInFlight = true
        viewModelScope.launch {
            var changed = false
            devices.forEach { device ->
                LanClient.fetchWatchState(device)?.let { if (mergeWatchState(it)) changed = true }
                LanClient.pushWatchState(device, exportWatchState())
            }
            watchSyncInFlight = false
            if (changed) refreshProgress()
        }
    }

    /** Most-opened first; ties keep whitelist order (sortedByDescending is stable). */
    private fun sortByUsage(channels: List<Source>) =
        channels.sortedByDescending { usage.opens(it.id) }

    /** Partially-watched, unblocked videos across all sources, most recent first. */
    private fun keepWatchingRow(): List<VideoItem> =
        sources.flatMap { videoCache.load(it.id) }
            .distinctBy { it.url }
            .filter { it.videoId !in blockedVideoIds }
            .filter { screener?.isVisible(it) != false }
            .mapNotNull { video ->
                history.progress(video.url)
                    ?.takeIf { !it.isFinished && it.fraction > 0.02f }
                    ?.let { VideoItem(video, it.fraction) to it.lastWatchedAt }
            }
            .sortedByDescending { it.second }
            .take(10)
            .map { it.first }

    /** A source gets a NEW badge when its newest cached video postdates the kid's last visit. */
    private fun computeNewBadges(): Set<String> =
        sources.mapNotNull { source ->
            val latest = videoCache.load(source.id).firstOrNull()?.url ?: return@mapNotNull null
            val seen = usage.lastSeenLatest(source.id) ?: return@mapNotNull null
            source.id.takeIf { latest != seen }
        }.toSet()

    /**
     * Sources whose every cached video is hidden (parent-blocked or held by AI
     * screening) vanish from the kid's home — an all-held tile opens onto an
     * empty grid, which reads as broken. They reappear as approvals land.
     * Sources with no cached feed yet stay visible: their state is unknown.
     */
    private fun visibleSources(channels: List<Source>): List<Source> =
        channels.filter { source ->
            val cached = videoCache.load(source.id)
            cached.isEmpty() || cached.any { v ->
                v.videoId !in blockedVideoIds && screener?.isVisible(v) != false
            }
        }

    /** Update home-screen tiles without ever disturbing the screen the kid is on. */
    private fun publishChannels(channels: List<Source>) {
        val onHome = _state.value.screen == Screen.Home
        _state.value = _state.value.copy(
            // distinctBy: two whitelist entries (URL form + UC id) can canonicalize
            // to the same channel — duplicate grid keys crash Compose.
            channels = sortByUsage(visibleSources(channels.distinctBy { it.id })),
            keepWatching = keepWatchingRow(),
            newBadges = computeNewBadges(),
            loading = if (onHome) false else _state.value.loading
        )
    }

    private var refreshInFlight = false

    /**
     * [userInitiated] drives the pull-to-refresh spinner; background refreshes stay
     * silent. The spinner completes as soon as the whitelist file is re-fetched and
     * the tile list updated — the slow per-channel name/avatar resolution continues
     * silently afterwards.
     */
    fun refresh(userInitiated: Boolean = false) = viewModelScope.launch {
        if (refreshInFlight) {
            // Attach the pull spinner to the refresh that's already running.
            if (userInitiated) _state.value = _state.value.copy(refreshing = true)
            return@launch
        }
        refreshInFlight = true
        if (userInitiated) _state.value = _state.value.copy(refreshing = true)

        // Paint instantly from the last successful run while the fresh list loads.
        val cached = sourceCache.load()
        if (cached.isNotEmpty()) {
            sources = cached
            publishChannels(cached)
        } else if (_state.value.screen == Screen.Home) {
            _state.value = _state.value.copy(loading = true)
        }
        runCatching { whitelist.load() }
            .onSuccess { list ->
                blockedVideoIds = list.blockedVideoIds
                screener?.config = list.ai
                screener?.allowedOverrides = list.aiAllowedVideoIds
                sessionGuard.saveLimits(list.limits)
                // Fast publish: entries merged with cached tile artwork (keyed by URL,
                // which survives id canonicalization). Adds/removes land right here.
                val cachedByUrl = cached.associateBy { it.url }
                val provisional = list.sources.map { e ->
                    cachedByUrl[e.url] ?: Source(e.id, e.url, e.label ?: e.id, null, e.kind)
                }
                sources = provisional
                publishChannels(provisional)
                _state.value = _state.value.copy(refreshing = false) // pull completes here
                refreshInFlight = false

                // Slow detail resolution — background lane when tiles are cosmetic.
                launch {
                    val cosmetic = cached.isNotEmpty()
                    val resolved = coroutineScope {
                        list.sources.map { entry ->
                            async {
                                runCatching { yt.source(entry, background = cosmetic) }
                                    .getOrElse { e ->
                                        android.util.Log.w("Pickwick", "source ${entry.id} failed", e)
                                        Source(entry.id, entry.url, entry.label ?: entry.id, null, entry.kind)
                                    }
                            }
                        }.awaitAll()
                    }
                    // Don't cache failed resolutions (null avatar) over previous good ones.
                    // distinctBy: entries in different forms may canonicalize to one channel.
                    val best = resolved.map { s ->
                        if (s.avatarUrl == null) cachedByUrl[s.url]?.takeIf { it.avatarUrl != null } ?: s
                        else s
                    }.distinctBy { it.id }
                    sources = best
                    sourceCache.save(best)
                    publishChannels(best)
                    // Warm the per-channel caches after the UI has settled.
                    kotlinx.coroutines.delay(3_000)
                    runCatching { warmCaches() }
                }
            }
            .onFailure { e ->
                android.util.Log.w("Pickwick", "whitelist refresh failed", e)
                _state.value = _state.value.copy(refreshing = false)
                refreshInFlight = false
                // Keep showing the cached tiles if we have them; only error on a cold cache.
                if (cached.isEmpty() && _state.value.screen == Screen.Home) {
                    _state.value = _state.value.copy(loading = false, error = e.message)
                }
            }
    }

    /**
     * Walks every source one at a time in the background lane, persisting each
     * source's videos to the disk cache as they land — so channels the kid has
     * never opened still open instantly afterwards (and feed the surprise pool).
     */
    private suspend fun warmCaches() {
        for (source in sources) {
            val videos = runCatching { yt.uploadsPage(source, background = true).videos }
                .getOrElse { e ->
                    android.util.Log.w("Pickwick", "warm ${source.id} failed", e)
                    emptyList()
                }
            if (videos.isNotEmpty()) {
                videoCache.save(source.id, videos)
                prefetchThumbs(videos.mapNotNull { it.thumbnailUrl })
                // Initial sweep: the background warm walks every source, so a fresh
                // whitelist (or new rules) gets its whole catalog screened here.
                kickScreening(videos)
            }
        }
    }

    /**
     * Sends anything not yet screened to the AI in the background; each verdict
     * batch re-filters whatever screen the kid is on, so held videos pop in as
     * they are cleared.
     */
    private fun kickScreening(videos: List<Video>) {
        screener?.screenAsync(viewModelScope, videos) {
            _state.value = _state.value.copy(
                // Verdicts can clear (or fully hold) a source — tiles follow along.
                channels = sortByUsage(visibleSources(sources.distinctBy { it.id })),
                keepWatching = keepWatchingRow(),
                videos = if (_state.value.screen == Screen.Home) _state.value.videos
                else annotated(includeFinished = includeFinishedNow()),
                held = if (_state.value.screen == Screen.Home) _state.value.held
                else heldByScreening()
            )
        }
    }

    /**
     * Channel browsing keeps fully-watched videos visible (kids rewatch!) — they
     * render dimmed with a full red bar. Surprise excludes them: it's the
     * discovery mix, and clutter there would defeat its purpose.
     */
    private fun includeFinishedNow(): Boolean = _state.value.screen is Screen.ChannelVideos

    /** Raw videos on the current screen hidden by the screener (no verdict yet or held for review). */
    private fun heldByScreening(): Int = rawVideos.count { v ->
        v.videoId !in blockedVideoIds && screener?.isVisible(v) == false
    }

    private fun annotated(includeFinished: Boolean): List<VideoItem> =
        rawVideos.mapNotNull { video ->
            if (video.videoId in blockedVideoIds) return@mapNotNull null
            if (screener?.isVisible(video) == false) return@mapNotNull null
            val p = history.progress(video.url)
            when {
                p == null -> VideoItem(video, null)
                p.isFinished && !includeFinished -> null
                else -> VideoItem(video, p.fraction)
            }
        }

    /** Re-read watch progress (called when returning from the player). */
    fun refreshProgress() {
        // Watched-to-the-end videos leave the saved list automatically.
        watchlistStore.load()
            .filter { history.progress(it.url)?.isFinished == true }
            .forEach { watchlistStore.remove(it.url) }
        val watchlisted = watchlistStore.urls()
        if (_state.value.screen == Screen.Home) {
            _state.value = _state.value.copy(
                keepWatching = keepWatchingRow(),
                newBadges = computeNewBadges(),
                watchlisted = watchlisted
            )
            return
        }
        _state.value = _state.value.copy(watchlisted = watchlisted)
        if (_state.value.screen == Screen.Downloads) {
            // Offline shelf: fresh red bars, but no screener/blocklist re-filtering.
            _state.value = _state.value.copy(videos = downloadItems())
            return
        }
        if (_state.value.screen == Screen.Watchlist) {
            rawVideos = watchlistStore.load()
        }
        _state.value = _state.value.copy(
            videos = annotated(includeFinished = includeFinishedNow()),
            held = heldByScreening()
        )
    }

    /**
     * Re-fetch the whitelist when the app comes back to the foreground — but only
     * from the home screen, so a parent's edit lands without yanking the kid out
     * of whatever they're browsing.
     */
    fun refreshIfIdle() {
        if (_state.value.screen == Screen.Home && !_state.value.loading) refresh()
        syncWatchState()
        syncConfigState()
    }

    fun openChannel(source: Source) = viewModelScope.launch {
        usage.bump(source.id)
        _state.value = _state.value.copy(screen = Screen.ChannelVideos(source), loading = true, videos = emptyList(), held = 0, error = null)
        feedHandle = null
        uploadsNextPage = null

        // Paint instantly from the last visit; refresh silently underneath.
        val cachedVideos = videoCache.load(source.id)
        if (cachedVideos.isNotEmpty()) {
            rawVideos = cachedVideos
            usage.setLastSeenLatest(source.id, cachedVideos.first().url)
            _state.value = _state.value.copy(loading = false, videos = annotated(includeFinished = true), held = heldByScreening())
            kickScreening(cachedVideos)
        } else if (source.kind == SourceKind.CHANNEL && source.id.startsWith("UC")) {
            // Cold channel: race a tiny RSS fetch for a fast first paint. The full
            // page replaces it when it lands; guards keep the slower result from
            // clobbering the richer one.
            launch {
                val quick = runCatching { yt.quickFeed(source.id) }.getOrDefault(emptyList())
                val current = _state.value.screen
                if (quick.isNotEmpty() && rawVideos.isEmpty() &&
                    current is Screen.ChannelVideos && current.source.id == source.id
                ) {
                    rawVideos = quick
                    _state.value = _state.value.copy(loading = false, videos = annotated(includeFinished = true), held = heldByScreening())
                    kickScreening(quick)
                }
            }
        }

        runCatching { yt.uploadsPage(source) }
            .onSuccess { page ->
                // Guard against a stale response landing after the kid navigated away.
                val current = _state.value.screen
                if (current !is Screen.ChannelVideos || current.source.id != source.id) return@onSuccess
                // Fresh first page, then previously-cached deeper videos after it —
                // a revisit keeps its scroll depth instead of snapping back to 30.
                rawVideos = (page.videos + cachedVideos).distinctBy { it.url }
                feedHandle = page.handle
                uploadsNextPage = page.nextPage
                videoCache.save(source.id, rawVideos.take(500))
                rawVideos.firstOrNull()?.let { usage.setLastSeenLatest(source.id, it.url) }
                // Give the on-screen thumbnails a head start before trickling the rest.
                launch {
                    kotlinx.coroutines.delay(1_500)
                    prefetchThumbs(page.videos.mapNotNull { it.thumbnailUrl })
                }
                _state.value = _state.value.copy(loading = false, videos = annotated(includeFinished = true), held = heldByScreening())
                kickScreening(rawVideos)
                topUpIfSparse()
            }
            .onFailure {
                android.util.Log.w("Pickwick", "open ${source.id} failed", it)
                if (cachedVideos.isEmpty()) {
                    _state.value = _state.value.copy(loading = false, error = it.message ?: it.javaClass.simpleName)
                }
            }
    }

    /** Random unwatched mix across whitelisted channels (playlists are excluded —
     *  those are curated sequences with autoplay, not discovery material). */
    fun surpriseMe() = viewModelScope.launch {
        _state.value = _state.value.copy(screen = Screen.Surprise, loading = true, videos = emptyList(), held = 0, error = null)
        feedHandle = null
        uploadsNextPage = null

        val channels = sources.filter { it.kind == SourceKind.CHANNEL }

        // Instant pool from the per-channel disk caches; fall back to a live fetch.
        val diskPool = channels.flatMap { videoCache.load(it.id) }
        if (diskPool.isNotEmpty()) {
            rawVideos = diskPool.shuffled()
            _state.value = _state.value.copy(loading = false, videos = annotated(includeFinished = false), held = heldByScreening())
            kickScreening(rawVideos)
            return@launch
        }
        runCatching {
            channels.flatMap { source ->
                runCatching { yt.uploadsPage(source, background = true).videos }
                    .getOrDefault(emptyList())
                    .also { if (it.isNotEmpty()) videoCache.save(source.id, it) }
            }
        }
            .onSuccess { pool ->
                if (_state.value.screen != Screen.Surprise) return@onSuccess
                rawVideos = pool.shuffled()
                _state.value = _state.value.copy(loading = false, videos = annotated(includeFinished = false), held = heldByScreening())
                kickScreening(rawVideos)
            }
            .onFailure {
                android.util.Log.w("Pickwick", "surprise failed", it)
                _state.value = _state.value.copy(loading = false, error = it.message ?: it.javaClass.simpleName)
            }
    }

    /** Called when the kid scrolls near the end of a source's grid. */
    fun loadMoreUploads() {
        if (loadingMore) return
        val handle = feedHandle ?: return
        val next = uploadsNextPage ?: return
        if (_state.value.screen !is Screen.ChannelVideos) return
        loadingMore = true
        _state.value = _state.value.copy(loadingMore = true)
        viewModelScope.launch {
            runCatching { yt.moreUploads(handle, next) }
                .onSuccess { page ->
                    uploadsNextPage = page.nextPage
                    // distinctBy: page 2 overlaps the cached deep list after a merge.
                    rawVideos = (rawVideos + page.videos).distinctBy { it.url }
                    // Persist the whole accumulated list so the next visit paints
                    // this deep instantly (capped to keep the cache file sane).
                    (_state.value.screen as? Screen.ChannelVideos)?.let { s ->
                        videoCache.save(s.source.id, rawVideos.take(500))
                    }
                    launch { prefetchThumbs(page.videos.mapNotNull { it.thumbnailUrl }) }
                    _state.value = _state.value.copy(videos = annotated(includeFinished = true), held = heldByScreening())
                    kickScreening(page.videos)
                }
                .onFailure { android.util.Log.w("Pickwick", "load more failed", it) }
            loadingMore = false
            _state.value = _state.value.copy(loadingMore = false)
            topUpIfSparse()
        }
    }

    /**
     * Hiding watched videos can leave a nearly-empty first screen even though older
     * pages exist — keep fetching until there's enough to fill the grid or pages run out.
     */
    private fun topUpIfSparse() {
        if (_state.value.screen is Screen.ChannelVideos &&
            _state.value.videos.size < 12 && uploadsNextPage != null
        ) {
            loadMoreUploads()
        }
    }

    /** Hold-to-save: toggles a video in the kid's saved-for-later list. */
    fun toggleWatchlist(item: VideoItem) {
        val url = item.video.url
        if (url in _state.value.watchlisted) {
            watchlistStore.remove(url)
        } else {
            watchlistStore.add(item.video)
        }
        _state.value = _state.value.copy(watchlisted = watchlistStore.urls())
        if (_state.value.screen == Screen.Watchlist) {
            rawVideos = watchlistStore.load()
            _state.value = _state.value.copy(videos = annotated(includeFinished = false), held = heldByScreening())
        }
        syncWatchState() // hearts propagate promptly
    }

    fun openWatchlist() {
        // Fully-watched entries have served their purpose — drop them silently.
        watchlistStore.load()
            .filter { history.progress(it.url)?.isFinished == true }
            .forEach { watchlistStore.remove(it.url) }
        rawVideos = watchlistStore.load()
        feedHandle = null
        uploadsNextPage = null
        _state.value = _state.value.copy(
            screen = Screen.Watchlist,
            loading = false,
            error = null,
            watchlisted = watchlistStore.urls(),
            videos = annotated(includeFinished = false),
            held = heldByScreening()
        )
    }

    /**
     * Kid taps the poster's ⬇ icon: request a download (parent approves later),
     * or withdraw a request that hasn't been approved yet. Already-downloaded
     * videos ignore the tap — deleting is the parent's job in settings.
     */
    fun toggleDownload(item: VideoItem) {
        val store = downloadStore ?: return
        val url = item.video.url
        when {
            url in _state.value.downloaded -> return
            url in _state.value.downloadPending -> store.cancelRequest(url)
            else -> store.request(item.video)
        }
        // The store bumps DownloadEvents.changes, which refreshes the badges.
    }

    /**
     * The offline shelf: finished downloads plus parent-sideloaded local files.
     * No screener/blocklist re-filtering here: everything on it was explicitly
     * approved (or added) by the parent, and it must work with no network.
     */
    fun openDownloads() {
        if (downloadStore == null && localLibrary == null) return
        rawVideos = downloadStore?.downloadedVideos().orEmpty() +
            localLibrary?.videos().orEmpty()
        feedHandle = null
        uploadsNextPage = null
        _state.value = _state.value.copy(
            screen = Screen.Downloads,
            loading = false,
            error = null,
            downloadPending = downloadStore?.pendingUrls().orEmpty(),
            downloaded = downloadStore?.downloadedUrls().orEmpty() + localLibrary?.urls().orEmpty(),
            videos = downloadItems()
        )
    }

    private fun downloadItems(): List<VideoItem> =
        rawVideos.map { VideoItem(it, history.progress(it.url)?.fraction) }

    private fun refreshDownloadState() {
        if (downloadStore == null && localLibrary == null) return
        _state.value = _state.value.copy(
            downloadPending = downloadStore?.pendingUrls().orEmpty(),
            downloaded = downloadStore?.downloadedUrls().orEmpty() + localLibrary?.urls().orEmpty()
        )
        if (_state.value.screen == Screen.Downloads) {
            rawVideos = downloadStore?.downloadedVideos().orEmpty() +
                localLibrary?.videos().orEmpty()
            _state.value = _state.value.copy(videos = downloadItems())
        }
    }

    /**
     * Long-press on a Keep watching tile: mark the video fully watched so it
     * leaves the row (and the channel grids treat it like any finished video).
     */
    fun dismissKeepWatching(item: VideoItem) {
        history.progress(item.video.url)?.let {
            history.save(item.video.url, it.durationMs, it.durationMs)
        }
        _state.value = _state.value.copy(keepWatching = keepWatchingRow())
        syncWatchState() // the dismissal propagates like any other watch progress
    }

    fun goHome() {
        rawVideos = emptyList()
        feedHandle = null
        uploadsNextPage = null
        _state.value = _state.value.copy(
            screen = Screen.Home, videos = emptyList(), held = 0, loading = false, error = null,
            // Re-rank right away so a freshly opened channel climbs immediately.
            channels = sortByUsage(_state.value.channels),
            keepWatching = keepWatchingRow(),
            newBadges = computeNewBadges()
        )
    }
}

// FragmentActivity: required host for androidx BiometricPrompt (parent gate).
class MainActivity : androidx.fragment.app.FragmentActivity() {

    /** Pairing progresses: scanned → confirm → waiting for approval → result. */
    sealed interface PairFlow {
        data class Confirm(val name: String, val host: String, val port: Int) : PairFlow
        data class Waiting(val name: String, val host: String, val port: Int) : PairFlow
        data class Result(val message: String) : PairFlow
    }

    private val pairFlow = mutableStateOf<PairFlow?>(null)

    private fun handlePairIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "pickwick" || data.host != "pair") return
        val host = data.getQueryParameter("host") ?: return
        val port = data.getQueryParameter("port")?.toIntOrNull() ?: return
        val name = data.getQueryParameter("name") ?: "TV"
        pairFlow.value = PairFlow.Confirm(name, host, port)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(applicationContext)
        val appContext = applicationContext
        val configStore = ConfigStore(applicationContext)
        val pairingStore = PairingStore(applicationContext)
        val downloadStore = DownloadStore(applicationContext)
        val localLibrary = LocalLibrary(applicationContext)
        // A queue interrupted by a reboot or crash resumes on next open.
        DownloadService.startIfPending(this)
        // Seeded before the ViewModel exists so the first cached paint is already
        // gated — a held video must never flash on screen while config loads.
        val screener = io.pickwick.app.data.Screener(io.pickwick.app.data.ScreeningStore(applicationContext)).also {
            val config = configStore.load()
            it.config = config.ai
            it.allowedOverrides = config.aiAllowedVideoIds
        }
        val deviceIsTv = (getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        if (deviceIsTv && LanServerHolder.server == null) {
            LanServerHolder.server = LanServer(
                configStore, SessionGuard(applicationContext), pairingStore,
                statsProvider = { io.pickwick.app.data.Stats.build(appContext) },
                watchStateProvider = { WatchSync.exportJson(appContext) },
                watchStateMerger = { json ->
                    if (WatchSync.mergeJson(appContext, json)) {
                        // Refresh keep-watching / hearts on the TV right away.
                        ConfigEvents.onConfigChanged?.invoke()
                    }
                }
            ).also { it.start() }
        }
        handlePairIntent(intent)
        val prefetchThumbs: suspend (List<String>) -> Unit = { urls ->
            val loader = coil.Coil.imageLoader(appContext)
            for (url in urls) {
                // execute() suspends until done — one image in flight at a time,
                // and a no-op for anything already in the disk cache.
                runCatching {
                    loader.execute(coil.request.ImageRequest.Builder(appContext).data(url).build())
                }
            }
        }
        setContent {
            MaterialTheme(colorScheme = PickwickDarkColors) {
                val vm: MainViewModel = viewModel {
                    MainViewModel(
                        WhitelistRepository(configStore),
                        WatchHistoryStore(applicationContext),
                        SourceCache(applicationContext),
                        VideoCache(applicationContext),
                        UsageStore(applicationContext),
                        SessionGuard(applicationContext),
                        WatchlistStore(applicationContext),
                        pairingStore,
                        configStore = configStore,
                        downloadStore = downloadStore,
                        localLibrary = localLibrary,
                        isOffline = {
                            val cm = appContext.getSystemService(
                                android.content.Context.CONNECTIVITY_SERVICE
                            ) as android.net.ConnectivityManager
                            cm.activeNetwork == null
                        },
                        exportWatchState = { WatchSync.exportJson(appContext) },
                        mergeWatchState = { WatchSync.mergeJson(appContext, it) },
                        prefetchThumbs = prefetchThumbs,
                        screener = screener
                    )
                }
                // TV: a paired phone just pushed new config — apply it live.
                SideEffect {
                    ConfigEvents.onConfigChanged = { runOnUiThread { vm.refresh() } }
                }
                // Phone: pairing flow started by scanning a TV's QR code.
                when (val flow = pairFlow.value) {
                    is PairFlow.Confirm -> AlertDialog(
                        onDismissRequest = { pairFlow.value = null },
                        title = { Text("Pair with ${flow.name}?") },
                        text = { Text("This phone will manage that device's channels and screen time.") },
                        confirmButton = {
                            Button(onClick = {
                                pairFlow.value = PairFlow.Waiting(flow.name, flow.host, flow.port)
                            }) { Text("Pair") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pairFlow.value = null }) { Text("Cancel") }
                        }
                    )
                    is PairFlow.Waiting -> {
                        // The dialog narrates which step is running: contacting the TV
                        // and copying its config are both LAN round-trips that can take
                        // seconds, and a static "Waiting for approval…" read as a freeze.
                        var status by remember(flow) {
                            mutableStateOf("Contacting ${flow.name}…")
                        }
                        var awaitingApproval by remember(flow) { mutableStateOf(false) }
                        LaunchedEffect(flow) {
                            val myToken = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                pairingStore.deviceToken()
                            }
                            val myName = android.os.Build.MODEL ?: "Phone"
                            suspend fun paired() {
                                awaitingApproval = false
                                status = "Copying settings from ${flow.name}…"
                                val device = PairedDevice(flow.name, flow.host, flow.port, myToken)
                                // Prefs and config are disk + JSON work; off the main
                                // thread so the spinner keeps animating.
                                val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    pairingStore.addPaired(device)
                                    configStore.load()
                                }
                                // Fresh install (or wiped phone): the TV still holds the
                                // family's config — blocks, safe-list, channels, rules.
                                // Adopt it instead of later stomping it with an empty one.
                                val blank = local.sources.isEmpty() &&
                                    local.blockedVideoIds.isEmpty() &&
                                    local.aiAllowedVideoIds.isEmpty()
                                val restored = blank &&
                                    LanClient.fetchConfig(device)?.let { remote ->
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val parsed = runCatching { ConfigStore.fromJson(remote) }.getOrNull()
                                            parsed != null && parsed.sources.isNotEmpty() &&
                                                configStore.saveRaw(remote)
                                        }
                                    } == true
                                if (restored) ConfigEvents.onConfigChanged?.invoke()
                                pairFlow.value = PairFlow.Result(
                                    if (restored) "Paired with ${flow.name} ✓ — its settings were copied to this phone"
                                    else "Paired with ${flow.name} ✓"
                                )
                            }
                            when (LanClient.requestPairing(flow.host, flow.port, myName, myToken)) {
                                "approved" -> paired()
                                "pending" -> {
                                    awaitingApproval = true
                                    status = "Open Pickwick settings on the family's " +
                                        "already-paired phone and approve this request " +
                                        "for \"$myName\"."
                                    // Wait for the family's approved phone to say yes.
                                    repeat(100) {
                                        kotlinx.coroutines.delay(3_000)
                                        when (LanClient.pairStatus(flow.host, flow.port, myToken)) {
                                            "approved" -> { paired(); return@LaunchedEffect }
                                            "unknown" -> {
                                                pairFlow.value =
                                                    PairFlow.Result("The request was declined")
                                                return@LaunchedEffect
                                            }
                                        }
                                    }
                                    pairFlow.value =
                                        PairFlow.Result("No approval received — ask on the paired phone, then scan again")
                                }
                                else -> pairFlow.value =
                                    PairFlow.Result("Couldn't reach ${flow.name} — same Wi-Fi, app open on it?")
                            }
                        }
                        AlertDialog(
                            onDismissRequest = { /* keep waiting; cancel via button */ },
                            title = {
                                Text(if (awaitingApproval) "Waiting for approval…" else "Pairing…")
                            },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text(status)
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { pairFlow.value = null }) { Text("Cancel") }
                            }
                        )
                    }
                    is PairFlow.Result -> AlertDialog(
                        onDismissRequest = { pairFlow.value = null },
                        title = { Text(flow.message) },
                        confirmButton = {
                            Button(onClick = { pairFlow.value = null }) { Text("OK") }
                        }
                    )
                    null -> {}
                }
                var showSettings by remember { mutableStateOf(false) }
                if (showSettings) {
                    SettingsFlow(settings, configStore, pairingStore, deviceIsTv) { changed ->
                        showSettings = false
                        if (changed) vm.refresh()
                    }
                } else {
                    PickwickScreen(
                        vm,
                        onOpenSettings = { showSettings = true }
                    ) { item ->
                        val s = vm.state.value
                        val screen = s.screen
                        val intent = Intent(this, PlayerActivity::class.java)
                        // Playlist sources auto-advance: hand the player the visible queue.
                        if (screen is Screen.ChannelVideos && screen.source.kind == SourceKind.PLAYLIST) {
                            intent.putStringArrayListExtra(
                                PlayerActivity.EXTRA_QUEUE,
                                ArrayList(s.videos.map { it.video.url })
                            )
                            intent.putExtra(
                                PlayerActivity.EXTRA_INDEX,
                                s.videos.indexOfFirst { it.video.url == item.video.url }.coerceAtLeast(0)
                            )
                        } else {
                            intent.putExtra(PlayerActivity.EXTRA_VIDEO_URL, item.video.url)
                        }
                        // Channel name travels with the intent for per-channel stats.
                        intent.putExtra(PlayerActivity.EXTRA_CHANNEL, item.video.channelName)
                        // Screen-time drain rate: exact from the open source; for
                        // mixed rows (Surprise, My list, Keep watching) resolved by
                        // the video's channel name, defaulting to normal speed.
                        val timePercent = when (screen) {
                            is Screen.ChannelVideos -> screen.source.timeMultiplierPercent
                            else -> s.channels
                                .firstOrNull { it.name == item.video.channelName }
                                ?.timeMultiplierPercent ?: 100
                        }
                        intent.putExtra(PlayerActivity.EXTRA_TIME_PERCENT, timePercent)
                        startActivity(intent)
                    }
                }
            }
        }
    }
}

/**
 * TV D-pad scrolling: the default bring-into-view spec scrolls only when the
 * focused item reaches the container's edge, so the grid sits still and then
 * lurches a whole row at once. This pivot spec instead keeps the focused item
 * anchored ~30% from the leading edge and animates every step — steady, smooth
 * motion (the same approach as Compose's TV libraries).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class TvPivotBringIntoView(
    /** Where the focused item's leading edge settles, as a fraction of the container. */
    private val pivot: Float
) : androidx.compose.foundation.gestures.BringIntoViewSpec {

    // Spring, not tween: holding the D-pad interrupts the animation on every
    // step, and a tween restarts from zero velocity each time (a pulsing,
    // stop-start feel). A no-bounce spring carries its velocity into the new
    // target, so held-key scrolling glides at a steady rate — the Netflix feel.
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        )

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val target = pivot * containerSize
        val leadingEdge = if (size <= containerSize && containerSize - target < size) {
            containerSize - size // near the end: don't overscroll past the content
        } else target
        return offset - leadingEdge
    }
}

/** Vertical browsing: the focused row settles a quarter down the screen. */
private val TvColumnPivot = TvPivotBringIntoView(0.25f)

/**
 * Inside a row: the focused tile stays pinned left-of-center. Not tighter than
 * this: with the pivot hugging the left edge there are no composed tiles to the
 * left of focus, so a held LEFT press must compose each tile inside the ~50ms
 * key-repeat window — which the TV can't do, making left-hold stutter while
 * right-hold (90% of the row pre-composed) glides. 25% keeps a runway on both sides.
 */
private val TvRowPivot = TvPivotBringIntoView(0.25f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickwickScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onPlay: (VideoItem) -> Unit
) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val isTv = remember {
        (context.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    BackHandler(enabled = state.screen != Screen.Home) { vm.goHome() }

    // Coming back from the player: re-read progress so bars/filters update.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    vm.refreshProgress()
                    // Fresh progress after watching — share it with paired devices.
                    vm.syncWatchState()
                }
                // App returned to foreground: pick up any whitelist edits.
                Lifecycle.Event.ON_START -> vm.refreshIfIdle()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides
                if (isTv) TvColumnPivot
                else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current,
            // No touch on TV → the overscroll stretch shader is pure render cost
            // at every list edge. Off.
            androidx.compose.foundation.LocalOverscrollConfiguration provides
                if (isTv) null
                else androidx.compose.foundation.LocalOverscrollConfiguration.current
        ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Something went wrong — tell a parent!\n\n${state.error}")
                }
                state.screen is Screen.Home -> {
                    // TV gets the ten-foot layout: horizontal rows under a pinned
                    // focus, like every streaming app's browse screen. No touch, so
                    // no pull-to-refresh either (auto + poll refresh cover it).
                    if (isTv) {
                        TvHomeRows(
                            channels = state.channels,
                            newBadges = state.newBadges,
                            keepWatching = state.keepWatching,
                            onPlay = onPlay,
                            onDismissKeepWatching = vm::dismissKeepWatching,
                            onOpen = vm::openChannel,
                            onSurprise = vm::surpriseMe,
                            onOpenWatchlist = vm::openWatchlist,
                            onOpenSettings = onOpenSettings
                        )
                    } else {
                        PullToRefreshBox(
                            isRefreshing = state.refreshing,
                            onRefresh = { vm.refresh(userInitiated = true) },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            ChannelGrid(
                                state.channels,
                                newBadges = state.newBadges,
                                keepWatching = state.keepWatching,
                                onPlay = onPlay,
                                onDismissKeepWatching = vm::dismissKeepWatching,
                                onOpen = vm::openChannel,
                                onSurprise = vm::surpriseMe,
                                onOpenWatchlist = vm::openWatchlist,
                                hasDownloads = state.downloaded.isNotEmpty(),
                                onOpenDownloads = vm::openDownloads,
                                onOpenSettings = onOpenSettings
                            )
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    val title = when (val s = state.screen) {
                        is Screen.ChannelVideos -> s.source.name
                        is Screen.Surprise -> "🎲 Surprise!"
                        is Screen.Watchlist -> "❤️ My list"
                        is Screen.Downloads -> "⬇️ Downloads"
                        else -> ""
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        val screen = state.screen
                        if (screen is Screen.ChannelVideos &&
                            screen.source.kind == SourceKind.PLAYLIST &&
                            state.videos.isNotEmpty()
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    // Resume mid-video if one is in progress, else the next
                                    // unwatched — never a finished one (they're visible now).
                                    val next = state.videos.firstOrNull {
                                        it.progress != null && it.progress < 0.98f
                                    }
                                        ?: state.videos.firstOrNull { it.progress == null }
                                        ?: state.videos.first()
                                    onPlay(next)
                                },
                                label = { Text("▶ Continue") },
                                modifier = Modifier.tvFocusHighlight()
                            )
                        }
                    }
                    VideoGrid(
                        state.videos,
                        onPlay = onPlay,
                        // An all-held source must say so — a silently empty grid
                        // reads as broken to the kid and the parent alike.
                        emptyText = if (state.held > 0) {
                            "${state.held} video(s) here are waiting for a parent's OK.\n" +
                                "A parent can allow them from the app on their phone\n" +
                                "(the kid device's page, under \"AI screening\")."
                        } else "Nothing here yet.",
                        loadingMore = state.loadingMore,
                        watchlisted = state.watchlisted,
                        onToggleWatchlist = vm::toggleWatchlist,
                        downloadPending = state.downloadPending,
                        downloaded = state.downloaded,
                        // Downloads are phone-only: the TV stays on home Wi-Fi.
                        onToggleDownload = if (isTv) null else vm::toggleDownload,
                        grabFocus = isTv,
                        onNearEnd = if (state.screen is Screen.ChannelVideos) vm::loadMoreUploads else null
                    )
                }
            }
        }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KeepWatchingRow(
    items: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    onDismiss: (VideoItem) -> Unit
) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // Room for the focus glow so it isn't clipped by the row bounds.
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        // Held ◀/▶ paces itself so leftward steps (nothing pre-composed behind
        // the pivot) stop sticking mid-scroll.
        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
    ) {
        items(items.size, key = { items[it].video.url }) { index ->
            val item = items[index]
            var focused by remember { mutableStateOf(false) }
            Card(
                shape = androidx.compose.ui.graphics.RectangleShape,
                modifier = Modifier
                    .tvFocusHighlight { focused = it }
                    // Touch long-press and remote hold-OK both dismiss the tile.
                    .dpadLongPress { onDismiss(item) }
                    .combinedClickable(
                        onClick = { onPlay(item) },
                        onLongClick = { onDismiss(item) }
                    )
                    .width(150.dp)
            ) {
                Column {
                    Box {
                        AsyncImage(
                            model = item.video.thumbnailUrl,
                            contentDescription = item.video.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        item.progress?.let { fraction ->
                            Box(
                                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                                    .height(4.dp).background(Color(0x66FFFFFF))
                            )
                            Box(
                                Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction)
                                    .height(4.dp).background(WatchedProgressRed)
                            )
                        }
                    }
                    Box(Modifier.padding(8.dp)) {
                        MarqueeTitle(item.video.title, focused,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelGrid(
    channels: List<Source>,
    newBadges: Set<String> = emptySet(),
    keepWatching: List<VideoItem> = emptyList(),
    onPlay: (VideoItem) -> Unit = {},
    onDismissKeepWatching: (VideoItem) -> Unit = {},
    onOpen: (Source) -> Unit,
    onSurprise: () -> Unit,
    onOpenWatchlist: () -> Unit,
    hasDownloads: Boolean = false,
    onOpenDownloads: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    if (channels.isEmpty()) {
        EmptyHome(onOpenSettings)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // Room for the focus glow on edge tiles.
        contentPadding = PaddingValues(8.dp)
    ) {
        // Branding + settings scroll away like everything else — content is king.
        item(key = "app-header", span = { GridItemSpan(maxLineSpan) }) {
            HomeHeader(onOpenSettings)
        }
        // Keep-watching scrolls away with the rest — not sticky.
        if (keepWatching.isNotEmpty()) {
            item(key = "kw-title", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Keep watching",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                )
            }
            item(key = "kw-row", span = { GridItemSpan(maxLineSpan) }) {
                KeepWatchingRow(keepWatching, onPlay = onPlay, onDismiss = onDismissKeepWatching)
            }
        }
        // First tile: Surprise — same size and shape as a channel tile.
        item(key = "surprise-tile") {
            SpecialTile(
                emoji = "🎲",
                label = "Surprise me!",
                circleColor = Color(0xFF00ACC1),
                onClick = onSurprise
            )
        }
        // Second tile: the kid's saved-for-later list.
        item(key = "watchlist-tile") {
            SpecialTile(
                emoji = "❤️",
                label = "My list",
                circleColor = Color(0xFF00897B),
                onClick = onOpenWatchlist
            )
        }
        // The offline shelf appears once the first download lands.
        if (hasDownloads) {
            item(key = "downloads-tile") {
                SpecialTile(
                    emoji = "⬇️",
                    label = "Downloads",
                    circleColor = Color(0xFF00636E),
                    onClick = onOpenDownloads
                )
            }
        }
        items(channels, key = { it.id }) { channel ->
            ChannelTile(channel, isNew = channel.id in newBadges, onOpen = onOpen)
        }
    }
}

@Composable
private fun ChannelTile(
    channel: Source,
    isNew: Boolean,
    onOpen: (Source) -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        shape = androidx.compose.ui.graphics.RectangleShape,
        modifier = modifier
            .tvFocusHighlight { focused = it }
            .clickable { onOpen(channel) }
    ) {
        Column {
            // Full-bleed cover, video-tile style (1:1 — avatars are square).
            Box {
                AsyncImage(
                    model = channel.avatarUrl,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
                if (isNew) {
                    Text(
                        "NEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color(0xFF4DB6AC))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                // The screen-time "price tag" — shown only when it differs from
                // normal, so kids can pick cheap/free channels knowingly.
                timeMultiplierColor(channel.timeMultiplierPercent)?.let { color ->
                    Text(
                        timeMultiplierLabel(channel.timeMultiplierPercent),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(color)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Box(Modifier.padding(8.dp)) {
                MarqueeTitle(
                    text = channel.name +
                        if (channel.kind == SourceKind.PLAYLIST) "  ·  playlist" else "",
                    focused = focused
                )
            }
        }
    }
}

/**
 * First-run screen: without it a fresh install is a dead end — the settings
 * entry lives in the home header, which only renders once channels exist.
 * The button auto-focuses so a TV remote can open settings with one press.
 */
@Composable
private fun EmptyHome(onOpenSettings: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            "No channels yet",
            style = MaterialTheme.typography.headlineSmall
        )
        Text("Add channels in parent settings to fill this screen.")
        Button(
            onClick = onOpenSettings,
            modifier = Modifier
                .focusRequester(focus)
                .tvFocusHighlight()
        ) {
            Text("Parent settings")
        }
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // Mini logo mark: dark teal square, white play triangle — the
            // launcher tile at header scale.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(0xFF00695C))
            ) {
                Text("▶", color = Color.White,
                    style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "Pickwick",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.5f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
            )
        }
        IconButton(
            modifier = Modifier.size(48.dp),
            onClick = onOpenSettings
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "Settings",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * The ten-foot home: a vertical stack of horizontal rows — Keep watching,
 * Channels (most-watched first), Explore — with the focused tile pinned near
 * the left edge of its row. The row structure plus the pivot scroll specs is
 * what gives streaming apps their steady, predictable D-pad motion.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TvHomeRows(
    channels: List<Source>,
    newBadges: Set<String>,
    keepWatching: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    onDismissKeepWatching: (VideoItem) -> Unit,
    onOpen: (Source) -> Unit,
    onSurprise: () -> Unit,
    onOpenWatchlist: () -> Unit,
    onOpenSettings: () -> Unit
) {
    if (channels.isEmpty()) {
        EmptyHome(onOpenSettings)
        return
    }
    val firstTileFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTileFocus.requestFocus() } }

    androidx.compose.foundation.lazy.LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item(key = "header") { HomeHeader(onOpenSettings) }

        if (keepWatching.isNotEmpty()) {
            item(key = "kw") {
                Column {
                    TvRowTitle("Keep watching")
                    CompositionLocalProvider(
                        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
                    ) {
                        KeepWatchingRow(keepWatching, onPlay = onPlay, onDismiss = onDismissKeepWatching)
                    }
                }
            }
        }

        item(key = "channels") {
            Column {
                TvRowTitle("Channels")
                CompositionLocalProvider(
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
                    ) {
                        items(channels.size, key = { channels[it].id }) { i ->
                            ChannelTile(
                                channels[i],
                                isNew = channels[i].id in newBadges,
                                onOpen = onOpen,
                                modifier = (if (i == 0) Modifier.focusRequester(firstTileFocus)
                                    else Modifier).width(150.dp)
                            )
                        }
                    }
                }
            }
        }

        item(key = "explore") {
            Column {
                TvRowTitle("Explore")
                CompositionLocalProvider(
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        item(key = "surprise") {
                            SpecialTile(
                                emoji = "🎲", label = "Surprise me!",
                                circleColor = Color(0xFF00ACC1),
                                modifier = Modifier.width(150.dp),
                                onClick = onSurprise
                            )
                        }
                        item(key = "watchlist") {
                            SpecialTile(
                                emoji = "❤️", label = "My list",
                                circleColor = Color(0xFF00897B),
                                modifier = Modifier.width(150.dp),
                                onClick = onOpenWatchlist
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvRowTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        ),
        modifier = Modifier.padding(start = 8.dp, top = 12.dp)
    )
}

/**
 * A small emoji action riding a poster corner: dark circular scrim for
 * legibility over any thumbnail, and a springy pop whenever its state flips
 * (🤍→❤️, ⬇️→⏳) — the subtle "got it" feedback for a kid's tap.
 */
@Composable
private fun PosterAction(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    var previous by remember { mutableStateOf(label) }
    LaunchedEffect(label) {
        if (previous != label) {
            previous = label
            scale.snapTo(1.4f)
            scale.animateTo(
                1f,
                androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy
                )
            )
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(0x66000000))
            .let { m -> if (onClick != null) m.clickable { onClick() } else m }
            .padding(6.dp)
    )
}

/** One line, always the same tile height; scrolls sideways while focused. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MarqueeTitle(
    text: String,
    focused: Boolean,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium
) {
    Text(
        text,
        maxLines = 1,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        style = style,
        modifier = if (focused) Modifier.basicMarquee() else Modifier
    )
}

@Composable
private fun SpecialTile(
    emoji: String,
    label: String,
    circleColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        shape = androidx.compose.ui.graphics.RectangleShape,
        modifier = modifier.tvFocusHighlight().clickable { onClick() }
    ) {
        Column {
            // Full-bleed color block matching the channel-tile geometry.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(circleColor)
            ) {
                Text(emoji, fontSize = androidx.compose.ui.unit.TextUnit(56f, androidx.compose.ui.unit.TextUnitType.Sp))
            }
            Box(Modifier.padding(8.dp)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VideoGrid(
    videos: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    emptyText: String = "Nothing here yet.",
    loadingMore: Boolean = false,
    watchlisted: Set<String> = emptySet(),
    onToggleWatchlist: ((VideoItem) -> Unit)? = null,
    downloadPending: Set<String> = emptySet(),
    downloaded: Set<String> = emptySet(),
    /** Non-null on phones: shows the tappable ❤️/⬇️ poster icons. */
    onToggleDownload: ((VideoItem) -> Unit)? = null,
    grabFocus: Boolean = false,
    onNearEnd: (() -> Unit)? = null
) {
    if (videos.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                emptyText,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    // TV: land focus on the first tile when the grid gets content — never the cog.
    val firstTileFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (grabFocus) {
        LaunchedEffect(videos.isNotEmpty()) {
            runCatching { firstTileFocus.requestFocus() }
        }
    }
    val gridState = rememberLazyGridState()
    if (onNearEnd != null) {
        // Fire on every scroll-position change, not on a boolean edge: if one page
        // fetch fails, the next nudge of the scroll retries instead of going dead.
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { lastVisible ->
                    if (lastVisible >= 0 && lastVisible >= gridState.layoutInfo.totalItemsCount - 6) {
                        onNearEnd()
                    }
                }
        }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 240.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // Room for the focus glow on edge tiles.
        contentPadding = PaddingValues(8.dp),
        // Held D-pad browsing (any direction) advances at a readable, steady
        // pace instead of one step per ~50ms key repeat, which outruns both the
        // reader and tile composition (worst going up/left — nothing behind the
        // pivot is pre-composed, so unpaced steps stick mid-scroll).
        modifier = Modifier.dpadHeldScrollThrottle()
    ) {
        items(videos, key = { it.video.url }) { item ->
            // Tap/OK plays; hold (touch long-press or held OK on the remote)
            // saves to / removes from the kid's list.
            var focused by remember { mutableStateOf(false) }
            val focusMod = if (grabFocus && item == videos.first()) {
                Modifier.focusRequester(firstTileFocus)
            } else Modifier
            val cardModifier = if (onToggleWatchlist != null) {
                focusMod
                    .tvFocusHighlight { focused = it }
                    .dpadLongPress { onToggleWatchlist(item) }
                    .combinedClickable(
                        onClick = { onPlay(item) },
                        onLongClick = { onToggleWatchlist(item) }
                    )
            } else {
                focusMod.tvFocusHighlight { focused = it }.clickable { onPlay(item) }
            }
            // Watched videos stay browsable (kids rewatch) but recede: dimmed
            // unless focused, plus their full red bar below.
            val finished = (item.progress ?: 0f) >= 0.98f
            Card(
                shape = androidx.compose.ui.graphics.RectangleShape,
                modifier = cardModifier.graphicsLayer {
                    alpha = if (finished && !focused) 0.5f else 1f
                }
            ) {
                Column {
                    Box {
                        AsyncImage(
                            model = item.video.thumbnailUrl,
                            contentDescription = item.video.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        if (onToggleDownload != null) {
                            // Phone: tappable poster actions — heart on the left
                            // (save for later), download on the right (ask a
                            // parent → ⏳ → progress ring → ✅ ready offline).
                            PosterAction(
                                label = if (item.video.url in watchlisted) "❤️" else "🤍",
                                onClick = { onToggleWatchlist?.invoke(item) },
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                            )
                            val active by DownloadEvents.progress.collectAsState()
                            val fraction = active?.takeIf { it.first == item.video.url }?.second
                            when {
                                item.video.url in downloaded -> PosterAction(
                                    label = "✅",
                                    onClick = null,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                )
                                fraction != null -> Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color(0x66000000))
                                        .padding(6.dp)
                                ) {
                                    CircularProgressIndicator(
                                        progress = { fraction },
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White,
                                        trackColor = Color(0x40FFFFFF)
                                    )
                                }
                                else -> PosterAction(
                                    label = if (item.video.url in downloadPending) "⏳" else "⬇️",
                                    onClick = { onToggleDownload(item) },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                )
                            }
                        } else if (item.video.url in watchlisted) {
                            Text(
                                "❤️",
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            )
                        }
                        // YouTube-style watched-progress bar.
                        item.progress?.let { fraction ->
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .background(Color(0x66FFFFFF))
                            )
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(fraction)
                                    .height(4.dp)
                                    .background(WatchedProgressRed)
                            )
                        }
                    }
                    Column(Modifier.padding(8.dp)) {
                        MarqueeTitle(item.video.title, focused)
                        Text(item.video.channelName, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
        }
    }
}
