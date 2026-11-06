package io.pickwick.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Small LRU for the info memos. Eviction is by size, not age: the TTL check
 * only runs when an entry is read, so nothing else would ever drop one.
 */
internal class BoundedCache<T>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<String, Pair<Long, T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, T>>?) =
            size > maxEntries
    }

    @Synchronized operator fun get(key: String): Pair<Long, T>? = map[key]

    @Synchronized operator fun set(key: String, value: Pair<Long, T>) { map[key] = value }
}

/**
 * A plain copy of a channel tab's link handler. The extractor hands out a
 * ReadyChannelTabListLinkHandler whose lambda captures the whole parsed channel
 * page; a cursor or UI state holding one keeps that alive long after the first
 * page is read. Paging only needs the id/filters (this is also how persisted
 * cursors are rebuilt — see IndexCrawler.deserializeCursor).
 */
private fun ListLinkHandler.detached() =
    ListLinkHandler(originalUrl, url, id, contentFilters, sortFilter)

/** A tile on the home screen: a whitelisted channel or playlist. */
data class Source(
    val id: String,
    /** Canonical YouTube URL — may be a /user/, /c/ or /@handle form, not just /channel/. */
    val url: String,
    val name: String,
    val avatarUrl: String?,
    val kind: SourceKind,
    /** Screen-time drain rate in percent (100 = normal, 0 = FREE), from the whitelist entry. */
    val timeMultiplierPercent: Int = 100,
    /** The picked videos of a [SourceKind.CUSTOM] source; empty for everything else. */
    val customVideos: List<Video> = emptyList()
)

data class Video(
    val url: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    /** Live broadcast right now — a hand-picked video only; drives the LIVE badge. */
    val live: Boolean = false,
    /** Can't be played right now (see [VideoStatus]): dimmed, "Offline", not tappable. */
    val offline: Boolean = false
) {
    val videoId: String?
        // Sideloaded files carry a synthetic pickwick://local/<hash> URL; the
        // hash is 16 hex chars so it can't collide with an 11-char YouTube id.
        get() = if (LocalLibrary.isLocal(url)) url.removePrefix(LocalLibrary.URL_PREFIX)
        else if (SmbPaths.isNetwork(url)) "smb-" + LocalLibrary.idFor(url)
        else if (PodcastPaths.isPodcast(url)) "pod-" + url.substringAfterLast('/')
        else VIDEO_ID.find(url)?.groupValues?.get(1)

    companion object {
        // Compiled once: videoId runs per-video in every screening/filter pass.
        private val VIDEO_ID = Regex("[?&]v=([A-Za-z0-9_-]{11})")
    }
}

/** All YouTube access goes through NewPipeExtractor — no API key, no quota, no ads. */
class YouTubeRepository {

    private val youtube = ServiceList.YouTube

