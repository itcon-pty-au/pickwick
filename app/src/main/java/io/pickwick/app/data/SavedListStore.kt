package io.pickwick.app.data

import android.content.Context
import java.io.File

/**
 * One of the kid's saved video lists — Favorites or Watch later — newest
 * first. Adds and removals are timestamped so devices can merge (latest event
 * per video wins) without removals resurrecting on the next sync.
 *
 * [listName] picks the backing files. Favorites keeps the historical
 * "watchlist" spelling: renaming it would orphan every installed family's
 * saved videos, and the list is only called Favorites on screen.
 */
class SavedListStore(
    context: Context,
    profileSuffix: String = "",
    listName: String = FAVORITES
) {

    data class Entry(val video: Video, val addedAt: Long)

    private val file = File(context.filesDir, "$listName$profileSuffix.tsv")
    private val removedFile = File(context.filesDir, "${listName}_removed$profileSuffix.tsv")

    fun loadEntries(): List<Entry> = synchronized(LOCK) {
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
    fun removedMap(): Map<String, Long> = synchronized(LOCK) {
        if (!removedFile.exists()) return emptyMap()
        return runCatching {
            removedFile.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 2) null else p[0] to (p[1].toLongOrNull() ?: 0L)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun add(video: Video) = synchronized(LOCK) {
        saveEntries(
            listOf(Entry(video, System.currentTimeMillis())) +
                loadEntries().filter { it.video.url != video.url }
        )
        saveRemoved(removedMap() - video.url)
    }

    fun remove(videoUrl: String) = synchronized(LOCK) {
        saveEntries(loadEntries().filter { it.video.url != videoUrl })
        saveRemoved(removedMap() + (videoUrl to System.currentTimeMillis()))
    }

    /** Merge another device's list: per video, the latest add/remove event wins. */
    fun merge(incoming: List<Entry>, incomingRemoved: Map<String, Long>) = synchronized(LOCK) {
        val localEntries = loadEntries().associateBy { it.video.url }
        val incomingByUrl = HashMap<String, Entry>(incoming.size)
        incoming.forEach { incomingByUrl.putIfAbsent(it.video.url, it) }
        val localRemoved = removedMap()
        val allUrls = localEntries.keys + localRemoved.keys +
            incomingByUrl.keys + incomingRemoved.keys

        val mergedEntries = mutableListOf<Entry>()
        val mergedRemoved = mutableMapOf<String, Long>()
        allUrls.forEach { url ->
            val addTs = maxOf(localEntries[url]?.addedAt ?: 0L,
                incomingByUrl[url]?.addedAt ?: 0L)
            val remTs = maxOf(localRemoved[url] ?: 0L, incomingRemoved[url] ?: 0L)
            if (addTs > remTs && addTs > 0) {
                val video = localEntries[url]?.video ?: incomingByUrl.getValue(url).video
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
                    e.video.title.tsvCell(),
                    e.video.channelName.tsvCell(),
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

    companion object {
        /** On-disk name of the hearted list (see the class doc). */
        const val FAVORITES = "watchlist"

        /** On-disk name of the queue-for-another-day list. */
        const val WATCH_LATER = "watchlater"

        /** Written from the UI (kid taps) and LAN sync workers over one file —
         *  an unsynchronized read-modify-write would drop one of the two.
         *  Coarse across both lists: the taps are seconds apart, not contended. */
        private val LOCK = Any()
    }
}
