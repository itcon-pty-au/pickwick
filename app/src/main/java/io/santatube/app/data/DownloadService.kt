package io.santatube.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads parent-approved videos one at a time as a foreground service, so a
 * long queue survives the parent putting the phone in their pocket. Streams
 * are fetched in ranged chunks (large single requests get throttled), written
 * to .part files, and renamed only when complete — interrupted downloads
 * resume from the last byte on the next run.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 41
        private const val CHUNK_BYTES = 8L * 1024 * 1024

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, DownloadService::class.java)
            )
        }

        /** App start: pick up a queue that was interrupted by a reboot or crash. */
        fun startIfPending(context: Context) {
            if (DownloadStore(context).hasPendingWork()) runCatching { start(context) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: DownloadStore
    private val repo = YouTubeRepository()
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = DownloadStore(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Offline video downloads" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Preparing downloads…", null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (!working) {
            working = true
            scope.launch {
                store.requeueInterrupted()
                while (true) {
                    val entry = store.nextQueued() ?: break
                    downloadEntry(entry)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadEvents.progress.value = null
        scope.cancel()
    }

    private suspend fun downloadEntry(entry: DownloadStore.Entry) {
        val url = entry.video.url
        val videoId = entry.video.videoId ?: run {
            store.setStatus(url, DownloadStatus.FAILED, error = "Unrecognized video link")
            return
        }
        store.setStatus(url, DownloadStatus.DOWNLOADING)
        notify(entry.video.title, 0)

        // One retry with freshly resolved URLs: stream links expire in hours,
        // and a paused queue can easily outlive them. The .part offsets carry over.
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                downloadStreams(entry, videoId)
                store.setStatus(url, DownloadStatus.DONE)
                DownloadEvents.progress.value = null
                return
            } catch (e: AbortedException) {
                // Parent removed it mid-flight — files already cleaned up by remove().
                DownloadEvents.progress.value = null
                return
            } catch (e: Exception) {
                android.util.Log.w("SantaTube", "download $videoId attempt $attempt failed", e)
                lastError = e
            }
        }
        DownloadEvents.progress.value = null
        store.setStatus(url, DownloadStatus.FAILED, error = lastError?.message ?: "Download failed")
    }

    private suspend fun downloadStreams(entry: DownloadStore.Entry, videoId: String) {
        val playback = repo.resolvePlayback(entry.video.url, store.maxHeight)
        val dir = store.dirFor(videoId).apply { mkdirs() }
        val videoFile = File(dir, "video.mp4")
        val audioFile = File(dir, "audio.m4a")

        val videoTotal = if (videoFile.exists()) videoFile.length() else remoteLength(playback.videoUrl)
        val audioTotal = playback.audioUrl?.let {
            if (audioFile.exists()) audioFile.length() else remoteLength(it)
        } ?: 0L
        val grandTotal = (videoTotal + audioTotal).coerceAtLeast(1L)

        var doneBase = 0L
        fun onBytes(fileBytes: Long) {
            val fraction = ((doneBase + fileBytes).toFloat() / grandTotal).coerceIn(0f, 1f)
            // Whole-percent granularity: StateFlow skips equal values, so the UI
            // recomposes ~100 times per download instead of every 64 KB.
            val percent = (fraction * 100).toInt()
            DownloadEvents.progress.value = entry.video.url to percent / 100f
            notify(entry.video.title, percent)
        }

        fetchToFile(playback.videoUrl, videoFile, videoTotal, entry.video.url, ::onBytes)
        doneBase = videoTotal
        playback.audioUrl?.let { fetchToFile(it, audioFile, audioTotal, entry.video.url, ::onBytes) }

        // Small extras, best-effort: the poster for the offline grid, and any
        // subtitle tracks (they're tiny) with an index the player can read back.
        entry.video.thumbnailUrl?.let { thumb ->
            runCatching { simpleGet(thumb, File(dir, "thumb.jpg")) }
        }
        runCatching {
            val lines = playback.subtitles.take(6).mapIndexedNotNull { i, sub ->
                val ext = when (sub.mimeType) {
                    "text/vtt" -> "vtt"
                    "application/ttml+xml" -> "ttml"
                    "application/x-subrip" -> "srt"
                    else -> return@mapIndexedNotNull null
                }
                val f = File(dir, "sub_$i.$ext")
                runCatching { simpleGet(sub.url, f) }.getOrNull() ?: return@mapIndexedNotNull null
                listOf(
                    f.name, sub.mimeType, sub.languageTag,
                    sub.name.replace('\t', ' '), sub.autoGenerated.toString()
                ).joinToString("\t")
            }
            if (lines.isNotEmpty()) File(dir, "subs.tsv").writeText(lines.joinToString("\n"))
        }
    }

    /** Total size via a 1-byte ranged probe (Content-Range carries the full length). */
    private fun remoteLength(url: String): Long {
        Http.client.newCall(
            Request.Builder().url(url).header("Range", "bytes=0-0").build()
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} probing size" }
            resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { return it }
            return resp.header("Content-Length")?.toLongOrNull()
                ?: error("Server did not report a size")
        }
    }

    /** Ranged, resumable fetch: .part grows chunk by chunk, then becomes [dest]. */
    private fun fetchToFile(
        url: String,
        dest: File,
        total: Long,
        entryUrl: String,
        onBytes: (Long) -> Unit
    ) {
        if (dest.exists()) return
        val part = File(dest.path + ".part")
        var offset = part.length()
        while (offset < total) {
            // Parent may have removed the video from settings mid-download.
            if (store.entries().none {
                    it.video.url == entryUrl && it.status == DownloadStatus.DOWNLOADING
                }) throw AbortedException()
            val end = minOf(offset + CHUNK_BYTES, total) - 1
            Http.client.newCall(
                Request.Builder().url(url).header("Range", "bytes=$offset-$end").build()
            ).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code} at byte $offset" }
                val body = resp.body ?: error("Empty response at byte $offset")
                FileOutputStream(part, true).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    val input = body.byteStream()
                    var chunkRead = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        chunkRead += n
                        onBytes(offset + chunkRead)
                    }
                }
            }
            offset = part.length()
        }
        check(part.renameTo(dest)) { "Could not finalize ${dest.name}" }
    }

    private fun simpleGet(url: String, dest: File) {
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            dest.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
        }
    }

    private var lastNotifiedPercent = -1

    private fun notify(title: String, percent: Int?) {
        if (percent != null) {
            if (percent == lastNotifiedPercent) return
            lastNotifiedPercent = percent
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(title, percent))
        }
    }

    private fun notification(title: String, percent: Int?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Saving for offline")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply { if (percent != null) setProgress(100, percent, false) }
            .build()

    private class AbortedException : Exception()
}
