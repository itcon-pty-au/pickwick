package io.pickwick.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.pickwick.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search hits screened per window — the bill tracks what's actually looked at,
 *  not the thousands a broad query can match against a full back catalog. */
private const val SEARCH_SCREEN_BATCH = 50

class MainViewModel(
    private val whitelist: WhitelistRepository,
    private val history: WatchHistoryStore,
    private val sourceCache: SourceCache,
    private val videoCache: VideoCache,
    private val usage: UsageStore,
    private val sessionGuard: SessionGuard,
    private val watchlistStore: SavedListStore,
    private val watchLaterStore: SavedListStore,
    private val queueStore: QueueStore,
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
    /** Serializes this device's AI verdicts for cross-device sharing. */
    private val exportVerdicts: () -> String = { "{}" },
    /** Imports a peer's AI verdicts. Returns true when any were new. */
    private val mergeVerdicts: (String) -> Boolean = { false },
    /**
     * Snapshots a device's stats into the phone-side cache. Riding the periodic
     * sync keeps the snapshot warm, so the parent's Stats page has yesterday's
     * picture even when the TV has been off all day.
     */
    private val cacheStats: suspend (PairedDevice) -> Unit = {},
    /**
     * Warms thumbnails into Coil's disk cache, one at a time, so the trickle
     * never competes with the images currently on screen.
     */
    private val prefetchThumbs: suspend (List<String>) -> Unit = {},
    /** AI screening gate; null in tests. Unscreened/blocked videos never render. */
    private val screener: io.pickwick.app.data.Screener? = null,
    /** Whose home screen this is; null = pre-profile single-kid behavior. */
    private val activeProfileId: String? = null,
    /** Search index; null in tests. The crawler runs only on the master device. */
    private val channelIndex: ChannelIndex? = null,
    private val yt: YouTubeRepository = YouTubeRepository()
) : ViewModel() {

    private val crawler = channelIndex?.let { IndexCrawler(yt, it) }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var sources: List<Source> = emptyList()
    private var blockedVideoIds: Set<String> = emptySet()
    /** This kid's "hide videos shorter than" rule, in seconds; 0 = no rule. */
    private var minVideoSeconds: Long = 0

    /**
     * The length rule, checked wherever [blockedVideoIds] is: a too-short video
     * is hidden outright, not held for review, so it never counts as
     * "waiting on screening" either. Unknown durations (0) pass — a live
     * stream or a cache row from before durations were stored is not a clip.
     */
    private fun tooShort(v: Video): Boolean =
        minVideoSeconds > 0 && v.durationSeconds in 1 until minVideoSeconds
    /** Channels that exist in the family config but belong to other kids —
     *  used to keep their downloads off this kid's offline shelf. */
    private var hiddenChannelNames: Set<String> = emptySet()

    /** The unfiltered videos behind the current screen; progress/filtering applied on top. */
    private var rawVideos: List<Video> = emptyList()

    // Pagination state for the currently open source.
    private var feedHandle: YouTubeRepository.FeedHandle? = null
    private var uploadsNextPage: org.schabi.newpipe.extractor.Page? = null
    private var loadingMore = false

    /**
     * The offline shelf through this kid's eyes: YouTube downloads follow
     * their channel's per-kid switches, local folders their own visibility.
     */
    private fun visibleDownloads(): List<Video> =
        downloadStore?.downloadedVideos().orEmpty()
            .filter { it.channelName !in hiddenChannelNames } +
            localLibrary?.videos(activeProfileId).orEmpty()

    private fun visibleDownloadUrls(): Set<String> =
        visibleDownloads().map { it.url }.toSet()

    init {
        viewModelScope.launch {
            // All five sets are file reads — off-main like everything else
            // here; the home rows render immediately, badges land a beat later.
            val (saved, dl) = withContext(Dispatchers.IO) {
                Triple(watchlistStore.urls(), watchLaterStore.urls(), queueStore.urls()) to
                    (downloadStore?.pendingUrls().orEmpty() to visibleDownloadUrls())
            }
            _state.value = _state.value.copy(
                watchlisted = saved.first,
                watchLater = saved.second,
                queued = saved.third,
                downloadPending = dl.first,
                // Sideloaded local files count as "downloaded": one set drives
                // the ✅ badges, the home tile and the offline auto-open alike.
                downloaded = dl.second
            )
            // Car trip / flight: no network but saved videos — open the offline shelf.
            if (isOffline() && dl.second.isNotEmpty()) openDownloads()
        }
        refresh()
        syncWatchState()
        syncConfigState()
        // Requests stranded mid-check by an app death resume where they left off.
        kickDownloadChecker()
        // Approvals, finished downloads and local-library edits update the
        // badges (and the Downloads screen, if it's open) as they happen —
        // LocalLibrary rides the same change signal since it feeds the same shelf.
        viewModelScope.launch {
            DownloadEvents.changes.collect { refreshDownloadState() }
        }
        // A parent's phone just granted time or changed the rules. Keyed
        // per-kid ViewModels all hear it; only the home actually on screen
        // says so, or the kid gets a pill from their sibling's screen.
        viewModelScope.launch {
            io.pickwick.app.data.KidNotices.messages.collect {
                if (uiActive) showNotice(it.text)
            }
        }
        // A TV can sit on the home screen for hours — poll for whitelist edits.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5 * 60 * 1000L)
                // Keyed per-kid ViewModels outlive a profile switch in the
                // activity's store; only the one on screen gets to poll.
                if (!uiActive) continue
                refreshIfIdle()
                syncWatchState()
                syncConfigState()
                syncIndex()
            }
        }
        // The deep crawl lives in IndexCrawlWorker (WorkManager) — it runs even
        // with the app closed, so no in-app loop here.
    }

    /** Set by the hosting composition; false while another kid's home is up. */
    @Volatile
    var uiActive = true

    /**
     * Phone-side config reconcile: an Allow/Block (or any settings edit) made
     * while a kid device was off must still land when it wakes — the original
     * push just failed silently. If a paired device answers with a *different,
     * older* config, push ours again; a device carrying a newer config (another
     * admin phone edited meanwhile) is left alone for a deliberate Push/Pull.
     */
    // Volatile + reset in finally: set on Main, cleared from IO, and one
    // escaping exception must not latch it and silently kill sync for good.
    @Volatile
    private var configSyncInFlight = false

    fun syncConfigState() {
        val store = configStore ?: return
        val devices = pairingStore?.paired().orEmpty()
        // Init and ON_START both fire this at launch — one reconcile is plenty.
        if (devices.isEmpty() || configSyncInFlight) return
        configSyncInFlight = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Master election: the first parent device to run with the slot
                // empty claims it; only the master builds the search index (its
                // crawl is rate-limit-expensive, so the family pays it once).
                // Kid devices never claim it, no matter how empty the field is.
                // Two co-parents CAN race this and both claim — harmless: the
                // MS: term in the config hash makes the copies differ, the
                // newest-wins reconcile below converges them, and the loser's
                // next worker run sees it isn't master and stops crawling.
                val isParent = pairingStore?.role() != PairingStore.Role.KID
                val loaded = store.load()
                if (loaded.masterDeviceToken == null && isParent) {
                    val me = pairingStore?.deviceToken() ?: return@launch
                    store.save(loaded.copy(masterDeviceToken = me))
                    android.util.Log.i("Pickwick", "claimed master role (indexing)")
                }
                val localHash = ConfigStore.fingerprint(store.load())
                val localAt = store.updatedAt()
                devices.forEach { stored ->
                    var device = stored
                    var status = LanClient.fullStatus(device)
                    if (status == null) {
                        // Not answering at the stored address — likely a DHCP
                        // re-lease while the TV sat off, or server port drift.
                        // Sweep the LAN for our device and re-point the entry.
                        val moved = LanClient.rediscover(device)
                        if (moved == null) {
                            android.util.Log.i("Pickwick", "config sync: ${device.name} unreachable")
                            return@forEach
                        }
                        pairingStore?.replacePaired(device, moved)
                        android.util.Log.i(
                            "Pickwick",
                            "config sync: ${device.name} moved " +
                                "${device.host}:${device.port} -> ${moved.host}:${moved.port}"
                        )
                        device = moved
                        status = LanClient.fullStatus(device) ?: return@forEach
                    } else if (device.id == null && status.deviceToken != null) {
                        // Legacy entry (paired before identities were stored):
                        // learn who this is, so a future re-discovery can prove
                        // it found the same device and not a sibling's tablet.
                        device = device.copy(id = status.deviceToken)
                        pairingStore?.replacePaired(stored, device)
                    }
                    val remoteHash = status.hash
                    val remoteAt = status.updatedAt
                    when {
                        remoteHash == localHash -> {}
                        remoteAt < localAt -> {
                            val ok = LanClient.pushConfig(device, store.rawJson())
                            android.util.Log.i(
                                "Pickwick",
                                "config sync: pushed #$localHash to ${device.name} " +
                                    "(had #$remoteHash) → ${if (ok) "accepted" else "REJECTED"}"
                            )
                        }
                        else -> android.util.Log.i(
                            "Pickwick",
                            "config sync: ${device.name} differs (#$remoteHash) but its copy is " +
                                "newer (theirs $remoteAt >= ours $localAt) — leaving for Push/Pull"
                        )
                    }
                }
            } finally {
                configSyncInFlight = false
            }
        }
    }

    @Volatile
    private var watchSyncInFlight = false

    /**
     * Exchange watch progress + saved list + AI verdicts with every paired device
     * (phone acts as the hub): pull-merge theirs, push the merged state back.
     * LWW converges; verdicts are add-only per rules version.
     */
    fun syncWatchState() {
        val devices = pairingStore?.paired().orEmpty()
        if (devices.isEmpty() || watchSyncInFlight) return
        watchSyncInFlight = true
        viewModelScope.launch {
            try {
                var changed = false
                var newVerdicts = false
                devices.forEach { device ->
                    // Merge/export are prefs + JSON work — same off-main rule as
                    // the verdict pair below.
                    LanClient.fetchWatchState(device)?.let { json ->
                        if (withContext(Dispatchers.IO) { mergeWatchState(json) }) changed = true
                    }
                    LanClient.pushWatchState(device, withContext(Dispatchers.IO) { exportWatchState() })
                    // Verdict-sharing: pull-merge what each device has screened, so a
                    // video the TV already ruled on is never re-billed to the AI here.
                    LanClient.fetchVerdicts(device)?.let { json ->
                        val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            mergeVerdicts(json)
                        }
                        if (fresh) newVerdicts = true
                    }
                    cacheStats(device)
                }
                // Push the merged set back after every pull, so verdicts also hop
                // between kid devices through this phone (they never talk directly).
                val export = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    exportVerdicts()
                }
                devices.forEach { LanClient.pushVerdicts(it, export) }
                if (changed) refreshProgress()
                // Imported verdicts can clear or hold videos on the current screen.
                if (newVerdicts) reapplyScreening()
            } finally {
                watchSyncInFlight = false
            }
        }
    }

    /**
     * Master-only: push index sources whose content hash differs from what each
     * paired device reports. Compared per source, so a one-channel delta ships
     * one small file, not the family's whole index.
     */
    @Volatile
    private var indexSyncInFlight = false

    fun syncIndex() {
        val index = channelIndex ?: return
        val devices = pairingStore?.paired().orEmpty()
        if (devices.isEmpty() || indexSyncInFlight) return
        indexSyncInFlight = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val me = pairingStore?.deviceToken()
                val master = configStore?.load()?.masterDeviceToken
                if (me == null || master != me) return@launch
                devices.forEach { device ->
                    val remoteStatus = LanClient.indexStatus(device) ?: return@forEach
                    val remote = runCatching { org.json.JSONObject(remoteStatus) }
                        .getOrNull() ?: return@forEach
                    index.allStates().forEach { (sourceId, state) ->
                        // contentHash is count:newest only, so a completeness
                        // flip with no new videos (exhaustion accepted after
                        // repeated probes, or a harvest un-completing) would
                        // never ship on the hash alone — compare the flag too.
                        // Deliberately not folded into the hash: the formula is
                        // wire format shared with old builds. A source the
                        // remote lacks entirely yields null for both reads and
                        // still pushes, as before.
                        val remoteEntry = remote.optJSONObject(sourceId)
                        if (remoteEntry?.optInt("hash") != state.contentHash() ||
                            remoteEntry?.optBoolean("complete") != state.complete
                        ) {
                            val body = index.exportSourceWithState(sourceId) ?: return@forEach
                            LanClient.pushIndexSource(device, sourceId, body)
                        }
                    }
                }
            } finally {
                indexSyncInFlight = false
            }
        }
    }

    /** Most-opened first; ties keep whitelist order (sortedByDescending is stable). */
    private fun sortByUsage(channels: List<Source>) =
        channels.sortedByDescending { usage.opens(it.id) }

    /**
     * Whitelist-scoped search over the local index. Visibility gates match the
     * feeds exactly: the kid's own sources only, parent blocks and the AI
     * screener applied on top — search can never surface what a feed wouldn't.
     */
    fun search(query: String) = viewModelScope.launch {
        val index = channelIndex ?: return@launch
        val q = query.trim()
        if (q.isEmpty()) return@launch
        _state.value = _state.value.copy(
            screen = Screen.SearchResults(q), loading = true, videos = emptyList(),
            held = 0, searchScreening = null, error = null
        )
        val visibleIds = withContext(Dispatchers.IO) {
            visibleSources(sources).map { it.id }.toSet()
        }
        val hits = withContext(Dispatchers.IO) { index.search(q, visibleIds) }
        val terms = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Dedup: the same video indexed under two sources is one result. The
        // index has no relevance ranking, so impose one: title hits above
        // channel-name-only hits (stable sort keeps newest-first within each
        // band) — the first screening window should be the results a kid
        // actually searched for, not whatever source iterated first.
        searchMatches = hits.map { it.toVideo() }.distinctBy { it.url }
            .filter { it.videoId !in blockedVideoIds && !tooShort(it) }
            .sortedBy { v -> if (terms.all { it in v.title.lowercase() }) 0 else 1 }
        searchWindow = 0
        searchSent = 0
        // Everything already cleared (or screening off) paints immediately —
        // the back catalog's unscreened bulk trickles in behind it.
        rawVideos = withContext(Dispatchers.IO) {
            searchMatches.filter { screener?.isVisible(it) != false }
        }
        _state.value = _state.value.copy(loading = false, videos = annotated(includeFinished = true))
        screenMoreSearch()
    }

    // ---- live screening of search hits ------------------------------------
    // The crawled index is never pre-screened (that would bill the AI for a
    // whole back catalog nobody may ever search), so search screens on demand:
    // a window of the best matches now, the next window when the kid scrolls.

    /** Relevance-ordered, deduped, unblocked matches for the current query. */
    private var searchMatches: List<Video> = emptyList()
    /** How much of [searchMatches] has been offered to the screener. */
    private var searchWindow = 0
    /** Matches actually sent to the AI this search (the progress bar's total). */
    private var searchSent = 0
    private var searchScreenMoreInFlight = false

    /** Called on search open and again when the kid nears the end of the grid. */
    fun screenMoreSearch() {
        val scr = screener
        if (_state.value.screen !is Screen.SearchResults) return
        if (scr == null || searchScreenMoreInFlight || searchWindow >= searchMatches.size) return
        searchScreenMoreInFlight = true
        viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) {
                    // Extend the window until it covers the next batch of
                    // unscreened matches; already-verdicted ones pass through free.
                    val out = mutableListOf<Video>()
                    var i = searchWindow
                    while (i < searchMatches.size && out.size < SEARCH_SCREEN_BATCH) {
                        searchMatches[i].takeIf { scr.needsScreening(it) }?.let { out += it }
                        i++
                    }
                    searchWindow = i
                    out
                }
                searchSent += batch.size
                publishSearchScreening()
                if (batch.isNotEmpty()) {
                    scr.screenAsync(viewModelScope, batch) { onSearchVerdicts() }
                }
            } finally {
                searchScreenMoreInFlight = false
            }
        }
    }

    /** A verdict batch landed (ours or a peer's): append what it cleared. */
    private fun onSearchVerdicts() {
        viewModelScope.launch {
            if (_state.value.screen !is Screen.SearchResults) return@launch
            val shown = rawVideos.mapTo(HashSet()) { it.url }
            val cleared = withContext(Dispatchers.IO) {
                searchMatches.filter { it.url !in shown && screener?.isVisible(it) == true }
            }
            // Append-only: cleared videos join at the bottom so nothing the
            // kid is already looking at moves.
            if (cleared.isNotEmpty()) rawVideos = rawVideos + cleared
            publishSearchScreening()
        }
    }

    /** Recompute the progress line/bar and held count for the search screen. */
    private suspend fun publishSearchScreening() {
        val scr = screener
        var pending = 0
        var heldNow = 0
        if (scr != null) withContext(Dispatchers.IO) {
            searchMatches.take(searchWindow).forEach { v ->
                if (scr.needsScreening(v)) pending++
                else if (!scr.isVisible(v)) heldNow++
            }
        }
        if (_state.value.screen !is Screen.SearchResults) return
        _state.value = _state.value.copy(
            videos = annotated(includeFinished = true),
            held = heldNow,
            searchScreening = if (pending > 0) SearchScreening(
                total = searchSent,
                done = searchSent - pending,
                beyondWindow = searchMatches.size - searchWindow
            ) else null
        )
    }

    /** Partially-watched, unblocked videos across all sources, most recent first. */
    private fun keepWatchingRow(): List<VideoItem> =
        sources.flatMap { videoCache.load(it.id) }
            .distinctBy { it.url }
            .filter { it.videoId !in blockedVideoIds && !tooShort(it) }
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
                v.videoId !in blockedVideoIds && !tooShort(v) && screener?.isVisible(v) != false
            }
        }

    /** Update home-screen tiles without ever disturbing the screen the kid is on. */
    private suspend fun publishChannels(channels: List<Source>) {
        // distinctBy: two whitelist entries (URL form + UC id) can canonicalize
        // to the same channel — duplicate grid keys crash Compose.
        val distinct = channels.distinctBy { it.id }
        // Tiles/rows/badges all re-read every source's cache file — a whole
        // whitelist of disk reads, so computed off-main, applied in one write.
        val (tiles, keepWatching, badges) = withContext(Dispatchers.IO) {
            Triple(sortByUsage(visibleSources(distinct)), keepWatchingRow(), computeNewBadges())
        }
        val onHome = _state.value.screen == Screen.Home
        _state.value = _state.value.copy(
            channels = tiles,
            keepWatching = keepWatching,
            newBadges = badges,
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
                // Per-kid blocks fold into one set — every downstream check
                // ("is this video blocked?") stays a plain membership test.
                blockedVideoIds = list.blockedVideoIds +
                    list.blockedFor.filterValues { activeProfileId in it }.keys
                minVideoSeconds = (list.limitsFor(activeProfileId).minVideoMinutes ?: 0) * 60L
                screener?.config = list.ai
                screener?.profiles = list.profiles
                screener?.activeProfileId = activeProfileId
                screener?.allowedOverrides = list.allowedIdsFor(activeProfileId)
                // Only the kid whose home this is owns these prefs. With nobody
                // picked yet (who's-watching screen) `sessionGuard` is still the
                // legacy unsuffixed store — which ProfileNamespace hands to the
                // *first* kid — so writing the family-wide limits there would wipe
                // that kid's real rules every time a push lands on the picker.
                // Their own refresh writes them properly the moment they're picked.
                if (activeProfileId != null || list.profiles.isEmpty()) {
                    sessionGuard.saveLimits(list.limitsFor(activeProfileId))
                }
                // Fast publish: entries merged with cached tile artwork (keyed by URL,
                // which survives id canonicalization). Adds/removes land right here.
                val cachedByUrl = cached.associateBy { it.url }
                fun names(sourcesList: List<Source>) {
                    // Which channel names belong to entries this kid can't see —
                    // resolved from whatever names we have at this point.
                    val byUrl = sourcesList.associateBy { it.url }
                    hiddenChannelNames = list.sources
                        .filterNot { it.visibleTo(activeProfileId) }
                        .mapNotNull { e -> byUrl[e.url]?.name ?: e.label }
                        .toSet()
                }
                // The full entry list resolves and caches (tile art is shared by
                // every kid); only the active kid's subset is published. Matched
                // by URL, never id: resolution canonicalizes user/, c/ and
                // @handle ids to UC… form, so an id join silently drops every
                // non-UC entry once its real identity comes back.
                fun visible(sourcesList: List<Source>): List<Source> {
                    val visibleUrls = list.sources
                        .filter { it.visibleTo(activeProfileId) }
                        .map { it.url }.toSet()
                    return sourcesList.filter { it.url in visibleUrls }
                }
                // Channel notes ride the same name resolution as the tiles:
                // verdict-time lookups only have a video's uploader name, so
                // the map re-derives whenever the resolved names improve.
                fun notes(sourcesList: List<Source>) {
                    screener?.channelNotes =
                        io.pickwick.app.data.DeepCheck.notesByChannelName(list.sources, sourcesList)
                }
                val provisional = list.sources.map { e ->
                    cachedByUrl[e.url] ?: Source(e.id, e.url, e.label ?: e.id, null, e.kind)
                }
                names(provisional)
                notes(provisional)
                sources = visible(provisional)
                publishChannels(sources)
                // hiddenChannelNames just settled — re-derive the offline badges.
                refreshDownloadState()
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
                    names(best)
                    notes(best)
                    sources = visible(best)
                    sourceCache.save(best)
                    publishChannels(sources)
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
                withContext(Dispatchers.IO) { videoCache.save(source.id, videos) }
                prefetchThumbs(videos.mapNotNull { it.thumbnailUrl })
                // Initial sweep: the background warm walks every source, so a fresh
                // whitelist (or new rules) gets its whole catalog screened here.
                kickScreening(videos)
                // Page-1 delta into the search index — zero extra requests, and
                // new uploads become searchable within a refresh or two.
                crawler?.harvestPage1(source, videos)
            }
        }
    }

    /**
     * Sends anything not yet screened to the AI in the background; each verdict
     * batch re-filters whatever screen the kid is on, so held videos pop in as
     * they are cleared.
     */
    private fun kickScreening(videos: List<Video>) {
        screener?.screenAsync(viewModelScope, videos) { reapplyScreening() }
    }

    /** Re-filters whatever is on screen after new verdicts land (AI batch, a
     *  peer's import, or the player's pre-play deep check — the last is why the
     *  home screen calls this on resume: a video blocked at the play press must
     *  be gone from the shelf the kid returns to). */
    fun reapplyScreening() {
        viewModelScope.launch {
            val onHome = _state.value.screen == Screen.Home
            // Search owns its own re-filter: results append as verdicts land
            // (never a wholesale rebuild, which could reorder under the kid).
            val onSearch = _state.value.screen is Screen.SearchResults
            val includeFinished = includeFinishedNow()
            // Tiles and the resume row walk the per-source cache files — off-main.
            val (tiles, keepWatching) = withContext(Dispatchers.IO) {
                sortByUsage(visibleSources(sources.distinctBy { it.id })) to keepWatchingRow()
            }
            // A channel re-splits instead: writing the flat list here first
            // would flash every watched video back into the grid for a frame.
            val channel = channelSource()
            val untouched = onHome || onSearch || channel != null
            _state.value = _state.value.copy(
                // Verdicts can clear (or fully hold) a source — tiles follow along.
                channels = tiles,
                keepWatching = keepWatching,
                videos = if (untouched) _state.value.videos
                else annotated(includeFinished = includeFinished),
                held = if (untouched) _state.value.held else heldByScreening()
            )
            channel?.let { publishChannel(it) }
            if (onSearch) onSearchVerdicts()
        }
    }

    /**
     * Channel browsing keeps fully-watched videos (kids rewatch!) — [publishChannel]
     * then sorts them onto the Watched shelf rather than dropping them. Up next
     * keeps them too: an item there was put there on purpose, so hiding it would
     * look like the add silently failed. Surprise excludes them: it's the
     * discovery mix, and clutter there would defeat its purpose.
     */
    private fun includeFinishedNow(): Boolean =
        channelSource() != null || _state.value.screen == Screen.Queue

    /** Raw videos on the current screen hidden by the screener (no verdict yet or held for review). */
    private fun heldByScreening(): Int = rawVideos.count { v ->
        v.videoId !in blockedVideoIds && !tooShort(v) && screener?.isVisible(v) == false
    }

    /**
     * Which of this channel's videos were already finished when it opened —
     * the frozen half of [splitWatched]. Filled in the first time each video
     * is classified (so a page arriving later is judged on arrival) and never
     * revised while the channel is open, which is what keeps a just-finished
     * video from sliding out from under the kid.
     */
    private val finishedOnArrival = mutableMapOf<String, Boolean>()

    /**
     * Channel grids only. A playlist is a curated sequence — its order *is*
     * the content, and the "▶ Continue" chip already finds the next unwatched
     * one — so sinking watched videos there would scramble what a parent
     * deliberately lined up.
     */
    private fun sinksWatched(source: Source): Boolean = source.kind == SourceKind.CHANNEL

    /**
     * Publishes the channel screen (or its Watched shelf) from [rawVideos].
     * [pin] fixes the "Watched" tile to the end of what's loaded now; it is
     * passed only once per channel, when the first page lands, and later pages
     * append below the tile so nothing already scrolled past moves.
     */
    private fun publishChannel(source: Source, pin: Boolean = false) {
        val items = annotated(includeFinished = true)
        if (!sinksWatched(source)) {
            _state.value = _state.value.copy(
                loading = false, videos = items, held = heldByScreening()
            )
            return
        }
        val (unwatched, watched) = splitWatched(items) { item ->
            // annotated() already read this video's progress — reuse it rather
            // than hitting the history store a second time per tile, on main,
            // on every publish.
            finishedOnArrival.getOrPut(item.video.url) { (item.progress ?: 0f) >= 0.98f }
        }
        val onShelf = _state.value.screen is Screen.WatchedVideos
        _state.value = _state.value.copy(
            loading = false,
            videos = if (onShelf) watched else unwatched,
            channelWatched = watched,
            // Held videos are unscreened, never watched — the count belongs to
            // the grid they're missing from, not to the shelf.
            held = if (onShelf) 0 else heldByScreening(),
            watchedTileAt = _state.value.watchedTileAt ?: unwatched.size.takeIf { pin }
        )
    }

    /** The channel's finished videos, on their own screen. */
    fun openChannelWatched() {
        val source = (_state.value.screen as? Screen.ChannelVideos)?.source ?: return
        _state.value = _state.value.copy(
            screen = Screen.WatchedVideos(source),
            videos = _state.value.channelWatched,
            held = 0,
            // Shared grid: without this the shelf would open at the depth the
            // channel was scrolled to.
            scrollTo = 0
        )
    }

    /** Back out of the Watched shelf. The channel's pages are still loaded. */
    fun backToChannel() {
        val source = (_state.value.screen as? Screen.WatchedVideos)?.source ?: return
        _state.value = _state.value.copy(screen = Screen.ChannelVideos(source), held = 0)
        publishChannel(source)
        // Land back on the tile they left through, rather than at the top of a
        // grid they had already scrolled a long way down.
        _state.value = _state.value.copy(scrollTo = _state.value.watchedTileAt ?: 0)
    }

    /** The grid obeyed [UiState.scrollTo]; don't make it jump again. */
    fun scrollHandled() {
        if (_state.value.scrollTo != null) _state.value = _state.value.copy(scrollTo = null)
    }

    private fun annotated(includeFinished: Boolean): List<VideoItem> =
        rawVideos.mapNotNull { video ->
            if (video.videoId in blockedVideoIds || tooShort(video)) return@mapNotNull null
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
        viewModelScope.launch {
            // Saved-list pruning and the row rebuilds are all file reads — off-main.
            val (watchlisted, watchLater, queued) = withContext(Dispatchers.IO) {
                pruneFinishedSavedLists()
                // Also drains the queue when the finish happened outside a
                // queue launch (kid watched the same video from its channel).
                pruneFinishedQueue()
                Triple(watchlistStore.urls(), watchLaterStore.urls(), queueStore.urls())
            }
            if (_state.value.screen == Screen.Home) {
                val (keepWatching, badges) = withContext(Dispatchers.IO) {
                    keepWatchingRow() to computeNewBadges()
                }
                _state.value = _state.value.copy(
                    keepWatching = keepWatching,
                    newBadges = badges,
                    watchlisted = watchlisted,
                    watchLater = watchLater,
                    queued = queued
                )
                return@launch
            }
            _state.value = _state.value.copy(
                watchlisted = watchlisted, watchLater = watchLater, queued = queued
            )
            if (_state.value.screen == Screen.Downloads) {
                // Offline shelf: fresh red bars, but no screener/blocklist re-filtering.
                _state.value = _state.value.copy(videos = downloadItems())
                return@launch
            }
            if (_state.value.screen == Screen.Watchlist) {
                rawVideos = withContext(Dispatchers.IO) { watchlistStore.load() }
            }
            if (_state.value.screen == Screen.WatchLater) {
                rawVideos = withContext(Dispatchers.IO) { watchLaterStore.load() }
            }
            if (_state.value.screen == Screen.Queue) {
                rawVideos = withContext(Dispatchers.IO) { queueStore.load() }
            }
            val channel = channelSource()
            if (channel != null) {
                // A video finished in the player stays put on the grid behind
                // it — the snapshot froze at open — so this only refreshes bars.
                publishChannel(channel)
                return@launch
            }
            _state.value = _state.value.copy(
                videos = annotated(includeFinished = includeFinishedNow()),
                held = heldByScreening()
            )
        }
    }

    /** The channel behind whichever of its two screens is showing, else null. */
    private fun channelSource(): Source? = when (val s = _state.value.screen) {
        is Screen.ChannelVideos -> s.source
        is Screen.WatchedVideos -> s.source
        else -> null
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
        _state.value = _state.value.copy(
            screen = Screen.ChannelVideos(source), loading = true, videos = emptyList(),
            held = 0, error = null, channelWatched = emptyList(), watchedTileAt = null,
            scrollTo = 0
        )
        feedHandle = null
        uploadsNextPage = null
        // A fresh visit is where the sort catches up with what was watched last
        // time — see [finishedOnArrival].
        finishedOnArrival.clear()
        gridTarget = SCREENFUL
        pagesThisPump = 0

        // Paint instantly from the last visit; refresh silently underneath.
        val cachedVideos = withContext(Dispatchers.IO) { videoCache.load(source.id) }
        if (cachedVideos.isNotEmpty()) {
            rawVideos = cachedVideos
            usage.setLastSeenLatest(source.id, cachedVideos.first().url)
            publishChannel(source)
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
                    publishChannel(source)
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
                withContext(Dispatchers.IO) { videoCache.save(source.id, rawVideos.take(500)) }
                rawVideos.firstOrNull()?.let { usage.setLastSeenLatest(source.id, it.url) }
                // Give the on-screen thumbnails a head start before trickling the rest.
                launch {
                    kotlinx.coroutines.delay(1_500)
                    prefetchThumbs(page.videos.mapNotNull { it.thumbnailUrl })
                }
                publishChannel(source)
                kickScreening(rawVideos)
                // Opening a channel fetches its fresh page 1 — harvest it.
                crawler?.harvestPage1(source, page.videos)
                // First fill: page 1 alone can be all watched videos.
                pumpUntilFilled()
            }
            .onFailure {
                android.util.Log.w("Pickwick", "open ${source.id} failed", it)
                if (cachedVideos.isEmpty()) {
                    _state.value = _state.value.copy(loading = false, error = it.message ?: it.javaClass.simpleName)
                } else {
                    // Offline, browsing the cache: no page is coming, so settle
                    // on what we have or the tile would never appear at all.
                    pumpUntilFilled()
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
        val diskPool = withContext(Dispatchers.IO) { channels.flatMap { videoCache.load(it.id) } }
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
                    .also {
                        if (it.isNotEmpty()) withContext(Dispatchers.IO) { videoCache.save(source.id, it) }
                    }
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

    /**
     * The kid is near the end of the grid and wants more.
     *
     * One upload page is no longer one screenful: the grid carries only
     * unwatched videos, so a page of thirty can add three tiles — or none at
     * all on a channel they've been through. Scroll position alone can't drive
     * that, because the grid barely moves and the "near the end" signal only
     * fires when it does; the kid ends up swiping again and again for one row
     * at a time. So a nudge sets a target instead, and [pumpUntilFilled] keeps
     * fetching until the grid has actually grown by a screenful.
     */
    fun loadMoreUploads() {
        if (_state.value.screen !is Screen.ChannelVideos) return
        if (uploadsNextPage == null) return
        // Fresh demand: aim a screenful past what's showing and restore the
        // page budget, including while a fetch is already in flight — that one
        // will pick the raised target up when it lands.
        gridTarget = maxOf(gridTarget, _state.value.videos.size + SCREENFUL)
        pagesThisPump = 0
        fetchNextPage()
    }

    private fun fetchNextPage() {
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
                        withContext(Dispatchers.IO) { videoCache.save(s.source.id, rawVideos.take(500)) }
                        // Browsed depth becomes searchable depth — the network
                        // cost is already paid, so harvest it into the index.
                        crawler?.harvestHistory(s.source, page.videos)
                    }
                    launch { prefetchThumbs(page.videos.mapNotNull { it.thumbnailUrl }) }
                    // No pin: later pages join below the tile, never above it.
                    (_state.value.screen as? Screen.ChannelVideos)?.let { publishChannel(it.source) }
                    kickScreening(page.videos)
                }
                .onFailure { android.util.Log.w("Pickwick", "load more failed", it) }
            loadingMore = false
            _state.value = _state.value.copy(loadingMore = false)
            pumpUntilFilled()
        }
    }

    /**
     * Keep paging until the grid reaches [gridTarget] — a page that yielded
     * nothing the kid can see hasn't answered their scroll, so the next one
     * goes straight out rather than waiting for another swipe.
     *
     * Budgeted per nudge, not per visit: a channel watched right through would
     * otherwise page its whole history in one go, but a kid genuinely scrolling
     * deep gets a fresh allowance every time they ask.
     */
    private fun pumpUntilFilled() {
        val source = (_state.value.screen as? Screen.ChannelVideos)?.source ?: return
        if (_state.value.videos.size < gridTarget &&
            uploadsNextPage != null &&
            pagesThisPump < MAX_PAGES_PER_PUMP
        ) {
            pagesThisPump++
            fetchNextPage()
            return
        }
        // Nothing more coming: what's loaded now is "the first set", and the
        // Watched tile pins to the end of it. Pinning at page 1 instead would
        // strand the tile two tiles from the top of a channel whose recent
        // uploads have all been watched.
        if (_state.value.watchedTileAt == null) publishChannel(source, pin = true)
    }

    /** How much new grid one scroll nudge (or a channel opening) should buy. */
    private var gridTarget = 0

    /** Pages spent since the last nudge — see [pumpUntilFilled]. */
    private var pagesThisPump = 0

    private companion object {
        /** Roughly a grid's worth of tiles on the biggest layout we render. */
        const val SCREENFUL = 12

        /** ~5 upload pages ≈ 150 videos sifted per nudge before we give up. */
        const val MAX_PAGES_PER_PUMP = 5
    }

    /** Hold-to-save: toggles a video in the kid's Favorites. */
    fun toggleWatchlist(item: VideoItem) = toggleSaved(item, watchlistStore, Screen.Watchlist)

    /** Hold-to-save: toggles a video in the kid's Watch later list. */
    fun toggleWatchLater(item: VideoItem) = toggleSaved(item, watchLaterStore, Screen.WatchLater)

    /**
     * Favorites and Watch later behave identically — only the store and the
     * screen mirroring it differ. [ownScreen] is that screen: standing on it,
     * an un-save has to drop the video out of the grid underfoot.
     */
    private fun toggleSaved(item: VideoItem, store: SavedListStore, ownScreen: Screen) {
        val url = item.video.url
        viewModelScope.launch {
            // Store round-trips are file I/O — off-main, per refreshProgress.
            val (urls, listVideos) = withContext(Dispatchers.IO) {
                if (url in savedUrls(ownScreen)) store.remove(url) else store.add(item.video)
                store.urls() to
                    if (_state.value.screen == ownScreen) store.load() else null
            }
            _state.value = _state.value.withSaved(ownScreen, urls)
            if (listVideos != null) {
                rawVideos = listVideos
                _state.value = _state.value.copy(videos = annotated(includeFinished = false), held = heldByScreening())
            }
            syncWatchState() // saves propagate promptly
        }
    }

    private fun savedUrls(ownScreen: Screen): Set<String> =
        if (ownScreen == Screen.WatchLater) _state.value.watchLater else _state.value.watchlisted

    private fun UiState.withSaved(ownScreen: Screen, urls: Set<String>): UiState =
        if (ownScreen == Screen.WatchLater) copy(watchLater = urls) else copy(watchlisted = urls)

    /** Watched-to-the-end videos leave both saved lists automatically. */
    private fun pruneFinishedSavedLists() {
        listOf(watchlistStore, watchLaterStore).forEach { store ->
            store.load()
                .filter { history.progress(it.url)?.isFinished == true }
                .forEach { store.remove(it.url) }
        }
    }

    fun openWatchlist() = openSaved(watchlistStore, Screen.Watchlist)

    fun openWatchLater() = openSaved(watchLaterStore, Screen.WatchLater)

    private fun openSaved(store: SavedListStore, ownScreen: Screen) {
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                // Fully-watched entries have served their purpose — drop them silently.
                pruneFinishedSavedLists()
                store.load()
            }
            rawVideos = videos
            feedHandle = null
            uploadsNextPage = null
            _state.value = _state.value
                .withSaved(ownScreen, videos.map { it.url }.toSet())
                .copy(
                    screen = ownScreen,
                    loading = false,
                    error = null,
                    videos = annotated(includeFinished = false),
                    held = heldByScreening()
                )
        }
    }

    /** Tap-to-line-up: toggles a video in the kid's play queue. */
    fun toggleQueue(item: VideoItem) {
        val url = item.video.url
        viewModelScope.launch {
            val (queued, listVideos) = withContext(Dispatchers.IO) {
                if (url in _state.value.queued) {
                    queueStore.remove(url)
                } else {
                    // A refused add (duplicate race or the 50-item cap) just
                    // leaves the badge unflipped — urls() below reads back
                    // what's really stored.
                    queueStore.add(item.video)
                }
                queueStore.urls() to
                    if (_state.value.screen == Screen.Queue) queueStore.load() else null
            }
            _state.value = _state.value.copy(queued = queued)
            if (listVideos != null) {
                rawVideos = listVideos
                _state.value = _state.value.copy(videos = annotated(includeFinished = true), held = heldByScreening())
            }
            // No syncWatchState(): the queue is device-local by design.
        }
    }

    /**
     * Videos that finished *after* they were lined up leave automatically. The
     * comparison against addedAt is the whole point: pruning on watch history
     * alone made a rewatch unqueueable — the kid picks a favourite they've seen,
     * and it vanishes before the lineup is even on screen.
     */
    private fun pruneFinishedQueue() {
        queueStore.entries()
            .filter { it.finishedSinceQueued(history.progress(it.video.url)) }
            .forEach { queueStore.remove(it.video.url) }
    }

    fun openQueue() {
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                pruneFinishedQueue()
                queueStore.load()
            }
            rawVideos = videos
            feedHandle = null
            uploadsNextPage = null
            _state.value = _state.value.copy(
                screen = Screen.Queue,
                loading = false,
                error = null,
                queued = videos.map { it.url }.toSet(),
                videos = annotated(includeFinished = true),
                held = heldByScreening()
            )
        }
    }

    fun removeFromQueue(item: VideoItem) {
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                queueStore.remove(item.video.url)
                queueStore.load()
            }
            rawVideos = videos
            _state.value = _state.value.copy(
                queued = videos.map { it.url }.toSet(),
                videos = annotated(includeFinished = true),
                held = heldByScreening()
            )
        }
    }

    fun moveQueue(item: VideoItem, delta: Int) {
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                queueStore.move(item.video.url, delta)
                queueStore.load()
            }
            rawVideos = videos
            _state.value = _state.value.copy(videos = annotated(includeFinished = true))
        }
    }

    /**
     * Hold-menu "Save offline" row: request a download (parent approves later),
     * or withdraw a request that hasn't been approved yet. Already-downloaded
     * videos ignore it — deleting is the parent's job in settings.
     */
    fun toggleDownload(item: VideoItem) {
        val store = downloadStore ?: return
        val url = item.video.url
        when {
            url in _state.value.downloaded -> return
            url in _state.value.downloadPending -> store.cancelRequest(url)
            else -> {
                // With screening on, the request goes through the AI deep check
                // first and reaches the parent only if it passes — the kid sees
                // the same ⏳ either way.
                store.request(item.video, checking = screener?.config?.enabled == true)
                kickDownloadChecker()
            }
        }
        // The store bumps DownloadEvents.changes, which refreshes the badges.
    }

    /**
     * Works off any CHECKING download requests. Also called once at startup:
     * an app death mid-check must not strand a request in a state neither the
     * parent (not REQUESTED yet) nor the kid (still ⏳) can see resolve.
     */
    private fun kickDownloadChecker() {
        val store = downloadStore ?: return
        val cs = configStore ?: return
        val scr = screener ?: return
        io.pickwick.app.data.DownloadChecker.kick(
            viewModelScope, cs, scr.store, store, yt, sourceCache, activeProfileId
        ) { video ->
            // The verdict also hides the video itself — pill first, then the
            // re-filter takes it off the shelf the kid is looking at.
            showNotice("\"${video.title}\" isn't available.")
            reapplyScreening()
        }
    }

    private var noticeJob: kotlinx.coroutines.Job? = null

    /** Shows the kid one transient line, replacing any previous one. */
    private fun showNotice(text: String) {
        noticeJob?.cancel()
        _state.value = _state.value.copy(notice = text)
        noticeJob = viewModelScope.launch {
            kotlinx.coroutines.delay(5_000)
            _state.value = _state.value.copy(notice = null)
        }
    }

    /**
     * The offline shelf: finished downloads plus parent-sideloaded local files.
     * No screener/blocklist re-filtering here: everything on it was explicitly
     * approved (or added) by the parent, and it must work with no network.
     */
    fun openDownloads() {
        if (downloadStore == null && localLibrary == null) return
        viewModelScope.launch {
            val (videos, pending) = withContext(Dispatchers.IO) {
                visibleDownloads() to downloadStore?.pendingUrls().orEmpty()
            }
            rawVideos = videos
            feedHandle = null
            uploadsNextPage = null
            _state.value = _state.value.copy(
                screen = Screen.Downloads,
                loading = false,
                error = null,
                downloadPending = pending,
                downloaded = videos.map { it.url }.toSet(),
                videos = downloadItems()
            )
        }
    }

    private fun downloadItems(): List<VideoItem> =
        rawVideos.map { VideoItem(it, history.progress(it.url)?.fraction) }

    private fun refreshDownloadState() {
        if (downloadStore == null && localLibrary == null) return
        viewModelScope.launch {
            val (pending, downloads) = withContext(Dispatchers.IO) {
                downloadStore?.pendingUrls().orEmpty() to visibleDownloads()
            }
            _state.value = _state.value.copy(
                downloadPending = pending,
                downloaded = downloads.map { it.url }.toSet()
            )
            if (_state.value.screen == Screen.Downloads) {
                rawVideos = downloads
                _state.value = _state.value.copy(videos = downloadItems())
            }
        }
    }

    /**
     * Long-press on a Keep watching tile: mark the video fully watched so it
     * leaves the row (and the channel grids treat it like any finished video).
     */
    fun dismissKeepWatching(item: VideoItem) {
        viewModelScope.launch {
            // history.save commits (fsync) and the row rebuild reads every cache
            // file — both off-main.
            val keepWatching = withContext(Dispatchers.IO) {
                history.progress(item.video.url)?.let {
                    history.save(item.video.url, it.durationMs, it.durationMs)
                }
                keepWatchingRow()
            }
            _state.value = _state.value.copy(keepWatching = keepWatching)
            syncWatchState() // the dismissal propagates like any other watch progress
        }
    }

    /**
     * Hold menu: mark a video watched, or put it back. Unlike finishing one in
     * the player, this moves the tile to the Watched shelf straight away — the
     * kid asked for it, and a tile that stayed put would read as the tap having
     * missed.
     */
    fun toggleWatched(item: VideoItem) {
        val url = item.video.url
        viewModelScope.launch {
            // history.save commits (fsync) — off-main like every store write.
            val nowWatched = withContext(Dispatchers.IO) {
                val existing = history.progress(url)
                // save() ignores a zero duration, and a video that was never
                // opened has no stored one; the feed's own length covers that,
                // and a nominal second covers a live or unknown-length entry.
                val duration = existing?.durationMs?.takeIf { it > 0 }
                    ?: (item.video.durationSeconds * 1000).takeIf { it > 0 }
                    ?: 1_000L
                val watched = existing?.isFinished == true
                // Rewound, not deleted: a removal loses the next sync to any
                // device that still holds the entry, while 0/duration carries a
                // fresh timestamp and wins the merge everywhere.
                history.save(url, if (watched) 0L else duration, duration)
                !watched
            }
            finishedOnArrival[url] = nowWatched
            val channel = channelSource()
            if (channel != null) {
                publishChannel(channel)
                _state.value = _state.value.copy(
                    keepWatching = withContext(Dispatchers.IO) { keepWatchingRow() }
                )
            } else {
                // Every other shelf: same rebuild as returning from the player,
                // so the red bar (and Surprise's hiding of finished videos)
                // follows immediately.
                refreshProgress()
            }
            syncWatchState() // it propagates like any other watch progress
        }
    }

    fun goHome() {
        rawVideos = emptyList()
        feedHandle = null
        uploadsNextPage = null
        finishedOnArrival.clear()
        gridTarget = 0
        pagesThisPump = 0
        _state.value = _state.value.copy(
            screen = Screen.Home, videos = emptyList(), held = 0, loading = false, error = null,
            channelWatched = emptyList(), watchedTileAt = null, scrollTo = null,
            // Re-rank right away so a freshly opened channel climbs immediately.
            channels = sortByUsage(_state.value.channels)
        )
        // Called synchronously from BackHandler — the rows re-read every source's
        // cache file, so they refresh off-main after the screen has switched.
        viewModelScope.launch {
            val (keepWatching, badges) = withContext(Dispatchers.IO) {
                keepWatchingRow() to computeNewBadges()
            }
            _state.value = _state.value.copy(keepWatching = keepWatching, newBadges = badges)
        }
    }
}
