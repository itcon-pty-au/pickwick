package io.pickwick.app.data

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One bounded connection per operation; SMBJ negotiates SMB2/3, never SMB1. */
class SmbConnection(val catalog: SmbCatalog) : Closeable {
    private val client = SMBClient(SmbConfig.builder()
        .withTimeout(15, TimeUnit.SECONDS).withSoTimeout(15, TimeUnit.SECONDS).build())
    private val connection = try { client.connect(catalog.host, catalog.port) } catch (e: Exception) { client.close(); throw e }
    val session = try {
        connection.authenticate(if (catalog.guest) AuthenticationContext.anonymous()
            else AuthenticationContext(catalog.username, catalog.password.toCharArray(), catalog.domain))
    } catch (e: Exception) { connection.close(); client.close(); throw e }
    private var diskShare: DiskShare? = null
    val share: DiskShare get() = diskShare ?: (session.connectShare(catalog.share) as DiskShare).also { diskShare = it }

    fun open(relative: String): com.hierynomus.smbj.share.File = share.openFile(
        SmbPaths.join(catalog.root, relative).replace('/', '\\'),
        setOf(AccessMask.GENERIC_READ), setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
        SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
        setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
    )
    override fun close() {
        runCatching { diskShare?.close() }; runCatching { session.close() }
        runCatching { connection.close() }; runCatching { client.close() }
    }
}

data class NetworkFile(val name: String, val path: String, val directory: Boolean,
    val size: Long = 0, val modified: Long = 0, val duration: Long = 0, val thumbnail: String? = null)

class SmbLibrary(private val context: Context) {
    private val cache by lazy { File(context.filesDir, "network_library").apply { mkdirs() } }
    private fun key(c: SmbCatalog, path: String) = LocalLibrary.idFor("${c.id}|${c.host}|${c.port}|${c.share}|${c.root}|$path")
    private fun snapshot(c: SmbCatalog, path: String) = File(cache, key(c, path) + ".json")

    fun isFresh(c: SmbCatalog, path: String): Boolean {
        val file = snapshot(c, path)
        return file.exists() && System.currentTimeMillis() - file.lastModified() in 0 until 15 * 60 * 1000L
    }

    /** Only reads previously visited folders; never crawls the share for cover art. */
    fun cover(c: SmbCatalog, path: String): String? {
        val pending = java.util.ArrayDeque<String>().apply { add(path) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty() && visited.size < 32) {
            val next = pending.removeFirst()
            if (!visited.add(next)) continue
            val entries = cached(c, next)
            entries.firstNotNullOfOrNull { it.thumbnail }?.let { return it }
            entries.filter { it.directory }.forEach { pending.add(it.path) }
        }
        return null
    }

