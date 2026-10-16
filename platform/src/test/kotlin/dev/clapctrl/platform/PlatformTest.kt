package dev.clapctrl.platform

import dev.clapctrl.core.ActionConfiguration
import dev.clapctrl.core.ActionType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlatformTest {
    private class RecordingRunner : CommandRunner {
        val requests = mutableListOf<CommandRequest>()
        var result = CommandOutcome(0)
        override suspend fun run(request: CommandRequest): CommandOutcome { requests += request; return result }
    }

    @Test fun `PCM signed little endian conversion preserves full scale and stereo average`() {
        assertContentEquals(floatArrayOf(0f, -1f, 32767f / 32768f), JavaSoundCapture.decodePcm16(byteArrayOf(0, 0, 0, -128, -1, 127), 1))
        assertContentEquals(floatArrayOf(-1f / 65536f), JavaSoundCapture.decodePcm16(byteArrayOf(0, -128, -1, 127), 2))
        assertFailsWith<IllegalArgumentException> { JavaSoundCapture.decodePcm16(byteArrayOf(1), 1) }
    }

    @Test fun `capture accepts an open provider that reports not running until first read`() = runBlocking {
        val opened = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val reads = AtomicInteger(0)
        var format = AudioFormat(48000f, 16, 1, true, false)
        val line = Proxy.newProxyInstance(TargetDataLine::class.java.classLoader, arrayOf(TargetDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "open" -> { format = args!![0] as AudioFormat; opened.set(true); null }
                "start" -> { started.set(true); null }
                "close" -> { opened.set(false); null }
                "isOpen" -> opened.get()
                "isRunning", "isActive" -> started.get() && reads.get() > 0
                "getFormat" -> format
                "read" -> {
                    check(opened.get() && started.get())
                    reads.incrementAndGet()
                    val buffer = args!![0] as ByteArray
                    val offset = args[1] as Int
                    val length = args[2] as Int
                    for (index in offset until offset + length step 2) { buffer[index] = 0; buffer[index + 1] = 0x40 }
                    length
                }
                else -> error("Unexpected TargetDataLine call: ${method.name}")
            }
        } as TargetDataLine
        assertFalse(line.isRunning)
        val capture = JavaSoundCapture { _, _ -> line }
        val frames = mutableListOf<dev.clapctrl.core.AudioFrame>()
        withTimeout(5000) {
            capture.capture("default") { frame -> frames += frame; capture.stop() }
        }
        assertEquals(1, reads.get())
        assertEquals(1, frames.size)
        assertEquals(48000, frames.single().sampleRate)
        assertEquals(480, frames.single().samples.size)
        assertTrue(frames.single().samples.all { it == 0.5f })
        assertFalse(opened.get(), "Capture releases the native line before returning")
        val diagnostics = assertNotNull(capture.lastCaptureDiagnostics())
        val firstFrameMs = assertNotNull(diagnostics.firstFrameMs)
        assertTrue(firstFrameMs >= 0)
        assertTrue(diagnostics.totalMs >= firstFrameMs)
        assertEquals(48000, diagnostics.openAttempts.single().sampleRate)
        assertTrue(diagnostics.openAttempts.single().success)
    }

    @Test fun `audio diagnostics repeated capture remains explicitly bounded and opt in`() {
        assertEquals(AudioDiagnosticOptions(1500, "default", 3), parseAudioDiagnosticOptions(arrayOf("--capture-ms", "1500", "--sessions", "3")))
        assertEquals(AudioDiagnosticOptions(100, "input-id", 1), parseAudioDiagnosticOptions(arrayOf("--capture-ms", "100", "input-id")))
        assertFailsWith<IllegalArgumentException> { parseAudioDiagnosticOptions(arrayOf("--sessions", "3")) }
        assertFailsWith<IllegalArgumentException> { parseAudioDiagnosticOptions(arrayOf("--capture-ms", "100", "--sessions", "11")) }
        assertFailsWith<IllegalArgumentException> { parseAudioDiagnosticOptions(arrayOf("--capture-ms", "0")) }
        assertFailsWith<IllegalArgumentException> { parseAudioDiagnosticOptions(arrayOf("--capture-ms", "100", "")) }
        assertFailsWith<IllegalArgumentException> { parseAudioDiagnosticOptions(arrayOf("--capture-ms", "100", "--sessions", "2", "--sessions", "3")) }
        assertFailsWith<IllegalArgumentException> { parseAudioDiagnosticOptions(arrayOf("--capture-ms", "100", "--sessions")) }
    }

    @Test fun `failed capture publishes bounded negotiation diagnostics without a false first frame`() = runBlocking {
        val probes = AtomicInteger(0)
        val capture = JavaSoundCapture { _, _ -> probes.incrementAndGet(); throw IllegalArgumentException("Unsupported test format") }
        repeat(2) { session ->
            assertFailsWith<IllegalStateException> {
                withTimeout(3000) { capture.capture("default") { error("Failed negotiation cannot deliver frames") } }
            }
            val diagnostics = assertNotNull(capture.lastCaptureDiagnostics())
            assertEquals((session + 1) * 6, probes.get(), "Failed sessions release ownership so the next attempt really runs")
            assertEquals(6, diagnostics.openAttempts.size)
            assertTrue(diagnostics.openAttempts.all { !it.success && it.failure == "Unsupported test format" && it.durationMs >= 0 })
            assertEquals(null, diagnostics.firstFrameMs)
            assertEquals(null, diagnostics.lineOpenedMs)
            assertEquals(null, diagnostics.startDurationMs)
        }
    }

    @Test fun `cancel and join waits for capture worker cleanup before immediate restart`() = runBlocking {
        val readEntered = CountDownLatch(1)
        val closeCalled = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val createdLines = AtomicInteger(0)
        fun fakeLine(blockRead: Boolean): TargetDataLine {
            val opened = AtomicBoolean(false)
            var format = AudioFormat(48000f, 16, 1, true, false)
            return Proxy.newProxyInstance(TargetDataLine::class.java.classLoader, arrayOf(TargetDataLine::class.java)) { _, method, args ->
                when (method.name) {
                    "open" -> { format = args!![0] as AudioFormat; opened.set(true); null }
                    "start" -> null
                    "close" -> { opened.set(false); if (blockRead) closeCalled.countDown(); null }
                    "isOpen" -> opened.get()
                    "getFormat" -> format
                    "read" -> if (blockRead) {
                        readEntered.countDown()
                        check(releaseRead.await(5, TimeUnit.SECONDS)) { "Test read was not released" }
                        0
                    } else {
                        val buffer = args!![0] as ByteArray
                        val offset = args[1] as Int
                        val length = args[2] as Int
                        buffer.fill(0, offset, offset + length)
                        length
                    }
                    else -> error("Unexpected TargetDataLine call: ${method.name}")
                }
            } as TargetDataLine
        }
        val capture = JavaSoundCapture { _, _ -> fakeLine(createdLines.getAndIncrement() == 0) }
        val first = launch(Dispatchers.Default) { capture.capture("default") { error("Cancelled capture must not publish a frame") } }
        try {
            assertTrue(readEntered.await(3, TimeUnit.SECONDS), "First capture entered native read")
            val joined = async(Dispatchers.Default) { first.cancelAndJoin() }
            assertTrue(closeCalled.await(3, TimeUnit.SECONDS), "Cancellation immediately closes the native line")
            assertFalse(withTimeoutOrNull(150) { joined.await(); true } ?: false, "cancelAndJoin must await the worker, not just its continuation")
            releaseRead.countDown()
            withTimeout(3000) { joined.await() }
            var received = false
            withTimeout(3000) { capture.capture("default") { received = true; capture.stop() } }
            assertTrue(received, "A new capture starts immediately after cancellation cleanup")
            assertEquals(2, createdLines.get())
        } finally {
            releaseRead.countDown()
            first.cancelAndJoin()
            capture.stop()
        }
    }

    @Test fun `Linux capabilities require available tools and compositor session`() {
        val adapter = DesktopPlatformAdapter(osName = "Linux", environment = emptyMap(), findExecutable = { if (it == "hyprctl") "/bin/hyprctl" else null })
        assertFalse(adapter.capabilities().first { it.type == ActionType.SWITCH_WORKSPACE }.supported)
        assertFalse(adapter.capabilities().first { it.type == ActionType.SCREENSHOT }.supported)
        assertTrue(adapter.capabilities().first { it.type == ActionType.CUSTOM_COMMAND }.supported)
    }

    @Test fun `Hyprland workspace passes validated argv without a shell`() = runBlocking {
        val runner = RecordingRunner()
        val adapter = DesktopPlatformAdapter(runner, "Linux", mapOf("HYPRLAND_INSTANCE_SIGNATURE" to "test"), { if (it == "hyprctl") "/usr/bin/hyprctl" else null })
        assertTrue(adapter.execute(ActionConfiguration(ActionType.SWITCH_WORKSPACE, workspace = 12)).success)
        assertEquals(listOf("/usr/bin/hyprctl", "dispatch", "workspace", "12"), runner.requests.last().argv)
        assertFalse(adapter.execute(ActionConfiguration(ActionType.SWITCH_WORKSPACE, workspace = -1)).success)
        assertEquals(2, runner.requests.size)
    }

    @Test fun `modern Hyprland uses documented Lua workspace dispatcher`() = runBlocking {
        val requests = mutableListOf<CommandRequest>()
        val runner = CommandRunner { request -> requests += request; if (request.argv.last() == "version") CommandOutcome(0, "Hyprland v0.55.2") else CommandOutcome(0, "ok") }
        val adapter = DesktopPlatformAdapter(runner, "Linux", mapOf("HYPRLAND_INSTANCE_SIGNATURE" to "test"), { if (it == "hyprctl") "/usr/bin/hyprctl" else null })
        assertTrue(adapter.execute(ActionConfiguration(ActionType.SWITCH_WORKSPACE, workspace = 5)).success)
        assertEquals(listOf("/usr/bin/hyprctl", "dispatch", "hl.dsp.focus({ workspace = \"5\" })"), requests.last().argv)
    }

    @Test fun `custom commands require approval and preserve metacharacters as arguments`() = runBlocking {
        val runner = RecordingRunner()
        val adapter = DesktopPlatformAdapter(runner, "Linux", emptyMap(), { null })
        val action = ActionConfiguration(ActionType.CUSTOM_COMMAND, listOf("program", "space argument", "$(touch injected); & |"))
        assertFalse(adapter.execute(action).success)
        assertTrue(runner.requests.isEmpty())
        assertTrue(adapter.execute(action.copy(approved = true)).success)
        assertEquals(action.command, runner.requests.single().argv)
        runner.result = CommandOutcome(3, "no player")
        assertFalse(adapter.execute(action.copy(approved = true)).success)
    }

    @Test fun `GUI launch reports launched and timed out custom action reports failure`() = runBlocking {
        val runner = RecordingRunner()
        runner.result = CommandOutcome(null, launched = true)
        val adapter = DesktopPlatformAdapter(runner, "Linux", emptyMap(), { if (it == "kitty") "/usr/bin/kitty" else null })
        val launched = adapter.execute(ActionConfiguration(ActionType.OPEN_TERMINAL))
        assertTrue(launched.success)
        assertTrue(launched.message.startsWith("Launched:"))
        assertTrue(runner.requests.single().launch)
        runner.result = CommandOutcome(null, timedOut = true)
        assertFalse(adapter.execute(ActionConfiguration(ActionType.CUSTOM_COMMAND, listOf("example"), approved = true)).success)
    }

    @Test fun `Windows controlled script contains no user arguments and workspace unsupported`() = runBlocking {
        val runner = RecordingRunner()
        val adapter = DesktopPlatformAdapter(runner, "Windows 11", emptyMap(), { if (it == "powershell.exe") "C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe" else null })
        assertTrue(adapter.execute(ActionConfiguration(ActionType.MEDIA_PLAY_PAUSE)).success)
        val request = runner.requests.single()
        assertEquals("-EncodedCommand", request.argv[4])
        val script = String(Base64.getDecoder().decode(request.argv[5]), Charsets.UTF_16LE)
        assertTrue(script.contains("SendInput"))
        assertTrue(script.contains("::Tap(179)"))
        assertFalse(adapter.execute(ActionConfiguration(ActionType.SWITCH_WORKSPACE)).success)
        assertEquals(1, runner.requests.size)
    }

    @Test fun `Wayland never falls back to X11 screenshot`() {
        val adapter = DesktopPlatformAdapter(osName = "Linux", environment = mapOf("WAYLAND_DISPLAY" to "wayland-1", "DISPLAY" to ":0"), findExecutable = { if (it == "scrot") "/bin/scrot" else null })
        assertFalse(adapter.capabilities().first { it.type == ActionType.SCREENSHOT }.supported)
    }

    private fun probe(mode: String): List<String> {
        val executable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
        val paths = listOf(ProcessProbe::class.java, kotlin.Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        return listOf(executable, "-cp", paths.joinToString(java.io.File.pathSeparator), ProcessProbe::class.java.name, mode)
    }

    @Test fun `real harmless subprocess timeout kills process promptly`() = runBlocking {
        val result = ProcessCommandRunner().run(CommandRequest(probe("sleep"), timeoutMs = 200))
        assertTrue(result.timedOut)
        assertFalse(result.launched)
    }

    @Test fun `real harmless subprocess cancellation propagates`() = runBlocking {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(200) { ProcessCommandRunner().run(CommandRequest(probe("sleep"), timeoutMs = 10000)) }
        }
        Unit
    }

    @Test fun `real subprocess output is drained and bounded`() = runBlocking {
        val result = ProcessCommandRunner(outputLimit = 1024).run(CommandRequest(probe("flood")))
        assertEquals(0, result.exitCode)
        assertEquals(1024, result.output.length)
    }
}

object ProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        when (args.single()) {
            "sleep" -> Thread.sleep(30000)
            "flood" -> repeat(20000) { println("harmless-output") }
            else -> error("Unknown probe mode")
        }
    }
}
