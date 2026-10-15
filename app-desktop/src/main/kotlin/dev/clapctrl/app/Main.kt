package dev.clapctrl.app

import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import dev.clapctrl.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.awt.Dimension
import java.awt.Robot
import java.awt.SystemTray
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO

private class AppIcon(private val active: Boolean) : Painter() {
    override val intrinsicSize = Size(32f, 32f)
    override fun DrawScope.onDraw() {
        drawCircle(if (active) Accent else Color(0xFF89968B), size.minDimension * 0.43f)
        drawCircle(Background, size.minDimension * 0.22f)
        drawCircle(if (active) Accent else Color(0xFF89968B), size.minDimension * 0.08f)
    }
}
private data class ShellStatus(val listening: Boolean = false, val starting: Boolean = false, val consentGiven: Boolean = false)

fun main(args: Array<String>) {
    args.firstOrNull { it.startsWith("--data-dir=") }?.substringAfter('=')?.let { System.setProperty("clapctrl.dataDir", it) }
    val directory = System.getProperty("clapctrl.dataDir")?.let(Path::of) ?: run {
        val base = if (System.getProperty("os.name").startsWith("Windows")) System.getenv("APPDATA") ?: System.getProperty("user.home")
            else System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config"
        Path.of(base, "clapctrl")
    }
    Files.createDirectories(directory)
    val lockChannel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = runCatching { lockChannel.tryLock() }.getOrNull()
    if (lock == null) {
        System.err.println("ClapCtrl is already running for $directory. Reopen it from the system tray.")
        lockChannel.close(); return
    }
    val controller = AppController(directory)
    val smokeDirectory = args.firstOrNull { it.startsWith("--ui-smoke=") }?.substringAfter('=')?.let(Path::of)
    val hardwareSmoke = "--hardware-smoke" in args
    require(!hardwareSmoke || (smokeDirectory != null && args.any { it.startsWith("--data-dir=") })) {
        "Hardware diagnostics require --ui-smoke and a separate explicit --data-dir"
    }
    try {
        application {
            val state by remember { controller.state.map { ShellStatus(it.listening, it.starting, it.configuration.consentGiven) }.distinctUntilChanged() }.collectAsState(ShellStatus())
            var visible by remember { mutableStateOf(true) }
            var consentDialog by remember { mutableStateOf(false) }
            var closeDialog by remember { mutableStateOf(false) }
            var smokePage by remember { mutableStateOf<Page?>(null) }
            val traySupported = remember { SystemTray.isSupported() }
            val uiScope = rememberCoroutineScope()
            val quit: () -> Unit = { uiScope.launch { controller.shutdown(); exitApplication() } }
            val toggle: () -> Unit = {
                if (!state.consentGiven) consentDialog = true else controller.toggleMonitoring()
            }
            if (traySupported) Tray(
                icon = AppIcon(state.listening),
                tooltip = "ClapCtrl · ${if (state.listening) "Listening" else if (state.starting) "Connecting" else "Paused"}",
                onAction = { visible = true },
                menu = {
                    Item("Open ClapCtrl", onClick = { visible = true })
                    Item(if (state.listening || state.starting) "Pause monitoring" else "Enable monitoring", onClick = { visible = true; toggle() })
                    Separator()
                    Item("Quit ClapCtrl", onClick = quit)
                },
            )
            val windowState = rememberWindowState(size = DpSize(1080.dp, 860.dp))
            Window(
                onCloseRequest = { if (traySupported) visible = false else closeDialog = true },
                state = windowState, title = "ClapCtrl — Sound-driven desktop automation", visible = visible,
                icon = AppIcon(state.listening),
            ) {
                LaunchedEffect(Unit) { window.minimumSize = Dimension(920, 680) }
                if (visible) Dashboard(controller, requestMonitoring = toggle, pageOverride = smokePage)
                if (consentDialog) ClapTheme {
                    AlertDialog(onDismissRequest = { consentDialog = false },
                        title = { Text("Enable continuous microphone monitoring?") },
                        text = { Text("ClapCtrl processes your microphone locally to detect claps and finger snaps. Audio is never uploaded or saved by default. Monitoring continues when the dashboard is hidden. You can pause it from the dashboard or tray at any time.") },
                        confirmButton = { Button(onClick = { consentDialog = false; controller.enableWithConsent() }) { Text("Enable microphone") } },
                        dismissButton = { TextButton(onClick = { consentDialog = false }) { Text("Keep off") } },
                    )
                }
                if (closeDialog) ClapTheme {
                    AlertDialog(onDismissRequest = { closeDialog = false }, title = { Text("No system tray is available") },
                        text = { Text("Minimize the window to keep monitoring visible in your taskbar, or quit and release the microphone.") },
                        confirmButton = { Button(onClick = { closeDialog = false; windowState.isMinimized = true }) { Text("Minimize") } },
                        dismissButton = { TextButton(onClick = quit) { Text("Quit") } },
                    )
                }
                if (smokeDirectory != null) LaunchedEffect(Unit) {
                    Files.createDirectories(smokeDirectory)
                    if (hardwareSmoke) {
                        controller.updateConfiguration { it.copy(triggers = it.triggers.map { trigger -> trigger.copy(enabled = false) }) }
                        withTimeout(5000) { while (controller.state.value.configuration.triggers.any { it.enabled }) delay(20) }
                        controller.enableWithConsent()
                        withTimeout(12000) { while (!controller.state.value.listening || controller.state.value.framesProcessed < 30) delay(20) }
                    }
                    delay(3500)
                    for (page in Page.entries) {
                        smokePage = page
                        delay(700)
                        withContext(Dispatchers.IO) { ImageIO.write(Robot().createScreenCapture(window.bounds), "png", smokeDirectory.resolve("${page.name.lowercase()}.png").toFile()) }
                    }
                    windowState.size = DpSize(920.dp, 680.dp)
                    smokePage = Page.TRIGGERS
                    delay(700)
                    withContext(Dispatchers.IO) { ImageIO.write(Robot().createScreenCapture(window.bounds), "png", smokeDirectory.resolve("resized.png").toFile()) }
                    visible = false
                    val hiddenStartFrames = controller.state.value.framesProcessed
                    delay(500)
                    check(window.isVisible.not()) { "Window failed to hide" }
                    if (hardwareSmoke) check(controller.state.value.framesProcessed > hiddenStartFrames) { "Microphone stopped processing while window hidden" }
                    visible = true
                    delay(500)
                    check(window.isVisible) { "Window failed to reopen" }
                    if (hardwareSmoke) {
                        controller.pause()
                        withTimeout(5000) { while (controller.state.value.listening || controller.state.value.starting) delay(20) }
                        val pausedFrames = controller.state.value.framesProcessed
                        delay(200)
                        check(controller.state.value.framesProcessed == pausedFrames) { "Frames arrived after pause" }
                        controller.start()
                        withTimeout(8000) { while (!controller.state.value.listening || controller.state.value.framesProcessed <= pausedFrames) delay(20) }
                        println("HARDWARE_SMOKE_PASS: real PCM ${controller.state.value.sampleRate} Hz; capture continued hidden; pause/release/resume; all actions disabled.")
                    } else println("UI_SMOKE_PASS: four pages, minimum size, hide/reopen. Microphone remained off.")
                    controller.shutdown()
                    exitApplication()
                }
            }
        }
    } finally {
        runBlocking { controller.shutdown() }
        lock.release(); lockChannel.close()
    }
}
