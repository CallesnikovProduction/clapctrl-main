package dev.clapctrl.app

import dev.clapctrl.core.*
import dev.clapctrl.core.calibration.CalibrationProfileStore
import dev.clapctrl.core.calibration.CalibrationSample
import dev.clapctrl.core.calibration.CalibrationTrainer
import dev.clapctrl.core.configuration.ConfigurationStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.math.exp
import kotlin.random.Random

class AppControllerTest {
    @TempDir lateinit var directory: Path

    private class Capture : AudioCaptureService {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val processedFrames = AtomicInteger()
        val selectedDevices = CopyOnWriteArrayList<String>()
        private val frames = Channel<AudioFrame>(16)
        override fun listDevices() = listOf(AudioInputDevice("default", "Test microphone"), AudioInputDevice("usb", "USB microphone"))
        override suspend fun capture(deviceId: String, onFrame: (AudioFrame) -> Unit) {
            selectedDevices += deviceId
            val count = active.incrementAndGet()
            maximumActive.updateAndGet { maxOf(it, count) }
            starts.incrementAndGet()
            try {
                while (currentCoroutineContext().isActive) {
                    onFrame(frames.receive())
                    processedFrames.incrementAndGet()
                }
            } finally { active.decrementAndGet() }
        }
        override fun stop() { stops.incrementAndGet() }
        suspend fun emit(timestamp: Long = 100) = frames.send(AudioFrame(FloatArray(480) { 0.01f }, 48000, timestamp))
        suspend fun emit(frame: AudioFrame) = frames.send(frame)
    }

    private class Adapter : PlatformAdapter {
        val executions = CopyOnWriteArrayList<ActionConfiguration>()
        override fun capabilities() = ActionType.entries.map { ActionCapability(it, true, "Test adapter") }
        override suspend fun execute(action: ActionConfiguration): ActionResult {
            executions += action
            return ActionResult(true, "Test action completed")
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(4000) { while (!condition()) delay(10) }
    }

    private suspend fun emitSyntheticClaps(capture: Capture, onsets: List<Int>, startMs: Long = 0, durationMs: Int = 400) {
        val rate = 48000
        val samples = FloatArray(rate * durationMs / 1000)
        val targetProcessedFrames = capture.processedFrames.get() + (samples.size + 479) / 480
        val random = Random(42)
        for (onset in onsets) {
            val start = onset * rate / 1000
            for (index in 0 until rate * 120 / 1000) {
                samples[start + index] = ((random.nextDouble() * 2 - 1) * 0.8 * exp(-index.toDouble() / (rate * 0.030))).toFloat()
            }
        }
        for (offset in samples.indices step 480) {
            capture.emit(AudioFrame(samples.copyOfRange(offset, minOf(offset + 480, samples.size)), rate, startMs + offset * 1000L / rate))
        }
        awaitCondition { capture.processedFrames.get() >= targetProcessedFrames }
    }

    private suspend fun emitSyntheticDoubleClap(capture: Capture) {
        emitSyntheticClaps(capture, listOf(200, 600), durationMs = 1200)
    }

    private suspend fun configureSyntheticClapThreshold(controller: AppController) {
        // This shaped-noise fixture is classified CLAP with a relative score near 0.707.
        // Exercise the configured threshold explicitly; it is below the production default 0.75.
        controller.updateConfiguration { configuration -> configuration.copy(triggers = configuration.triggers.map {
            if (it.gesture == Gesture.DOUBLE_CLAP) it.copy(confidence = 0.5) else it
        }) }
        awaitCondition { controller.state.value.configuration.triggers.single { it.gesture == Gesture.DOUBLE_CLAP }.confidence == 0.5 }
    }

    private fun assertSyntheticClapConfidence(state: DashboardState) {
        val scorePercent = assertNotNull(Regex("(\\d+)%").find(state.lastGesture)).groupValues[1].toInt()
        assertTrue(scorePercent in 50..74, "Synthetic clap score should pass the configured 50% threshold and remain below the default 75%; observed $scorePercent%")
    }

    @Test fun `first launch requires consent and never opens the microphone implicitly`(): Unit = runBlocking {
        val capture = Capture()
        val controller = AppController(directory, capture, Adapter())
        try {
            awaitCondition { controller.state.value.devices.isNotEmpty() }
            assertFalse(controller.state.value.configuration.consentGiven)
            assertFalse(controller.state.value.listening)
            controller.start()
            delay(100)
            assertEquals(0, capture.starts.get())
            assertTrue(Files.notExists(directory.resolve("settings.json")))
        } finally { controller.shutdown() }
    }

