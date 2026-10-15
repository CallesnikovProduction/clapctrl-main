package dev.clapctrl.core.dsp

import dev.clapctrl.core.AcousticClass
import dev.clapctrl.core.AcousticFeatures

fun interface AcousticClassifier {
    fun classify(features: AcousticFeatures): Pair<AcousticClass, Double>
}

/**
 * Conservative starter rules, not a measured general-purpose sound recognizer.
 * Spectral positions are fractions of Nyquist, so no microphone sample rate is assumed.
 * Short, bright snaps and longer broadband claps overlap in reality: microphone calibration
 * should replace these rules when they fail held-out validation.
 */
class RuleBasedClassifier : AcousticClassifier {
    override fun classify(features: AcousticFeatures): Pair<AcousticClass, Double> = with(features) {
        if (vector().any { !it.isFinite() } || !rms.isFinite() || !peak.isFinite() ||
            rms <= 0.0 || peak <= 0.0 || durationMs !in 3.0..160.0 ||
            crestFactor < 1.8 || attackMs > 30.0 || bandwidth < 0.12 ||
            zeroCrossingRate < 0.08 || spectralFlux < 0.01
        ) return AcousticClass.NEGATIVE to 0.0

        if (durationMs <= 38.0 && attackMs <= 9.0 && decayMs <= 24.0 &&
            centroid >= 0.46 && highFrequencyRatio >= 0.32 && crestFactor >= 2.2
        ) {
            val brevity = ((38.0 - durationMs) / 35.0).coerceIn(0.0, 1.0)
            return AcousticClass.SNAP to (0.62 + 0.2 * brevity)
        }
        if (durationMs >= 40.0 && decayMs >= 12.0 && centroid in 0.18..0.75 &&
            highFrequencyRatio >= 0.07
        ) {
            val impulsiveness = ((crestFactor - 1.8) / 6.0).coerceIn(0.0, 1.0)
            return AcousticClass.CLAP to (0.65 + 0.2 * impulsiveness)
        }
        AcousticClass.NEGATIVE to 0.0
    }
}
