package io.pickwick.app.data

import android.content.Context
import java.io.File

/**
 * The kid's lined-up videos for one sitting (bedtime stories), in play order.
 * Self-clearing: the player removes an item only when it truly finishes, so a
 * crash or Wi-Fi blip never silently drops a pick. Device-local on purpose —
 * a queue is "tonight, on this screen", unlike the synced watchlist.
 */
class QueueStore(private val file: File) {

    constructor(context: Context, profileSuffix: String = "") :
        this(File(context.filesDir, "queue$profileSuffix.tsv"))

    fun load(): List<Video> = synchronized(LOCK) {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 5) return@mapNotNull null
                Video(p[0], p[1], p[2], p[3].ifEmpty { null }, p[4].toLongOrNull() ?: 0L)
            }
        }.getOrDefault(emptyList())
    }

    fun urls(): Set<String> = load().map { it.url }.toSet()

    /** Appends to the end (play order = add order). False when already queued
     *  or at the cap, so the caller's badge doesn't flip on a refused add. */
    fun add(video: Video): Boolean = synchronized(LOCK) {
        val current = load()
        if (current.any { it.url == video.url } || current.size >= MAX_ITEMS) return false
        save(current + video)
        true
    }

    fun remove(videoUrl: String) = synchronized(LOCK) {
        save(load().filter { it.url != videoUrl })
    }

    fun move(videoUrl: String, delta: Int) = synchronized(LOCK) {
        save(moved(load(), videoUrl, delta))
    }

    private fun save(videos: List<Video>) {
        runCatching {
            file.writeText(videos.take(MAX_ITEMS).joinToString("\n") { v ->
                listOf(
                    v.url,
                    v.title.tsvCell(),
                    v.channelName.tsvCell(),
                    v.thumbnailUrl.orEmpty(),
                    v.durationSeconds.toString()
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
        fun moved(list: List<Video>, videoUrl: String, delta: Int): List<Video> {
            val from = list.indexOfFirst { it.url == videoUrl }
            if (from < 0) return list
            val to = (from + delta).coerceIn(0, list.lastIndex)
            if (to == from) return list
            return list.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
}

/**
 * Screen-time drain rate per queue item: the video's own channel's multiplier,
 * normal speed when the channel is unknown — the same resolution mixed rows
 * (Surprise, My list) already use for single launches. A cross-channel queue
 * billed at one flat rate would over- or under-charge every other item.
 */
fun queuePercents(videos: List<Video>, channels: List<Source>): List<Int> =
    videos.map { v ->
        channels.firstOrNull { it.name == v.channelName }?.timeMultiplierPercent ?: 100
    }