    @Test fun `persisted consent does not automatically enable monitoring`(): Unit = runBlocking {
        ConfigurationStore(directory.resolve("settings.json")).save(AppConfiguration(consentGiven = true))
        val capture = Capture()
        val controller = AppController(directory, capture, Adapter())
        try {
            awaitCondition { controller.state.value.devices.isNotEmpty() }
            delay(100)
            assertTrue(controller.state.value.configuration.consentGiven)
            assertFalse(controller.state.value.listening)
            assertEquals(0, capture.starts.get())
            controller.start()
            awaitCondition { capture.starts.get() == 1 }
            capture.emit()
            awaitCondition { controller.state.value.listening }
        } finally { controller.shutdown() }
        assertEquals(0, capture.active.get())
    }

    @Test fun `explicit enable saves consent then pause releases capture and resume owns a single session`(): Unit = runBlocking {
        val capture = Capture()
        val controller = AppController(directory, capture, Adapter())
        try {
            controller.enableWithConsent()
            awaitCondition { capture.starts.get() == 1 }
            assertTrue(ConfigurationStore(directory.resolve("settings.json")).load().consentGiven)
            capture.emit()
            awaitCondition { controller.state.value.listening }
            assertEquals(48000, controller.state.value.sampleRate)
            assertTrue(controller.state.value.levels.last() > 0f)
            controller.pause()
            awaitCondition { capture.active.get() == 0 && !controller.state.value.listening && !controller.state.value.starting }
            assertTrue(capture.stops.get() > 0)
            assertTrue(controller.state.value.levels.all { it == 0f })
            controller.start()
            awaitCondition { capture.starts.get() == 2 }
            capture.emit(200)
            awaitCondition { controller.state.value.listening }
            controller.start()
            delay(50)
            assertEquals(2, capture.starts.get())
            assertEquals(1, capture.maximumActive.get())
        } finally { controller.shutdown() }
        assertEquals(0, capture.active.get())
        assertFalse(controller.state.value.listening)
        controller.start()
        delay(50)
        assertEquals(2, capture.starts.get())
    }

    @Test fun `changing input persists configuration and replaces the live capture session`(): Unit = runBlocking {
        val capture = Capture()
        val controller = AppController(directory, capture, Adapter())
        try {
            controller.enableWithConsent()
            awaitCondition { capture.starts.get() == 1 }
            capture.emit()
            awaitCondition { controller.state.value.listening }
            controller.updateConfiguration { it.copy(microphoneId = "usb", triggers = it.triggers.map { trigger -> trigger.copy(enabled = false) }) }
            awaitCondition { capture.selectedDevices.lastOrNull() == "usb" && capture.starts.get() == 2 }
            val stored = ConfigurationStore(directory.resolve("settings.json")).load()
            assertEquals("usb", stored.microphoneId)
            assertTrue(stored.triggers.all { !it.enabled })
            assertEquals(1, capture.maximumActive.get())
            capture.emit(200)
            awaitCondition { controller.state.value.listening }
        } finally { controller.shutdown() }
        val reopenedCapture = Capture()
        val reopened = AppController(directory, reopenedCapture, Adapter())
        try {
            assertEquals("usb", reopened.state.value.configuration.microphoneId)
            assertTrue(reopened.state.value.configuration.triggers.all { !it.enabled })
            assertEquals(0, reopenedCapture.starts.get())
        } finally { reopened.shutdown() }
    }

    @Test fun `invalid settings leave persisted and live configuration intact`(): Unit = runBlocking {
        val capture = Capture()
        val controller = AppController(directory, capture, Adapter())
        try {
            controller.enableWithConsent()
            awaitCondition { capture.starts.get() == 1 }
            val original = ConfigurationStore(directory.resolve("settings.json")).load()
            controller.updateConfiguration { it.copy(detector = it.detector.copy(sensitivity = Double.NaN)) }
            awaitCondition { controller.state.value.error?.contains("not saved") == true }
            assertEquals(original, controller.state.value.configuration)
            assertEquals(original, ConfigurationStore(directory.resolve("settings.json")).load())
            assertEquals(1, capture.starts.get())
        } finally { controller.shutdown() }
    }

