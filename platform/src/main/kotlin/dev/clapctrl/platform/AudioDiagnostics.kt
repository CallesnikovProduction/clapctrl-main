package dev.clapctrl.platform

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Read-only by default. --capture-ms N explicitly opts into microphone access; no audio is saved. */
fun main(args: Array<String>) = runBlocking {
    val capture = JavaSoundCapture()
    capture.listDevices().forEach { println("${it.id}\t${it.name}\t${it.description}") }
    if (args.isEmpty()) return@runBlocking
    require(args.size in 2..3 && args[0] == "--capture-ms") { "Usage: AudioDiagnosticsKt [--capture-ms 100..30000 [device-id]]" }
    val duration = args[1].toLong().also { require(it in 100..30000) }
    var frames = 0L
    var samples = 0L
    var rate = 0
    var maxRms = 0.0
    var maxPeak = 0.0
    try {
        withTimeoutOrNull(duration) { capture.capture(args.getOrElse(2) { "default" }) {
            frames++; samples += it.samples.size; rate = it.sampleRate
            val rms = kotlin.math.sqrt(it.samples.sumOf { value -> value.toDouble() * value } / it.samples.size.coerceAtLeast(1))
            maxRms = maxOf(maxRms, rms)
            maxPeak = maxOf(maxPeak, it.samples.maxOfOrNull { value -> kotlin.math.abs(value.toDouble()) } ?: 0.0)
        } }
    } finally { capture.stop() }
    println("Capture diagnostic: frames=$frames samples=$samples sampleRate=$rate maxRms=$maxRms maxPeak=$maxPeak; audio was not saved")
    check(frames > 0) { "No audio frames received; check selected device and microphone permission" }
}
