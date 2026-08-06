package io.pickwick.app.data

import android.content.Context
import java.io.File

/**
 * Persists each source's last-fetched video list so opening a channel paints
 * instantly from disk while the fresh list loads in the background.
 */
class VideoCache(context: Context) {

    private val dir = File(context.filesDir, "video_cache").apply { mkdirs() }

    private fun fileFor(sourceId: String) = File(dir, "$sourceId.tsv")

    fun load(sourceId: String): List<Video> {
        val file = fileFor(sourceId)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 5) return@mapNotNull null
                Video(
                    url = p[0],
                    title = p[1],
                    channelName = p[2],
                    thumbnailUrl = p[3].ifEmpty { null },
                    durationSeconds = p[4].toLongOrNull() ?: 0L
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(sourceId: String, videos: List<Video>) {
        runCatching {
            fileFor(sourceId).writeText(
                videos.joinToString("\n") { v ->
                    listOf(
                        v.url,
                        v.title.tsvCell(),
                        v.channelName.tsvCell(),
                        v.thumbnailUrl.orEmpty(),
                        v.durationSeconds.toString()
                    ).joinToString("\t")
                }
            )
        }
    }
}
