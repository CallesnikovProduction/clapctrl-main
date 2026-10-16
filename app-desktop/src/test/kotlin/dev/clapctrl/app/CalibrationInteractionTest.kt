package dev.clapctrl.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import dev.clapctrl.app.ui.Dashboard
import dev.clapctrl.core.*
import dev.clapctrl.core.calibration.CalibrationProfileStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*

/** Exercises rendered controls and production DSP/training with deterministic PCM and harmless OS fakes.
 * These synthetic shapes prove integration behavior, not real-world acoustic recognition accuracy. */
class CalibrationInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    private class Audio : AudioCaptureService {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        private val processed = AtomicInteger()
        private val frames = Channel<AudioFrame>(64)
        private var nextTimestamp = 0L
        override fun listDevices() = listOf(AudioInputDevice("default", "Synthetic test microphone"))
        override suspend fun capture(deviceId: String, onFrame: (AudioFrame) -> Unit) {
            starts.incrementAndGet()
            while (currentCoroutineContext().isActive) {
                onFrame(frames.receive())
                processed.incrementAndGet()
            }
        }
        override fun stop() { stops.incrementAndGet() }

        private suspend fun feed(samples: FloatArray) {
            val target = processed.get() + (samples.size + 479) / 480
            val start = nextTimestamp
            for (offset in samples.indices step 480) {
                frames.send(AudioFrame(samples.copyOfRange(offset, minOf(offset + 480, samples.size)), 48000, start + offset * 1000L / 48000))
            }
            nextTimestamp += samples.size * 1000L / 48000
            withTimeout(4000) { while (processed.get() < target) delay(5) }
        }

        suspend fun silence(durationMs: Int) = feed(FloatArray(48 * durationMs))

        suspend fun example(label: AcousticClass, readyAtMs: Long) {
            val quietMs = maxOf(400L, readyAtMs - nextTimestamp + 20)
            silence(((quietMs + 9) / 10 * 10).toInt())
            val samples = FloatArray(48 * 400)
            val random = Random(42)
            val durationMs = if (label == AcousticClass.SNAP) 20 else 120
            val decay = if (label == AcousticClass.SNAP) 0.003 else 0.030
            for (index in 0 until durationMs * 48) {
                val carrier = if (label == AcousticClass.NEGATIVE) sin(2 * PI * 440 * index / 48000.0) else random.nextDouble() * 2 - 1
                samples[50 * 48 + index] = (carrier * 0.8 * exp(-index / (48000 * decay))).toFloat()
            }
            feed(samples)
        }

