package io.pickwick.app.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Pure string plumbing for [ChunkedStreamDataSource], kept off android.net.Uri
 * so the JVM unit tests can exercise the range math directly.
 */
internal object StreamChunker {
    /** How much one request asks for. Small enough that the server's
     * per-connection throttle never gets a long window to bite; large enough
     * that request overhead stays negligible at video bitrates. */
    const val CHUNK_BYTES = 2L * 1024 * 1024

    fun isGoogleVideo(host: String?): Boolean =
        host != null && (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))

    /** Total stream size the extractor URL already carries (`clen`), or null. */
    fun clenOf(url: String): Long? =
        queryValue(url, "clen")?.toLongOrNull()?.takeIf { it > 0 }

    /**
     * Rewrites the stream URL to fetch exactly [start]..[endInclusive] via the
     * `range` query parameter (the form the servers serve at full speed —
     * a `Range` header on an un-parameterised URL is what gets throttled),
     * with `rn` numbering requests the way the official clients do.
     */
    fun chunkUrl(url: String, start: Long, endInclusive: Long, rn: Long): String {
        val q = url.indexOf('?')
        if (q < 0) return "$url?range=$start-$endInclusive&rn=$rn"
        val kept = url.substring(q + 1).split('&').filter {
            val key = it.substringBefore('=')
            key != "range" && key != "rn"
        }
        val params = (kept + listOf("range=$start-$endInclusive", "rn=$rn"))
            .joinToString("&")
        return url.substring(0, q + 1) + params
    }

    private fun queryValue(url: String, key: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        return url.substring(q + 1).split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Defeats googlevideo's progressive-download throttling by splitting the
 * transfer into fixed-size `range=`-parameter requests, the way the official
 * clients fetch media. A single long-lived request gets rate-limited to about
 * playback speed, which is why the player could never build up a buffer and
 * stalled on every Wi-Fi dip.
 *
 * Measured on a Chromecast over a 433 Mbps 5 GHz link, one ~2:40 video:
 * unwrapped, playback stalled at start and the buffer then crept ahead at
 * only ~1.5x realtime; wrapped, the entire video was buffered 19 s in. The
 * link was never the constraint in either run.
 *
 * Only kicks in for googlevideo hosts whose URL carries `clen` (the total
 * length — needed to know where the last chunk ends without risking a 416).
 * Everything else — downloads on disk, SAF files, subtitle tracks — passes
 * straight through to the upstream source untouched.
 */
@UnstableApi
class ChunkedStreamDataSource(
    private val upstreamFactory: DataSource.Factory
) : DataSource {

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = ChunkedStreamDataSource(upstreamFactory)
    }

    private val listeners = mutableListOf<TransferListener>()

    /** Delegate for non-chunked opens; null while in chunked mode. */
    private var passthrough: DataSource? = null

    // Chunked-mode state. Positions are absolute offsets into the stream.
    private var chunked: DataSource? = null
    private var originalUri: Uri? = null
    private var originalUrl: String = ""
    private var position = 0L
    private var endExclusive = 0L
    private var chunkRemaining = 0L
    private var requestNumber = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        passthrough?.addTransferListener(transferListener)
        chunked?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val clen = if (StreamChunker.isGoogleVideo(dataSpec.uri.host))
            StreamChunker.clenOf(url) else null
        if (clen == null) {
            android.util.Log.i(
                "Pickwick", "stream[] passthrough host=${dataSpec.uri.host}"
            )
            val source = upstreamFactory.createDataSource()
            listeners.forEach(source::addTransferListener)
            passthrough = source
            return source.open(dataSpec)
        }
        originalUri = dataSpec.uri
        originalUrl = url
        position = dataSpec.position
        endExclusive = if (dataSpec.length != C.LENGTH_UNSET.toLong())
            (dataSpec.position + dataSpec.length).coerceAtMost(clen) else clen
        chunkRemaining = 0
        android.util.Log.i(
            "Pickwick",
            "stream[] chunked clen=$clen from=$position to=$endExclusive"
        )
        return endExclusive - position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        passthrough?.let { return it.read(buffer, offset, length) }
        if (position >= endExclusive) return C.RESULT_END_OF_INPUT
        if (chunkRemaining <= 0) openNextChunk()
        val want = minOf(length.toLong(), chunkRemaining).toInt()
        val read = chunked!!.read(buffer, offset, want)
        if (read == C.RESULT_END_OF_INPUT) {
            // Server closed the chunk early. Resume from wherever we actually
            // are; openNextChunk throws if that makes no forward progress.
            chunkRemaining = 0
            return read(buffer, offset, length)
        }
        position += read
        chunkRemaining -= read
        return read
    }

    private fun openNextChunk() {
        chunked?.let { runCatching { it.close() } }
        val start = position
        if (start >= endExclusive) throw java.io.EOFException()
        val end = (start + StreamChunker.CHUNK_BYTES).coerceAtMost(endExclusive) - 1
        val url = StreamChunker.chunkUrl(originalUrl, start, end, requestNumber++)
        val source = upstreamFactory.createDataSource()
        listeners.forEach(source::addTransferListener)
        chunked = source
        val opened = source.open(
            DataSpec.Builder().setUri(Uri.parse(url)).build()
        )
        chunkRemaining = if (opened == C.LENGTH_UNSET.toLong()) end - start + 1 else opened
        if (chunkRemaining <= 0) throw java.io.EOFException()
    }

    override fun getUri(): Uri? = passthrough?.uri ?: originalUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        passthrough?.responseHeaders ?: chunked?.responseHeaders ?: emptyMap()

    override fun close() {
        passthrough?.let { passthrough = null; it.close() }
        chunked?.let { chunked = null; it.close() }
        originalUri = null
    }
}
