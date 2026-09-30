package io.pickwick.app

import io.pickwick.app.data.SmbReadBuffer
import org.junit.Assert.*
import org.junit.Test

class SmbReadBufferTest {
    @Test fun smallReadsShareOneFetchAndPreserveBytes() {
        val buffer = SmbReadBuffer(8)
        var calls = 0
        var position = 0
        val output = ByteArray(12)
        repeat(6) { index ->
            val count = buffer.read(output, index * 2, 2, (12 - position).toLong()) { block, size ->
                calls++
                repeat(size) { block[it] = (position + it).toByte() }
                size
            }
            position += count
        }
        assertEquals(2, calls)
        assertArrayEquals(ByteArray(12) { it.toByte() }, output)
        assertEquals(-1, buffer.read(output, 0, 2, 0) { _, _ -> error("EOF fetched") })
    }

    @Test fun clearDiscardsDataOnSeekAndHandlesShortReads() {
        val buffer = SmbReadBuffer(8)
        val output = ByteArray(2)
        buffer.read(output, 0, 1, 20) { block, _ -> block.fill(1); 3 }
        buffer.clear()
        assertEquals(2, buffer.read(output, 0, 2, 10) { block, _ -> block.fill(9); 2 })
        assertArrayEquals(byteArrayOf(9, 9), output)
        assertEquals(0, buffer.read(output, 0, 0, 10) { _, _ -> error("Empty read fetched") })
    }
}
