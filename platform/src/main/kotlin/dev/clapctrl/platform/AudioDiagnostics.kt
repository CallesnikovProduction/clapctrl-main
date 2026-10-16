package dev.clapctrl.platform

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal data class AudioDiagnosticOptions(val durationMs: Long, val deviceId: String, val sessions: Int)

internal fun parseAudioDiagnosticOptions(args: Array<String>): AudioDiagnosticOptions {
    val usage = "Usage: AudioDiagnosticsKt [--capture-ms 100..30000 [device-id] [--sessions 1..10]]"
    require(args.size >= 2 && args[0] == "--capture-ms") { usage }
    val duration = args[1].toLong().also { require(it in 100..30000) { usage } }
    var deviceId: String? = null
    var sessions: Int? = null
    var index = 2
    while (index < args.size) {
        when (val option = args[index++]) {
            "--sessions" -> {
                require(sessions == null && index < args.size) { usage }
                sessions = args[index++].toInt().also { require(it in 1..10) { usage } }
            }
            else -> { require(option.isNotBlank() && !option.startsWith("--") && deviceId == null) { usage }; deviceId = option }
        }
    }
    return AudioDiagnosticOptions(duration, deviceId ?: "default", sessions ?: 1)
}

/** Read-only by default. --capture-ms N explicitly opts into microphone access; no audio is saved. */
fun main(args: Array<String>) = runBlocking {
    val capture = JavaSoundCapture()
    capture.listDevices().forEach { println("${it.id}\t${it.name}\t${it.description}") }
    if (args.isEmpty()) return@runBlocking
    val options = parseAudioDiagnosticOptions(args)
    val firstFrameTimings = mutableListOf<Double>()
    repeat(options.sessions) { session ->
        var frames = 0L
        var samples = 0L
        var rate = 0
        var maxRms = 0.0
        var maxPeak = 0.0
        var failure: Exception? = null
        try {
            withTimeoutOrNull(options.durationMs) { capture.capture(options.deviceId) {
                frames++; samples += it.samples.size; rate = it.sampleRate
                val rms = kotlin.math.sqrt(it.samples.sumOf { value -> value.toDouble() * value } / it.samples.size.coerceAtLeast(1))
                maxRms = maxOf(maxRms, rms)
                maxPeak = maxOf(maxPeak, it.samples.maxOfOrNull { value -> kotlin.math.abs(value.toDouble()) } ?: 0.0)
            } }
        } catch (error: Exception) { failure = error }
        finally { capture.stop() }
        val diagnostic = capture.lastCaptureDiagnostics()
        diagnostic?.firstFrameMs?.let { firstFrameTimings += it }
        println("Capture diagnostic session=${session + 1}/${options.sessions}: frames=$frames samples=$samples sampleRate=$rate maxRms=$maxRms maxPeak=$maxPeak firstFrameMs=${diagnostic?.firstFrameMs} deviceResolutionMs=${diagnostic?.deviceResolutionMs} lineOpenedMs=${diagnostic?.lineOpenedMs} startDurationMs=${diagnostic?.startDurationMs} totalMs=${diagnostic?.totalMs}; audio was not saved")
        diagnostic?.openAttempts?.forEachIndexed { index, attempt ->
            println("Open attempt=${index + 1}: sampleRate=${attempt.sampleRate} channels=${attempt.channels} durationMs=${attempt.durationMs} success=${attempt.success} failure=${attempt.failure}")
        }
        failure?.let { throw it }
        check(frames > 0) { "No audio frames received; check selected device and microphone permission" }
    }
    println("Capture timing summary: sessions=${firstFrameTimings.size} firstFrameMinMs=${firstFrameTimings.minOrNull()} firstFrameAverageMs=${firstFrameTimings.average()} firstFrameMaxMs=${firstFrameTimings.maxOrNull()}")
}
