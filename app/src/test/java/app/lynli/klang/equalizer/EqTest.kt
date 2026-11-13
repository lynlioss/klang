package app.lynli.klang.equalizer

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class EqTest {
    @Test fun songOverridesDeviceAndFallsBackToDefault() {
        val bass = BUILTIN_PRESETS[1].curve()
        val vocal = BUILTIN_PRESETS[2].curve()
        val settings = EqSettings(songCurves = mapOf("song" to vocal), deviceCurves = mapOf("bt" to bass))
        assertEquals(vocal, settings.resolve("song", "bt"))
        assertEquals(bass, settings.resolve("other", "bt"))
        assertEquals(EqCurve(), settings.resolve("other", "phone"))
        val edited = settings.editActive("song", "bt", EqCurve())
        assertEquals(EqCurve(), edited.songCurves["song"])
        assertEquals(bass, edited.deviceCurves["bt"])
    }
    @Test fun persistenceRetainsAssignmentsAndCustomPresets() {
        val custom = EqPreset("custom", "My headphones", listOf(1f,2f,3f,4f,5f,6f,5f,4f,3f,2f))
        val settings = EqSettings(enabled = true, outputTrimDb = -3f, defaultCurve = custom.curve(),
            songCurves = mapOf("content://songs/1" to custom.curve()), deviceCurves = mapOf("bt:headphones" to EqCurve()),
            presets = listOf(custom))
        assertEquals(settings, EqCodec.decode(EqCodec.encode(settings)))
    }
    @Test fun corruptPersistedGainsAreClampedAndPadded() {
        val decoded = EqCodec.decode("""{"default":{"gains":[100,-100,"NaN",4]},"trim":100}""")
        assertEquals(listOf(12f, -12f, 0f, 4f, 0f,0f,0f,0f,0f,0f), decoded.defaultCurve.gains)
        assertEquals(0f, decoded.outputTrimDb)
    }
    @Test fun peakingFiltersHaveRequestedCenterGainAndUnityAtEdges() {
        for (rate in listOf(44100, 48000, 96000)) for (db in listOf(-12f, -6f, 6f, 12f)) {
            val filter = PeakFilter.create(1000.0, db, rate)
            assertEquals(db.toDouble(), 20 * log10(filter.magnitude(2 * PI * 1000 / rate)), 0.0001)
            assertEquals(1.0, filter.magnitude(0.0), 0.0001)
            assertEquals(1.0, filter.magnitude(PI), 0.0001)
        }
    }
    @Test fun bypassPreservesSamplesExactlyAndStereoDoesNotLeak() {
        val dsp = EqDsp(48000, 2)
        val frame = doubleArrayOf(0.234, -0.123)
        dsp.process(frame)
        assertArrayEquals(doubleArrayOf(0.234, -0.123), frame, 0.0)
        dsp.configure(true, BUILTIN_PRESETS[1].gains)
        repeat(6000) { i ->
            frame[0] = 0.4 * sin(2 * PI * 62.5 * i / 48000); frame[1] = 0.0
            dsp.process(frame)
            assertEquals(0.0, frame[1], 0.0)
            assertTrue(frame[0].isFinite() && abs(frame[0]) <= 1.0)
        }
    }
    @Test fun strongestBoostsRemainFiniteAtLowSampleRatesAndRapidEdits() {
        for (rate in listOf(8000, 22050, 44100, 48000, 96000)) {
            val dsp = EqDsp(rate, 2)
            val frame = DoubleArray(2)
            repeat(12000) { i ->
                if (i % 1000 == 0) dsp.configure(true, List(10) { if ((i / 1000) % 2 == 0) 12f else -12f })
                frame[0] = sin(2 * PI * 440 * i / rate); frame[1] = cos(2 * PI * 440 * i / rate)
                dsp.process(frame)
                assertTrue(frame.all { it.isFinite() && abs(it) <= 1.0 })
            }
        }
    }
    @Test fun disabledAndFlatRecoverExactBypassAfterRamp() {
        val dsp = EqDsp(48000, 1)
        val frame = doubleArrayOf(0.125)
        dsp.configure(true, BUILTIN_PRESETS[1].gains)
        repeat(2000) { frame[0] = 0.125; dsp.process(frame) }
        dsp.configure(false, BUILTIN_PRESETS[1].gains)
        repeat(2000) { frame[0] = 0.125; dsp.process(frame) }
        frame[0] = 0.125; dsp.process(frame)
        assertEquals(0.125, frame[0], 0.0)
    }
    @Test fun outputTrimMatchesDbAndBoostHasAutomaticHeadroom() {
        val frame = doubleArrayOf(0.25)
        val trim = EqDsp(48000, 1)
        trim.configure(true, FLAT_GAINS, -6f)
        repeat(2000) { frame[0] = 0.25; trim.process(frame) }
        assertEquals(0.25 * 10.0.pow(-6.0 / 20), frame[0], 0.00001)
        fun amplitude(hz: Double): Double {
            val dsp = EqDsp(48000, 1)
            dsp.configure(true, FLAT_GAINS.mapIndexed { index, _ -> if (index == 5) 6f else 0f })
            var energy = 0.0
            repeat(48000) { sample ->
                frame[0] = 0.1 * sin(2 * PI * hz * sample / 48000)
                dsp.process(frame)
                if (sample >= 24000) energy += frame[0] * frame[0]
            }
            return sqrt(energy / 24000 * 2)
        }
        val center = amplitude(1000.0)
        val low = amplitude(31.25)
        assertEquals(6.0, 20 * log10(center / low), 0.15)
        assertTrue("Boosted center stays below unprocessed level", center < 0.1)
    }

}
