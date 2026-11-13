package app.lynli.klang.equalizer

import kotlin.math.*

/** RBJ peaking biquads. Pure DSP so frequency response and stereo isolation can be verified. */
internal data class PeakFilter(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun magnitude(omega: Double): Double {
        val c1 = cos(omega); val s1 = sin(omega); val c2 = cos(2 * omega); val s2 = sin(2 * omega)
        return hypot(b0 + b1 * c1 + b2 * c2, b1 * s1 + b2 * s2) /
            hypot(1 + a1 * c1 + a2 * c2, a1 * s1 + a2 * s2)
    }
    companion object {
        fun create(frequency: Double, db: Float, sampleRate: Int): PeakFilter {
            val values = DoubleArray(5)
            write(frequency, db, sampleRate, values, 0)
            return PeakFilter(values[0], values[1], values[2], values[3], values[4])
        }
        fun write(frequency: Double, db: Float, sampleRate: Int, values: DoubleArray, offset: Int) {
            if (frequency >= sampleRate / 2.0 || db == 0f) {
                values[offset] = 1.0
                for (i in 1..4) values[offset + i] = 0.0
                return
            }
            val a = 10.0.pow(db.coerceIn(-12f, 12f) / 40.0)
            val omega = 2 * PI * frequency / sampleRate
            val alpha = sin(omega) / (2 * 1.4) // Octave-spaced graphic EQ bands.
            val a0 = 1 + alpha / a
            values[offset] = (1 + alpha * a) / a0
            values[offset + 1] = -2 * cos(omega) / a0
            values[offset + 2] = (1 - alpha * a) / a0
            values[offset + 3] = values[offset + 1]
            values[offset + 4] = (1 - alpha / a) / a0
        }
    }
}

internal class EqDsp(private val sampleRate: Int, private val channels: Int) {
    private val coefficients = DoubleArray(EQ_FREQUENCIES.size * 5)
    private val targetCoefficients = DoubleArray(coefficients.size)
    private val z1 = Array(channels) { DoubleArray(EQ_FREQUENCIES.size) }
    private val z2 = Array(channels) { DoubleArray(EQ_FREQUENCIES.size) }
    private val responseGrid = Array(257) { i ->
        val hz = 20.0 * (min(20000.0, sampleRate * 0.49) / 20.0).pow(i / 256.0)
        val omega = 2 * PI * hz / sampleRate
        doubleArrayOf(cos(omega), sin(omega), cos(2 * omega), sin(2 * omega))
    }
    init { updateCoefficients(FLAT_GAINS, coefficients) }
    private fun updateCoefficients(gains: List<Float>, output: DoubleArray) {
        for (i in EQ_FREQUENCIES.indices) PeakFilter.write(EQ_FREQUENCIES[i], gains[i], sampleRate, output, i * 5)
    }
    private var preamp = 1.0
    private var targetPreamp = 1.0
    private val activeGains = FloatArray(EQ_FREQUENCIES.size)
    private var targetGains = FLAT_GAINS
    private var trimDb = 0f
    private var remaining = 0
    private var bypass = true
    fun configure(enabled: Boolean, gains: List<Float>, outputTrimDb: Float = 0f) {
        val next = if (enabled) gains else FLAT_GAINS
        val nextTrim = if (enabled) outputTrimDb.coerceIn(-12f, 0f) else 0f
        if (next == targetGains && nextTrim == trimDb) return
        trimDb = nextTrim
        targetGains = next
        remaining = (sampleRate * 0.025).toInt().coerceAtLeast(1)
        updateCoefficients(next, targetCoefficients)
        // Precomputed response grid avoids expensive trigonometry during audio buffer updates.
        var peak = 1.0
        for (point in responseGrid) {
            var response = 1.0
            for (band in EQ_FREQUENCIES.indices) {
                val j = band * 5
                val c = targetCoefficients
                response *= hypot(c[j] + c[j + 1] * point[0] + c[j + 2] * point[2], c[j + 1] * point[1] + c[j + 2] * point[3]) /
                    hypot(1 + c[j + 3] * point[0] + c[j + 4] * point[2], c[j + 3] * point[1] + c[j + 4] * point[3])
            }
            peak = max(peak, response)
        }
        targetPreamp = 10.0.pow(trimDb / 20.0) * (if (next.all { it == 0f }) 1.0 else 1.0 / max(1.0, peak * 10.0.pow(1.0 / 20.0)))
        bypass = false
    }
    fun isBypassed() = bypass
    fun process(frame: DoubleArray) {
        if (remaining > 0) {
            // Interpolate gains (stable filters), rather than crossing unstable coefficient sets.
            for (i in activeGains.indices) activeGains[i] += (targetGains[i] - activeGains[i]) / remaining
            preamp += (targetPreamp - preamp) / remaining
            // Coefficients update every 32 frames to keep CPU usage low on a phone.
            if (remaining % 32 == 0 || remaining == 1) {
                for (i in EQ_FREQUENCIES.indices) PeakFilter.write(EQ_FREQUENCIES[i], activeGains[i], sampleRate, coefficients, i * 5)
            }
            remaining--
            if (remaining == 0 && targetGains.all { it == 0f } && trimDb == 0f) {
                bypass = true
                z1.forEach { it.fill(0.0) }; z2.forEach { it.fill(0.0) }
            }
        }
        if (bypass) return
        for (channel in 0 until channels) {
            var value = frame[channel] * preamp
            for (band in EQ_FREQUENCIES.indices) {
                val j = band * 5
                val output = coefficients[j] * value + z1[channel][band]
                z1[channel][band] = coefficients[j + 1] * value - coefficients[j + 3] * output + z2[channel][band]
                z2[channel][band] = coefficients[j + 2] * value - coefficients[j + 4] * output
                value = output
            }
            frame[channel] = value.coerceIn(-1.0, 1.0)
        }
    }
}