    fun cached(c: SmbCatalog, path: String): List<NetworkFile> = runCatching {
        val file = snapshot(c, path)
        val legacy = File(context.cacheDir, "network_library/${key(c, path)}.json")
        val a = JSONArray((if (file.exists()) file else legacy).readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            NetworkFile(o.getString("name"), o.getString("path"), o.getBoolean("directory"),
                o.optLong("size"), o.optLong("modified"), o.optLong("duration"),
                o.optString("thumbnail").takeIf { it.endsWith(NetworkThumbnailPolicy.SUFFIX) }?.let { uri ->
                    runCatching {
                    val old = File(java.net.URI(uri))
                    val local = File(cache, old.name)
                    if (!old.exists() && !local.exists()) null else {
                        if (old != local && !local.exists()) old.copyTo(local)
                        local.toURI().toString()
                    }
                    }.getOrNull()
                })
        } }
    }.getOrDefault(emptyList())

    fun list(c: SmbCatalog, path: String): List<NetworkFile> {
        val old = cached(c, path).associateBy { it.path }
        val result = SmbConnection(c).use { smb ->
            smb.share.openDirectory(SmbPaths.join(c.root, path).replace('/', '\\'),
                setOf(AccessMask.GENERIC_READ), setOf(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                setOf(SMB2CreateOptions.FILE_DIRECTORY_FILE, SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)).use { directory ->
            val entries = directory.asSequence().take(10001).toList()
            if (entries.size > 10000) throw IOException("This folder has too many entries. Select a smaller folder.")
            entries.asSequence()
                .filter { it.fileName != "." && it.fileName != ".." }
                .filter { it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value == 0L }
                .map { f ->
                    val dir = f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    val p = SmbPaths.join(path, f.fileName)
                    val previous = old[p]?.takeIf { it.size == f.endOfFile && it.modified == f.lastWriteTime.toEpochMillis() }
                    NetworkFile(f.fileName, p, dir, f.endOfFile, f.lastWriteTime.toEpochMillis(),
                        previous?.duration ?: 0, previous?.thumbnail)
                }.filter { it.directory || isVideo(it.name) }.toList()
            }
        }
        if (result.size > 10000) throw IOException("This folder has too many entries. Select a smaller folder.")
        val sorted = result.sortedWith { a, b ->
            if (a.directory != b.directory) if (a.directory) -1 else 1
            else SmbPaths.naturalOrder.compare(a.name, b.name)
        }
        save(c, path, sorted)
        return sorted
    }

    fun metadata(c: SmbCatalog, f: NetworkFile): NetworkFile {
        if (f.directory || (f.duration > 0 && f.thumbnail != null)) return f
        return SmbConnection(c).use { metadata(c, f, it) }
    }

    fun metadata(c: SmbCatalog, f: NetworkFile, smb: SmbConnection): NetworkFile {
        if (f.directory || (f.duration > 0 && f.thumbnail != null)) return f
        return smb.open(f.path).use { file ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(object : MediaDataSource() {
                    private val block = ByteArray(256 * 1024)
                    private var start = -1L
                    private var count = 0
                    override fun getSize() = f.size
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        if (size == 0) return 0
                        if (position >= f.size) return -1
                        if (position < start || position >= start + count) {
                            start = position
                            count = file.read(block, position, 0, minOf(block.size.toLong(), f.size - position).toInt())
                            if (count <= 0) return -1
                        }
                        val index = (position - start).toInt()
                        val length = minOf(size, count - index)
                        block.copyInto(buffer, offset, index, index + length)
                        return length
                    }
                    override fun close() = Unit
                })
                val duration = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000
                val thumbnail = File(cache, key(c, "${f.path}|${f.size}|${f.modified}") + NetworkThumbnailPolicy.SUFFIX)
                // Skip recurring intros; retry only when a candidate is unusable.
                var frame: android.graphics.Bitmap? = null
                for (time in NetworkThumbnailPolicy.timesUs(duration)) {
                    val candidate = runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= 27)
                            retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                        else null
                    }.getOrNull() ?: continue
                    val pixels = IntArray(32 * 18) { index ->
                        candidate.getPixel((index % 32) * candidate.width / 32,
                            (index / 32) * candidate.height / 18)
                    }
                    if (NetworkThumbnailPolicy.isNearBlack(pixels)) candidate.recycle()
                    else { frame = candidate; break }
                }
                frame?.let { bitmap ->
                    try { thumbnail.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, it) } }
                    finally { bitmap.recycle() }
                }
                f.copy(duration = duration.takeIf { it > 0 } ?: f.duration,
                    thumbnail = thumbnail.takeIf { it.exists() }?.toURI()?.toString() ?: f.thumbnail)
            } finally { retriever.release() }
        }
    }

    fun save(c: SmbCatalog, path: String, files: List<NetworkFile>) {
        val a = JSONArray()
        files.forEach { f -> a.put(JSONObject().put("name", f.name).put("path", f.path)
            .put("directory", f.directory).put("size", f.size).put("modified", f.modified)
            .put("duration", f.duration).put("thumbnail", f.thumbnail.orEmpty())) }
        val atomic = android.util.AtomicFile(snapshot(c, path))
        synchronized(CACHE_LOCK) {
            val stream = atomic.startWrite()
            try { stream.write(a.toString().toByteArray()); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
    }

    companion object {
        private val CACHE_LOCK = Any()
        fun shares(c: SmbCatalog): List<String> = SmbConnection(c).use { smb ->
            val transport = com.rapid7.client.dcerpc.transport.SMBTransportFactories.SRVSVC.getTransport(smb.session)
            com.rapid7.client.dcerpc.mssrvs.ServerService(transport).shares0
                .map { it.netName }.filterNot { it.endsWith("$") }.sortedWith(SmbPaths.naturalOrder)
        }
        fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in
            setOf("mp4", "mkv", "m4v", "avi", "webm", "mov", "ts", "m2ts", "mpeg", "mpg", "3gp")
        fun message(e: Throwable): String {
            val text = e.message.orEmpty()
            return when {
                text.contains("too many entries", true) -> "This folder is too large. Select a smaller folder."
                text.contains("LOGON", true) || text.contains("ACCESS_DENIED", true) -> "Sign-in failed or access denied. Check the share account and permissions."
                text.contains("BAD_NETWORK_NAME", true) -> "Share not found. Check the share name."
                text.contains("OBJECT_NAME_NOT_FOUND", true) || text.contains("OBJECT_PATH_NOT_FOUND", true) -> "This folder or video is no longer available."
                else -> "Cannot reach this SMB share. Check the address, Wi-Fi and SMB2/3 sharing settings."
            }
        }
    }
}