        suspend fun doubleClap() {
            silence(400)
            val samples = FloatArray(48 * 700)
            for (onsetMs in listOf(50, 350)) {
                val random = Random(42)
                for (index in 0 until 120 * 48) {
                    samples[onsetMs * 48 + index] = ((random.nextDouble() * 2 - 1) * 0.8 * exp(-index / (48000 * 0.030))).toFloat()
                }
            }
            feed(samples)
        }
    }

    private fun saveLabelDialogScreenshot() {
        compose.onNodeWithText("What was that sound?", substring = false).assertIsDisplayed()
        AcousticClass.entries.forEach { label ->
            compose.onNodeWithTag("calibration-label-${label.name}").assertIsDisplayed().assertIsEnabled()
        }
        compose.onNodeWithTag("calibration-discard").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("calibration-pause-review").assertIsDisplayed().assertIsEnabled()

        // Desktop node capture crops the shared Skia surface using boundsInWindow. A dialog
        // has its own coordinate space, so capturing its tagged node can crop off its title.
        // The original main root spans the full test viewport and includes the dialog layer.
        val root = compose.onAllNodes(isRoot())[0]
        val viewport = root.fetchSemanticsNode().boundsInRoot
        assertTrue(viewport.width >= 1020 && viewport.height >= 720, "QA requires a viewport matching the desktop minimum window size: $viewport")
        val pixels = root.captureToImage().toPixelMap()
        assertTrue(pixels.width >= 1020 && pixels.height >= 720, "The screenshot must preserve the whole desktop test viewport")
        val output = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val path = Path.of("build", "qa", "calibration-label-dialog.png")
        Files.createDirectories(path.parent)
        assertTrue(ImageIO.write(output, "png", path.toFile()), "PNG writer must save the actual Compose dialog")
        val dialog = compose.onNodeWithTag("calibration-label-dialog").fetchSemanticsNode()
        val title = compose.onNodeWithText("What was that sound?", substring = false).fetchSemanticsNode()
        val caption = compose.onNodeWithText("Sample captured · collection is waiting for your answer.", substring = false)
            .assertIsDisplayed().fetchSemanticsNode()
        val waveformHeightPx = 100f * compose.density.density
        val titleToCaptionGap = caption.boundsInRoot.top - title.boundsInRoot.bottom
        assertTrue(titleToCaptionGap >= waveformHeightPx,
            "The 100 dp waveform must fit between the title and caption without overlapping the title: " +
                "gap=$titleToCaptionGap px, required=$waveformHeightPx px, title=${title.boundsInRoot}, caption=${caption.boundsInRoot}")
        Files.writeString(path.resolveSibling("calibration-label-dialog-bounds.txt"),
            "viewport=$viewport\nrootWindow=${root.fetchSemanticsNode().boundsInWindow}\n" +
                "dialogRoot=${dialog.boundsInRoot}\ndialogWindow=${dialog.boundsInWindow}\n" +
                "titleRoot=${title.boundsInRoot}\ntitleWindow=${title.boundsInWindow}\n")
    }

    @Test fun `guided calibration controls collect review undo validate and test an actual profile without reopening input`() {
        val audio = Audio()
        val executions = CopyOnWriteArrayList<ActionConfiguration>()
        val adapter = object : PlatformAdapter {
            override fun capabilities() = ActionType.entries.map { ActionCapability(it, true, "Harmless test backend") }
            override suspend fun execute(action: ActionConfiguration): ActionResult {
                executions += action
                return ActionResult(true, "Explicit fake OS action completed")
            }
        }
        val controller = AppController(files.root.toPath(), audio, adapter)
        try {
            compose.setContent {
                Dashboard(controller, requestMonitoring = {
                    if (controller.state.value.listening || controller.state.value.starting) controller.pause()
                    else controller.enableWithConsent()
                })
            }
            compose.onNodeWithText("Calibration", substring = false).performClick()
            compose.onNodeWithTag("calibration-start").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("calibration-train").performScrollTo().assertIsNotEnabled()
            assertEquals(0, audio.starts.get())
            compose.onNodeWithText("Enable monitoring", substring = false).performScrollTo().performClick()
            compose.waitUntil(4000) { audio.starts.get() == 1 }
            runBlocking { audio.silence(200) }
            compose.waitUntil(4000) { controller.state.value.listening }
            compose.onNodeWithTag("calibration-minimum").performScrollTo().performTextReplacement("5")
            compose.onNodeWithTag("calibration-start").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(4000) { controller.state.value.calibrating }
            assertEquals(5, controller.state.value.calibrationMinimumSamples)
            compose.onNodeWithTag("calibration-minimum").assertIsNotEnabled()
            compose.onNodeWithTag("calibration-label-dialog").assertDoesNotExist()
            compose.onNodeWithTag("calibration-undo").performScrollTo().assertIsNotEnabled()

            // Reject and undo are real controls: neither operation should silently add examples.
            runBlocking { audio.example(AcousticClass.CLAP, controller.state.value.calibrationReadyAtMs) }
            compose.waitUntil(4000) { controller.state.value.candidate != null }
            compose.onNodeWithTag("calibration-label-dialog").assertExists()
            compose.onNodeWithText("What was that sound?", substring = false).assertIsDisplayed()
            saveLabelDialogScreenshot()
            val frozen = assertNotNull(controller.state.value.candidate)
            val processedBeforeSecondSound = controller.state.value.framesProcessed
            runBlocking { audio.example(AcousticClass.SNAP, controller.state.value.calibrationReadyAtMs) }
            compose.waitUntil(4000) { controller.state.value.framesProcessed > processedBeforeSecondSound }
            assertSame(frozen, controller.state.value.candidate, "A later sound must not replace the event awaiting a label")
            assertTrue(controller.state.value.sampleCounts.isEmpty(), "Audio must not count itself without a user's label")
            assertTrue(controller.state.value.listening)
            compose.onNodeWithTag("calibration-label-dialog").assertExists()
            compose.onNodeWithTag("calibration-discard").assertIsEnabled().performClick()
            compose.waitUntil(4000) { controller.state.value.candidate == null }
            compose.onNodeWithTag("calibration-label-dialog").assertDoesNotExist()
            assertTrue(controller.state.value.sampleCounts.isEmpty())
            runBlocking { audio.example(AcousticClass.CLAP, controller.state.value.calibrationReadyAtMs) }
            compose.onNodeWithTag("calibration-label-CLAP").assertIsEnabled().performClick()
            compose.waitUntil(4000) { controller.state.value.sampleCounts[AcousticClass.CLAP] == 1 }
            compose.onNodeWithTag("calibration-count-CLAP").assertTextContains("1/5", substring = true)
            runBlocking { audio.example(AcousticClass.NEGATIVE, controller.state.value.calibrationReadyAtMs) }
            compose.waitUntil(4000) { controller.state.value.candidate != null }
            compose.onNodeWithTag("calibration-pause-review").assertIsEnabled().performClick()
            compose.waitUntil(4000) { !controller.state.value.calibrating && controller.state.value.testingRecognition }
            compose.onNodeWithTag("calibration-label-dialog").assertDoesNotExist()
            assertNull(controller.state.value.candidate)
            assertEquals(mapOf(AcousticClass.CLAP to 1), controller.state.value.sampleCounts)
            compose.onNodeWithTag("calibration-start").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(4000) { controller.state.value.calibrating && !controller.state.value.testingRecognition }
            assertEquals(mapOf(AcousticClass.CLAP to 1), controller.state.value.sampleCounts)
            assertEquals(1, audio.starts.get())
            assertEquals(0, audio.stops.get())
            compose.onNodeWithTag("calibration-undo").performScrollTo().performClick()
            compose.waitUntil(4000) { controller.state.value.sampleCounts.isEmpty() }
            compose.onNodeWithTag("calibration-train").performScrollTo().assertIsNotEnabled()

            compose.onNodeWithTag("calibration-end").performScrollTo().performClick()
            compose.waitUntil(4000) { !controller.state.value.calibrating && controller.state.value.testingRecognition }
            compose.onNodeWithTag("calibration-start").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(4000) { controller.state.value.calibrating && !controller.state.value.testingRecognition }
            compose.onNodeWithText("Reset samples", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Reset collected examples?", substring = false).assertIsDisplayed()
            compose.onNodeWithText("Keep samples", substring = false).performClick()
            assertTrue(controller.state.value.calibrationAvailable)
            compose.onNodeWithText("Reset samples", substring = false).performScrollTo().performClick()
            compose.onAllNodesWithText("Reset samples", substring = false)[1].performClick()
            compose.waitUntil(4000) { !controller.state.value.calibrationAvailable }
            compose.onNodeWithTag("calibration-minimum").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("calibration-start").performScrollTo().performClick()
            compose.waitUntil(4000) { controller.state.value.calibrating }

            val expectedCounts = mapOf(AcousticClass.CLAP to 10, AcousticClass.SNAP to 5, AcousticClass.NEGATIVE to 5)
            for (label in AcousticClass.entries) {
                repeat(expectedCounts.getValue(label)) { index ->
                    if (label == AcousticClass.NEGATIVE && index == 4) {
                        assertFalse(controller.state.value.readyToTrain)
                        compose.onNodeWithTag("calibration-train").performScrollTo().assertIsNotEnabled()
                    }
                    runBlocking { audio.example(label, controller.state.value.calibrationReadyAtMs) }
                    compose.waitUntil(4000) { controller.state.value.candidate != null }
                    compose.onNodeWithTag("calibration-label-dialog").assertExists()
                    compose.onNodeWithTag("calibration-label-${label.name}").assertIsEnabled().performClick()
                    compose.waitUntil(4000) { controller.state.value.sampleCounts[label] == index + 1 && controller.state.value.candidate == null }
                    compose.onNodeWithTag("calibration-count-${label.name}").assertTextContains("${index + 1}/5", substring = true)
                }
                if (label == AcousticClass.CLAP) {
                    assertEquals(mapOf(AcousticClass.CLAP to 10), controller.state.value.sampleCounts)
                    assertFalse(controller.state.value.readyToTrain)
                    assertFalse(controller.state.value.calibrationSaved)
                    assertTrue(controller.state.value.hasUnsavedCalibration)
                    assertNull(controller.state.value.activeProfileName)
                    assertTrue(Files.notExists(files.root.toPath().resolve("calibration.json")))
                    compose.onNodeWithTag("calibration-train").performScrollTo().assertIsNotEnabled()
                    compose.onNodeWithText("Still needed:", substring = true).assertTextContains("finger snap", substring = true)
                        .assertTextContains("other sounds", substring = true)
                    compose.onNodeWithText("Triggers", substring = false).performClick()
                    compose.onNodeWithTag("calibration-continue").assertIsDisplayed().assertIsEnabled().performClick()
                    compose.onNodeWithTag("calibration-count-CLAP").assertTextContains("10/5", substring = true)
                }
            }
            assertEquals(expectedCounts, controller.state.value.sampleCounts)
            assertTrue(controller.state.value.readyToTrain)
            assertFalse(controller.state.value.calibrating, "Collection must pause once every target is reached")
            assertTrue(controller.state.value.testingRecognition, "Completed collection must keep OS actions suspended")
            val processedAtCompletion = controller.state.value.framesProcessed
            runBlocking { audio.example(AcousticClass.CLAP, readyAtMs = 0) }
            compose.waitUntil(4000) { controller.state.value.framesProcessed > processedAtCompletion }
            compose.onNodeWithTag("calibration-label-dialog").assertDoesNotExist()
            assertNull(controller.state.value.candidate)
            assertEquals(expectedCounts, controller.state.value.sampleCounts)
            assertTrue(executions.isEmpty())
            compose.onNodeWithTag("calibration-train").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("Triggers", substring = false).performClick()
            compose.onNodeWithTag("calibration-activate").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10000) { !controller.state.value.training && controller.state.value.activeProfileName != null && controller.state.value.calibrationSaved }
            assertFalse(controller.state.value.calibrating)
            assertFalse(controller.state.value.testingRecognition, "Explicit save and activation must immediately enable real trigger dispatch")
            assertFalse(controller.state.value.hasUnsavedCalibration)
            assertNull(controller.state.value.error)
            val profilePath = files.root.toPath().resolve("calibration.json")
            assertTrue(Files.exists(profilePath))
            assertEquals(expectedCounts.mapKeys { it.key.name }, CalibrationProfileStore(profilePath).load().sampleCounts)
            assertTrue(controller.state.value.configuration.triggers.all { it.confidence == 0.75 })
            assertEquals(1, audio.starts.get())
            assertEquals(0, audio.stops.get())

            // Fresh sounds must immediately execute actual configured actions through the fake OS.
            runBlocking { audio.example(AcousticClass.SNAP, readyAtMs = 0) }
            compose.waitUntil(4000) { executions.size == 1 }
            assertEquals(ActionType.SCREENSHOT, executions.single().type)
            val score = assertNotNull(Regex("(\\d+)%").find(controller.state.value.lastGesture)).groupValues[1].toInt()
            assertTrue(score >= 75, "Saved classifier should pass the real runtime threshold; observed $score%")
            runBlocking { audio.doubleClap() }
            compose.waitUntil(4000) { executions.size == 2 }
            assertEquals(ActionType.OPEN_TERMINAL, executions.last().type)
            assertTrue(controller.state.value.lastGesture.startsWith("Double clap"))

            compose.onNodeWithText("Calibration", substring = false).performClick()
            compose.onNodeWithTag("recognition-test-toggle").performScrollTo().performClick()
            assertTrue(controller.state.value.testingRecognition)
            runBlocking { audio.example(AcousticClass.SNAP, readyAtMs = 0) }
            compose.waitUntil(4000) { controller.state.value.activity.any { it.title == "Recognition test · Finger snap" } }
            compose.onNodeWithTag("recognition-feedback").performScrollTo().assertTextContains("snap", substring = true)
            assertTrue(controller.state.value.activity.first { it.title == "Recognition test · Finger snap" }.detail.contains("Would run"))
            assertEquals(2, executions.size, "Optional recognition testing must suspend OS effects")
            compose.onNodeWithText("Triggers", substring = false).performClick()
            compose.onNodeWithTag("actions-enable").assertIsDisplayed().assertIsEnabled().performClick()
            assertFalse(controller.state.value.testingRecognition)
            runBlocking { audio.example(AcousticClass.SNAP, readyAtMs = 0) }
            compose.waitUntil(4000) { executions.size == 3 }
            assertEquals(ActionType.SCREENSHOT, executions.last().type)

            // The explicit action button reaches the fake backend, independently of recognition.
            compose.onAllNodesWithText("Test action")[1].performScrollTo().performClick()
            compose.waitUntil(4000) { executions.size == 4 }
            assertEquals(ActionType.SCREENSHOT, executions.last().type)
            assertEquals(1, audio.starts.get())
            assertEquals(0, audio.stops.get())
        } finally { runBlocking { controller.shutdown() } }
    }
}
