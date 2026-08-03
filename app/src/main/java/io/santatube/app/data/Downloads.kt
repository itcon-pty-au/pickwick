package io.santatube.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Offline downloads: the kid requests a video from its poster, the parent
 * approves in settings, and the file lands here for Wi-Fi-free playback.
 * REQUESTED → QUEUED (approved) → DOWNLOADING → DONE, or FAILED for retry.
 */
enum class DownloadStatus { REQUESTED, QUEUED, DOWNLOADING, DONE, FAILED }

/**
 * Live signals between the download service and whatever UI is on screen —
 * same style as NowPlaying/ConfigEvents, but flows so several observers
 * (kid grid, parent settings) can watch at once.
 */
object DownloadEvents {
    /** videoUrl → fraction (0..1) of the download currently in flight. */
    val progress = MutableStateFlow<Pair<String, Float>?>(null)
    /** Bumped after any status change; collectors re-read the store. */
    val changes = MutableStateFlow(0)
    fun notifyChanged() { changes.value = changes.value + 1 }
}

/**
 * Index + files for offline videos, one directory per video under
 * filesDir/downloads/<videoId>/ (video.mp4, audio.m4a, thumb.jpg, subs.tsv).
 * Internal storage: private to the app, no permissions, survives reboots.
 */
class DownloadStore(context: Context) {

    data class Entry(
        val video: Video,
        val status: DownloadStatus,
        val requestedAt: Long,
        val error: String? = null
    )

    private val root = File(context.filesDir, "downloads").apply { mkdirs() }
    private val index = File(root, "index.tsv")
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    /** Parent-set quality cap for downloads (matches playback height targets). */
    var maxHeight: Int
        get() = prefs.getInt("max_height", 720)
        set(value) { prefs.edit().putInt("max_height", value).apply() }

    fun dirFor(videoId: String): File = File(root, videoId)

    fun entries(): List<Entry> = synchronized(LOCK) { loadEntries() }

    /** Kid taps the ⬇ icon: joins the parent's approval queue. No-op if known. */
    fun request(video: Video) {
        if (video.videoId == null) return
        synchronized(LOCK) {
            val all = loadEntries()
            if (all.any { it.video.url == video.url }) return
            saveEntries(all + Entry(video, DownloadStatus.REQUESTED, System.currentTimeMillis()))
        }
        DownloadEvents.notifyChanged()
    }

    /** Kid taps again while still pending: withdraws the request. */
    fun cancelRequest(videoUrl: String) {
        synchronized(LOCK) {
            val all = loadEntries()
            val entry = all.firstOrNull { it.video.url == videoUrl } ?: return
            if (entry.status != DownloadStatus.REQUESTED) return
            saveEntries(all - entry)
        }
        DownloadEvents.notifyChanged()
    }

    /** Parent approves: the request becomes downloadable work. */
    fun approve(videoUrl: String) = setStatus(videoUrl, DownloadStatus.QUEUED)

    /** Parent declines (or removes a failed one): entry and any files go away. */
    fun remove(videoUrl: String) {
        synchronized(LOCK) {
            val all = loadEntries()
            val entry = all.firstOrNull { it.video.url == videoUrl } ?: return
            saveEntries(all - entry)
            entry.video.videoId?.let { dirFor(it).deleteRecursively() }
        }
        DownloadEvents.notifyChanged()
    }

    fun setStatus(videoUrl: String, status: DownloadStatus, error: String? = null) {
        synchronized(LOCK) {
            saveEntries(loadEntries().map { e ->
                if (e.video.url == videoUrl) e.copy(status = status, error = error) else e
            })
        }
        DownloadEvents.notifyChanged()
    }

    fun nextQueued(): Entry? = synchronized(LOCK) {
        loadEntries().firstOrNull { it.status == DownloadStatus.QUEUED }
    }

    /** A crash mid-download leaves DOWNLOADING behind — put it back in the queue. */
    fun requeueInterrupted() {
        synchronized(LOCK) {
            saveEntries(loadEntries().map { e ->
                if (e.status == DownloadStatus.DOWNLOADING) e.copy(status = DownloadStatus.QUEUED) else e
            })
        }
    }

