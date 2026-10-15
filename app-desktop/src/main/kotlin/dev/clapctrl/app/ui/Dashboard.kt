package dev.clapctrl.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.clapctrl.app.*
import dev.clapctrl.core.*

enum class Page(val title: String, val glyph: String) { TRIGGERS("Triggers", "◎"), AUDIO("Audio input", "≋"), CALIBRATION("Calibration", "◈"), ACTIVITY("Activity", "≡") }

@Composable fun Dashboard(controller: AppController, requestMonitoring: () -> Unit, pageOverride: Page? = null) {
    val state by controller.state.collectAsState()
    var currentPage by remember { mutableStateOf(Page.TRIGGERS) }
    val page = pageOverride ?: currentPage
    ClapTheme {
        Row(Modifier.fillMaxSize().background(Background)) {
            Column(Modifier.width(185.dp).fillMaxHeight().background(Color(0xFF151916)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("◉", color = Accent, fontSize = 29.sp)
                    Column { Text("ClapCtrl", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("DESKTOP, HANDS FREE", fontSize = 8.sp, color = Muted, letterSpacing = 1.sp) }
                }
                Spacer(Modifier.height(26.dp))
                Eyebrow("Workspace")
                Page.entries.forEach { destination ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(if (page == destination) SurfaceRaised else Color.Transparent)
                        .clickable { currentPage = destination }.padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(destination.glyph, color = if (page == destination) Accent else Muted, fontSize = 19.sp)
                        Text(destination.title, color = if (page == destination) Ink else Muted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Divider(color = Border)
                Hint("Private by design")
                Text("Audio stays on this device.\nNo accounts. No cloud.", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                Text("v0.1.0  ·  LOCAL ENGINE", color = Muted, fontSize = 9.sp, letterSpacing = 0.8.sp)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 30.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Sound-driven desktop automation")
                        Spacer(Modifier.height(6.dp))
                        Text("Your desktop. On cue.", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(if (state.listening) "●  Listening" else if (state.starting) "◌  Connecting" else "○  Paused", color = if (state.listening) Accent else Muted,
                        fontSize = 12.sp, modifier = Modifier.border(1.dp, Border, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 9.dp))
                }
                state.error?.let { message ->
                    Row(Modifier.fillMaxWidth().background(Color(0xFF362623), RoundedCornerShape(8.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, color = Danger, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = controller::clearError) { Text("Dismiss", color = Danger) }
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Eyebrow("Audio engine")
                            Text(state.status, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                        Button(onClick = requestMonitoring, shape = RoundedCornerShape(7.dp)) {
                            Text(if (state.listening || state.starting) "Pause monitoring" else "Enable monitoring", fontSize = 12.sp)
                        }
                    }
                    Signal(state.levels, Modifier.height(34.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text((state.devices.firstOrNull { it.id == state.configuration.microphoneId }?.name ?: "Default microphone") +
                            if (state.listening) " · ${state.sampleRate} Hz" else " · input off", color = Muted, fontSize = 11.sp)
                        Text(state.lastGesture, color = Muted, fontSize = 11.sp)
                    }
                }
                when (page) {
                    Page.TRIGGERS -> TriggersScreen(state, controller, onCalibrate = { currentPage = Page.CALIBRATION })
                    Page.AUDIO -> AudioScreen(state, controller)
                    Page.CALIBRATION -> CalibrationScreen(state, controller)
                    Page.ACTIVITY -> ActivityScreen(state, controller)
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("●  Processed on your device", color = Muted, fontSize = 10.sp)
                    Text("Close to tray  ·  Quit from the tray menu", color = Muted, fontSize = 10.sp)
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 12.dp))
            }
        }
    }
}

@Composable private fun TriggersScreen(state: DashboardState, controller: AppController, onCalibrate: () -> Unit) {
    SectionTitle("Acoustic triggers", "Two gestures. Choose what happens next.")
    state.configuration.triggers.forEach { trigger ->
        var editAction by remember(trigger.gesture) { mutableStateOf(false) }
        fun update(next: TriggerConfiguration) = controller.updateConfiguration { config -> config.copy(triggers = config.triggers.map { if (it.gesture == next.gesture) next else it }) }
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(SurfaceRaised, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text(if (trigger.gesture == Gesture.DOUBLE_CLAP) "◉◉" else "✧", color = Accent, fontSize = 18.sp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(trigger.gesture.label(), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Hint(if (trigger.gesture == Gesture.DOUBLE_CLAP) "Two distinct handclaps in a short interval" else "A short, sharp finger-generated transient")
                }
                Switch(trigger.enabled, onCheckedChange = { update(trigger.copy(enabled = it)) }, modifier = Modifier.semantics { contentDescription = "${trigger.gesture.label()} trigger" })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Assigned action")
                    TextButton(onClick = { editAction = true }, contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp)) { Text("${trigger.action.type.label()}   ⌄") }
                }
                OutlinedButton(onClick = { controller.testAction(trigger) }, border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(7.dp)) { Text("Test action", fontSize = 12.sp) }
            }
            val capability = state.capabilities.firstOrNull { it.type == trigger.action.type }
            if (capability?.supported == false) Hint("Unavailable: ${capability.detail}", Danger)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    var confidence by remember(trigger.confidence) { mutableStateOf(trigger.confidence.toFloat()) }
                    Hint("Confidence threshold  ${(confidence * 100).toInt()}%")
                    Slider(confidence, onValueChange = { confidence = it }, valueRange = 0.5f..0.99f,
                        onValueChangeFinished = { update(trigger.copy(confidence = confidence.toDouble())) }, modifier = Modifier.height(24.dp).semantics { contentDescription = "${trigger.gesture.label()} confidence threshold" })
                }
                Column(Modifier.width(135.dp)) {
                    Hint("Cooldown")
                    Choice(trigger.cooldownMs, listOf(500L, 1000L, 1500L, 3000L, 5000L), { "${it / 1000.0} seconds" }) { update(trigger.copy(cooldownMs = it)) }
                }
                TextButton(onClick = onCalibrate) { Text("Calibrate", fontSize = 12.sp) }
            }
        }
        if (editAction) ActionEditor(trigger.action, state.capabilities, onDismiss = { editAction = false }) { action -> update(trigger.copy(action = action)); editAction = false }
    }
    Hint("The default classifier is a starting point. Calibrate with your microphone to reduce clap / snap confusion. “Test action” runs the action directly.")
}

@Composable private fun ActivityScreen(state: DashboardState, controller: AppController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { SectionTitle("Activity", "Detections, action results and microphone errors. Latest 200 entries.") }
        TextButton(onClick = controller::clearActivity) { Text("Clear log") }
    }
    Panel(Modifier.fillMaxWidth()) {
        if (state.activity.isEmpty()) {
            Text("A quiet start", fontSize = 17.sp)
            Hint("Enable monitoring and perform a gesture, or use Test action. Events appear here.")
        } else state.activity.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(entry.time, color = Muted, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, color = if (entry.success) Accent else Danger, fontSize = 13.sp)
                    Hint(entry.detail)
                }
            }
            Divider(color = Border)
        }
    }
}
