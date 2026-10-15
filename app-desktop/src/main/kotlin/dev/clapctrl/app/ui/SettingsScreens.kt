package dev.clapctrl.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.clapctrl.app.*
import dev.clapctrl.core.*
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

@Composable fun AudioScreen(state: DashboardState, controller: AppController) {
    SectionTitle("Audio input", "Choose a microphone and tune the timing for your room.")
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("Microphone")
        val selected = state.devices.firstOrNull { it.id == state.configuration.microphoneId }
            ?: AudioInputDevice(state.configuration.microphoneId, "${state.configuration.microphoneId} (not available)")
        Choice(selected, state.devices, { it.name }) { device -> controller.updateConfiguration { it.copy(microphoneId = device.id) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Hint("Devices are scanned on request. Changing input restarts capture.")
            TextButton(onClick = controller::refreshDevices) { Text("Refresh inputs") }
        }
        Divider(color = Border)
        var sensitivity by remember(state.configuration.detector.sensitivity) { mutableStateOf(state.configuration.detector.sensitivity.toFloat()) }
        Text("Onset sensitivity  ${(sensitivity * 100).toInt()}%", fontSize = 14.sp)
        Hint("Higher values admit quieter transients. Classification still uses spectral and temporal features.")
        Slider(sensitivity, onValueChange = { sensitivity = it }, onValueChangeFinished = {
            controller.updateConfiguration { it.copy(detector = it.detector.copy(sensitivity = sensitivity.toDouble())) }
        })
        var minimum by remember(state.configuration.detector.doubleClapMinMs) { mutableStateOf(state.configuration.detector.doubleClapMinMs.toString()) }
        var maximum by remember(state.configuration.detector.doubleClapMaxMs) { mutableStateOf(state.configuration.detector.doubleClapMaxMs.toString()) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(minimum, onValueChange = { minimum = it }, label = { Text("Minimum interval · ms") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(maximum, onValueChange = { maximum = it }, label = { Text("Maximum interval · ms") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(onClick = {
            val min = minimum.toLongOrNull(); val max = maximum.toLongOrNull()
            if (min == null || max == null) controller.report("Intervals must be whole numbers in milliseconds")
            else controller.updateConfiguration { it.copy(detector = it.detector.copy(doubleClapMinMs = min, doubleClapMaxMs = max)) }
        }) { Text("Save timing") }
    }
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("Diagnostics")
        Hint("${if (state.sampleRate == 0) "No captured frames" else "Negotiated ${state.sampleRate} Hz PCM input"}\nAdaptive noise floor: ${"%.5f".format(state.noiseFloor)} RMS\nProfile: ${state.activeProfileName ?: "Uncalibrated · default heuristic"}")
        Hint("Output hardware is not needed for recognition. Feedback sounds are not included in this version.")
        Hint("Processed ${state.framesProcessed} captured frames this session.")
        Hint("Settings: ${controller.dataDirectory.resolve("settings.json")}")
    }
}

@Composable fun ActionEditor(initial: ActionConfiguration, capabilities: List<ActionCapability>, onDismiss: () -> Unit, onSave: (ActionConfiguration) -> Unit) {
    var type by remember { mutableStateOf(initial.type) }
    var executable by remember { mutableStateOf(initial.command.firstOrNull() ?: "") }
    var arguments by remember { mutableStateOf(initial.command.drop(1).joinToString("\n")) }
    var timeout by remember { mutableStateOf(initial.timeoutMs.toString()) }
    var workspace by remember { mutableStateOf(initial.workspace.toString()) }
    var approved by remember { mutableStateOf(initial.approved) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Configure desktop action") },
        text = {
            Column(Modifier.width(470.dp).heightIn(max = 490.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Choice(type, ActionType.entries, { it.label() }) { type = it }
                capabilities.firstOrNull { it.type == type }?.let { Hint(it.detail, if (it.supported) Muted else Danger) }
                if (type == ActionType.CUSTOM_COMMAND) {
                    OutlinedTextField(executable, onValueChange = { executable = it; approved = false }, label = { Text("Executable path or name") }, singleLine = true)
                    OutlinedTextField(arguments, onValueChange = { arguments = it; approved = false }, label = { Text("One argument per line · no quotes needed") }, modifier = Modifier.fillMaxWidth().height(115.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(approved, onCheckedChange = { approved = it })
                        Hint("I approve this command. It runs with my account privileges.")
                    }
                }
                if (type == ActionType.SWITCH_WORKSPACE) OutlinedTextField(workspace, onValueChange = { workspace = it }, label = { Text("Workspace number") }, singleLine = true)
                OutlinedTextField(timeout, onValueChange = { timeout = it }, label = { Text("Timeout · milliseconds") }, singleLine = true)
                error?.let { Hint(it, Danger) }
            }
        },
        confirmButton = { Button(onClick = {
            val timeoutMs = timeout.toLongOrNull(); val number = workspace.toIntOrNull()
            when {
                timeoutMs == null || timeoutMs !in 100..120000 -> error = "Timeout must be 100–120000 ms"
                type == ActionType.SWITCH_WORKSPACE && (number == null || number !in 1..999) -> error = "Workspace must be 1–999"
                type == ActionType.CUSTOM_COMMAND && (executable.isBlank() || !approved) -> error = "Enter and explicitly approve a command"
                else -> onSave(ActionConfiguration(type, if (type == ActionType.CUSTOM_COMMAND) listOf(executable) + if (arguments.isEmpty()) emptyList() else arguments.lines() else emptyList(), timeoutMs, number ?: 1, type == ActionType.CUSTOM_COMMAND && approved))
            }
        }) { Text("Save action") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable fun CalibrationScreen(state: DashboardState, controller: AppController) {
    SectionTitle("Make it sound like you", "Teach the classifier your claps, snaps and everyday background sounds.")
    var count by remember { mutableStateOf("30") }
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("1 · Collect examples")
        Hint("Select your microphone in Audio input, then enable monitoring. Clap once or snap once, inspect the captured transient and label it. Include typing and desk taps as negatives. Raw audio is kept only in memory.")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(count, onValueChange = { count = it }, label = { Text("Examples per class") }, modifier = Modifier.width(180.dp), singleLine = true, enabled = !state.calibrating)
            Button(onClick = {
                val minimum = count.toIntOrNull()
                if (minimum == null || minimum !in 5..200) controller.report("Use 5–200 examples per class") else controller.beginCalibration(minimum)
            }, enabled = !state.calibrating && !state.training) { Text("Start session") }
            if (state.calibrating) TextButton(onClick = controller::endCalibration, enabled = !state.training) { Text("End session") }
        }
        if (state.calibrating) Hint("Calibration is active. Desktop actions are suspended while you collect examples.", Accent)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AcousticClass.entries.forEach { label -> Text("${label.name.lowercase().replaceFirstChar { it.uppercase() }}   ${state.sampleCounts[label] ?: 0} / $count", fontSize = 13.sp, color = Accent) }
        }
        val candidate = state.candidate
        if (candidate != null) {
            Waveform(candidate.waveform)
            Hint("${"%.0f".format(candidate.features.durationMs)} ms · centroid ${"%.2f".format(candidate.features.centroid)} × Nyquist · crest ${"%.1f".format(candidate.features.crestFactor)}\nPredicted ${candidate.classification.name.lowercase()} · ${(candidate.confidence * 100).toInt()}% confidence")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AcousticClass.entries.forEach { label -> OutlinedButton(onClick = { controller.acceptCandidate(label) }, enabled = !state.training) { Text("Accept ${label.name.lowercase()}", fontSize = 11.sp) } }
                TextButton(onClick = controller::rejectCandidate, enabled = !state.training) { Text("Reject", fontSize = 11.sp) }
            }
        } else Hint(if (state.calibrating) "Waiting for a transient. Leave a short silence between examples." else "Start a session to collect microphone samples.")
    }
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("2 · Validate and use")
        Hint("Validation holds out examples and reports a confusion matrix. A profile must reach at least 80% precision and recall per class, with at most 10% false positives. Failed validation keeps the previous profile. Test again with fresh recordings.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = controller::trainProfile, enabled = state.sampleCounts.isNotEmpty() && !state.training) { Text(if (state.training) "Validating…" else "Validate & save profile") }
            TextButton(onClick = { chooseProfile(false)?.let(controller::importProfile) }) { Text("Import") }
            TextButton(onClick = { chooseProfile(true)?.let(controller::exportProfile) }, enabled = state.profileName != null) { Text("Export") }
        }
        Hint("Profile for this input: ${state.activeProfileName ?: "None · conservative default classifier"}")
        state.validation?.let { report -> Text(report, fontSize = 11.sp, color = Muted, lineHeight = 17.sp) }
    }
}

private fun chooseProfile(save: Boolean): Path? {
    val dialog = FileDialog(null as Frame?, if (save) "Export calibration profile" else "Import calibration profile", if (save) FileDialog.SAVE else FileDialog.LOAD)
    try {
        dialog.file = "calibration.json"
        dialog.isVisible = true
        return dialog.file?.let { Path.of(dialog.directory, it) }
    } finally { dialog.dispose() }
}
