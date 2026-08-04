package io.pickwick.app.data

import android.content.Context
import java.io.File

/**
 * The kid's saved-for-later videos, newest first. Adds and removals are
 * timestamped so devices can merge (latest event per video wins) without
 * removals resurrecting on the next sync.
 */
class WatchlistStore(context: Context) {

    data class Entry(val video: Video, val addedAt: Long)

    private val file = File(context.filesDir, "watchlist.tsv")
    private val removedFile = File(context.filesDir, "watchlist_removed.tsv")

    fun loadEntries(): List<Entry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 5) return@mapNotNull null
                Entry(
                    Video(p[0], p[1], p[2], p[3].ifEmpty { null }, p[4].toLongOrNull() ?: 0L),
                    // Legacy rows (5 cols) predate sync — treat as ancient adds.
                    p.getOrNull(5)?.toLongOrNull() ?: 1L
                )
            }
        }.getOrDefault(emptyList())
    }

    fun load(): List<Video> = loadEntries().map { it.video }

    fun urls(): Set<String> = loadEntries().map { it.video.url }.toSet()

    /** url → when it was removed (tombstones for merge). */
    fun removedMap(): Map<String, Long> {
        if (!removedFile.exists()) return emptyMap()
        return runCatching {
            removedFile.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 2) null else p[0] to (p[1].toLongOrNull() ?: 0L)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun add(video: Video) {
        saveEntries(
            listOf(Entry(video, System.currentTimeMillis())) +
                loadEntries().filter { it.video.url != video.url }
        )
        saveRemoved(removedMap() - video.url)
    }

    fun remove(videoUrl: String) {
        saveEntries(loadEntries().filter { it.video.url != videoUrl })
        saveRemoved(removedMap() + (videoUrl to System.currentTimeMillis()))
    }

    /** Merge another device's list: per video, the latest add/remove event wins. */
    fun merge(incoming: List<Entry>, incomingRemoved: Map<String, Long>) {
        val localEntries = loadEntries().associateBy { it.video.url }
        val localRemoved = removedMap()
        val allUrls = localEntries.keys + localRemoved.keys +
            incoming.map { it.video.url } + incomingRemoved.keys

        val mergedEntries = mutableListOf<Entry>()
        val mergedRemoved = mutableMapOf<String, Long>()
        allUrls.forEach { url ->
            val addTs = maxOf(localEntries[url]?.addedAt ?: 0L,
                incoming.firstOrNull { it.video.url == url }?.addedAt ?: 0L)
            val remTs = maxOf(localRemoved[url] ?: 0L, incomingRemoved[url] ?: 0L)
            if (addTs > remTs && addTs > 0) {
                val video = localEntries[url]?.video
                    ?: incoming.first { it.video.url == url }.video
                mergedEntries += Entry(video, addTs)
            } else if (remTs > 0) {
                mergedRemoved[url] = remTs
            }
        }
        saveEntries(mergedEntries.sortedByDescending { it.addedAt })
        saveRemoved(mergedRemoved)
    }

    private fun saveEntries(entries: List<Entry>) {
        runCatching {
            file.writeText(entries.take(200).joinToString("\n") { e ->
                listOf(
                    e.video.url,
                    e.video.title.replace('\t', ' ').replace('\n', ' '),
                    e.video.channelName.replace('\t', ' '),
                    e.video.thumbnailUrl.orEmpty(),
                    e.video.durationSeconds.toString(),
                    e.addedAt.toString()
                ).joinToString("\t")
            })
        }
    }

    private fun saveRemoved(removed: Map<String, Long>) {
        runCatching {
            removedFile.writeText(
                removed.entries.sortedByDescending { it.value }.take(200)
                    .joinToString("\n") { "${it.key}\t${it.value}" }
            )
        }
    }
}