    @Test fun `manual action test works with monitoring off and logs the actual adapter result`(): Unit = runBlocking {
        val capture = Capture()
        val adapter = Adapter()
        val controller = AppController(directory, capture, adapter)
        try {
            val trigger = TriggerConfiguration(Gesture.FINGER_SNAP, enabled = false, action = ActionConfiguration(ActionType.SCREENSHOT))
            controller.testAction(trigger)
            awaitCondition { controller.state.value.activity.any { it.detail == "Test action completed" } }
            assertEquals(listOf(trigger.action), adapter.executions.toList())
            assertEquals(0, capture.starts.get())
            assertEquals("No detections yet", controller.state.value.lastGesture)
        } finally { controller.shutdown() }
    }

    @Test fun `captured PCM goes through production detection and configured action dispatch`(): Unit = runBlocking {
        val capture = Capture()
        val adapter = Adapter()
        val controller = AppController(directory, capture, adapter)
        try {
            configureSyntheticClapThreshold(controller)
            controller.enableWithConsent()
            awaitCondition { capture.starts.get() == 1 }
            emitSyntheticDoubleClap(capture)
            awaitCondition { controller.state.value.activity.any { it.title == "Double clap" } }
            assertEquals(listOf(ActionType.OPEN_TERMINAL), adapter.executions.map { it.type })
            assertTrue(controller.state.value.lastGesture.startsWith("Double clap"))
            assertSyntheticClapConfidence(controller.state.value)
            assertTrue(controller.state.value.listening)
        } finally { controller.shutdown() }
    }

    @Test fun `calibration previews real candidates while suppressing desktop actions`(): Unit = runBlocking {
        val capture = Capture()
        val adapter = Adapter()
        val controller = AppController(directory, capture, adapter)
        try {
            controller.beginCalibration(2)
            awaitCondition { controller.state.value.calibrating }
            controller.enableWithConsent()
            awaitCondition { capture.starts.get() == 1 }
            emitSyntheticDoubleClap(capture)
            awaitCondition { controller.state.value.candidate != null }
            assertTrue(controller.state.value.candidate!!.waveform.isNotEmpty())
            assertTrue(adapter.executions.isEmpty())
            assertEquals("No detections yet", controller.state.value.lastGesture)
            controller.acceptCandidate(AcousticClass.CLAP)
            assertEquals(1, controller.state.value.sampleCounts[AcousticClass.CLAP])
            assertNull(controller.state.value.candidate)
            controller.endCalibration()
            awaitCondition { !controller.state.value.calibrating }
            assertFalse(controller.state.value.calibrating)
        } finally { controller.shutdown() }
    }

    @Test fun `calibration freezes its first preview until explicit rejection or acceptance`(): Unit = runBlocking {
        val capture = Capture()
        val controller = AppController(directory, capture, Adapter())
        try {
            controller.beginCalibration(2)
            awaitCondition { controller.state.value.calibrating }
            controller.enableWithConsent()
            awaitCondition { capture.starts.get() == 1 }
            emitSyntheticClaps(capture, listOf(200))
            val first = assertNotNull(controller.state.value.candidate)
            assertEquals(200L, first.timestampMs)
            emitSyntheticClaps(capture, listOf(50), startMs = 400)
            assertSame(first, controller.state.value.candidate)
            controller.rejectCandidate()
            assertNull(controller.state.value.candidate)
            emitSyntheticClaps(capture, listOf(50), startMs = 800)
            assertEquals(850L, controller.state.value.candidate?.timestampMs)
            controller.acceptCandidate(AcousticClass.CLAP)
            assertNull(controller.state.value.candidate)
            assertEquals(1, controller.state.value.sampleCounts[AcousticClass.CLAP])
            emitSyntheticClaps(capture, listOf(50), startMs = 1200)
            assertEquals(1250L, controller.state.value.candidate?.timestampMs)
            assertEquals(1, controller.state.value.sampleCounts[AcousticClass.CLAP])
        } finally { controller.shutdown() }
    }

