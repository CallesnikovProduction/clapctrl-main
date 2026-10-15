package dev.clapctrl.core.dsp

import dev.clapctrl.core.AcousticClass
import dev.clapctrl.core.AcousticFeatures
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureExtractorTest {
    @Test fun `silence and empty signals yield finite zero features`() {
        for (samples in listOf(FloatArray(0), FloatArray(1000))) {
            val features = FeatureExtractor(48_000).extract(samples)
            assertTrue(features.vector().all { it == 0.0 })
            assertEquals(0.0, features.rms)
        }
    }

    @Test fun `RMS crest and zero crossings describe a sinusoid`() {
        val samples = tone(48_000, 1000.0, 0.5)
        val result = FeatureExtractor(48_000).extract(samples)
        assertEquals(0.5 / kotlin.math.sqrt(2.0), result.rms, 0.005)
        assertEquals(kotlin.math.sqrt(2.0), result.crestFactor, 0.02)
        assertEquals(2000.0 / 48_000, result.zeroCrossingRate, 0.003)
        assertEquals(1000.0 / 24_000, result.centroid, 0.01)
        assertTrue(result.bandwidth < 0.02)
    }

    @Test fun `frequency positions scale consistently across capture sample rates`() {
        for (rate in listOf(8_000, 16_000, 44_100, 48_000, 96_000)) {
            val result = FeatureExtractor(rate).extract(tone(rate, rate * 0.15, 0.4))
            assertEquals(0.3, result.centroid, 0.02)
            assertTrue(result.highFrequencyRatio < 0.05)
        }
    }

    @Test fun `high frequency energy and bandwidth are computed spectrally`() {
        val rate = 48_000
        val bright = FeatureExtractor(rate).extract(tone(rate, 15_000.0, 0.4))
        val random = Random(5)
        val noise = FeatureExtractor(rate).extract(FloatArray(rate / 10) { (random.nextDouble() * 0.8 - 0.4).toFloat() })
        assertTrue(bright.highFrequencyRatio > 0.95)
        assertTrue(noise.bandwidth > 0.2)
        assertTrue(noise.centroid in 0.4..0.6)
        assertTrue(noise.spectralFlux > 0.0)
    }

    @Test fun `duration attack and decay exclude silent padding`() {
        val rate = 48_000
        val samples = FloatArray(rate / 5)
        for (index in 0 until rate / 20) {
            val envelope = if (index < rate / 200) index.toDouble() / (rate / 200) else exp(-(index - rate / 200).toDouble() / (rate / 100))
            samples[index + rate / 20] = (envelope * sin(index * 2.0 * PI * 3000 / rate)).toFloat()
        }
        val result = FeatureExtractor(rate).extract(samples)
        assertTrue(result.durationMs in 20.0..40.0)
        assertTrue(result.attackMs in 3.0..7.0)
        assertTrue(result.decayMs in 17.0..25.0)
    }

    @Test fun `shape features remain stable when input gain changes`() {
        val signal = tone(48_000, 5000.0, 0.1)
        val quiet = FeatureExtractor(48_000).extract(signal)
        val loud = FeatureExtractor(48_000).extract(FloatArray(signal.size) { signal[it] * 3 })
        assertEquals(quiet.centroid, loud.centroid, 1e-5)
        assertEquals(quiet.crestFactor, loud.crestFactor, 1e-5)
        assertEquals(quiet.rms * 3, loud.rms, 1e-5)
    }

    @Test fun `invalid samples are sanitized instead of poisoning the pipeline`() {
        val features = FeatureExtractor(16_000).extract(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -5f, 5f))
        assertTrue(features.vector().all { it.isFinite() })
        assertTrue(features.peak <= 1.0)
    }

    @Test fun `classifier rejects tones long sounds and invalid feature vectors`() {
        val classifier = RuleBasedClassifier()
        assertEquals(AcousticClass.NEGATIVE, classifier.classify(FeatureExtractor(48_000).extract(tone(48_000, 1000.0, 0.4))).first)
        assertEquals(AcousticClass.NEGATIVE, classifier.classify(example().copy(durationMs = 300.0)).first)
        assertEquals(AcousticClass.NEGATIVE, classifier.classify(example().copy(centroid = Double.NaN)).first)
    }

    @Test fun `starter rules distinguish short bright and longer broad transients`() {
        val classifier = RuleBasedClassifier()
        val clap = classifier.classify(example())
        val snap = classifier.classify(example().copy(durationMs = 14.0, attackMs = 2.0, decayMs = 10.0, centroid = 0.55, highFrequencyRatio = 0.65))
        assertEquals(AcousticClass.CLAP, clap.first)
        assertEquals(AcousticClass.SNAP, snap.first)
        assertTrue(clap.second in 0.0..1.0 && snap.second in 0.0..1.0)
    }

    private fun example() = AcousticFeatures(80.0, 0.1, 0.5, 5.0, 0.45, 0.25, 0.55, 0.4, 3.0, 60.0, 0.5)

    private fun tone(rate: Int, frequency: Double, amplitude: Double): FloatArray =
        FloatArray(rate / 10) { (amplitude * sin(2.0 * PI * frequency * it / rate)).toFloat() }
}
