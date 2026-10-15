package dev.clapctrl.core.dsp

import dev.clapctrl.core.AcousticFeatures
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Reuses the FFT working set. An instance belongs to one processing thread. */
class FeatureExtractor(private val sampleRate: Int) {
    init { require(sampleRate in 8_000..192_000) }
    private val windowSamples = max(16, sampleRate / 50)
    private val spectrum = SpectrumAnalyzer(windowSamples)

    fun extract(samples: FloatArray, length: Int = samples.size): AcousticFeatures {
        require(length in 0..samples.size)
        if (length == 0) return emptyFeatures()
        val envelopeSize = max(1, sampleRate / 1000)
        var maximumEnvelope = 0.0
        var maximumBin = 0
        var binCount = 0
        var offset = 0
        while (offset < length) {
            val end = min(length, offset + envelopeSize)
            var energy = 0.0
            for (index in offset until end) {
                val sample = clean(samples[index])
                energy += sample * sample
            }
            val envelope = sqrt(energy / (end - offset))
            if (envelope > maximumEnvelope) {
                maximumEnvelope = envelope
                maximumBin = binCount
            }
            binCount++
            offset = end
        }
        if (maximumEnvelope < 1e-12) return emptyFeatures()

        var firstBin = -1
        var lastBin = 0
        offset = 0
        var bin = 0
        while (offset < length) {
            val end = min(length, offset + envelopeSize)
            var energy = 0.0
            for (index in offset until end) {
                val sample = clean(samples[index])
                energy += sample * sample
            }
            if (sqrt(energy / (end - offset)) >= maximumEnvelope * 0.12) {
                if (firstBin < 0) firstBin = bin
                lastBin = bin
            }
            offset = end
            bin++
        }
        val start = firstBin * envelopeSize
        val end = min(length, (lastBin + 1) * envelopeSize)
        val activeSamples = end - start
        var energy = 0.0
        var peak = 0.0
        var crossings = 0
        var previous = clean(samples[start])
        for (index in start until end) {
            val value = clean(samples[index])
            energy += value * value
            peak = max(peak, abs(value))
            if (index > start && (value >= 0.0) != (previous >= 0.0)) crossings++
            previous = value
        }
        val rms = sqrt(energy / activeSamples)
        spectrum.reset()
        var weight = 0.0
        var centroid = 0.0
        var bandwidth = 0.0
        var highRatio = 0.0
        var flux = 0.0
        var windows = 0
        offset = start
        while (offset < end) {
            val analysis = spectrum.analyze(samples, min(windowSamples, end - offset), offset)
            centroid += analysis.centroid * analysis.energy
            bandwidth += analysis.bandwidth * analysis.energy
            highRatio += analysis.highFrequencyRatio * analysis.energy
            weight += analysis.energy
            flux += analysis.flux
            windows++
            offset += windowSamples / 2
        }
        val binMs = envelopeSize * 1000.0 / sampleRate
        return AcousticFeatures(
            durationMs = activeSamples * 1000.0 / sampleRate,
            rms = rms,
            peak = peak,
            crestFactor = if (rms > 0.0) peak / rms else 0.0,
            centroid = if (weight > 0.0) centroid / weight else 0.0,
            bandwidth = if (weight > 0.0) bandwidth / weight else 0.0,
            highFrequencyRatio = if (weight > 0.0) highRatio / weight else 0.0,
            zeroCrossingRate = crossings.toDouble() / max(1, activeSamples - 1),
            attackMs = (maximumBin - firstBin).coerceAtLeast(0) * binMs,
            decayMs = (lastBin - maximumBin).coerceAtLeast(0) * binMs,
            spectralFlux = if (windows > 0) flux / windows else 0.0,
        )
    }

    private fun emptyFeatures() = AcousticFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
}

internal fun clean(sample: Float): Double = if (sample.isFinite()) sample.toDouble().coerceIn(-1.0, 1.0) else 0.0

internal data class SpectrumStats(
    val centroid: Double,
    val bandwidth: Double,
    val highFrequencyRatio: Double,
    val flux: Double,
    val energy: Double,
)

/** Iterative radix-2 FFT with a Hann window, DC removal and reusable primitive arrays. */
internal class SpectrumAnalyzer(private val windowSamples: Int) {
    private val size = Integer.highestOneBit(windowSamples - 1) shl 1
    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    private val magnitudes = DoubleArray(size / 2 + 1)
    private val previous = DoubleArray(magnitudes.size)
    private val hann = DoubleArray(windowSamples) { 0.5 - 0.5 * cos(2.0 * PI * it / max(1, windowSamples - 1)) }
    private val cosine = DoubleArray(size / 2) { cos(-2.0 * PI * it / size) }
    private val sine = DoubleArray(size / 2) { sin(-2.0 * PI * it / size) }
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(size)) }

    fun reset() { previous.fill(0.0) }

    fun analyze(samples: FloatArray, length: Int, offset: Int = 0): SpectrumStats {
        require(length in 1..windowSamples)
        real.fill(0.0)
        imaginary.fill(0.0)
        var mean = 0.0
        for (index in 0 until length) mean += clean(samples[offset + index])
        mean /= length
        // For a final partial window, use a complete shorter Hann window rather than its leading tail.
        for (index in 0 until length) {
            val window = if (length == windowSamples) hann[index]
            else 0.5 - 0.5 * cos(2.0 * PI * index / max(1, length - 1))
            real[reversed[index]] = (clean(samples[offset + index]) - mean) * window
        }
        var span = 2
        while (span <= size) {
            val half = span / 2
            val step = size / span
            var start = 0
            while (start < size) {
                for (index in 0 until half) {
                    val right = start + index + half
                    val left = start + index
                    val twiddle = index * step
                    val r = real[right] * cosine[twiddle] - imaginary[right] * sine[twiddle]
                    val i = real[right] * sine[twiddle] + imaginary[right] * cosine[twiddle]
                    real[right] = real[left] - r
                    imaginary[right] = imaginary[left] - i
                    real[left] += r
                    imaginary[left] += i
                }
                start += span
            }
            span *= 2
        }
        var magnitudeSum = 0.0
        var centroidSum = 0.0
        var squaredSum = 0.0
        var highSum = 0.0
        for (index in 1 until magnitudes.size) {
            val magnitude = sqrt(real[index] * real[index] + imaginary[index] * imaginary[index])
            magnitudes[index] = magnitude
            magnitudeSum += magnitude
            centroidSum += magnitude * index / (size / 2.0)
            squaredSum += magnitude * magnitude
            if (index >= size * 0.2) highSum += magnitude
        }
        val centroid = if (magnitudeSum > 1e-15) centroidSum / magnitudeSum else 0.0
        var variance = 0.0
        var flux = 0.0
        val norm = sqrt(squaredSum)
        for (index in 1 until magnitudes.size) {
            val distance = index / (size / 2.0) - centroid
            variance += distance * distance * magnitudes[index]
            val normalized = if (norm > 1e-15) magnitudes[index] / norm else 0.0
            val difference = (normalized - previous[index]).coerceAtLeast(0.0)
            flux += difference * difference
            previous[index] = normalized
        }
        return SpectrumStats(
            centroid,
            if (magnitudeSum > 1e-15) sqrt(variance / magnitudeSum) else 0.0,
            if (magnitudeSum > 1e-15) highSum / magnitudeSum else 0.0,
            sqrt(flux),
            squaredSum,
        )
    }
}
