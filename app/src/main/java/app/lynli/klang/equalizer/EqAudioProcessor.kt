@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** Always present in the PCM chain: toggling EQ never needs to rebuild playback. */
class EqAudioProcessor(private val store: EqStore) : BaseAudioProcessor() {
    private var dsp: EqDsp? = null
    private var frame = DoubleArray(0)
    private var random = 0x13579BDF
    private fun uniform(): Double {
        random = random xor (random shl 13)
        random = random xor (random ushr 17)
        random = random xor (random shl 5)
        return (random.toLong() and 0xffffffffL) / 4294967296.0
    }
    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(input)
        return input
    }
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        dsp = EqDsp(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        frame = DoubleArray(inputAudioFormat.channelCount)
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val engine = dsp ?: return
        val settings = store.settings.value
        val route = store.runtime.value
        engine.configure(settings.enabled, settings.resolve(route.songId, route.deviceId).gains, settings.outputTrimDb)
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (engine.isBypassed()) {
            output.put(inputBuffer); output.flip(); return
        }
        while (inputBuffer.remaining() >= frame.size * 2) {
            for (channel in frame.indices) frame[channel] = inputBuffer.short / 32768.0
            engine.process(frame)
            for (sample in frame) {
                // TPDF dither at the final 16-bit boundary, only while processing.
                val dither = if (engine.isBypassed()) 0.0 else uniform() - uniform()
                output.putShort((sample * 32768.0 + dither).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        output.flip()
    }
    override fun onReset() { dsp = null; frame = DoubleArray(0) }
}

private fun Double.roundToInt() = kotlin.math.round(this).toInt()
