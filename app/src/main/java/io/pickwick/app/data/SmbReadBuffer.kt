package io.pickwick.app.data

/** Coalesces small extractor reads into bounded sequential network reads. */
internal class SmbReadBuffer(private val capacity: Int = 1024 * 1024) {
    private val bytes = ByteArray(capacity)
    private var start = 0
    private var end = 0

    fun clear() { start = 0; end = 0 }

    fun read(target: ByteArray, offset: Int, length: Int, remaining: Long,
             fetch: (ByteArray, Int) -> Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        if (start == end) {
            start = 0
            end = fetch(bytes, minOf(capacity.toLong(), remaining).toInt())
            if (end <= 0) { end = 0; return -1 }
        }
        val count = minOf(length.toLong(), (end - start).toLong(), remaining).toInt()
        bytes.copyInto(target, offset, start, start + count)
        start += count
        return count
    }
}
