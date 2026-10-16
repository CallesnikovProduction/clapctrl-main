package dev.clapctrl.core.calibration

import dev.clapctrl.core.AcousticClass
import dev.clapctrl.core.dsp.AcousticClassifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.math.exp
import kotlin.random.Random

class DatasetEvaluatorTest {
    @TempDir lateinit var directory: Path

    private fun silence(filename: String) {
        val format = AudioFormat(16000f, 16, 1, true, false)
        AudioInputStream(ByteArrayInputStream(ByteArray(32000)), format, 16000).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, directory.resolve(filename).toFile())
        }
    }

    @Test fun `actual WAV decoding and pipeline replay produce a negative silence result`() {
        silence("silence.wav")
        val manifest = directory.resolve("dataset.json")
        Files.writeString(manifest, """[{"file":"silence.wav","label":"NEGATIVE"}]""")
        val report = DatasetEvaluator.evaluate(manifest)
        assertEquals(1, report.metrics.totalSamples)
        assertEquals(1, report.metrics.confusion["NEGATIVE"]!!["NEGATIVE"])
        assertEquals(0, report.recordings.single().candidateCount)
        assertNull(report.metrics.latencyMs)
    }

    @Test fun `missed positive recording counts against recall`() {
        silence("missing clap.wav")
        val manifest = directory.resolve("dataset.csv")
        Files.writeString(manifest, "file,label,eventEndMs\n\"missing clap.wav\",CLAP,500\n")
        val report = DatasetEvaluator.evaluate(manifest)
        assertEquals(1, report.metrics.confusion["CLAP"]!!["NEGATIVE"])
        assertEquals(0.0, report.metrics.perClass["CLAP"]!!.recall)
        assertNull(report.metrics.latencyMs)
    }

    @Test fun `missing recordings and invalid manifests fail explicitly`() {
        val manifest = directory.resolve("dataset.json")
        Files.writeString(manifest, """[{"file":"missing.wav","label":"SNAP"}]""")
        assertFailsWith<IllegalArgumentException> { DatasetEvaluator.evaluate(manifest) }
        Files.writeString(manifest, "[]")
        assertFailsWith<IllegalArgumentException> { DatasetEvaluator.evaluate(manifest) }
    }

    @Test fun `recording replay measures confidence rejection as missed recognition`() {
        transient("snap.wav")
        val manifest = directory.resolve("dataset.json")
        Files.writeString(manifest, """[{"file":"snap.wav","label":"SNAP"}]""")
        val classifier = AcousticClassifier { AcousticClass.SNAP to 0.7 }
        val rejected = DatasetEvaluator.evaluate(manifest, classifier)
        assertEquals(1, rejected.recordings.single().candidateCount)
        assertEquals("NEGATIVE", rejected.recordings.single().predicted)
        assertEquals(0.0, rejected.metrics.perClass.getValue("SNAP").recall)
        val accepted = DatasetEvaluator.evaluate(manifest, classifier,
            mapOf(AcousticClass.CLAP to 0.75, AcousticClass.SNAP to 0.6))
        assertEquals("SNAP", accepted.recordings.single().predicted)
        assertEquals(1.0, accepted.metrics.perClass.getValue("SNAP").recall)
    }

    private fun transient(filename: String) {
        val rate = 16_000
        val bytes = ByteArray(rate * 2)
        val random = Random(42)
        for (index in 0 until rate / 20) {
            val sample = ((random.nextDouble() * 2 - 1) * 0.7 * exp(-index.toDouble() / (rate * 0.01)) * 32767).toInt()
            val offset = (rate / 5 + index) * 2
            bytes[offset] = sample.toByte()
            bytes[offset + 1] = (sample shr 8).toByte()
        }
        val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
        AudioInputStream(ByteArrayInputStream(bytes), format, rate.toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, directory.resolve(filename).toFile())
        }
    }
}
