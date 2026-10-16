package dev.clapctrl.core.dsp

import dev.clapctrl.core.AcousticCandidate
import dev.clapctrl.core.AcousticClass
import dev.clapctrl.core.AudioFrame
import dev.clapctrl.core.Detection
import dev.clapctrl.core.DetectorSettings
import dev.clapctrl.core.PipelineOutput
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Single-thread-owned, bounded streaming detector. Frames carry the time of their first sample.
 * Analysis uses 10 ms blocks, independent of capture read size. There is no continuous recording.
 * Long bursts are rejected; a new transient requires a quiet release and a refractory interval.
 */
class AcousticPipeline(
    private val settings: DetectorSettings = DetectorSettings(),
    private val classifier: AcousticClassifier = RuleBasedClassifier(),
) {
    init { require(settings.sensitivity.isFinite() && settings.sensitivity in 0.0..1.0) }
    private val recognizer = TemporalGestureRecognizer(settings)
    private var sampleRate = 0
    private var block = FloatArray(0)
    private var event = FloatArray(0)
    private var spectrum: SpectrumAnalyzer? = null
    private var extractor: FeatureExtractor? = null
    private var blockPosition = 0
    private var streamSamples = 0L
    private var streamStartMs = 0L
    private var previousFrameMs: Long? = null
    private var expectedFrameEndMs = 0L
    private var noise = INITIAL_NOISE
    private var active = false
    private var waitingForQuiet = false
    private var quietBlocks = 0
    private var eventSize = 0
    private var eventStartMs = 0L
    private var lastOnsetMs: Long? = null
    private var skipPartialBlock = false

    fun process(frame: AudioFrame, confidenceThresholds: Map<AcousticClass, Double> = emptyMap()): PipelineOutput {
        require(frame.sampleRate in 8_000..192_000) { "Sample rate must be 8000–192000 Hz" }
        require(confidenceThresholds.values.all { it.isFinite() && it in 0.0..1.0 }) {
            "Confidence thresholds must be finite values between 0 and 1"
        }
        val frameRms = energy(frame.samples)
        val previous = previousFrameMs
        // Old frames must never pair a stale clap with a newer one or rewind detector time.
        if (previous != null && frame.timestampMs < previous) return PipelineOutput(frameRms, noise, null, emptyList())
        if (frame.samples.isEmpty()) return PipelineOutput(frameRms, noise, null, emptyList())
        if (sampleRate != frame.sampleRate || previous == null || frame.timestampMs > expectedFrameEndMs + 100L) {
            configure(frame.sampleRate, frame.timestampMs)
        }
        previousFrameMs = frame.timestampMs
        expectedFrameEndMs = frame.timestampMs + frame.samples.size * 1000L / sampleRate
        var candidate: AcousticCandidate? = null
        val detections = ArrayList<Detection>(1)
        for (sample in frame.samples) {
            block[blockPosition++] = clean(sample).toFloat()
            streamSamples++
            if (blockPosition == block.size) {
                val blockMs = streamStartMs + (streamSamples - block.size) * 1000L / sampleRate
                val completed = processBlock(blockMs)
                if (completed != null) {
                    candidate = completed
                    // Keep the raw classification for collection/diagnostics, but prevent an
                    // ineligible clap from consuming the first good clap of a genuine pair.
                    val temporalClass = if (completed.classification != AcousticClass.NEGATIVE &&
                        completed.confidence < (confidenceThresholds[completed.classification] ?: 0.0)) {
                        AcousticClass.NEGATIVE
                    } else completed.classification
                    detections += recognizer.accept(temporalClass, completed.confidence, completed.timestampMs)
                }
                blockPosition = 0
            }
        }
        // Diagnostics expose the latest candidate if a caller submits several events at once.
        // Capture/evaluation use 10 ms frames, so every event remains individually observable.
        return PipelineOutput(frameRms, noise, candidate, detections)
    }

    private fun processBlock(timestampMs: Long): AcousticCandidate? {
        val rms = energy(block)
        var peak = 0.0
        for (sample in block) peak = max(peak, abs(sample.toDouble()))
        val spectral = spectrum!!.analyze(block, block.size)
        if (skipPartialBlock) {
            skipPartialBlock = false
            return null
        }
        val onsetMultiplier = 8.0 - settings.sensitivity * 5.0
        val onsetThreshold = max(MIN_ONSET_RMS, noise * onsetMultiplier)
        val releaseThreshold = max(MIN_ONSET_RMS * 0.45, noise * 1.8)
        if (waitingForQuiet) {
            adaptNoise(rms, risingSpeed = 0.025)
            if (rms < releaseThreshold) quietBlocks++ else quietBlocks = 0
            if (quietBlocks >= RELEASE_BLOCKS) {
                waitingForQuiet = false
                quietBlocks = 0
            }
            return null
        }
        if (!active) {
            val sinceEvent = lastOnsetMs?.let { timestampMs - it } ?: Long.MAX_VALUE
            val onset = rms >= onsetThreshold && peak >= onsetThreshold * 1.5 &&
                (spectral.flux >= 0.08 || peak / max(rms, 1e-12) >= 3.0)
            if (!onset || sinceEvent < settings.refractoryMs) {
                // Do not rapidly teach the floor that an ignored clap is stationary noise.
                if (rms < onsetThreshold) adaptNoise(rms, risingSpeed = 0.025)
                return null
            }
            active = true
            eventStartMs = timestampMs
            lastOnsetMs = timestampMs
            eventSize = 0
            quietBlocks = 0
        }
        if (eventSize + block.size > event.size) {
            // Sustained music, speech and noisy bursts cannot grow the recording indefinitely.
            active = false
            waitingForQuiet = true
            quietBlocks = 0
            eventSize = 0
            return null
        }
        block.copyInto(event, eventSize)
        eventSize += block.size
        if (rms < releaseThreshold) quietBlocks++ else quietBlocks = 0
        if (quietBlocks < RELEASE_BLOCKS) return null

        active = false
        quietBlocks = 0
        val features = extractor!!.extract(event, eventSize)
        val result = classifier.classify(features)
        val confidence = if (result.second.isFinite()) result.second.coerceIn(0.0, 1.0) else 0.0
        val waveform = event.copyOf(eventSize)
        eventSize = 0
        return AcousticCandidate(eventStartMs, features, waveform, sampleRate, result.first, confidence)
    }

    private fun adaptNoise(rms: Double, risingSpeed: Double) {
        // Recover promptly when a sustained disturbance stops; otherwise its elevated floor
        // could hide genuine events for several seconds after the room is quiet again.
        val speed = if (rms > noise) risingSpeed else 0.1
        noise = (noise + (rms - noise) * speed).coerceAtLeast(0.00005)
    }

    private fun configure(rate: Int, timestampMs: Long) {
        sampleRate = rate
        block = FloatArray(rate / 100)
        // 180 ms includes release padding. Longer events are rejected, not split into gestures.
        event = FloatArray(block.size * MAX_EVENT_BLOCKS)
        spectrum = SpectrumAnalyzer(block.size)
        extractor = FeatureExtractor(rate)
        clearState()
        streamStartMs = timestampMs
    }

    private fun clearState() {
        blockPosition = 0
        streamSamples = 0
        previousFrameMs = null
        expectedFrameEndMs = 0
        noise = INITIAL_NOISE
        active = false
        waitingForQuiet = false
        quietBlocks = 0
        eventSize = 0
        lastOnsetMs = null
        skipPartialBlock = false
        spectrum?.reset()
        recognizer.reset()
    }

    /** Call when pausing, changing profiles or restarting a microphone stream. */
    fun reset() { clearState() }

    /**
     * Change a collection/recognition boundary without reopening capture or forgetting ambient
     * noise. Wait for a quiet release so an already sounding clap cannot become a new event.
     * Partial blocks retain stream alignment but cannot seed recognition across the boundary.
     */
    fun resetRecognition() {
        active = false
        waitingForQuiet = true
        quietBlocks = 0
        eventSize = 0
        lastOnsetMs = null
        skipPartialBlock = blockPosition > 0
        recognizer.reset()
    }

    private fun energy(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val value = clean(sample)
            sum += value * value
        }
        return sqrt(sum / samples.size)
    }

    companion object {
        private const val INITIAL_NOISE = 0.001
        private const val MIN_ONSET_RMS = 0.0015
        private const val RELEASE_BLOCKS = 2
        private const val MAX_EVENT_BLOCKS = 18
    }
}
