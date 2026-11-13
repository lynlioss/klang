@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EqAudioProcessorTest {
    private fun pcm() = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).apply {
        listOf<Short>(1234, -2345, 0, 32767, -32768, 777, 200, -100).forEach { putShort(it) }
        flip()
    }
    @Test fun coldStartWithEqDisabledCopiesPcmWithoutNeedingToggle() {
        val processor = EqAudioProcessor { EqProcessing(false) }
        // Sink resets/flushes all processors, including those not configured yet.
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = pcm()
        val expected = ByteArray(input.remaining()).apply { input.duplicate().get(this) }
        processor.queueInput(input)
        assertFalse(input.hasRemaining())
        val output = processor.output
        val actual = ByteArray(output.remaining()).apply { output.get(this) }
        assertArrayEquals(expected, actual)
        processor.queueEndOfStream()
        assertTrue(processor.isEnded)
    }
    @Test fun emptyPipelineInputDoesNotDiscardBufferedAudio() {
        val processor = EqAudioProcessor { EqProcessing(false) }
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(pcm())
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertEquals(16, processor.output.remaining())
    }
    @Test fun trackTransitionAndEnabledBypassCanStartImmediately() {
        var config = EqProcessing(true, BUILTIN_PRESETS[1].gains)
        val processor = EqAudioProcessor { config }
        for (rate in listOf(44100, 48000, 22050)) {
            processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.queueInput(pcm())
            assertEquals(16, processor.output.remaining())
            config = EqProcessing(false)
            processor.reset()
        }
    }
    @Test fun disabledProcessorWorksInsideMedia3PipelineFromColdStart() {
        val processor = EqAudioProcessor { EqProcessing(false) }
        val pipeline = androidx.media3.common.audio.AudioProcessingPipeline(
            com.google.common.collect.ImmutableList.of(processor))
        pipeline.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = pcm()
        pipeline.queueInput(input)
        assertFalse(input.hasRemaining())
        val output = pipeline.output
        assertEquals(16, output.remaining())
        output.position(output.limit())
        pipeline.queueEndOfStream()
        pipeline.output
        assertTrue(pipeline.isEnded)
    }

}
