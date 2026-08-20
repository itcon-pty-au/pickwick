package io.pickwick.app.data

import android.content.Context
import java.io.File

/**
 * The kid's lined-up videos for one sitting (bedtime stories), in play order.
 * Self-clearing: the player removes an item only when it truly finishes, so a
 * crash or Wi-Fi blip never silently drops a pick. Device-local on purpose —
 * a queue is "tonight, on this screen", unlike the synced watchlist.
 */
/**
 * A queued video and when it was lined up. The timestamp is what separates
 * "finished during this sitting" (drain it) from "watched last week" (a
 * deliberate rewatch — leave it alone).
 */
data class QueuedVideo(val video: Video, val addedAt: Long)

class QueueStore(private val file: File) {

    constructor(context: Context, profileSuffix: String = "") :
        this(File(context.filesDir, "queue$profileSuffix.tsv"))

    /** Rows with their line-up timestamps — what [pruning][QueuedVideo.addedAt]
     *  needs; screens that only draw the lineup want [load]. */
    fun entries(): List<QueuedVideo> = synchronized(LOCK) {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 5) return@mapNotNull null
                QueuedVideo(
                    Video(p[0], p[1], p[2], p[3].ifEmpty { null }, p[4].toLongOrNull() ?: 0L),
                    // Rows written before queues carried a timestamp: 0 reads as
                    // "lined up before time began", so any past finish still
                    // drains them — the pre-upgrade behaviour, not a surprise
                    // resurrection of everything the kid already watched.
                    p.getOrNull(5)?.toLongOrNull() ?: 0L
                )
            }
        }.getOrDefault(emptyList())
    }

    fun load(): List<Video> = entries().map { it.video }

    fun urls(): Set<String> = entries().map { it.video.url }.toSet()

    /** Appends to the end (play order = add order). False when already queued
     *  or at the cap, so the caller's badge doesn't flip on a refused add. */
    fun add(video: Video, addedAt: Long = System.currentTimeMillis()): Boolean =
        synchronized(LOCK) {
            val current = entries()
            if (current.any { it.video.url == video.url } || current.size >= MAX_ITEMS) return false
            save(current + QueuedVideo(video, addedAt))
            true
        }

    fun remove(videoUrl: String) = synchronized(LOCK) {
        save(entries().filter { it.video.url != videoUrl })
    }

    fun move(videoUrl: String, delta: Int) = synchronized(LOCK) {
        save(moved(entries(), videoUrl, delta) { it.video.url })
    }

    private fun save(items: List<QueuedVideo>) {
        runCatching {
            file.writeText(items.take(MAX_ITEMS).joinToString("\n") { (v, addedAt) ->
                listOf(
                    v.url,
                    v.title.tsvCell(),
                    v.channelName.tsvCell(),
                    v.thumbnailUrl.orEmpty(),
                    v.durationSeconds.toString(),
                    addedAt.toString()
                ).joinToString("\t")
            })
        }
    }

    companion object {
        /** Enough for any bedtime; small enough that "play the queue" is never
         *  an accidental all-day marathon. */
        const val MAX_ITEMS = 50

        /** The player drains finished items (IO dispatcher) while the home
         *  screen reorders — an unsynchronized read-modify-write would drop
         *  one of the two. */
        private val LOCK = Any()

        /** Reorder by one step, clamped at the ends; unknown url is a no-op. */
        fun <T> moved(
            list: List<T>,
            videoUrl: String,
            delta: Int,
            urlOf: (T) -> String
        ): List<T> {
            val from = list.indexOfFirst { urlOf(it) == videoUrl }
            if (from < 0) return list
            val to = (from + delta).coerceIn(0, list.lastIndex)
            if (to == from) return list
            return list.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
}

/**
 * Whether this item has served its purpose and should leave the lineup. Only a
 * finish *later than* the line-up counts: a video watched last week and queued
 * again tonight is a deliberate rewatch, and draining it on watch history alone
 * would make the add look like it silently failed. A history entry with no
 * timestamp (0, pre-timestamp or merged from an old device) therefore never
 * drains a freshly queued item.
 */
fun QueuedVideo.finishedSinceQueued(progress: WatchProgress?): Boolean =
    progress != null && progress.isFinished && progress.lastWatchedAt > addedAt

/**
 * Screen-time drain rate per queue item: the video's own channel's multiplier,
 * normal speed when the channel is unknown — the same resolution mixed rows
 * (Surprise, Favorites) already use for single launches. A cross-channel queue
 * billed at one flat rate would over- or under-charge every other item.
 */
fun queuePercents(videos: List<Video>, channels: List<Source>): List<Int> =
    videos.map { v ->
        channels.firstOrNull { it.name == v.channelName }?.timeMultiplierPercent ?: 100
    }
