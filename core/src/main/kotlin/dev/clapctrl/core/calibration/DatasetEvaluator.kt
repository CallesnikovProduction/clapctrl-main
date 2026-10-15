package dev.clapctrl.core.calibration

import dev.clapctrl.core.AcousticClass
import dev.clapctrl.core.AudioFrame
import dev.clapctrl.core.dsp.AcousticClassifier
import dev.clapctrl.core.dsp.AcousticPipeline
import dev.clapctrl.core.dsp.RuleBasedClassifier
import dev.clapctrl.core.configuration.readBoundedJson
import dev.clapctrl.core.configuration.storageJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** One labeled event per positive recording, or a representative negative sound window. */
@Serializable
data class DatasetRecording(
    val file: String,
    @Serializable(with = AcousticClassSerializer::class) val label: AcousticClass,
    val eventEndMs: Long? = null,
)

@Serializable
data class DatasetRecordingResult(
    val file: String,
    val expected: String,
    val predicted: String,
    val candidateCount: Int,
    val decisionLatencyMs: Long? = null,
)

@Serializable
data class DatasetReport(val metrics: EvaluationReport, val recordings: List<DatasetRecordingResult>)

object DatasetEvaluator {
    fun evaluate(manifest: Path, classifier: AcousticClassifier = RuleBasedClassifier()): DatasetReport {
        val recordings = if (manifest.fileName.toString().endsWith(".csv", ignoreCase = true)) {
            parseCsv(readBoundedJson(manifest))
        } else storageJson.decodeFromString<List<DatasetRecording>>(readBoundedJson(manifest))
        require(recordings.isNotEmpty() && recordings.size <= 10_000) { "Manifest needs 1–10000 recordings" }
        val base = manifest.toAbsolutePath().parent
        val results = recordings.map { recording ->
            require(recording.file.isNotBlank() && (recording.eventEndMs == null || recording.eventEndMs >= 0)) { "Invalid recording metadata" }
            evaluateRecording(base.resolve(recording.file).normalize(), recording, classifier)
        }
        val summary = CalibrationEvaluation.summarize(results.map {
            AcousticClass.valueOf(it.expected) to AcousticClass.valueOf(it.predicted)
        })
        val latencies = results.mapNotNull { it.decisionLatencyMs }
        return DatasetReport(summary.copy(
            latencyMs = latencies.takeIf { it.isNotEmpty() }?.average(),
            note = "Independent recording-level replay through the production DSP pipeline. No detected candidate counts as NEGATIVE. " +
                "A positive file must contain one event; multiple candidates are reported. Latency requires eventEndMs annotations " +
                "and measures audio completion to candidate emission, excluding hardware/action time. Use recordings separate from calibration.",
        ), results)
    }

    private fun evaluateRecording(path: Path, recording: DatasetRecording, classifier: AcousticClassifier): DatasetRecordingResult {
        require(Files.isRegularFile(path) && Files.size(path) <= 120_000_000) { "Recording is missing or exceeds 120 MB: $path" }
        val pipeline = AcousticPipeline(classifier = classifier)
        var bestClass = AcousticClass.NEGATIVE
        var bestConfidence = -1.0
        var candidateCount = 0
        var selectedEmissionMs: Long? = null
        AudioSystem.getAudioInputStream(path.toFile()).use { source ->
            val rate = source.format.sampleRate
            require(rate.isFinite() && rate in 8000f..192000f) { "Unsupported recording sample rate: $rate" }
            val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, 1, 2, rate, false)
            AudioSystem.getAudioInputStream(target, source).use { audio ->
                val frameSamples = (rate / 100).toInt()
                val bytes = ByteArray(frameSamples * 2)
                var samplePosition = 0L
                while (true) {
                    var count = 0
                    while (count < bytes.size) {
                        val read = audio.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        if (read == 0) continue
                        count += read
                    }
                    if (count == 0) break
                    require(count % 2 == 0) { "Truncated PCM recording: $path" }
                    val samples = FloatArray(count / 2) { index ->
                        val low = bytes[index * 2].toInt() and 255
                        val high = bytes[index * 2 + 1].toInt()
                        ((high shl 8) or low).toShort() / 32768f
                    }
                    val timestamp = (samplePosition * 1000 / rate).toLong()
                    val output = pipeline.process(AudioFrame(samples, rate.toInt(), timestamp))
                    samplePosition += samples.size
                    require(samplePosition <= rate * 600) { "Recording exceeds ten minutes: $path" }
                    output.candidate?.let { candidate ->
                        candidateCount++
                        if (candidate.classification != AcousticClass.NEGATIVE && candidate.confidence > bestConfidence) {
                            bestConfidence = candidate.confidence
                            bestClass = candidate.classification
                            selectedEmissionMs = (samplePosition * 1000 / rate).toLong()
                        }
                    }
                }
            }
        }
        val latency = if (recording.eventEndMs != null && selectedEmissionMs != null &&
            recording.label != AcousticClass.NEGATIVE && bestClass == recording.label && selectedEmissionMs!! >= recording.eventEndMs) {
            selectedEmissionMs!! - recording.eventEndMs
        } else null
        return DatasetRecordingResult(recording.file, recording.label.name, bestClass.name, candidateCount, latency)
    }

    private fun parseCsv(text: String): List<DatasetRecording> {
        val rows = text.lineSequence().filter { it.isNotBlank() }.map(::csvFields).toList()
        require(rows.firstOrNull() == listOf("file", "label", "eventEndMs")) { "CSV header must be file,label,eventEndMs" }
        return rows.drop(1).map { row ->
            require(row.size == 3) { "CSV rows need three fields" }
            DatasetRecording(row[0], AcousticClass.valueOf(row[1].uppercase()), row[2].takeIf { it.isNotBlank() }?.toLong())
        }
    }

    private fun csvFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            when (val character = line[i]) {
                '"' -> if (quoted && i + 1 < line.length && line[i + 1] == '"') { field.append('"'); i++ } else quoted = !quoted
                ',' -> if (quoted) field.append(character) else { fields += field.toString(); field.clear() }
                else -> field.append(character)
            }
            i++
        }
        require(!quoted) { "Unclosed CSV quotation" }
        fields += field.toString()
        return fields
    }
}

/** Run with a held-out dataset. An optional calibration profile replaces the baseline classifier. */
fun main(args: Array<String>) {
    require(args.size in 1..3) { "Usage: DatasetEvaluatorKt manifest.json|csv [profile.json] [report.json]" }
    val classifier = if (args.size >= 2) CalibrationClassifier(CalibrationProfileStore(Path.of(args[1])).load()) else RuleBasedClassifier()
    val result = DatasetEvaluator.evaluate(Path.of(args[0]), classifier)
    val json = storageJson.encodeToString(result)
    if (args.size == 3) dev.clapctrl.core.configuration.atomicWriteJson(Path.of(args[2]), json)
    println(json)
}