    @Test fun `ending calibration cannot pair its old clap with a later live clap`(): Unit = runBlocking {
        val capture = Capture()
        val adapter = Adapter()
        val controller = AppController(directory, capture, adapter)
        try {
            configureSyntheticClapThreshold(controller)
            controller.beginCalibration(2)
            awaitCondition { controller.state.value.calibrating }
            controller.enableWithConsent()
            awaitCondition { capture.starts.get() == 1 }
            emitSyntheticClaps(capture, listOf(200))
            assertEquals(200L, controller.state.value.candidate?.timestampMs)
            controller.endCalibration()
            awaitCondition { !controller.state.value.calibrating && capture.starts.get() == 2 }
            emitSyntheticClaps(capture, listOf(50), startMs = 400)
            assertEquals("No detections yet", controller.state.value.lastGesture)
            assertTrue(adapter.executions.isEmpty())
            emitSyntheticClaps(capture, listOf(50), startMs = 800)
            awaitCondition { controller.state.value.activity.any { it.title == "Double clap" } }
            assertEquals(listOf(ActionType.OPEN_TERMINAL), adapter.executions.map { it.type })
            assertSyntheticClapConfidence(controller.state.value)
            assertEquals(1, capture.maximumActive.get())
        } finally { controller.shutdown() }
    }

    @Test fun `changing microphone during calibration is rejected without replacing its configuration or capture`(): Unit = runBlocking {
        val capture = Capture()
        val controller = AppController(directory, capture, Adapter())
        try {
            controller.beginCalibration(2)
            awaitCondition { controller.state.value.calibrating }
            controller.enableWithConsent()
            awaitCondition { capture.starts.get() == 1 }
            val original = ConfigurationStore(directory.resolve("settings.json")).load()
            controller.updateConfiguration { it.copy(microphoneId = "usb") }
            awaitCondition { controller.state.value.error?.contains("End calibration") == true }
            assertEquals(original, controller.state.value.configuration)
            assertEquals(original, ConfigurationStore(directory.resolve("settings.json")).load())
            assertTrue(controller.state.value.calibrating)
            assertEquals(listOf("default"), capture.selectedDevices.toList())
            assertEquals(1, capture.starts.get())
            emitSyntheticClaps(capture, listOf(200))
            assertNotNull(controller.state.value.candidate)
        } finally { controller.shutdown() }
    }

    @Test fun `failed held out validation preserves the previous persisted and active profile`(): Unit = runBlocking {
        val priorSamples = AcousticClass.entries.flatMap { label ->
            List(2) { index -> CalibrationSample(label, List(9) { label.ordinal * 3.0 + index * 0.001 }) }
        }
        val prior = CalibrationTrainer.train(priorSamples, "default", "Prior known profile", minimumSamplesPerClass = 2)
        val profilePath = directory.resolve("calibration.json")
        CalibrationProfileStore(profilePath).save(prior)
        val originalBytes = Files.readAllBytes(profilePath)
        val capture = Capture()
        val adapter = Adapter()
        val controller = AppController(directory, capture, adapter)
        try {
            awaitCondition { controller.state.value.activeProfileName == prior.name }
            controller.beginCalibration(2)
            awaitCondition { controller.state.value.calibrating }
            controller.enableWithConsent()
            awaitCondition { capture.starts.get() == 1 }
            // The same actual PCM event is deliberately mislabeled as every class. Held-out
            // validation must reject this contradictory dataset rather than replace a profile.
            val labels = AcousticClass.entries.flatMap { label -> List(2) { label } }
            for ((index, label) in labels.withIndex()) {
                emitSyntheticClaps(capture, listOf(200), startMs = index * 400L)
                assertNotNull(controller.state.value.candidate)
                controller.acceptCandidate(label)
            }
            assertEquals(AcousticClass.entries.associateWith { 2 }, controller.state.value.sampleCounts)
            controller.trainProfile()
            awaitCondition {
                !controller.state.value.training &&
                    controller.state.value.validation?.contains("Validation failed") == true &&
                    controller.state.value.error?.contains("Validation failed") == true
            }
            assertTrue(controller.state.value.validation!!.contains("2-fold held-out validation"))
            assertTrue(controller.state.value.validation!!.contains("Confusion"))
            assertEquals(prior.name, controller.state.value.activeProfileName)
            assertContentEquals(originalBytes, Files.readAllBytes(profilePath))
            assertEquals(prior, CalibrationProfileStore(profilePath).load())
            assertTrue(adapter.executions.isEmpty())

            // Export exercises the active profile object, beyond the dashboard's name field.
            val exported = directory.resolve("retained-profile.json")
            controller.exportProfile(exported)
            awaitCondition { controller.state.value.activity.any { it.title == "Profile exported" && it.detail == exported.toString() } }
            assertEquals(prior, CalibrationProfileStore(exported).load())
            assertEquals(1, capture.starts.get())
        } finally { controller.shutdown() }
    }
}
