package io.pickwick.app.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmbDataSource(private val context: Context, private val profileId: String?) : BaseDataSource(true) {
    private var connection: SmbConnection? = null
    private var file: com.hierynomus.smbj.share.File? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private val readBuffer = SmbReadBuffer()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        readBuffer.clear()
        try {
            val (id, relative) = SmbPaths.parse(dataSpec.uri.toString())
            val config = ConfigStore(context).load()
            val catalog = config.networkCatalogFor(id, profileId)
                ?: throw IOException("This catalog is no longer available for this profile")
            val smb = SmbConnection(catalog).also { connection = it }
            val handle = smb.open(relative).also { file = it }
            val size = handle.fileInformation.standardInformation.endOfFile
            if (dataSpec.position > size) throw IOException("Seek position is past the end of the file")
            position = dataSpec.position
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - position
                else minOf(dataSpec.length, size - position)
            uri = dataSpec.uri
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) { close(); throw IOException(SmbLibrary.message(e), e) }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            val count = readBuffer.read(buffer, offset, length, remaining) { block, size ->
                file!!.read(block, position, 0, size)
            }
            if (count <= 0) throw IOException("The network file ended unexpectedly")
            position += count; remaining -= count; bytesTransferred(count)
            return count
        } catch (e: Exception) { throw IOException("Network connection interrupted. Try playing again.", e) }
    }

    override fun getUri() = uri
    override fun close() {
        readBuffer.clear()
        runCatching { file?.close() }; file = null
        connection?.close(); connection = null; uri = null
        if (opened) { opened = false; transferEnded() }
    }

    class Factory(private val context: Context, private val profileId: String?, private val fallback: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            private var source: DataSource? = null
            private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
            override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) { listeners += listener }
            override fun open(spec: DataSpec): Long {
                val next = if (SmbPaths.isNetwork(spec.uri.toString())) SmbDataSource(context, profileId) else fallback.createDataSource()
                source = next; listeners.forEach(next::addTransferListener)
                return next.open(spec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = source!!.read(buffer, offset, length)
            override fun getUri() = source?.uri
            override fun getResponseHeaders() = source?.responseHeaders.orEmpty()
            override fun close() { source?.close(); source = null }
        }
    }
}
