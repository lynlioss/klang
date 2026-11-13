@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** Always present in the PCM chain: toggling EQ never needs to rebuild playback. */
class EqAudioProcessor internal constructor(private val snapshot: () -> EqProcessing) : BaseAudioProcessor() {
    constructor(store: EqStore) : this({
        val settings = store.settings.value
        val route = store.runtime.value
        EqProcessing(settings.enabled, settings.resolve(route.songId, route.deviceId).gains, settings.outputTrimDb)
    })
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
        // Media3 also flushes inactive processors before/after stream configuration.
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            dsp = null; frame = DoubleArray(0); return
        }
        dsp = EqDsp(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        frame = DoubleArray(inputAudioFormat.channelCount)
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val engine = dsp ?: run {
            // Preserve audio during lifecycle transitions rather than leaving input unconsumed.
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer); output.flip(); return
        }
        val settings = snapshot()
        engine.configure(settings.enabled, settings.gains, settings.trimDb)
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

internal data class EqProcessing(val enabled: Boolean, val gains: List<Float> = FLAT_GAINS, val trimDb: Float = 0f)
