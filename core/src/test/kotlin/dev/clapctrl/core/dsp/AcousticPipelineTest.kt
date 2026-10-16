package dev.clapctrl.core.dsp

import dev.clapctrl.core.AcousticCandidate
import dev.clapctrl.core.AcousticClass
import dev.clapctrl.core.AudioFrame
import dev.clapctrl.core.Detection
import dev.clapctrl.core.DetectorSettings
import dev.clapctrl.core.Gesture
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** These fixtures test DSP mechanics only. They do not measure real clap/snap accuracy. */
class AcousticPipelineTest {
    private val alwaysClap = AcousticClassifier { AcousticClass.CLAP to 0.9 }

    @Test fun `silence never generates candidates or gestures`() {
        val result = run(AcousticPipeline(), FloatArray(48_000 * 2))
        assertTrue(result.first.isEmpty() && result.second.isEmpty())
    }

    @Test fun `stable low level background noise stays below onset`() {
        val random = Random(8)
        val result = run(AcousticPipeline(), FloatArray(48_000) { (random.nextDouble() * 0.001 - 0.0005).toFloat() })
        assertTrue(result.first.isEmpty())
    }

    @Test fun `stationary audible noise adapts without being split into repeated candidates`() {
        val random = Random(18)
        val result = run(AcousticPipeline(), FloatArray(48_000 * 3) { (random.nextDouble() * 0.08 - 0.04).toFloat() })
        assertTrue(result.first.isEmpty())
        assertTrue(result.second.isEmpty())
    }

    @Test fun `production rules detect synthetic broad claps and short bright snaps`() {
        // These shaped noise examples exercise rule wiring, not recognition accuracy.
        val clap = run(AcousticPipeline(), shapedSignal(48_000, 1200, 120, 0.030, 200, 600))
        assertEquals(listOf(AcousticClass.CLAP, AcousticClass.CLAP), clap.first.map { it.classification })
        assertEquals(Gesture.DOUBLE_CLAP, clap.second.single().gesture)
        val snap = run(AcousticPipeline(), shapedSignal(48_000, 800, 20, 0.003, 200))
        assertEquals(AcousticClass.SNAP, snap.first.single().classification)
        assertEquals(Gesture.FINGER_SNAP, snap.second.single().gesture)
    }

    @Test fun `single transient yields candidate but no double clap`() {
        val result = run(AcousticPipeline(classifier = alwaysClap), signal(48_000, 1000, 200))
        assertEquals(1, result.first.size)
        assertTrue(result.second.isEmpty())
        assertTrue(result.first.single().waveform.size <= 48_000 * 0.18)
    }

    @Test fun `two distinct transients produce one double clap`() {
        val result = run(AcousticPipeline(classifier = alwaysClap), signal(48_000, 1200, 200, 500))
        assertEquals(2, result.first.size)
        assertEquals(Gesture.DOUBLE_CLAP, result.second.single().gesture)
        assertEquals(500L, result.second.single().timestampMs)
    }

    @Test fun `weak raw candidate cannot consume the first strong clap of a later pair`() {
        val confidence = listOf(0.2, 0.9, 0.9).iterator()
        val pipeline = AcousticPipeline(classifier = AcousticClassifier { AcousticClass.CLAP to confidence.next() })
        val result = run(pipeline, signal(48_000, 1200, 200, 500, 800),
            confidenceThresholds = mapOf(AcousticClass.CLAP to 0.75, AcousticClass.SNAP to 0.75))
        assertEquals(listOf(0.2, 0.9, 0.9), result.first.map { it.confidence })
        assertTrue(result.first.all { it.classification == AcousticClass.CLAP })
        assertEquals(Gesture.DOUBLE_CLAP, result.second.single().gesture)
        assertEquals(800L, result.second.single().timestampMs)
        assertEquals(0.9, result.second.single().confidence)
    }

    @Test fun `minimum interval remains usable after a longer first transient`() {
        val result = run(AcousticPipeline(classifier = alwaysClap), signal(48_000, 1000, 200, 350))
        assertEquals(2, result.first.size)
        assertEquals(1, result.second.size)
    }

    @Test fun `duplicate impulses are suppressed by refractory timing`() {
        val result = run(AcousticPipeline(classifier = alwaysClap), signal(48_000, 1000, 200, 250))
        assertTrue(result.second.isEmpty())
    }

    @Test fun `sustained noisy burst is discarded and detector recovers`() {
        val rate = 48_000
        val random = Random(22)
        val samples = signal(rate, 1800, 1200, 1500)
        for (index in rate / 5 until rate * 4 / 5) samples[index] = (random.nextDouble() * 0.8 - 0.4).toFloat()
        val result = run(AcousticPipeline(classifier = alwaysClap), samples)
        assertTrue(result.first.all { it.timestampMs >= 1200 })
        assertEquals(1, result.second.size)
    }

    @Test fun `sample rates and irregular frame sizes preserve timing`() {
        for (rate in listOf(8_000, 16_000, 44_100, 48_000, 96_000)) {
            val result = run(AcousticPipeline(classifier = alwaysClap), signal(rate, 1200, 200, 500), rate, chunk = 137)
            assertEquals(2, result.first.size, "rate=$rate")
            assertEquals(1, result.second.size, "rate=$rate")
            assertEquals(500L, result.second.single().timestampMs, "rate=$rate")
        }
    }