    fun hasPendingWork(): Boolean = entries().any {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
    }

    /** Video URLs waiting on the parent or the network (drives the ⏳ icon).
     *  FAILED counts as pending too — to the kid it's still "coming"; the
     *  parent sees the failure and retries or removes it in settings. */
    fun pendingUrls(): Set<String> = entries()
        .filter { it.status != DownloadStatus.DONE }
        .map { it.video.url }.toSet()

    /** Video URLs fully on disk (drives the ✅ icon and the Downloads screen). */
    fun downloadedUrls(): Set<String> = entries()
        .filter { it.status == DownloadStatus.DONE }
        .map { it.video.url }.toSet()

    /**
     * Videos ready for offline play, newest first, with thumbnails pointed at
     * the local copy — the grid must render with the radio off.
     */
    fun downloadedVideos(): List<Video> = entries()
        .filter { it.status == DownloadStatus.DONE }
        .sortedByDescending { it.requestedAt }
        .map { e ->
            val thumb = e.video.videoId?.let { File(dirFor(it), "thumb.jpg") }
            if (thumb?.exists() == true) e.video.copy(thumbnailUrl = thumb.toURI().toString())
            else e.video
        }

    /** Local streams for the player, or null if this video isn't fully on disk. */
    fun localPlayback(videoUrl: String): YouTubeRepository.Playback? {
        val entry = entries().firstOrNull {
            it.video.url == videoUrl && it.status == DownloadStatus.DONE
        } ?: return null
        val dir = dirFor(entry.video.videoId ?: return null)
        val video = File(dir, "video.mp4").takeIf { it.exists() } ?: return null
        val audio = File(dir, "audio.m4a").takeIf { it.exists() }
        val subs = File(dir, "subs.tsv").takeIf { it.exists() }?.let { f ->
            runCatching {
                f.readLines().mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 4) return@mapNotNull null
                    val subFile = File(dir, p[0]).takeIf { it.exists() } ?: return@mapNotNull null
                    YouTubeRepository.Subtitle(
                        url = subFile.toURI().toString(),
                        mimeType = p[1],
                        languageTag = p[2],
                        name = p[3],
                        autoGenerated = p.getOrNull(4)?.toBoolean() ?: false
                    )
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        return YouTubeRepository.Playback(
            entry.video.title, video.toURI().toString(), audio?.toURI()?.toString(), subs
        )
    }

    /** Bytes on disk for one video (parent's storage view). */
    fun sizeOf(videoId: String): Long =
        dirFor(videoId).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun totalSize(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // --- persistence ---------------------------------------------------------

    private fun loadEntries(): List<Entry> {
        if (!index.exists()) return emptyList()
        return runCatching {
            index.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 7) return@mapNotNull null
                // Rows written before the totalBytes column was dropped have 9
                // fields, with a byte count at index 7 and the error last.
                val error = if (p.size >= 9) p[8] else p.getOrNull(7).orEmpty()
                Entry(
                    Video(p[0], p[1], p[2], p[3].ifEmpty { null }, p[4].toLongOrNull() ?: 0L),
                    runCatching { DownloadStatus.valueOf(p[5]) }.getOrNull() ?: return@mapNotNull null,
                    p[6].toLongOrNull() ?: 0L,
                    error.ifEmpty { null }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveEntries(entries: List<Entry>) {
        runCatching {
            index.writeText(entries.joinToString("\n") { e ->
                listOf(
                    e.video.url,
                    e.video.title.replace('\t', ' ').replace('\n', ' '),
                    e.video.channelName.replace('\t', ' '),
                    e.video.thumbnailUrl.orEmpty(),
                    e.video.durationSeconds.toString(),
                    e.status.name,
                    e.requestedAt.toString(),
                    e.error.orEmpty().replace('\t', ' ').replace('\n', ' ')
                ).joinToString("\t")
            })
        }
    }

    companion object {
        /** The store is constructed in several places (UI, service) over one file. */
        private val LOCK = Any()
    }
}
