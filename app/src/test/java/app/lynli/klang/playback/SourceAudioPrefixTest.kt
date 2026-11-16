@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SourceAudioPrefixTest {
    private class Audio(private val fail: Boolean = false) {
        var opens = 0
        var readBytes = 0
        var closed = false
        fun open() { opens++; if (fail) throw IOException("Range rejected") }
        fun read(buffer: ByteArray, offset: Int, length: Int): Int { readBytes += length; return length }
        fun close() { closed = true }
    }
    @Test fun prefixStopsAtItsLimitEvenIfServerOffersAWholeSong() {
        val source = Audio()
        cacheAudioPrefix(source::open, source::read, source::close, 192L * 1024)
        assertEquals(192 * 1024, source.readBytes)
        assertEquals(1, source.opens)
        assertTrue(source.closed)
    }
    @Test fun failedRangeNeverFallsBackToAnUnboundedDownload() {
        val source = Audio(fail = true)
        assertThrows(IOException::class.java) {
            cacheAudioPrefix(source::open, source::read, source::close, 192L * 1024)
        }
        assertEquals(1, source.opens)
        assertEquals(0, source.readBytes)
        assertTrue(source.closed)
    }
}
