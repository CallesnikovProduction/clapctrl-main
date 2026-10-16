package dev.clapctrl.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.clapctrl.app.ui.Dashboard
import dev.clapctrl.core.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Real Compose controls, fake OS effects: this suite never captures audio or changes the desktop. */
class DashboardInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    @Test fun `switch action editor test button and navigation reach actual controller state`() {
        val executions = CopyOnWriteArrayList<ActionConfiguration>()
        val adapter = object : PlatformAdapter {
            override fun capabilities() = ActionType.entries.map { ActionCapability(it, true, "Test backend") }
            override suspend fun execute(action: ActionConfiguration): ActionResult {
                executions += action
                return ActionResult(true, "Verified fake OS effect")
            }
        }
        val audio = object : AudioCaptureService {
            override fun listDevices() = listOf(AudioInputDevice("default", "Test microphone"))
            override suspend fun capture(deviceId: String, onFrame: (AudioFrame) -> Unit): Unit = awaitCancellation()
            override fun stop() = Unit
        }
        val controller = AppController(files.root.toPath(), audio, adapter)
        try {
            compose.setContent { Dashboard(controller, requestMonitoring = {}) }
            compose.onNodeWithContentDescription("Double clap trigger").performClick()
            compose.waitUntil(4000) { !controller.state.value.configuration.triggers.first().enabled }
            compose.onNodeWithText("Open terminal   ⌄").performClick()
            compose.onNodeWithText("Configure desktop action").assertIsDisplayed()
            compose.onNodeWithText("Open terminal", substring = false).performClick()
            compose.onNodeWithText("Toggle system mute").performClick()
            compose.onNodeWithText("Save action").performClick()
            compose.waitUntil(4000) { controller.state.value.configuration.triggers.first().action.type == ActionType.TOGGLE_MUTE }
            compose.onAllNodesWithText("Test action")[0].performClick()
            compose.waitUntil(4000) { executions.isNotEmpty() }
            assertEquals(ActionType.TOGGLE_MUTE, executions.single().type)
            assertFalse(controller.state.value.listening)
            compose.onNodeWithText("Activity").performClick()
            compose.onNodeWithText("Action test · Toggle system mute").assertExists()
            compose.onNodeWithText("Audio input").performClick()
            compose.onNodeWithText("Refresh inputs").assertExists().performClick()
            compose.onNodeWithText("Calibration").performClick()
            compose.onNodeWithText("Start session").assertExists()
            compose.onNodeWithTag("calibration-train").assertIsNotEnabled()
        } finally { runBlocking { controller.shutdown() } }
    }
}
