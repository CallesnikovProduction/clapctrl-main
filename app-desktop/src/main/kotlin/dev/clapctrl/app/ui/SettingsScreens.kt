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
import androidx.compose.ui.platform.testTag
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
        Hint("${state.inputDb} · ${state.inputOpenedInMs?.let { "Opened in $it ms" } ?: "Waiting for input"}\nLast event: ${state.lastAcousticEvent}")
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
    SectionTitle("Calibrate your microphone", "Make a sound. We catch it, then ask you what it was.")
    var count by remember { mutableStateOf("10") }
    var resetDialog by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("1 · Start with live input")
        Hint(if (state.listening) "Microphone is delivering real audio · ${state.inputDb}. Desktop actions stop during calibration." else "Enable monitoring above and wait until the microphone is open. Calibration cannot collect samples while input is off.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(count, onValueChange = { count = it }, label = { Text("Examples per class") }, modifier = Modifier.width(175.dp).testTag("calibration-minimum"), singleLine = true, enabled = !state.calibrationAvailable && !state.training)
            if (!state.calibrationAvailable) Button(onClick = {
                val minimum = count.toIntOrNull()
                if (minimum == null || minimum !in 5..200) controller.report("Use 5–200 examples per class") else controller.beginCalibration(minimum)
            }, enabled = state.listening && !state.training, modifier = Modifier.testTag("calibration-start")) { Text("Start session") }
            else if (!state.calibrating) Button(onClick = controller::resumeCalibration, enabled = state.listening && !state.training, modifier = Modifier.testTag("calibration-start")) { Text("Resume session") }
            if (state.calibrating) TextButton(onClick = controller::endCalibration, enabled = !state.training, modifier = Modifier.testTag("calibration-end")) { Text("Pause session") }
            if (state.calibrationAvailable) TextButton(onClick = { resetDialog = true }, enabled = !state.training) { Text("Reset samples") }
        }
        Hint("10 EACH: handclaps + finger snaps + other sounds = 30 accepted samples. Collection alone does not train a profile: finish with Save profile & enable actions. Raw audio is never saved.")
    }
    if (state.calibrationAvailable) Panel(Modifier.fillMaxWidth()) {
        Eyebrow("2 · Make a sound, then label it")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AcousticClass.entries.forEach { label ->
                Text("${label.sampleLabel()}  ${state.sampleCounts[label] ?: 0}/${state.calibrationMinimumSamples}", fontSize = 12.sp,
                    color = if ((state.sampleCounts[label] ?: 0) >= state.calibrationMinimumSamples) Accent else Muted,
                    modifier = Modifier.weight(1f).testTag("calibration-count-${label.name}"))
            }
        }
        val suggestedClass = AcousticClass.entries.firstOrNull { (state.sampleCounts[it] ?: 0) < state.calibrationMinimumSamples }
        val instruction = when (suggestedClass) {
            AcousticClass.CLAP -> "Try ONE handclap. A question will appear when the sound is captured."
            AcousticClass.SNAP -> "Try ONE finger snap. A question will appear when the sound is captured."
            AcousticClass.NEGATIVE -> "Try another sound: a key press, mouse click or desk tap. Label it Other sounds."
            null -> "All three classes have enough examples. Validate and save below."
        }
        val candidate = state.candidate
        Text(when {
            !state.calibrating && state.readyToTrain -> "Collection complete · ready to validate and save"
            !state.calibrating -> "Session paused · accepted samples are kept"
            !state.listening -> "Microphone is paused · enable monitoring to continue"
            state.calibrationWaitingMs > 0 -> "Get ready · waiting for button noise to settle…"
            candidate != null -> "Sound captured · waiting for your answer"
            else -> instruction
        }, color = Accent, fontSize = 14.sp, modifier = Modifier.testTag("calibration-feedback"))
        Hint("Collection waits while the question is open. Further sounds cannot replace the captured example. After your answer, wait for button noise to settle before making another sound.")
        Hint("If the live meter moves but no sample appears, try a closer, clearer sound and increase onset sensitivity in Audio input. Leave silence between sounds.")
        TextButton(onClick = controller::undoLastSample, enabled = state.sampleCounts.values.sum() > 0 && !state.training, modifier = Modifier.testTag("calibration-undo")) { Text("Undo last") }
    }
    val captured = state.candidate
    if (state.calibrating && captured != null) AlertDialog(
        modifier = Modifier.testTag("calibration-label-dialog"),
        onDismissRequest = controller::rejectCandidate,
        buttons = {
            // One normal column avoids AlertDialog's text-baseline positioning pulling the
            // waveform above the heading when the first text follows a non-text visual.
            Column(Modifier.width(540.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("What was that sound?", style = MaterialTheme.typography.h6)
                Waveform(captured.waveform)
                Text("Sample captured · collection is waiting for your answer.", color = Accent)
                Hint(state.calibrationProgress)
                Hint("Label what you actually made. Choose Other sounds for typing, clicks or taps; Discard if unsure.")
                Hint("${"%.0f".format(captured.features.durationMs)} ms · ${(captured.confidence * 100).toInt()}% diagnostic score. Desktop actions stay suspended.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AcousticClass.entries.forEach { label ->
                        OutlinedButton(onClick = { controller.acceptCandidate(label) }, enabled = !state.training,
                            modifier = Modifier.weight(1f).testTag("calibration-label-${label.name}")) { Text("${label.sampleLabel()} ${state.sampleCounts[label] ?: 0}/${state.calibrationMinimumSamples}", fontSize = 11.sp) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = controller::endCalibration, modifier = Modifier.testTag("calibration-pause-review")) { Text("Pause session") }
                    TextButton(onClick = controller::rejectCandidate, modifier = Modifier.testTag("calibration-discard")) { Text("Discard sample") }
                }
            }
        },
    )
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("3 · Validate, save and try it")
        val missing = AcousticClass.entries.mapNotNull { label ->
            val remaining = state.calibrationMinimumSamples - (state.sampleCounts[label] ?: 0)
            if (remaining > 0) "$remaining ${label.sampleLabel().lowercase()}" else null
        }
        if (!state.readyToTrain) Hint("Still needed: ${missing.joinToString(", ")}. Collect all three classes before saving.")
        Hint("This button checks your samples, saves the profile, and enables automatic desktop actions. Failed validation shows the reason and keeps actions paused. Safe recognition testing remains optional.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = controller::trainProfile, enabled = state.readyToTrain && !state.training, modifier = Modifier.testTag("calibration-train")) { Text(if (state.training) "Validating…" else "Save profile & enable actions") }
            TextButton(onClick = { chooseProfile(false)?.let(controller::importProfile) }, enabled = !state.training && !state.calibrating) { Text("Import") }
            TextButton(onClick = { chooseProfile(true)?.let(controller::exportProfile) }, enabled = state.profileName != null && !state.training) { Text("Export") }
        }
        Hint("Profile for this input: ${state.activeProfileName ?: "None · default classifier"}")
        state.validation?.let { report -> Text(report, fontSize = 11.sp, color = Muted, lineHeight = 17.sp) }
        OutlinedButton(onClick = controller::toggleRecognitionTest, enabled = state.listening && !state.calibrating && !state.training, modifier = Modifier.testTag("recognition-test-toggle")) {
            Text(if (state.testingRecognition) "Finish test · enable desktop actions" else "Test recognition safely")
        }
        if (state.testingRecognition) {
            Hint("Desktop actions are suspended. Try TWO claps within the interval, or ONE finger snap. Results appear below and in Activity.", Accent)
            Text(state.lastAcousticEvent, fontSize = 13.sp, modifier = Modifier.testTag("recognition-feedback"))
            Text(state.lastGesture, color = Accent, fontSize = 14.sp)
        }
    }
    if (resetDialog) AlertDialog(onDismissRequest = { resetDialog = false }, title = { Text("Reset collected examples?") },
        text = { Text("This clears the current session's accepted features. Your saved calibration profile is kept.") },
        confirmButton = { Button(onClick = { controller.resetCalibration(); resetDialog = false }) { Text("Reset samples") } },
        dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("Keep samples") } })
}

private fun AcousticClass.sampleLabel() = when (this) {
    AcousticClass.CLAP -> "Handclap"
    AcousticClass.SNAP -> "Finger snap"
    AcousticClass.NEGATIVE -> "Other sounds"
}

private fun chooseProfile(save: Boolean): Path? {
    val dialog = FileDialog(null as Frame?, if (save) "Export calibration profile" else "Import calibration profile", if (save) FileDialog.SAVE else FileDialog.LOAD)
    try {
        dialog.file = "calibration.json"
        dialog.isVisible = true
        return dialog.file?.let { Path.of(dialog.directory, it) }
    } finally { dialog.dispose() }
}
