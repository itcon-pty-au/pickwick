package io.pickwick.app.data

import org.schabi.newpipe.extractor.Page

/**
 * Builds the full [ChannelIndex] on the master device: for each whitelisted
 * source, page 1 first (instant usefulness), then a slow trickle through the
 * back catalog until the oldest video. Rate-limited hard — this is the single
 * most aggressive thing the app ever does to YouTube, and a 429 here would
 * degrade normal browsing for the whole family.
 *
 * Pagination cursors (NewPipe [Page]s) live only in memory: they aren't
 * stable across extractor versions, so a process restart re-walks a partly
 * crawled source from page 1 and lets per-videoId dedup absorb the overlap.
 */
class IndexCrawler(
    private val yt: YouTubeRepository,
    private val index: ChannelIndex
) {
    /** sourceId → (handle, next page) between steps; lost on process death. */
    private val cursors = mutableMapOf<String, Pair<YouTubeRepository.FeedHandle, Page>>()

    /**
     * One page of one source per call. Returns false when the source is fully
     * crawled (or has nothing more). The caller schedules; this never loops on
     * its own, so throttling policy lives in exactly one place.
     */
    suspend fun crawlOnce(source: Source): Boolean {
        if (index.state(source.id)?.complete == true) return false

        val cursor = cursors[source.id]
        val isFirstPage = cursor == null
        val page = if (isFirstPage) {
            yt.uploadsPage(source, background = true)
        } else {
            val (handle, next) = cursor
            yt.moreUploads(handle, next)
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
            true
        } else if (!exhausted && isFirstPage && handle != null) {
            // Full page but no usable continuation: park it for another run
            // rather than declaring completion — the next run re-fetches page 1
            // (dedup absorbs it) and often gets a real continuation.
            false.also { cursors.remove(source.id) }
        } else {
            cursors.remove(source.id)
            index.addVideos(source.id, emptyList(), complete = true)
            false
        }
    }

    /**
     * Delta harvest: feed it the page-1 videos a refresh already fetched and
     * it prepends whatever's new. Zero extra network cost.
     */
    fun harvestPage1(source: Source, videos: List<Video>) {
        if (videos.isEmpty()) return
        index.addVideos(source.id, videos.map { it.toIndexed(source.id) })
    }

    /** Whitelist edit dropped a source: forget its cursor too. */
    fun dropSource(sourceId: String) {
        cursors.remove(sourceId)
        index.dropSource(sourceId)
    }

    private fun Video.toIndexed(sourceId: String) = ChannelIndex.IndexedVideo(
        videoId = videoId ?: url.hashCode().toString(16),
        title = title,
        channelName = channelName,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        sourceId = sourceId
    )

    companion object {
        /** Between crawl steps: ~2 pages/minute keeps us far under YouTube's
         *  throttling threshold; a 3,000-video channel fills in over a day or
         *  so of idle app time, without touching interactive responsiveness. */
        const val CRAWL_DELAY_MS = 30_000L

        /**
         * What a "full" uploads page looks like. A first page at this size with
         *  no continuation is the NewPipe no-initial-continuation quirk, NOT an
         *  exhausted channel — see crawlOnce. NewPipe's channel tab pages ~30.
         */
        const val FULL_PAGE = 20
    }
}
