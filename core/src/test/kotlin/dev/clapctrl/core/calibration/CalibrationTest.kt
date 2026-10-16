package dev.clapctrl.core.calibration

import dev.clapctrl.core.AcousticCandidate
import dev.clapctrl.core.AcousticClass
import dev.clapctrl.core.AcousticFeatures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationTest {
    @TempDir lateinit var directory: Path
    private fun samples(count: Int = 8) = AcousticClass.entries.flatMap { label ->
        List(count) { index -> CalibrationSample(label, List(9) { label.ordinal * 3.0 + index * 0.001 }, index.toLong()) }
    }
    private fun train() = CalibrationTrainer.train(samples(), "microphone", "Living room")

    @Test fun `profile round trip stores no waveforms or recording samples`() {
        val file = directory.resolve("profile.json")
        val profile = train()
        CalibrationProfileStore(file).save(profile)
        assertEquals(profile, CalibrationProfileStore(file).load())
        val text = Files.readString(file)
        assertFalse(text.contains("waveform"))
        assertFalse(text.contains("timestampMs"))
    }

    @Test fun `profiles reject future schema incompatible pipeline and malformed shape`() {
        val profile = train()
        assertFailsWith<IllegalArgumentException> { profile.copy(schemaVersion = 2).validate() }
        assertFailsWith<IllegalArgumentException> { profile.copy(pipelineSignature = "unknown").validate() }
        assertFailsWith<IllegalArgumentException> { profile.copy(scales = List(9) { 0.0 }).validate() }
        assertFailsWith<IllegalArgumentException> { profile.copy(means = listOf(Double.NaN)).validate() }
        assertFailsWith<IllegalArgumentException> { profile.copy(prototypes = profile.prototypes.dropLast(1)).validate() }
        assertFailsWith<IllegalArgumentException> { profile.copy(sampleCounts = emptyMap()).validate() }
    }

    @Test fun `invalid profile export and corrupt import preserve existing files`() {
        val file = directory.resolve("profile.json")
        val store = CalibrationProfileStore(file)
        store.save(train())
        val original = Files.readString(file)
        assertFailsWith<IllegalArgumentException> { store.save(train().copy(schemaVersion = 3)) }
        assertEquals(original, Files.readString(file))
        Files.writeString(file, "{corrupt}")
        assertFailsWith<IllegalArgumentException> { store.load() }
        assertEquals("{corrupt}", Files.readString(file))
    }

    @Test fun `insufficient or missing negatives prevent training and validation`() {
        assertFailsWith<IllegalArgumentException> { CalibrationTrainer.train(samples(1), "mic", "test") }
        assertFailsWith<IllegalArgumentException> { CalibrationTrainer.train(samples().filter { it.label != AcousticClass.NEGATIVE }, "mic", "test") }
        assertFailsWith<IllegalArgumentException> { CalibrationEvaluation.crossValidate(samples(1)) }
    }

    @Test fun `cross validation evaluates each separate event exactly once`() {
        val report = CalibrationEvaluation.crossValidate(samples())
        assertEquals(24, report.totalSamples)
        assertEquals(24, report.confusion.values.sumOf { it.values.sum() })
        assertEquals(5, report.folds)
        assertEquals(1.0, report.accuracy)
        assertNull(report.latencyMs)
        assertTrue(report.perClass.values.all { it.precision == 1.0 && it.recall == 1.0 && it.falsePositiveRate == 0.0 })
    }

    @Test fun `fold training cannot memorize unique event features`() {
        // Every class has the same feature distribution; training on validation samples would be misleading.
        val indistinguishable = AcousticClass.entries.flatMap { label ->
            List(6) { index -> CalibrationSample(label, List(9) { index.toDouble() }) }
        }
        val report = CalibrationEvaluation.crossValidate(indistinguishable, 3)
        assertTrue(report.accuracy!! < 0.7)
    }

    @Test fun `normalization derives exclusively from supplied training subset`() {
        val training = samples(3)
        val profile = CalibrationTrainer.train(training, "mic", "test", 1)
        assertEquals(training.map { it.vector[0] }.average(), profile.means.first())
        val withheld = CalibrationSample(AcousticClass.CLAP, List(9) { 1000.0 })
        assertTrue(CalibrationTrainer.train(training + withheld, "mic", "test", 1).means.first() > profile.means.first() + 10)
    }

    @Test fun `confusion metrics include false positives and undefined denominators`() {
        val report = CalibrationEvaluation.summarize(listOf(
            AcousticClass.CLAP to AcousticClass.CLAP,
            AcousticClass.CLAP to AcousticClass.SNAP,
            AcousticClass.NEGATIVE to AcousticClass.CLAP,
            AcousticClass.NEGATIVE to AcousticClass.NEGATIVE,
        ))
        assertEquals(1, report.confusion["CLAP"]!!["SNAP"])
        assertEquals(0.5, report.perClass["CLAP"]!!.precision)
        assertEquals(0.5, report.perClass["CLAP"]!!.recall)
        assertEquals(0.5, report.perClass["CLAP"]!!.falsePositiveRate)
        assertNull(report.perClass["SNAP"]!!.recall)
        assertNull(CalibrationEvaluation.summarize(emptyList()).accuracy)
    }

    @Test fun `session accepts metadata independently of mutable preview waveform`() {
        val session = CalibrationSession("mic", "test", 2)
        val waveform = floatArrayOf(0.1f, 0.5f)
        val features = AcousticFeatures(20.0, 0.1, 0.5, 5.0, 0.5, 0.2, 0.4, 0.3, 2.0, 10.0, 0.5)
        session.accept(AcousticClass.CLAP, AcousticCandidate(100L, features, waveform, 48000, AcousticClass.SNAP, 0.6))
        waveform.fill(0f)
        assertEquals(features.vector(), session.samples.single().vector)
        assertEquals(AcousticClass.CLAP, session.samples.single().label)
        session.remove(0)
        assertTrue(session.samples.isEmpty())
    }

    @Test fun `out of distribution input is negative`() {
        val classifier = CalibrationClassifier(train())
        assertEquals(AcousticClass.NEGATIVE, classifier.classifyVector(List(9) { 1000.0 }).first)
    }

    @Test fun `classes separated in one feature exceed deployed confidence at their prototypes`() {
        // Regression: absolute-distance softmax gave only about 0.53–0.61 for these exact
        // prototypes, despite perfect separation, so default production triggers rejected all.
        val training = narrowSamples(List(6) { 0.0 })
        val classifier = CalibrationClassifier(CalibrationTrainer.train(training, "mic", "one informative feature"))
        AcousticClass.entries.forEach { label ->
            val (predicted, confidence) = classifier.classifyVector(oneFeature(label.ordinal.toDouble()))
            assertEquals(label, predicted)
            assertEquals(1.0, confidence, 1e-12)
        }
        val heldOut = CalibrationEvaluation.crossValidate(narrowSamples(listOf(-0.02, -0.01, 0.0, 0.01, 0.02, 0.03)))
        assertEquals(1.0, heldOut.accuracy)
    }

    @Test fun `ambiguous classification has low margin and is rejected at deployed threshold`() {
        val classifier = CalibrationClassifier(CalibrationTrainer.train(narrowSamples(List(6) { 0.0 }), "mic", "test"))
        val (predicted, confidence) = classifier.classifyVector(oneFeature(0.45))
        assertEquals(AcousticClass.CLAP, predicted)
        assertTrue(confidence < 0.25)
        assertEquals(AcousticClass.NEGATIVE, CalibrationEvaluation.deployedClass(predicted, confidence, CalibrationEvaluation.defaultConfidenceThresholds))
    }

    @Test fun `identical prototypes have zero confidence instead of an arbitrary positive winner`() {
        val training = AcousticClass.entries.flatMap { label -> List(6) { CalibrationSample(label, List(9) { 0.5 }) } }
        val classifier = CalibrationClassifier(CalibrationTrainer.train(training, "mic", "test"))
        val (_, confidence) = classifier.classifyVector(List(9) { 0.5 })
        assertEquals(0.0, confidence)
        assertEquals(AcousticClass.NEGATIVE, CalibrationEvaluation.deployedClass(AcousticClass.CLAP, confidence, CalibrationEvaluation.defaultConfidenceThresholds))
    }

    @Test fun `held out validation counts confidence rejected positives as missed events`() {
        val training = narrowSamples(listOf(-0.3, -0.2, -0.1, 0.1, 0.2, 0.3))
        val argmax = CalibrationEvaluation.crossValidate(training, 3, mapOf(AcousticClass.CLAP to 0.0, AcousticClass.SNAP to 0.0))
        val deployed = CalibrationEvaluation.crossValidate(training, 3)
        assertEquals(1.0, argmax.accuracy)
        assertTrue(deployed.accuracy!! < argmax.accuracy!!)
        assertTrue(deployed.confusion.getValue("CLAP").getValue("NEGATIVE") > 0)
        assertTrue(deployed.confusion.getValue("SNAP").getValue("NEGATIVE") > 0)
    }

    @Test fun `validation honors independent clap and snap trigger thresholds`() {
        val report = CalibrationEvaluation.crossValidate(
            narrowSamples(listOf(-0.3, -0.2, -0.1, 0.1, 0.2, 0.3)), 3,
            mapOf(AcousticClass.CLAP to 0.99, AcousticClass.SNAP to 0.0),
        )
        assertEquals(0.0, report.perClass.getValue("CLAP").recall)
        assertEquals(1.0, report.perClass.getValue("SNAP").recall)
        assertTrue(report.note.contains("CLAP 0.99, SNAP 0.0"))
    }

    @Test fun `invalid or missing deployed thresholds fail explicitly`() {
        assertFailsWith<IllegalArgumentException> { CalibrationEvaluation.crossValidate(samples(), confidenceThresholds = emptyMap()) }
        assertFailsWith<IllegalArgumentException> { CalibrationEvaluation.crossValidate(samples(), confidenceThresholds = mapOf(AcousticClass.CLAP to Double.NaN, AcousticClass.SNAP to 0.75)) }
    }

    private fun oneFeature(value: Double): List<Double> = List(9) { if (it == 0) value else 0.5 }
    private fun narrowSamples(jitter: List<Double>) = AcousticClass.entries.flatMap { label ->
        jitter.mapIndexed { index, variation -> CalibrationSample(label, oneFeature(label.ordinal + variation), index.toLong()) }
    }
}
