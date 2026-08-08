package io.pickwick.app.data

import io.pickwick.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

/**
 * Builds the full [ChannelIndex] on the master device: for each whitelisted
 * source, page 1 first (instant usefulness), then a slow trickle through the
 * back catalog until the oldest video. Rate-limited hard — this is the single
 * most aggressive thing the app ever does to YouTube, and a 429 here would
 * degrade normal browsing for the whole family.
 *
 * Pagination cursors (handle + NewPipe [Page]) persist to disk via
 * [ChannelIndex], stamped with the app versionCode: a cursor is only trusted
 * by the build that wrote it, because Page internals aren't stable across
 * extractor upgrades (which arrive bundled with app updates). A stale or
 * missing cursor falls back to re-walking from page 1, and per-videoId dedup
 * absorbs the overlap.
 */
class IndexCrawler(
    private val yt: YouTubeRepository,
    private val index: ChannelIndex
) {
    /** sourceId → (handle, next page); write-through cache over the persisted copy. */
    private val cursors = mutableMapOf<String, Pair<YouTubeRepository.FeedHandle, Page>>()

    /**
     * One page of one source per call. Returns false when the source is fully
     * crawled (or has nothing more). The caller schedules; this never loops on
     * its own, so throttling policy lives in exactly one place.
     */
    suspend fun crawlOnce(source: Source): Boolean {
        if (index.state(source.id)?.complete == true) return false

        val cursor = cursors[source.id]
            ?: index.loadCursor(source.id)?.let { deserializeCursor(it) }
        val isFirstPage = cursor == null
        val page = if (isFirstPage) {
            yt.uploadsPage(source, background = true)
        } else {
            val (handle, next) = cursor
            yt.moreUploads(handle, next, background = true)
        }

        // NewPipe sometimes returns a FULL first page with no continuation
        // token (the videos tab's initial render lacks one; it only appears on
        // getMoreItems). Trusting nextPage==null there marks a 500-video
        // channel complete after ~30. Only treat exhaustion as real when the
        // page was short/empty, or when a CONTINUATION fetch ran dry — a full
        // page always means "keep trying".
        val exhausted = page.nextPage == null &&
            (page.videos.size < FULL_PAGE || !isFirstPage)

        if (page.videos.isNotEmpty()) {
            index.addVideos(
                source.id,
                page.videos.map { it.toIndexed(source.id) },
                complete = exhausted,
                append = !isFirstPage
            )
        }

        val handle = page.handle
        val next = page.nextPage
        return if (!exhausted && handle != null && next != null) {
            cursors[source.id] = handle to next
            serializeCursor(handle, next)?.let { index.saveCursor(source.id, it) }
            true
        } else if (!exhausted && isFirstPage && handle != null) {
            // Full page but no usable continuation: park it for another run
            // rather than declaring completion — the next run re-fetches page 1
            // (dedup absorbs it) and often gets a real continuation.
            false.also { forgetCursor(source.id) }
        } else {
            forgetCursor(source.id)
            index.addVideos(source.id, emptyList(), complete = true)
            false
        }
    }

    /**
     * Delta harvest: feed it the page-1 videos a refresh already fetched and
     * it prepends whatever's new. Zero extra network cost. Suspend + IO here
     * because the callers sit on the main dispatcher and addVideos hits disk.
     */
    suspend fun harvestPage1(source: Source, videos: List<Video>) {
        if (videos.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            index.addVideos(source.id, videos.map { it.toIndexed(source.id) })
        }
    }

    /**
     * History harvest: a kid scrolling a channel's older pages has already paid
     * the network cost — append them so browsed depth becomes searchable depth.
     */
    suspend fun harvestHistory(source: Source, videos: List<Video>) {
        if (videos.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            index.addVideos(source.id, videos.map { it.toIndexed(source.id) }, append = true)
        }
    }

    /** Whitelist edit dropped a source: forget its cursor too. */
    fun dropSource(sourceId: String) {
        cursors.remove(sourceId)
        index.dropSource(sourceId)
    }

    private fun forgetCursor(sourceId: String) {
        cursors.remove(sourceId)
        index.dropCursor(sourceId)
    }

    // ---- cursor wire format ------------------------------------------------

    private fun serializeCursor(handle: YouTubeRepository.FeedHandle, page: Page): String? =
        runCatching {
            JSONObject().apply {
                put("v", BuildConfig.VERSION_CODE)
                put("handle", when (handle) {
                    is YouTubeRepository.FeedHandle.Playlist ->
                        JSONObject().put("kind", "playlist").put("url", handle.url)
                    is YouTubeRepository.FeedHandle.ChannelTab -> JSONObject().apply {
                        put("kind", "tab")
                        put("url", handle.tab.url)
                        put("originalUrl", handle.tab.originalUrl)
                        put("id", handle.tab.id)
                        put("filters", JSONArray(handle.tab.contentFilters))
                        put("sort", handle.tab.sortFilter)
                    }
                })
                put("page", JSONObject().apply {
                    page.url?.let { put("url", it) }
                    page.id?.let { put("id", it) }
                    page.ids?.let { put("ids", JSONArray(it)) }
                    page.cookies?.let { put("cookies", JSONObject(it)) }
                    page.body?.let {
                        put("body", java.util.Base64.getEncoder().encodeToString(it))
                    }
                })
            }.toString()
        }.getOrNull()

    private fun deserializeCursor(json: String): Pair<YouTubeRepository.FeedHandle, Page>? =
        runCatching {
            val o = JSONObject(json)
            // A cursor written by another build re-walks instead — Page
            // internals may have changed shape with the bundled extractor.
            if (o.optInt("v") != BuildConfig.VERSION_CODE) return@runCatching null
            val h = o.getJSONObject("handle")
            val handle = if (h.getString("kind") == "playlist") {
                YouTubeRepository.FeedHandle.Playlist(h.getString("url"))
            } else {
                val filters = h.optJSONArray("filters") ?: JSONArray()
                YouTubeRepository.FeedHandle.ChannelTab(
                    ListLinkHandler(
                        h.optString("originalUrl"),
                        h.optString("url"),
                        h.optString("id"),
                        (0 until filters.length()).map { filters.getString(it) },
                        h.optString("sort")
                    )
                )
            }
            val p = o.getJSONObject("page")
            val cookies = p.optJSONObject("cookies")?.let { c ->
                c.keys().asSequence().associateWith { c.getString(it) }
            }
            val page = Page(
                p.optString("url").ifEmpty { null },
                p.optString("id").ifEmpty { null },
                p.optJSONArray("ids")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                cookies,
                p.optString("body").takeIf { it.isNotEmpty() }
                    ?.let { java.util.Base64.getDecoder().decode(it) }
            )
            handle to page
        }.getOrNull()

    private fun Video.toIndexed(sourceId: String) = ChannelIndex.IndexedVideo(
        videoId = videoId ?: url.hashCode().toString(16),
        title = title,
        channelName = channelName,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        sourceId = sourceId
    )

    companion object {
        /**
         * Pause between crawl pages inside a worker run. A run's budget spread
         * over a couple of minutes reads as browsing, not scraping — and the
         * background fetch lane already keeps it off the kid's interactive path.
         */
        const val CRAWL_DELAY_MS = 4_000L

        /**
         * What a "full" uploads page looks like. A first page at this size with
         *  no continuation is the NewPipe no-initial-continuation quirk, NOT an
         *  exhausted channel — see crawlOnce. NewPipe's channel tab pages ~30.
         */
        const val FULL_PAGE = 20
    }
}
