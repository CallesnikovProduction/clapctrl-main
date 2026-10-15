package dev.clapctrl.core.calibration

import dev.clapctrl.core.AcousticClass
import dev.clapctrl.core.AcousticFeatures
import dev.clapctrl.core.configuration.atomicWriteJson
import dev.clapctrl.core.configuration.readBoundedJson
import dev.clapctrl.core.configuration.storageJson
import dev.clapctrl.core.dsp.AcousticClassifier
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import kotlin.math.exp
import kotlin.math.sqrt

const val PIPELINE_SIGNATURE = "clapctrl-features-v1-nine-normalized"
internal const val FEATURE_COUNT = 9

object AcousticClassSerializer : KSerializer<AcousticClass> {
    override val descriptor = PrimitiveSerialDescriptor("AcousticClass", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: AcousticClass) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): AcousticClass = AcousticClass.valueOf(decoder.decodeString())
}

/** Feature metadata only. The candidate waveform remains transient and is never serialized. */
@Serializable
data class CalibrationSample(
    @Serializable(with = AcousticClassSerializer::class) val label: AcousticClass,
    val vector: List<Double>,
    val timestampMs: Long = 0,
)

@Serializable
data class ClassPrototype(
    @Serializable(with = AcousticClassSerializer::class) val label: AcousticClass,
    val centroid: List<Double>,
)

@Serializable
data class CalibrationProfile(
    val schemaVersion: Int = 1,
    val pipelineSignature: String = PIPELINE_SIGNATURE,
    val microphoneId: String,
    val name: String,
    val means: List<Double>,
    val scales: List<Double>,
    val prototypes: List<ClassPrototype>,
    val sampleCounts: Map<String, Int>,
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported calibration schema $schemaVersion" }
        require(pipelineSignature == PIPELINE_SIGNATURE) { "Calibration uses an incompatible feature pipeline" }
        require(microphoneId.isNotBlank() && microphoneId.length <= 4096 && '\u0000' !in microphoneId) { "Invalid microphone association" }
        require(name.isNotBlank() && name.length <= 256) { "Profile name must contain 1–256 characters" }
        validateVector(means)
        validateVector(scales)
        require(scales.all { it >= 1e-6 }) { "Feature scales must be positive" }
        require(prototypes.map { it.label }.toSet() == AcousticClass.entries.toSet() && prototypes.size == AcousticClass.entries.size) {
            "Profile needs exactly one prototype for CLAP, SNAP and NEGATIVE"
        }
        prototypes.forEach { validateVector(it.centroid) }
        require(sampleCounts.keys == AcousticClass.entries.map { it.name }.toSet() && sampleCounts.values.all { it in 1..10_000 }) {
            "Profile needs valid sample counts for every class"
        }
    }
}

internal fun validateVector(vector: List<Double>) {
    require(vector.size == FEATURE_COUNT && vector.all { it.isFinite() && kotlin.math.abs(it) <= 1e9 }) { "Invalid feature vector" }
}

class CalibrationProfileStore(private val path: Path) {
    fun load(): CalibrationProfile = try {
        storageJson.decodeFromString<CalibrationProfile>(readBoundedJson(path)).also { it.validate() }
    } catch (e: Exception) {
        throw IllegalArgumentException("Cannot import calibration profile at $path: ${e.message}", e)
    }

    fun save(profile: CalibrationProfile) {
        profile.validate()
        atomicWriteJson(path, storageJson.encodeToString(profile))
    }
}

/** Standardized nearest centroid with an explicit negative class and an out-of-distribution gate. */
class CalibrationClassifier(private val profile: CalibrationProfile) : AcousticClassifier {
    init { profile.validate() }

    override fun classify(features: AcousticFeatures): Pair<AcousticClass, Double> = classifyVector(features.vector())

    internal fun classifyVector(vector: List<Double>): Pair<AcousticClass, Double> {
        validateVector(vector)
        val normalized = vector.indices.map { (vector[it] - profile.means[it]) / profile.scales[it] }
        val distances = profile.prototypes.map { prototype ->
            prototype to sqrt(normalized.indices.sumOf { i ->
                val difference = normalized[i] - prototype.centroid[i]
                difference * difference
            } / FEATURE_COUNT)
        }
        val nearest = distances.minBy { it.second }
        if (nearest.second > 5.0) return AcousticClass.NEGATIVE to 1.0
        // This is a relative distance score, not a calibrated probability of correctness.
        val weights = distances.map { exp(-2.0 * (it.second - nearest.second)) }
        val confidence = weights[distances.indexOf(nearest)] / weights.sum()
        return nearest.first.label to confidence
    }
}