    companion object {
        private const val INFO_TTL_MS = 10 * 60 * 1000L
        private const val STORYBOARD_FRAME_WIDTH = 160
        // Shared across instances so MainActivity and PlayerActivity reuse fetches.
        // Bounded on purpose: a ChannelInfo keeps its extractor's whole parsed
        // channel page reachable (several MB each), and the background warm
        // walks every source. Unbounded, 47 channels held ~165 MB and OOM'd a
        // 192 MB-heap Fire TV Cube; the TTL below is only checked on read.
        private val channelInfoCache = BoundedCache<ChannelInfo>(6)
        private val playlistInfoCache = BoundedCache<PlaylistInfo>(12)
        private val channelTabCache = BoundedCache<ChannelTabInfo>(12)

        // Parent-side search results, kept for INFO_TTL_MS so flipping between the
        // Channels / Playlists / Videos chips (or re-running a query) costs nothing.
        // Small lists, so a handful of entries each is plenty.
        private val channelSearchCache = BoundedCache<SearchPage<ChannelResult>>(24)
        private val playlistSearchCache = BoundedCache<SearchPage<PlaylistResult>>(24)
        private val videoSearchCache = BoundedCache<SearchPage<VideoResult>>(24)

        /**
         * Two priority lanes: user taps never queue behind background warming.
         * YouTube throttles bursts, so both lanes are narrow.
         */
        private val interactiveFetches = Semaphore(3)
        private val backgroundFetches = Semaphore(1)

        /** Per-key locks so concurrent callers join one in-flight fetch, not duplicate it. */
        private val fetchLocks = ConcurrentHashMap<String, Mutex>()

        /**
         * Delay before each retry, chosen by failure class; empty = permanent, fail fast.
         * ContentNotAvailable covers geo-blocked, paid, private, age-restricted and
         * terminated-account errors — retrying those only wastes a fetch lane and delays
         * the player's skip-to-next. A ReCaptcha (429) gets one long pause: hammering a
         * rate limit makes it worse.
         */
        internal fun retryDelaysFor(e: Throwable): LongArray = when (e) {
            is ContentNotAvailableException -> longArrayOf()
            is ReCaptchaException -> longArrayOf(4_000)
            else -> longArrayOf(800, 2_500)
        }

        /** Escalating backoff with ±20% jitter: most extractor failures are transient. */
        internal suspend fun <T> retrying(
            what: String,
            sleep: suspend (Long) -> Unit = { delay(it) },
            block: () -> T
        ): T {
            var attempt = 1
            while (true) {
                try {
                    return block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val delays = retryDelaysFor(e)
                    if (attempt > delays.size) throw e
                    val backoff = (delays[attempt - 1] * (0.8 + Math.random() * 0.4)).toLong()
                    println(
                        "Pickwick: $what failed on attempt $attempt " +
                            "(${e.javaClass.simpleName}: ${e.message}) — retrying in ${backoff}ms"
                    )
                    sleep(backoff)
                    attempt++
                }
            }
        }
    }

    /** TTL memo + in-flight dedup + rate limiting, shared by all info fetches. */
    private suspend fun <T> memoized(
        key: String,
        cache: BoundedCache<T>,
        limiter: Semaphore,
        what: String,
        fetch: () -> T
    ): T {
        fun fresh(): T? = cache[key]
            ?.let { (ts, v) -> if (System.currentTimeMillis() - ts < INFO_TTL_MS) v else null }

        fresh()?.let { return it }
        val lock = fetchLocks.computeIfAbsent(key) { Mutex() }
        lock.withLock {
            fresh()?.let { return it }
            val started = System.currentTimeMillis()
            return limiter.withPermit { retrying(what) { fetch() } }
                .also {
                    cache[key] = System.currentTimeMillis() to it
                    println("Pickwick: $what fetched in ${System.currentTimeMillis() - started}ms")
                }
        }
    }

    private suspend fun channelInfo(id: String, url: String, limiter: Semaphore): ChannelInfo =
        memoized("c:$id", channelInfoCache, limiter, "channel $id") {
            ChannelInfo.getInfo(youtube, url)
        }

    private suspend fun playlistInfo(id: String, url: String, limiter: Semaphore): PlaylistInfo =
        memoized("p:$id", playlistInfoCache, limiter, "playlist $id") {
            PlaylistInfo.getInfo(youtube, url)
        }


    /**
     * Smallest image that's still sharp at tile size — full-res thumbnails
     * (1280×720) are needlessly slow to fetch and decode on a TV.
     */
    private fun List<org.schabi.newpipe.extractor.Image>.pick(minWidth: Int): String? {
        val known = filter { it.width > 0 }.sortedBy { it.width }
        return known.firstOrNull { it.width >= minWidth }?.url
            ?: known.lastOrNull()?.url
            ?: lastOrNull()?.url
    }

    /** Opaque handle for fetching further pages of a source's videos. */
    sealed interface FeedHandle {
        data class ChannelTab(val tab: ListLinkHandler) : FeedHandle
        data class Playlist(val url: String) : FeedHandle
    }

    data class UploadsPage(
        val videos: List<Video>,
        val handle: FeedHandle?,
        val nextPage: Page?
    )