    @Test fun `sample rate change resets incomplete double clap sequence`() {
        val pipeline = AcousticPipeline(classifier = alwaysClap)
        run(pipeline, signal(48_000, 400, 200))
        val result = run(pipeline, signal(16_000, 400, 100), 16_000, startMs = 400)
        assertEquals(1, result.first.size)
        assertTrue(result.second.isEmpty())
    }

    @Test fun `pause reset cannot pair clap across sessions`() {
        val pipeline = AcousticPipeline(classifier = alwaysClap)
        run(pipeline, signal(48_000, 400, 200))
        pipeline.reset()
        val result = run(pipeline, signal(48_000, 400, 200))
        assertTrue(result.second.isEmpty())
    }

    @Test fun `recognition reset preserves noise estimate but breaks an unfinished clap pair`() {
        val pipeline = AcousticPipeline(classifier = alwaysClap)
        run(pipeline, signal(48_000, 400, 200))
        val before = pipeline.process(AudioFrame(FloatArray(0), 48_000, 400)).noiseFloor
        pipeline.resetRecognition()
        assertEquals(before, pipeline.process(AudioFrame(FloatArray(0), 48_000, 400)).noiseFloor)
        val result = run(pipeline, signal(48_000, 400, 100), startMs = 400)
        assertEquals(1, result.first.size)
        assertTrue(result.second.isEmpty())
    }

    @Test fun `recognition reset discards active transient and its tail without resetting stream time`() {
        val pipeline = AcousticPipeline(classifier = alwaysClap)
        val samples = signal(48_000, 1000, 200, 500)
        run(pipeline, samples.copyOfRange(0, 48_000 * 215 / 1000), chunk = 137)
        pipeline.resetRecognition()
        val result = run(pipeline, samples.copyOfRange(48_000 * 215 / 1000, samples.size), startMs = 215)
        assertEquals(listOf(500L), result.first.map { it.timestampMs })
        assertTrue(result.second.isEmpty())
    }

    @Test fun `timestamp gap clears previous clap and stale frames are ignored`() {
        val pipeline = AcousticPipeline(classifier = alwaysClap)
        run(pipeline, signal(48_000, 400, 200))
        assertEquals(null, pipeline.process(AudioFrame(FloatArray(480) { 0.9f }, 48_000, 100)).candidate)
        val result = run(pipeline, signal(48_000, 400, 100), startMs = 600)
        assertTrue(result.second.isEmpty())
    }

    @Test fun `snap classification maps to immediate finger snap gesture`() {
        val result = run(AcousticPipeline(classifier = AcousticClassifier { AcousticClass.SNAP to 0.82 }), signal(48_000, 700, 200))
        assertEquals(Gesture.FINGER_SNAP, result.second.single().gesture)
    }

    @Test fun `noise floor adapts and invalid samples stay finite`() {
        val pipeline = AcousticPipeline()
        var output = pipeline.process(AudioFrame(FloatArray(480), 48_000, 0))
        for (time in 10L..2000L step 10) output = pipeline.process(AudioFrame(FloatArray(480) { 0.0008f }, 48_000, time))
        assertTrue(output.noiseFloor in 0.0006..0.001)
        val invalid = pipeline.process(AudioFrame(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 4f), 48_000, 2010))
        assertTrue(invalid.rms.isFinite() && invalid.rms <= 1.0)
    }

    @Test fun `invalid sensitivity and sample rate fail fast`() {
        assertFailsWith<IllegalArgumentException> { AcousticPipeline(DetectorSettings(sensitivity = Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { AcousticPipeline().process(AudioFrame(FloatArray(5), 0, 0)) }
        assertFailsWith<IllegalArgumentException> {
            AcousticPipeline().process(AudioFrame(FloatArray(480), 48_000, 0), mapOf(AcousticClass.CLAP to Double.NaN))
        }
    }

    private fun signal(rate: Int, durationMs: Int, vararg onsetMs: Int): FloatArray {
        return shapedSignal(rate, durationMs, 70, 0.018, *onsetMs)
    }

    private fun shapedSignal(rate: Int, durationMs: Int, eventMs: Int, decaySeconds: Double, vararg onsetMs: Int): FloatArray {
        val samples = FloatArray(rate * durationMs / 1000)
        val random = Random(42)
        for (onset in onsetMs) {
            val start = onset * rate / 1000
            for (index in 0 until rate * eventMs / 1000) {
                val envelope = exp(-index.toDouble() / (rate * decaySeconds))
                samples[start + index] += ((random.nextDouble() * 2 - 1) * 0.8 * envelope).toFloat()
            }
        }
        return samples
    }

    private fun run(pipeline: AcousticPipeline, samples: FloatArray, rate: Int = 48_000, chunk: Int = rate / 100, startMs: Long = 0,
                    confidenceThresholds: Map<AcousticClass, Double> = emptyMap()): Pair<List<AcousticCandidate>, List<Detection>> {
        val candidates = ArrayList<AcousticCandidate>()
        val detections = ArrayList<Detection>()
        var offset = 0
        while (offset < samples.size) {
            val end = (offset + chunk).coerceAtMost(samples.size)
            val output = pipeline.process(AudioFrame(samples.copyOfRange(offset, end), rate, startMs + offset * 1000L / rate), confidenceThresholds)
            output.candidate?.let(candidates::add)
            detections += output.detections
            offset = end
        }
        return candidates to detections
    }
}
