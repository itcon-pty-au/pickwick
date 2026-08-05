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

/** A tile on the home screen: a whitelisted channel or playlist. */
data class Source(
    val id: String,
    /** Canonical YouTube URL — may be a /user/, /c/ or /@handle form, not just /channel/. */
    val url: String,
    val name: String,
    val avatarUrl: String?,
    val kind: SourceKind,
    /** Screen-time drain rate in percent (100 = normal, 0 = FREE), from the whitelist entry. */
    val timeMultiplierPercent: Int = 100
)

data class Video(
    val url: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long
) {
    val videoId: String?
        // Sideloaded files carry a synthetic pickwick://local/<hash> URL; the
        // hash is 16 hex chars so it can't collide with an 11-char YouTube id.
        get() = if (LocalLibrary.isLocal(url)) url.removePrefix(LocalLibrary.URL_PREFIX)
        else Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
}

/** All YouTube access goes through NewPipeExtractor — no API key, no quota, no ads. */
class YouTubeRepository {

    private val youtube = ServiceList.YouTube

    companion object {
        private const val INFO_TTL_MS = 10 * 60 * 1000L
        // Shared across instances so MainActivity and PlayerActivity reuse fetches.
        private val channelInfoCache = ConcurrentHashMap<String, Pair<Long, ChannelInfo>>()
        private val playlistInfoCache = ConcurrentHashMap<String, Pair<Long, PlaylistInfo>>()
        private val channelTabCache = ConcurrentHashMap<String, Pair<Long, ChannelTabInfo>>()

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
        cache: ConcurrentHashMap<String, Pair<Long, T>>,
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
                        handle = FeedHandle.ChannelTab(videosTab),
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
            }
        }

    suspend fun moreUploads(handle: FeedHandle, page: Page): UploadsPage =
        withContext(Dispatchers.IO) {
            val more = interactiveFetches.withPermit {
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

    /** Parent-facing channel search for the admin UI (no IDs, no URLs to paste). */
    suspend fun searchChannels(query: String): List<ChannelResult> =
        withContext(Dispatchers.IO) {
            val handler = youtube.searchQHFactory.fromQuery(query, listOf("channels"), "")
            val info = interactiveFetches.withPermit {
                retrying("channel search '$query'") { SearchInfo.getInfo(youtube, handler) }
            }
            info.relatedItems
                .filterIsInstance<ChannelInfoItem>()
                .map {
                    ChannelResult(
                        name = it.name,
                        url = it.url,
                        avatarUrl = it.thumbnails.pick(160),
                        subscriberCount = it.subscriberCount
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
        withContext(Dispatchers.IO) {
            val handler = youtube.searchQHFactory.fromQuery(query, listOf("playlists"), "")
            val info = interactiveFetches.withPermit {
                retrying("playlist search '$query'") { SearchInfo.getInfo(youtube, handler) }
            }
            info.relatedItems
                .filterIsInstance<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem>()
                .map {
                    PlaylistResult(
                        name = it.name,
                        url = it.url,
                        thumbnailUrl = it.thumbnails.pick(160),
                        uploaderName = it.uploaderName?.takeIf { n -> n.isNotBlank() },
                        videoCount = it.streamCount
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
    )

    /** What the player should feed ExoPlayer. Separate audio → merge for HD. */
    data class Playback(
        val title: String,
        val videoUrl: String,
        val audioUrl: String?,
        val subtitles: List<Subtitle> = emptyList()
    )

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

            if (maxHeight != null) {
                val videoOnly = info.videoOnlyStreams
                    .filter { it.content != null && it.height in 1..maxHeight }
                // Prefer mp4 at the best height; fall back to any container.
                val bestVideo = videoOnly
                    .filter { it.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 }
                    .maxByOrNull { it.height }
                    ?: videoOnly.maxByOrNull { it.height }
                val bestAudio = info.audioStreams
                    .filter { it.content != null }
                    .maxByOrNull { it.averageBitrate }
                if (bestVideo != null && bestAudio != null) {
                    return@withContext Playback(
                        info.name, bestVideo.content, bestAudio.content, subtitlesOf(info)
                    )
                }
            }
            val muxed = info.videoStreams
                .filter { !it.isVideoOnly }
                .maxByOrNull { it.height }
                ?: error("No playable stream found")
            Playback(info.name, muxed.content, null, subtitlesOf(info))
        }

    private fun StreamInfoItem.toVideo() = Video(
        url = url,
        title = name,
        channelName = uploaderName.orEmpty(),
        thumbnailUrl = thumbnails.pick(QualityTargets.videoThumbMinWidth),
        durationSeconds = duration
    )
}
