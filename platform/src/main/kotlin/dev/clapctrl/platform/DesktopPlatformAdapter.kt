package dev.clapctrl.platform

import dev.clapctrl.core.ActionCapability
import dev.clapctrl.core.ActionConfiguration
import dev.clapctrl.core.ActionResult
import dev.clapctrl.core.ActionType
import dev.clapctrl.core.PlatformAdapter
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID

class DesktopPlatformAdapter(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val osName: String = System.getProperty("os.name"),
    private val environment: Map<String, String> = System.getenv(),
    private val findExecutable: (String) -> String? = { findOnPath(it) },
    private val dataDirectory: Path = defaultDataDirectory(),
) : PlatformAdapter {
    private val windows get() = osName.startsWith("Windows", ignoreCase = true)
    private val linux get() = osName.contains("Linux", ignoreCase = true)
    private val hyprland get() = !environment["HYPRLAND_INSTANCE_SIGNATURE"].isNullOrBlank()
    private val wayland get() = !environment["WAYLAND_DISPLAY"].isNullOrBlank()
    private data class Plan(val argv: List<String>, val detail: String, val launch: Boolean = false, val env: Map<String, String> = emptyMap())

    override fun capabilities(): List<ActionCapability> = ActionType.entries.map { type ->
        val plan = plan(ActionConfiguration(type), null)
        ActionCapability(type, type == ActionType.CUSTOM_COMMAND || plan != null,
            if (type == ActionType.CUSTOM_COMMAND) "Requires explicit approval; argument list, no implicit shell" else plan?.detail ?: unsupported(type))
    }

    override suspend fun execute(action: ActionConfiguration): ActionResult {
        if (action.timeoutMs !in 100..120000) return ActionResult(false, "Timeout must be between 100 and 120000 ms")
        if (action.type == ActionType.SWITCH_WORKSPACE && action.workspace !in 1..999) return ActionResult(false, "Workspace must be between 1 and 999")
        if (action.type == ActionType.CUSTOM_COMMAND) {
            if (!action.approved) return ActionResult(false, "Custom command has not been explicitly approved")
            if (action.command.isEmpty() || action.command.first().isBlank() || action.command.any { '\u0000' in it }) return ActionResult(false, "Provide an executable and a valid argument list")
        }
        return try {
            // Capability detection itself never creates directories or captures the screen.
            if (plan(action, null) == null) return ActionResult(false, unsupported(action.type))
            val screenshot = if (action.type == ActionType.SCREENSHOT) {
                Files.createDirectories(dataDirectory.resolve("screenshots"))
                dataDirectory.resolve("screenshots").resolve("clapctrl-${Instant.now().toEpochMilli()}-${UUID.randomUUID().toString().take(8)}.png")
            } else null
            var selected = plan(action, screenshot) ?: return ActionResult(false, unsupported(action.type))
            if (linux && action.type == ActionType.SWITCH_WORKSPACE) {
                // Hyprland 0.55 introduced Lua dispatch syntax; retain compatibility with older sessions.
                val version = runner.run(CommandRequest(listOf(selected.argv.first(), "version"), minOf(action.timeoutMs, 1500)))
                val match = Regex("(?:v|Hyprland\\s+)(\\d+)\\.(\\d+)").find(version.output)
                if (match != null && (match.groupValues[1].toInt() > 0 || match.groupValues[2].toInt() >= 55)) {
                    selected = selected.copy(argv = listOf(selected.argv.first(), "dispatch", "hl.dsp.focus({ workspace = \"${action.workspace}\" })"))
                }
            }
            val result = runner.run(CommandRequest(selected.argv, action.timeoutMs, selected.launch, selected.env))
            when {
                result.timedOut -> ActionResult(false, "Action timed out after ${action.timeoutMs} ms")
                result.launched -> ActionResult(true, "Launched: ${selected.detail}")
                result.exitCode != 0 -> ActionResult(false, "${selected.detail} failed (exit ${result.exitCode}): ${result.output.take(2000)}")
                screenshot != null && (!Files.isRegularFile(screenshot) || Files.size(screenshot) == 0L) -> ActionResult(false, "Screenshot tool exited without creating the image")
                screenshot != null -> ActionResult(true, "Screenshot saved: $screenshot")
                else -> ActionResult(true, "Completed: ${selected.detail}")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ActionResult(false, "Action failed: ${error.message ?: error.javaClass.simpleName}") }
    }

    private fun unsupported(type: ActionType): String = when {
        !windows && !linux -> "Unsupported operating system: $osName"
        windows && type == ActionType.SWITCH_WORKSPACE -> "Selecting a numbered Windows virtual desktop is unsupported"
        type == ActionType.SWITCH_WORKSPACE -> "Workspace switching requires an active Hyprland session and hyprctl"
        else -> "No supported ${type.name.lowercase().replace('_', ' ')} backend detected in this session"
    }

    private fun plan(action: ActionConfiguration, screenshot: Path?): Plan? {
        if (action.type == ActionType.CUSTOM_COMMAND) return Plan(action.command, "Approved custom command")
        if (windows) return windowsPlan(action, screenshot)
        if (!linux) return null
        fun first(vararg names: String) = names.firstNotNullOfOrNull { name -> findExecutable(name)?.let { name to it } }
        return when (action.type) {
            ActionType.OPEN_TERMINAL -> first("xdg-terminal-exec", "foot", "kitty", "alacritty", "wezterm", "gnome-terminal", "konsole", "x-terminal-emulator")?.let { Plan(listOf(it.second), it.first, true) }
            ActionType.SCREENSHOT -> when {
                wayland && findExecutable("grim") != null -> Plan(listOf(findExecutable("grim")!!, screenshot?.toString() ?: "capture.png"), "Wayland screenshot via grim")
                !wayland && !environment["DISPLAY"].isNullOrBlank() && findExecutable("scrot") != null -> Plan(listOf(findExecutable("scrot")!!, screenshot?.toString() ?: "capture.png"), "X11 screenshot via scrot")
                findExecutable("gnome-screenshot") != null -> Plan(listOf(findExecutable("gnome-screenshot")!!, "-f", screenshot?.toString() ?: "capture.png"), "GNOME screenshot")
                else -> null
            }
            ActionType.LOCK_SCREEN -> when {
                hyprland && findExecutable("hyprlock") != null -> Plan(listOf(findExecutable("hyprlock")!!), "Hyprland lock via hyprlock", true)
                !environment["XDG_SESSION_ID"].isNullOrBlank() && findExecutable("loginctl") != null -> Plan(listOf(findExecutable("loginctl")!!, "lock-session", environment.getValue("XDG_SESSION_ID")), "Session lock via loginctl")
                else -> null
            }
            ActionType.APP_LAUNCHER -> first("walker", "fuzzel", "wofi")?.let { (name, executable) -> Plan(if (name == "wofi") listOf(executable, "--show", "drun") else listOf(executable), name, true) }
                ?: if (!wayland && !environment["DISPLAY"].isNullOrBlank()) findExecutable("rofi")?.let { Plan(listOf(it, "-show", "drun"), "rofi", true) } else null
            ActionType.MEDIA_PLAY_PAUSE -> findExecutable("playerctl")?.let { Plan(listOf(it, "play-pause"), "MPRIS playback via playerctl") }
            ActionType.TOGGLE_MUTE -> findExecutable("wpctl")?.let { Plan(listOf(it, "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"), "PipeWire mute via wpctl") }
                ?: findExecutable("pactl")?.let { Plan(listOf(it, "set-sink-mute", "@DEFAULT_SINK@", "toggle"), "PulseAudio mute via pactl") }
            ActionType.SWITCH_WORKSPACE -> if (hyprland) findExecutable("hyprctl")?.let { Plan(listOf(it, "dispatch", "workspace", action.workspace.toString()), "Hyprland workspace ${action.workspace}") } else null
            ActionType.CUSTOM_COMMAND -> error("Handled above")
        }
    }

    private fun windowsPlan(action: ActionConfiguration, screenshot: Path?): Plan? {
        val powershell = findExecutable("powershell.exe") ?: return null
        fun script(script: String, detail: String, env: Map<String, String> = emptyMap()) = Plan(
            listOf(powershell, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))), detail, env = env)
        return when (action.type) {
            ActionType.OPEN_TERMINAL -> findExecutable("wt.exe")?.let { Plan(listOf(it), "Windows terminal", true) }
                ?: script("${'$'}ErrorActionPreference = 'Stop'; Start-Process -FilePath ${'$'}env:CLAPCTRL_TERMINAL", "Windows PowerShell terminal launched", mapOf("CLAPCTRL_TERMINAL" to powershell))
            ActionType.SCREENSHOT -> script(WINDOWS_SCREENSHOT, "Windows screenshot", mapOf("CLAPCTRL_SCREENSHOT" to (screenshot?.toAbsolutePath()?.toString() ?: "capture.png")))
            ActionType.LOCK_SCREEN -> script(WINDOWS_LOCK, "Windows workstation lock")
            ActionType.APP_LAUNCHER -> script(windowsKey(0x5B), "Windows Start launcher")
            ActionType.MEDIA_PLAY_PAUSE -> script(windowsKey(0xB3), "Windows media play/pause")
            ActionType.TOGGLE_MUTE -> script(windowsKey(0xAD), "Windows audio mute")
            ActionType.SWITCH_WORKSPACE -> null
            ActionType.CUSTOM_COMMAND -> error("Handled above")
        }
    }

    companion object {
        fun defaultDataDirectory(): Path = System.getProperty("clapctrl.dataDir")?.let { Path.of(it) }
            ?: if (System.getProperty("os.name").startsWith("Windows")) Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "ClapCtrl")
            else Path.of(System.getenv("XDG_DATA_HOME") ?: Path.of(System.getProperty("user.home"), ".local", "share").toString(), "clapctrl")

        internal fun findOnPath(name: String): String? = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).asSequence()
            .filter { it.isNotBlank() }.map { Path.of(it.trim('"')).resolve(name) }
            .firstOrNull { Files.isRegularFile(it) && (System.getProperty("os.name").startsWith("Windows") || Files.isExecutable(it)) }?.toAbsolutePath()?.toString()

        private const val WINDOWS_SCREENSHOT = """
${'$'}ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public static class ClapDpi { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }'
[void][ClapDpi]::SetProcessDPIAware()
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
${'$'}bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
${'$'}bitmap = New-Object System.Drawing.Bitmap ${'$'}bounds.Width, ${'$'}bounds.Height
${'$'}graphics = [System.Drawing.Graphics]::FromImage(${'$'}bitmap)
try { ${'$'}graphics.CopyFromScreen(${'$'}bounds.Location, [System.Drawing.Point]::Empty, ${'$'}bounds.Size); ${'$'}bitmap.Save(${'$'}env:CLAPCTRL_SCREENSHOT, [System.Drawing.Imaging.ImageFormat]::Png) }
finally { ${'$'}graphics.Dispose(); ${'$'}bitmap.Dispose() }
"""
        private const val WINDOWS_LOCK = """
${'$'}ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class ClapLock { [DllImport("user32.dll", SetLastError=true)] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool LockWorkStation(); }'
if (-not [ClapLock]::LockWorkStation()) { throw 'LockWorkStation failed' }
"""
        private fun windowsKey(key: Int): String {
            require(key in listOf(0x5B, 0xB3, 0xAD))
            return """
${'$'}ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ClapKeys {
 [StructLayout(LayoutKind.Sequential)] public struct Mouse { public int x,y; public uint mouseData,flags,time; public UIntPtr extra; }
 [StructLayout(LayoutKind.Sequential)] public struct Key { public ushort vk,scan; public uint flags,time; public UIntPtr extra; }
 [StructLayout(LayoutKind.Explicit)] public struct Union { [FieldOffset(0)] public Mouse mouse; [FieldOffset(0)] public Key key; }
 [StructLayout(LayoutKind.Sequential)] public struct Input { public uint type; public Union data; }
 [DllImport("user32.dll", SetLastError=true)] public static extern uint SendInput(uint n, Input[] inputs, int size);
 public static void Tap(ushort vk) { var inputs = new Input[2]; inputs[0].type=1; inputs[0].data.key.vk=vk; inputs[1].type=1; inputs[1].data.key.vk=vk; inputs[1].data.key.flags=2; if (SendInput(2, inputs, Marshal.SizeOf(typeof(Input))) != 2) throw new Exception("SendInput failed; elevated windows may block input"); }
}
'@
[ClapKeys]::Tap($key)
"""
        }
    }
}