    suspend fun source(entry: WhitelistEntry, background: Boolean = false): Source =
        withContext(Dispatchers.IO) {
            val limiter = if (background) backgroundFetches else interactiveFetches
            when (entry.kind) {
                SourceKind.CHANNEL -> {
                    val info = channelInfo(entry.id, entry.url, limiter)
                    // Canonicalize /user/ and @handle entries to their UC id so the
                    // RSS fast-path and cache keys work uniformly. Seed the memo under
                    // the canonical key too, so the tap-path never refetches.
                    val id = info.id?.takeIf { it.startsWith("UC") } ?: entry.id
                    if (id != entry.id) {
                        channelInfoCache["c:$id"] = System.currentTimeMillis() to info
                    }
                    Source(id, entry.url, entry.label ?: info.name,
                        info.avatars.pick(QualityTargets.avatarMinWidth), entry.kind,
                        entry.timeMultiplierPercent)
                }
                SourceKind.PLAYLIST -> {
                    val info = playlistInfo(entry.id, entry.url, limiter)
                    Source(entry.id, entry.url, entry.label ?: info.name,
                        info.thumbnails.pick(QualityTargets.videoThumbMinWidth), entry.kind,
                        entry.timeMultiplierPercent)
                }
                SourceKind.CUSTOM -> entry.toCustomSource()
            }
        }

