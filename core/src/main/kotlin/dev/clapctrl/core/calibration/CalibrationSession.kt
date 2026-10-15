package dev.clapctrl.core.calibration

import dev.clapctrl.core.AcousticCandidate
import dev.clapctrl.core.AcousticClass
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.serialization.Serializable

class CalibrationSession(
    val microphoneId: String,
    val profileName: String,
    val minimumSamplesPerClass: Int = 5,
) {
    private val accepted = mutableListOf<CalibrationSample>()
    val samples: List<CalibrationSample> get() = accepted.toList()

    init { require(minimumSamplesPerClass in 2..1000) { "Sample minimum must be between 2 and 1000" } }

    fun accept(label: AcousticClass, candidate: AcousticCandidate): CalibrationSample {
        require(accepted.size < 10_000) { "Calibration session is full" }
        val sample = CalibrationSample(label, candidate.features.vector().toList(), candidate.timestampMs)
        validateVector(sample.vector)
        accepted += sample
        return sample
    }

    fun remove(index: Int) { accepted.removeAt(index) }
    fun clear() { accepted.clear() }
    fun train(): CalibrationProfile = CalibrationTrainer.train(samples, microphoneId, profileName, minimumSamplesPerClass)
    fun evaluate(folds: Int = 5): EvaluationReport = CalibrationEvaluation.crossValidate(samples, folds)
}

object CalibrationTrainer {
    fun train(
        samples: List<CalibrationSample>,
        microphoneId: String,
        name: String,
        minimumSamplesPerClass: Int = 5,
    ): CalibrationProfile {
        require(minimumSamplesPerClass in 1..1000)
        require(samples.size <= 10_000)
        samples.forEach { validateVector(it.vector) }
        val groups = AcousticClass.entries.associateWith { label -> samples.filter { it.label == label } }
        require(groups.values.all { it.size >= minimumSamplesPerClass }) {
            "Need at least $minimumSamplesPerClass accepted samples of each class, including NEGATIVE"
        }
        val means = (0 until FEATURE_COUNT).map { i -> samples.map { it.vector[i] }.average() }
        val scales = means.indices.map { i ->
            sqrt(samples.sumOf { val difference = it.vector[i] - means[i]; difference * difference } / samples.size).coerceAtLeast(0.03)
        }
        return CalibrationProfile(
            microphoneId = microphoneId,
            name = name,
            means = means,
            scales = scales,
            prototypes = groups.map { (label, group) ->
                ClassPrototype(label, means.indices.map { i -> group.map { (it.vector[i] - means[i]) / scales[i] }.average() })
            },
            sampleCounts = groups.mapKeys { it.key.name }.mapValues { it.value.size },
        ).also { it.validate() }
    }
}

@Serializable
data class ClassMetrics(val precision: Double?, val recall: Double?, val falsePositiveRate: Double?, val support: Int)

@Serializable
data class EvaluationReport(
    val totalSamples: Int,
    val confusion: Map<String, Map<String, Int>>,
    val perClass: Map<String, ClassMetrics>,
    val accuracy: Double?,
    val folds: Int = 0,
    val latencyMs: Double? = null,
    val note: String = "Feature classification only; gesture latency is not measured.",
)

object CalibrationEvaluation {
    /** Every fold learns scaling and centroids exclusively from its training subset. */
    fun crossValidate(samples: List<CalibrationSample>, requestedFolds: Int = 5): EvaluationReport {
        require(requestedFolds in 2..20) { "Cross-validation needs 2–20 folds" }
        val groups = AcousticClass.entries.associateWith { label -> samples.filter { it.label == label }.shuffled(Random(42 + label.ordinal)) }
        val minimumCount = groups.values.minOf { it.size }
        require(minimumCount >= 2) { "Validation needs at least two separate samples of every class" }
        val folds = minOf(requestedFolds, minimumCount)
        val results = mutableListOf<Pair<AcousticClass, AcousticClass>>()
        repeat(folds) { fold ->
            val training = groups.values.flatMap { group -> group.filterIndexed { index, _ -> index % folds != fold } }
            val validation = groups.values.flatMap { group -> group.filterIndexed { index, _ -> index % folds == fold } }
            val classifier = CalibrationClassifier(CalibrationTrainer.train(training, "validation", "held-out fold", 1))
            validation.forEach { results += it.label to classifier.classifyVector(it.vector).first }
        }
        return summarize(results, folds)
    }

    fun summarize(results: List<Pair<AcousticClass, AcousticClass>>, folds: Int = 0): EvaluationReport {
        val confusion = AcousticClass.entries.associate { actual ->
            actual.name to AcousticClass.entries.associate { predicted ->
                predicted.name to results.count { it.first == actual && it.second == predicted }
            }
        }
        val metrics = AcousticClass.entries.associate { label ->
            val tp = results.count { it.first == label && it.second == label }
            val fp = results.count { it.first != label && it.second == label }
            val fn = results.count { it.first == label && it.second != label }
            val tn = results.size - tp - fp - fn
            fun ratio(numerator: Int, denominator: Int): Double? = if (denominator == 0) null else numerator.toDouble() / denominator
            label.name to ClassMetrics(ratio(tp, tp + fp), ratio(tp, tp + fn), ratio(fp, fp + tn), tp + fn)
        }
        return EvaluationReport(results.size, confusion, metrics,
            if (results.isEmpty()) null else results.count { it.first == it.second }.toDouble() / results.size, folds)
    }
}
