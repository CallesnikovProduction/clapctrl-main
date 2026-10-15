package dev.clapctrl.platform

import dev.clapctrl.core.AudioCaptureService
import dev.clapctrl.core.AudioFrame
import dev.clapctrl.core.AudioInputDevice
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Java Sound backend. Enumerating inputs never opens a microphone. Capture is opt-in. */
class JavaSoundCapture(
    private val lineFactory: (AudioFormat, Mixer.Info?) -> TargetDataLine = { format, mixer ->
        if (mixer == null) AudioSystem.getTargetDataLine(format)
        else AudioSystem.getMixer(mixer).getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
    },
) : AudioCaptureService {
    private class Session {
        val stopped = AtomicBoolean(false)
        val line = AtomicReference<TargetDataLine?>()
        val failure = AtomicReference<Throwable?>()
        val finished = CompletableDeferred<Unit>()
        @Volatile var lastRead = System.nanoTime()
        fun close() {
            // Closing a TargetDataLine releases the native device and unblocks read().
            // Keep the reference until the worker finishes: cancellation can race with open(),
            // and the worker must close a line that was reopened after the first close attempt.
            runCatching { line.get()?.close() }
        }
    }
    private val active = AtomicReference<Session?>()

    private fun mixers(): List<Pair<String, Mixer.Info>> {
        val counts = mutableMapOf<String, Int>()
        return AudioSystem.getMixerInfo().filter { info ->
            runCatching { AudioSystem.getMixer(info).targetLineInfo.any { TargetDataLine::class.java.isAssignableFrom(it.lineClass) } }.getOrDefault(false)
        }.map { info ->
            val signature = listOf(info.name, info.vendor, info.description, info.version).joinToString("\u0000")
            val hash = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
            val occurrence = counts.getOrDefault(hash, 0).also { counts[hash] = it + 1 }
            "javasound:$hash:$occurrence" to info
        }
    }

    override fun listDevices(): List<AudioInputDevice> = listOf(AudioInputDevice("default", "System default input", "Java Sound default device")) +
        mixers().map { (id, info) -> AudioInputDevice(id, info.name, info.description) }

    override fun stop() {
        active.get()?.let { it.stopped.set(true); it.close() }
    }

    override suspend fun capture(deviceId: String, onFrame: (AudioFrame) -> Unit) {
        val session = Session()
        check(active.compareAndSet(null, session)) { "Audio capture already running or still stopping" }
        try {
            captureSession(session, deviceId, onFrame)
        } finally {
            session.stopped.set(true)
            session.close()
            val stopped = withContext(NonCancellable) {
                withTimeoutOrNull(5000) { session.finished.await(); true } ?: false
            }
            // Ownership stays with the old session if a broken provider ignores close().
            // Never report a clean stop or permit overlapping native capture in this case.
            check(stopped) { "Microphone worker did not stop within 5 seconds; input remains reserved until the audio backend releases it" }
        }
    }

    private suspend fun captureSession(session: Session, deviceId: String, onFrame: (AudioFrame) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { session.stopped.set(true); session.close() }
        var watchdog: ScheduledFuture<*>? = null
        try {
            watchdog = scheduler.scheduleAtFixedRate({
                if (!session.stopped.get() && System.nanoTime() - session.lastRead > TimeUnit.SECONDS.toNanos(4)) {
                    session.failure.compareAndSet(null, IllegalStateException("Microphone stopped delivering audio; reconnect or select another input"))
                    session.close()
                }
            }, 4, 1, TimeUnit.SECONDS)
            workers.execute {
                try {
                    val info = if (deviceId == "default") null else {
                        mixers().firstOrNull { it.first == deviceId }?.second
                            ?: throw IllegalArgumentException("Selected microphone is unavailable: $deviceId")
                    }
                    val line = open(session, info)
                    if (!session.stopped.get()) {
                        line.start()
                        val rate = line.format.sampleRate.toInt()
                        val frameSamples = rate / 100
                        val frameBytes = frameSamples * line.format.frameSize
                        val bytes = ByteArray(frameBytes)
                        var filled = 0
                        var sampleCount = 0L
                        val startedMs = System.nanoTime() / 1_000_000
                        session.lastRead = System.nanoTime()
                        while (!session.stopped.get()) {
                            session.failure.get()?.let { throw it }
                            // Some providers report isRunning=false until the first read delivers frames.
                            // An open line may also be temporarily inactive; the watchdog detects a real stall.
                            if (!line.isOpen) throw IllegalStateException("Microphone disconnected or stopped")
                            val count = line.read(bytes, filled, bytes.size - filled)
                            if (count < 0) throw IllegalStateException("Microphone input ended")
                            if (count == 0) { Thread.sleep(5); continue }
                            session.lastRead = System.nanoTime()
                            filled += count
                            if (filled == bytes.size) {
                                if (!session.stopped.get()) onFrame(AudioFrame(decodePcm16(bytes, line.format.channels), rate, startedMs + sampleCount * 1000 / rate))
                                sampleCount += frameSamples
                                filled = 0
                            }
                        }
                    }
                    session.failure.get()?.let { throw it }
                    watchdog?.cancel(false)
                    session.close()
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: Throwable) {
                    watchdog?.cancel(false)
                    session.close()
                    if (continuation.isActive) {
                        if (session.stopped.get() && session.failure.get() == null) continuation.resume(Unit)
                        else continuation.resumeWithException(session.failure.get() ?: error)
                    }
                } finally {
                    watchdog?.cancel(false)
                    session.close()
                    active.compareAndSet(session, null)
                    session.finished.complete(Unit)
                }
            }
        } catch (error: Throwable) {
            watchdog?.cancel(false)
            session.close()
            active.compareAndSet(session, null)
            session.finished.complete(Unit)
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun open(session: Session, mixer: Mixer.Info?): TargetDataLine {
        val errors = mutableListOf<Throwable>()
        for (rate in listOf(48000f, 44100f, 16000f)) for (channels in listOf(1, 2)) {
            session.failure.get()?.let { throw it }
            if (session.stopped.get()) throw InterruptedException("Capture stopped")
            val format = AudioFormat(rate, 16, channels, true, false)
            try {
                val line = lineFactory(format, mixer)
                session.line.set(line)
                line.open(format, (rate / 10).toInt() * format.frameSize)
                session.failure.get()?.let { throw it }
                if (session.stopped.get()) { session.close(); throw InterruptedException("Capture stopped") }
                return line
            } catch (error: Exception) { session.close(); errors += error }
        }
        throw IllegalStateException("Cannot open microphone as PCM16 mono/stereo at 48, 44.1 or 16 kHz. Check input permissions and audio backend.", errors.lastOrNull())
    }

    companion object {
        private val workers = Executors.newCachedThreadPool { Thread(it, "clapctrl-audio").apply { isDaemon = true } }
        private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "clapctrl-audio-watchdog").apply { isDaemon = true } }
        internal fun decodePcm16(bytes: ByteArray, channels: Int): FloatArray {
            require(channels in 1..2 && bytes.size % (channels * 2) == 0)
            return FloatArray(bytes.size / (channels * 2)) { sample ->
                var sum = 0
                repeat(channels) { channel ->
                    val offset = (sample * channels + channel) * 2
                    sum += ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
                }
                sum.toFloat() / (32768f * channels)
            }
        }
    }
}
