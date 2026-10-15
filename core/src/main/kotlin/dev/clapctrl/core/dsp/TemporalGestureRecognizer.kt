package dev.clapctrl.core.dsp

import dev.clapctrl.core.AcousticCandidate
import dev.clapctrl.core.AcousticClass
import dev.clapctrl.core.Detection
import dev.clapctrl.core.DetectorSettings
import dev.clapctrl.core.Gesture
import kotlin.math.min

/** Consumes completed, distinct transients. Action cooldown belongs to the trigger dispatcher. */
class TemporalGestureRecognizer(private val settings: DetectorSettings = DetectorSettings()) {
    init {
        require(settings.doubleClapMinMs >= 1)
        require(settings.doubleClapMaxMs >= settings.doubleClapMinMs)
        require(settings.refractoryMs >= 0)
    }

    private var lastEventMs: Long? = null
    private var lastObservedMs: Long? = null
    private var pendingClap: Pair<Long, Double>? = null

    fun accept(candidate: AcousticCandidate): List<Detection> =
        accept(candidate.classification, candidate.confidence, candidate.timestampMs)

    fun accept(classification: AcousticClass, confidence: Double, timestampMs: Long): List<Detection> {
        if (lastObservedMs?.let { timestampMs <= it } == true) return emptyList()
        lastObservedMs = timestampMs
        val previous = lastEventMs
        if (pendingClap?.let { timestampMs - it.first > settings.doubleClapMaxMs } == true) pendingClap = null
        if (classification == AcousticClass.NEGATIVE || !confidence.isFinite() || confidence <= 0.0) {
            // An intervening unrelated transient breaks a double-clap sequence.
            pendingClap = null
            return emptyList()
        }
        if (previous != null && timestampMs - previous < settings.refractoryMs) return emptyList()
        val boundedConfidence = confidence.coerceIn(0.0, 1.0)
        if (classification == AcousticClass.SNAP) {
            lastEventMs = timestampMs
            pendingClap = null
            return listOf(Detection(Gesture.FINGER_SNAP, boundedConfidence, timestampMs))
        }
        val first = pendingClap
        if (first == null) {
            lastEventMs = timestampMs
            pendingClap = timestampMs to boundedConfidence
            return emptyList()
        }
        val interval = timestampMs - first.first
        if (interval < settings.doubleClapMinMs) {
            // Keep the earlier onset: a room echo must not move the matching window.
            return emptyList()
        }
        pendingClap = null
        lastEventMs = timestampMs
        return listOf(Detection(Gesture.DOUBLE_CLAP, min(first.second, boundedConfidence), timestampMs))
    }

    fun reset() {
        lastEventMs = null
        lastObservedMs = null
        pendingClap = null
    }
}
