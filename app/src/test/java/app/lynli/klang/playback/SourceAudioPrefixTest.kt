@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SourceAudioPrefixTest {
    private class Audio(private val fail: Boolean = false) : DataSource {
        var opens = 0
        var readBytes = 0
        var closed = false
        override fun addTransferListener(listener: TransferListener) {}
        override fun open(spec: DataSpec): Long { opens++; if (fail) throw IOException("Range rejected"); return 5_000_000 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { readBytes += length; return length }
        override fun getUri(): Uri? = null
        override fun close() { closed = true }
    }
    @Test fun prefixStopsAtItsLimitEvenIfServerOffersAWholeSong() {
        val source = Audio()
        cacheAudioPrefix(source, DataSpec.Builder().setUri("https://audio.test/file").setLength(192L * 1024).build(), 192L * 1024)
        assertEquals(192 * 1024, source.readBytes)
        assertEquals(1, source.opens)
        assertTrue(source.closed)
    }
    @Test fun failedRangeNeverFallsBackToAnUnboundedDownload() {
        val source = Audio(fail = true)
        assertThrows(IOException::class.java) {
            cacheAudioPrefix(source, DataSpec.Builder().setUri("https://audio.test/file").setLength(192L * 1024).build(), 192L * 1024)
        }
        assertEquals(1, source.opens)
        assertEquals(0, source.readBytes)
        assertTrue(source.closed)
    }
}