    suspend fun uploadsPage(source: Source, background: Boolean = false): UploadsPage =
        withContext(Dispatchers.IO) {
            val limiter = if (background) backgroundFetches else interactiveFetches
            when (source.kind) {
                SourceKind.CHANNEL -> {
                    val info = channelInfo(source.id, source.url, limiter)
                    val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
                    if (videosTab == null) {
                        // Auto-generated "Topic" channels have no Videos tab; their
                        // content lives in the auto uploads playlist (UC… → UU…).
                        val channelId = info.id?.takeIf { it.startsWith("UC") }
                            ?: return@withContext UploadsPage(emptyList(), null, null)
                        val uploadsId = "UU" + channelId.removePrefix("UC")
                        val url = "https://www.youtube.com/playlist?list=$uploadsId"
                        val pl = playlistInfo(uploadsId, url, limiter)
                        return@withContext UploadsPage(
                            videos = pl.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toVideo() },
                            handle = FeedHandle.Playlist(url),
                            nextPage = pl.nextPage
                        )
                    }
                    val tab = memoized("t:${source.id}", channelTabCache, limiter, "uploads of ${source.id}") {
                        ChannelTabInfo.getInfo(youtube, videosTab)
                    }
                    UploadsPage(
                        videos = tab.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toVideo() },
                        handle = FeedHandle.ChannelTab(videosTab.detached()),
                        nextPage = tab.nextPage
                    )
                }
                SourceKind.PLAYLIST -> {
                    val info = playlistInfo(source.id, source.url, limiter)
                    UploadsPage(
                        videos = info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toVideo() },
                        handle = FeedHandle.Playlist(source.url),
                        nextPage = info.nextPage
                    )
                }
                // Local list, no paging: the videos live on the source itself.
                SourceKind.CUSTOM -> UploadsPage(source.customVideos, null, null)
            }
        }

    suspend fun moreUploads(handle: FeedHandle, page: Page, background: Boolean = false): UploadsPage =
        withContext(Dispatchers.IO) {
            val limiter = if (background) backgroundFetches else interactiveFetches
            val more = limiter.withPermit {
                retrying("more uploads") {
                    when (handle) {
                        is FeedHandle.ChannelTab -> ChannelTabInfo.getMoreItems(youtube, handle.tab, page)
                        is FeedHandle.Playlist -> PlaylistInfo.getMoreItems(youtube, handle.url, page)
                    }
                }
            }
            UploadsPage(
                videos = more.items.filterIsInstance<StreamInfoItem>().map { it.toVideo() },
                handle = handle,
                nextPage = more.nextPage
            )
        }

    /** A channel found via admin search, ready to be whitelisted. */
    data class ChannelResult(
        val name: String,
        val url: String,
        val avatarUrl: String?,
        val subscriberCount: Long
    )

    /** One page of search results; [next] is the cursor for the page after it, if any. */
    data class SearchPage<T>(val items: List<T>, val next: Page?)

    /**
     * One page of a parent search, cached per query and page for INFO_TTL_MS. [page] is
     * the previous result's [SearchPage.next], or null for the first page.
     */
    private suspend fun <T> searchPaged(
        filter: String,
        cache: BoundedCache<SearchPage<T>>,
        query: String,
        page: Page?,
        what: String,
        map: (org.schabi.newpipe.extractor.InfoItem) -> T?
    ): SearchPage<T> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val pageKey = page?.let { "${it.url}|${it.id}|${it.body?.contentHashCode()}" } ?: "first"
        memoized("s:$filter:${q.lowercase()}:$pageKey", cache, interactiveFetches, "$what search '$q'") {
            val handler = youtube.searchQHFactory.fromQuery(q, listOf(filter), "")
            if (page == null) {
                val info = SearchInfo.getInfo(youtube, handler)
                SearchPage(info.relatedItems.mapNotNull(map), info.nextPage)
            } else {
                val more = SearchInfo.getMoreItems(youtube, handler, page)
                SearchPage(more.items.mapNotNull(map), more.nextPage)
            }
        }
    }

    /** Parent-facing channel search for the admin UI (no IDs, no URLs to paste). */
    suspend fun searchChannels(query: String): List<ChannelResult> =
        searchChannelsPage(query, null).items

    suspend fun searchChannelsPage(query: String, page: Page?): SearchPage<ChannelResult> =
        searchPaged("channels", channelSearchCache, query, page, "channel") {
            (it as? ChannelInfoItem)?.let { c ->
                ChannelResult(
                    name = c.name,
                    url = c.url,
                    avatarUrl = c.thumbnails.pick(160),
                    subscriberCount = c.subscriberCount
                )
            }
        }

    /** A playlist found via search (AI discovery), ready to be whitelisted. */
    data class PlaylistResult(
        val name: String,
        val url: String,
        val thumbnailUrl: String?,
        val uploaderName: String?,
        val videoCount: Long
    )

    suspend fun searchPlaylists(query: String): List<PlaylistResult> =
        searchPlaylistsPage(query, null).items

    suspend fun searchPlaylistsPage(query: String, page: Page?): SearchPage<PlaylistResult> =
        searchPaged("playlists", playlistSearchCache, query, page, "playlist") {
            (it as? org.schabi.newpipe.extractor.playlist.PlaylistInfoItem)?.let { p ->
                PlaylistResult(
                    name = p.name,
                    url = p.url,
                    thumbnailUrl = p.thumbnails.pick(160),
                    uploaderName = p.uploaderName?.takeIf { n -> n.isNotBlank() },
                    videoCount = p.streamCount
                )
            }
        }

    /** A single video found via parent search; [live] is the extractor's own stream type. */
    data class VideoResult(
        val id: String,
        val title: String,
        val channel: String,
        val thumbnailUrl: String?,
        val durationSeconds: Long,
        val live: Boolean
    ) {
        fun toCustomVideo() = CustomVideo(id, title, channel, thumbnailUrl, durationSeconds.coerceAtLeast(0), live)
    }

    suspend fun searchVideosPage(query: String, page: Page?): SearchPage<VideoResult> =
        searchPaged("videos", videoSearchCache, query, page, "video") {
            (it as? StreamInfoItem)?.let { v ->
                val id = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(v.url)?.groupValues?.get(1)
                    ?: return@let null
                VideoResult(
                    id = id,
                    title = v.name,
                    channel = v.uploaderName.orEmpty(),
                    thumbnailUrl = v.thumbnails.pick(QualityTargets.videoThumbMinWidth),
                    durationSeconds = v.duration,
                    live = v.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
                )
            }
        }

    /**
     * Fast first paint for a cold channel: YouTube's RSS feed is one tiny (~30KB)
     * request returning the latest 15 videos — seconds instead of the extractor's
     * heavyweight page chain. No durations; thumbnails derived from video IDs.
     */
    suspend fun quickFeed(channelId: String): List<Video> = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
            .build()
        val xml = Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            resp.body?.string().orEmpty()
        }
        val entryRegex = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
        val idRegex = Regex("<yt:videoId>([A-Za-z0-9_-]{11})</yt:videoId>")
        val titleRegex = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        val nameRegex = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)

        entryRegex.findAll(xml).mapNotNull { entry ->
            val body = entry.groupValues[1]
            val videoId = idRegex.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            Video(
                url = "https://www.youtube.com/watch?v=$videoId",
                title = titleRegex.find(body)?.groupValues?.get(1)?.unescapeXml() ?: return@mapNotNull null,
                channelName = nameRegex.find(body)?.groupValues?.get(1)?.unescapeXml().orEmpty(),
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/" +
                    (if (QualityTargets.videoThumbMinWidth >= 480) "hqdefault.jpg" else "mqdefault.jpg"),
                durationSeconds = 0
            )
        }.toList()
    }

    private fun String.unescapeXml(): String = this
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    /** A side-loadable subtitle track (URL + enough metadata to select one). */
    data class Subtitle(
        val url: String,
        val mimeType: String,
        val languageTag: String,
        val name: String,
        val autoGenerated: Boolean
    ) {
        /** A video often has both a human and an auto track for one language, both named alike. */
        val label: String get() = if (autoGenerated) "$name (auto)" else name
    }

    data class AudioTrack(
        val url: String,
        val languageTag: String,
        val name: String,
        val original: Boolean
    )

    /** What the player should feed ExoPlayer. Separate audio → merge for HD. */
    data class Playback(
        val title: String,
        val videoUrl: String,
        val audioUrl: String?,
        val subtitles: List<Subtitle> = emptyList(),
        val audioTracks: List<AudioTrack> = emptyList(),
        /** Uploader-written page fields, carried for the pre-play deep check —
         *  the StreamInfo fetch already paid for them. Empty for local files. */
        val description: String = "",
        val tags: List<String> = emptyList(),
        val uploaderUrl: String? = null,
        /** One video-only stream per height the video offers, tallest first —
         *  the player's quality picker. Empty when YouTube offered nothing to
         *  choose between (muxed fallback, local files, network shares). */
        val videoOptions: List<VideoOption> = emptyList(),
        /** Height of the stream in [videoUrl]; 0 when unknown (local files). */
        val videoHeight: Int = 0,
        /** [videoUrl] is a live HLS playlist, played as is (no audio merge, no chunking). */
        val live: Boolean = false,
        /** Uploader-marked sections, earliest first; empty when the video has none. */
        val chapters: List<Chapter> = emptyList(),
        /** Sprite sheets for the scrub preview; null when YouTube offers none. */
        val storyboard: Storyboard? = null
    )

    data class VideoOption(val height: Int, val url: String)

    data class Chapter(val startMs: Long, val title: String)

    /** A grid of evenly spaced frames, split across [urls] image sheets. */
    data class Storyboard(
        val urls: List<String>,
        val frameWidth: Int,
        val frameHeight: Int,
        val framesPerX: Int,
        val framesPerY: Int,
        val totalFrames: Int,
        val msPerFrame: Int
    ) {
        data class Frame(val url: String, val x: Int, val y: Int)

        /** Where the frame for [positionMs] sits; null past the sheets YouTube listed. */
        fun frameAt(positionMs: Long): Frame? {
            val index = (positionMs.coerceAtLeast(0) / msPerFrame).toInt().coerceAtMost(totalFrames - 1)
            val perSheet = framesPerX * framesPerY
            val onSheet = index % perSheet
            return urls.getOrNull(index / perSheet)?.let {
                Frame(it, (onSheet % framesPerX) * frameWidth, (onSheet / framesPerX) * frameHeight)
            }
        }
    }

    private fun chaptersOf(info: StreamInfo): List<Chapter> =
        info.streamSegments.orEmpty()
            .filter { !it.title.isNullOrBlank() }
            .map { Chapter(it.startTimeSeconds * 1000L, it.title) }
            .sortedBy { it.startMs }

    /**
     * The sheet set nearest [STORYBOARD_FRAME_WIDTH]: a preview card's worth of
     * detail, with sheets small enough for a Chromecast's heap. The largest
     * sets cost several times the memory for a card this size.
     */
    private fun storyboardOf(info: StreamInfo): Storyboard? =
        info.previewFrames.orEmpty()
            .filter {
                it.urls.isNotEmpty() && it.durationPerFrame > 0 && it.totalCount > 0 &&
                    it.framesPerPageX > 0 && it.framesPerPageY > 0 &&
                    it.frameWidth > 0 && it.frameHeight > 0
            }
            .minByOrNull { kotlin.math.abs(it.frameWidth - STORYBOARD_FRAME_WIDTH) }
            ?.let {
                Storyboard(
                    it.urls, it.frameWidth, it.frameHeight,
                    it.framesPerPageX, it.framesPerPageY, it.totalCount, it.durationPerFrame
                )
            }

    /** Human-authored tracks first — auto-captions are a fallback, not a default. */
    private fun subtitlesOf(info: StreamInfo): List<Subtitle> =
        info.subtitles.mapNotNull { s ->
            val mime = when (s.format) {
                org.schabi.newpipe.extractor.MediaFormat.VTT -> "text/vtt"
                org.schabi.newpipe.extractor.MediaFormat.TTML -> "application/ttml+xml"
                org.schabi.newpipe.extractor.MediaFormat.SRT -> "application/x-subrip"
                else -> null
            } ?: return@mapNotNull null
            val url = s.content?.takeIf { s.isUrl } ?: return@mapNotNull null
            Subtitle(
                url = url,
                mimeType = mime,
                languageTag = s.languageTag.orEmpty(),
                name = s.displayLanguageName.orEmpty().ifEmpty { s.languageTag.orEmpty() },
                autoGenerated = s.isAutoGenerated
            )
        }.sortedBy { it.autoGenerated }

    /**
     * Resolve playback streams lazily (URLs expire). With [maxHeight] set, picks a
     * video-only stream up to that height plus the best audio (merged at playback —
     * this is how 720p+ is reached; muxed streams cap around 360p). Falls back to
     * muxed when separation isn't available. Age-restricted videos are refused.
     */
    suspend fun resolvePlayback(videoPageUrl: String, maxHeight: Int?): Playback =
        withContext(Dispatchers.IO) {
            val info = interactiveFetches.withPermit {
                retrying("stream $videoPageUrl") { StreamInfo.getInfo(youtube, videoPageUrl) }
            }
            check(info.ageLimit == 0) { "This video is age-restricted and can't be played here." }

            if (info.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM &&
                !info.hlsUrl.isNullOrBlank()
            ) {
                return@withContext Playback(
                    info.name, info.hlsUrl, null, emptyList(),
                    description = info.description?.content.orEmpty(),
                    tags = info.tags.orEmpty(), uploaderUrl = info.uploaderUrl,
                    live = true
                )
            }

            if (maxHeight != null) {
                val videoOnly = info.videoOnlyStreams
                    .filter { it.content != null && it.height in 1..maxHeight }
                // Prefer mp4 at the best height; fall back to any container.
                val bestVideo = videoOnly
                    .filter { it.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 }
                    .maxByOrNull { it.height }
                    ?: videoOnly.maxByOrNull { it.height }
                val audioTracks = info.audioStreams
                    .filter { it.content != null }
                    .groupBy { stream ->
                        listOf(
                            stream.audioTrackId ?: stream.audioLocale?.toLanguageTag() ?: "default",
                            stream.audioTrackType?.name.orEmpty(),
                            stream.audioTrackName.orEmpty()
                        ).joinToString(":")
                    }
                    .values
                    .mapNotNull { streams -> streams.maxByOrNull { it.averageBitrate } }
                    // Original first, then bitrate as a deterministic tiebreak:
                    // some multi-language videos flag no track ORIGINAL, and
                    // without the tiebreak the default (first) track — what a
                    // kid hears — would be whichever dub grouped first.
                    .sortedWith(
                        compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                            it.audioTrackType ==
                                org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL
                        }.thenByDescending { it.averageBitrate }
                    )
                    .map { stream ->
                        val locale = stream.audioLocale
                        AudioTrack(
                            url = stream.content,
                            languageTag = locale?.toLanguageTag().orEmpty(),
                            name = stream.audioTrackName
                                ?: locale?.getDisplayLanguage(locale).orEmpty()
                                .ifBlank { "Original" },
                            original = stream.audioTrackType ==
                                org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL
                        )
                    }
                val bestAudio = audioTracks.firstOrNull()
                if (bestVideo != null && bestAudio != null) {
                    println(
                        "Pickwick: stream '${info.name}' max=$maxHeight picked " +
                            "${bestVideo.height}p ${bestVideo.format} ${bestVideo.bitrate / 1000}kbps; " +
                            "offered ${info.videoOnlyStreams.map { it.height }.distinct().sorted()}"
                    )
                    // Every height on offer (not just those under maxHeight) so
                    // the viewer can pick above or below the automatic target.
                    val options = info.videoOnlyStreams
                        .filter { it.content != null && it.height > 0 }
                        .groupBy { it.height }
                        .map { (height, streams) ->
                            val best = streams.firstOrNull {
                                it.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4
                            } ?: streams.first()
                            VideoOption(height, best.content)
                        }
                        .sortedByDescending { it.height }
                    return@withContext Playback(
                        info.name, bestVideo.content, bestAudio.url, subtitlesOf(info), audioTracks,
                        description = info.description?.content.orEmpty(),
                        tags = info.tags.orEmpty(), uploaderUrl = info.uploaderUrl,
                        videoOptions = options, videoHeight = bestVideo.height,
                        chapters = chaptersOf(info), storyboard = storyboardOf(info)
                    )
                }
            }
            val muxed = info.videoStreams
                .filter { !it.isVideoOnly }
                .maxByOrNull { it.height }
                ?: error("No playable stream found")
            println(
                "Pickwick: stream '${info.name}' max=$maxHeight fell back to muxed " +
                    "${muxed.height}p (video-only offered " +
                    "${info.videoOnlyStreams.map { it.height }.distinct().sorted()}, " +
                    "audio tracks ${info.audioStreams.size})"
            )
            Playback(
                info.name, muxed.content, null, subtitlesOf(info),
                description = info.description?.content.orEmpty(),
                tags = info.tags.orEmpty(), uploaderUrl = info.uploaderUrl,
                videoHeight = muxed.height,
                chapters = chaptersOf(info), storyboard = storyboardOf(info)
            )
        }

    /** The extractor's answer for one video, as the custom-list flows need it. */
    private sealed interface Fetched {
        class Ok(val info: StreamInfo) : Fetched
        /** Private, removed, blocked or age-restricted. */
        data object Gone : Fetched
        /** Couldn't tell (offline, extractor hiccup). */
        data object Error : Fetched
    }

    private suspend fun fetchInfo(videoId: String, limiter: Semaphore): Fetched =
        withContext(Dispatchers.IO) {
            val url = "https://www.youtube.com/watch?v=$videoId"
            try {
                val info = limiter.withPermit { retrying("video $videoId") { StreamInfo.getInfo(youtube, url) } }
                if (info.ageLimit != 0) Fetched.Gone else Fetched.Ok(info)
            } catch (e: org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException) {
                Fetched.Gone
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Fetched.Error
            }
        }

    sealed interface VideoLookup {
        data class Found(val video: CustomVideo) : VideoLookup
        /** Can't be played in the app: don't add it. */
        data object Unplayable : VideoLookup
        data object Failed : VideoLookup
    }

    /** One video's tile data for a parent's custom list, with its live flag. */
    suspend fun lookupVideo(videoId: String): VideoLookup =
        when (val f = fetchInfo(videoId, interactiveFetches)) {
            is Fetched.Ok -> VideoLookup.Found(
                CustomVideo(
                    id = videoId,
                    title = f.info.name.orEmpty(),
                    channel = f.info.uploaderName.orEmpty(),
                    thumb = f.info.thumbnails.pick(QualityTargets.videoThumbMinWidth),
                    // Live streams report a negative duration.
                    durationSeconds = f.info.duration.coerceAtLeast(0),
                    live = f.info.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
                )
            )
            Fetched.Gone -> VideoLookup.Unplayable
            Fetched.Error -> VideoLookup.Failed
        }

    /** Playable and live right now; null when it couldn't be checked. Background lane. */
    suspend fun videoStatus(videoId: String): VideoStatus.Info? =
        when (val f = fetchInfo(videoId, backgroundFetches)) {
            is Fetched.Ok -> VideoStatus.Info(
                playable = true,
                live = f.info.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
            )
            Fetched.Gone -> VideoStatus.Info(playable = false, live = false)
            Fetched.Error -> null
        }

    private fun StreamInfoItem.toVideo() = Video(
        url = url,
        title = name,
        channelName = uploaderName.orEmpty(),
        thumbnailUrl = thumbnails.pick(QualityTargets.videoThumbMinWidth),
        durationSeconds = duration
    )
}
