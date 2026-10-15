package dev.clapctrl.app

import dev.clapctrl.core.*
import dev.clapctrl.core.calibration.*
import dev.clapctrl.core.configuration.ConfigurationStore
import dev.clapctrl.core.dsp.*
import dev.clapctrl.platform.DesktopPlatformAdapter
import dev.clapctrl.platform.JavaSoundCapture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ActivityEntry(val time: String, val title: String, val detail: String, val success: Boolean = true)
data class DashboardState(
    val configuration: AppConfiguration = AppConfiguration(),
    val devices: List<AudioInputDevice> = emptyList(),
    val capabilities: List<ActionCapability> = emptyList(),
    val listening: Boolean = false,
    val starting: Boolean = false,
    val status: String = "Monitoring is off",
    val error: String? = null,
    val levels: List<Float> = List(72) { 0f },
    val noiseFloor: Double = 0.0,
    val sampleRate: Int = 0,
    val framesProcessed: Long = 0,
    val lastGesture: String = "No detections yet",
    val activity: List<ActivityEntry> = emptyList(),
    val calibrating: Boolean = false,
    val candidate: AcousticCandidate? = null,
    val sampleCounts: Map<AcousticClass, Int> = emptyMap(),
    val profileName: String? = null,
    val profileMicrophoneId: String? = null,
    val validation: String? = null,
    val training: Boolean = false,
) {
    val activeProfileName get() = profileName.takeIf { profileMicrophoneId == configuration.microphoneId }
}

class AppController(
    val dataDirectory: Path,
    private val capture: AudioCaptureService = JavaSoundCapture(),
    private val adapter: PlatformAdapter = DesktopPlatformAdapter(),
) {
    private val log = LoggerFactory.getLogger(AppController::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = ConfigurationStore(dataDirectory.resolve("settings.json"))
    private val mutable = MutableStateFlow(DashboardState())
    val state = mutable.asStateFlow()
    private val lifecycle = Mutex()
    private val saves = Mutex()
    private val trainingGate = Mutex()
    private val dispatcher = TriggerDispatcher(adapter)
    private var monitorJob: Job? = null
    private var profile: CalibrationProfile? = null
    private var calibration: CalibrationSession? = null
    private val profileReady = CompletableDeferred<Unit>()

    init {
        Files.createDirectories(dataDirectory)
        val loaded = runCatching { store.load() }
        mutable.update { it.copy(configuration = loaded.getOrDefault(AppConfiguration()), error = loaded.exceptionOrNull()?.let { e -> "Settings were not loaded: ${e.message}. Original file is preserved." }) }
        refreshDevices()
        scope.launch(Dispatchers.IO) {
            try {
            val profilePath = dataDirectory.resolve("calibration.json")
            if (Files.exists(profilePath)) runCatching { CalibrationProfileStore(profilePath).load() }
                .onSuccess { profile = it; mutable.update { s -> s.copy(profileName = it.name, profileMicrophoneId = it.microphoneId) } }
                .onFailure { report("Calibration profile could not be loaded: ${it.message}") }
            } finally { profileReady.complete(Unit) }
        }
    }

    fun refreshDevices() { scope.launch(Dispatchers.IO) {
        runCatching { capture.listDevices() to adapter.capabilities() }
            .onSuccess { (devices, capabilities) -> mutable.update { it.copy(devices = devices, capabilities = capabilities) } }
            .onFailure { report("Device scan failed: ${it.message}") }
    } }

    fun updateConfiguration(transform: (AppConfiguration) -> AppConfiguration) { scope.launch {
        saves.withLock {
            val next = transform(state.value.configuration)
            if (state.value.calibrating && next.microphoneId != state.value.configuration.microphoneId) {
                report("End calibration before changing microphone"); return@withLock
            }
            runCatching { withContext(Dispatchers.IO) { store.save(next) } }
                .onSuccess {
                    mutable.update { it.copy(configuration = next, error = null) }
                    if (monitorJob?.isActive == true) restartMonitoring()
                }.onFailure { report("Settings were not saved: ${it.message}") }
        }
    } }

    fun toggleMonitoring() { if (state.value.listening || state.value.starting) pause() else start() }
    fun enableWithConsent() { scope.launch {
        saves.withLock {
            val next = state.value.configuration.copy(consentGiven = true)
            runCatching { withContext(Dispatchers.IO) { store.save(next) } }
                .onSuccess { mutable.update { it.copy(configuration = next) }; start() }
                .onFailure { report("Could not save microphone consent: ${it.message}") }
        }
    } }

    fun start() { scope.launch { lifecycle.withLock {
        profileReady.await()
        if (!state.value.configuration.consentGiven) return@withLock
        if (monitorJob?.isActive == true) return@withLock
        startMonitorLocked()
    } } }

    private fun startMonitorLocked() {
        val config = state.value.configuration
        val selectedProfile = profile?.takeIf { it.microphoneId == config.microphoneId }
        val classifier = selectedProfile?.let { CalibrationClassifier(it) } ?: RuleBasedClassifier()
        val pipeline = AcousticPipeline(config.detector, classifier)
        mutable.update { it.copy(starting = true, status = "Opening microphone…", error = null) }
        monitorJob = scope.launch(Dispatchers.IO) monitor@ {
            var lastUiTime = 0L
            var framesSinceUi = 0L
            var previousFrameTime: Long? = null
            var failures = 0
            while (isActive) {
                try {
                    capture.capture(config.microphoneId) { frame ->
                        if (!isActive) return@capture
                        if (previousFrameTime?.let { frame.timestampMs < it } == true) pipeline.reset()
                        previousFrameTime = frame.timestampMs
                        val output = pipeline.process(frame)
                        if (!isActive) return@capture
                        framesSinceUi++
                        if (frame.timestampMs - lastUiTime >= 40) {
                            lastUiTime = frame.timestampMs
                            mutable.update { current -> current.copy(
                                listening = true, starting = false, status = "Listening locally", error = null,
                                levels = current.levels.drop(1) + (output.rms * 5).toFloat().coerceIn(0f, 1f),
                                noiseFloor = output.noiseFloor, sampleRate = frame.sampleRate,
                                framesProcessed = current.framesProcessed + framesSinceUi,
                            ) }
                            framesSinceUi = 0
                        }
                        output.candidate?.let { candidate ->
                            if (state.value.calibrating) mutable.update { it.copy(candidate = it.candidate ?: candidate) }
                        }
                        if (!state.value.calibrating) for (detection in output.detections) {
                            val trigger = state.value.configuration.triggers.firstOrNull { it.gesture == detection.gesture } ?: continue
                            mutable.update { it.copy(lastGesture = "${detection.gesture.label()} · ${(detection.confidence * 100).toInt()}%") }
                            this@monitor.launch action@ {
                                val current = state.value
                                if (!isActive || current.calibrating) return@action
                                val freshTrigger = current.configuration.triggers.firstOrNull { it.gesture == detection.gesture } ?: return@action
                                dispatcher.dispatch(detection, freshTrigger)?.let { result ->
                                activity(detection.gesture.label(), "${(detection.confidence * 100).toInt()}% · ${freshTrigger.action.type.label()} · ${result.message}", result.success)
                            } }
                        }
                    }
                    if (isActive) error("Audio stream stopped")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    failures++
                    val message = error.message ?: "Microphone unavailable"
                    log.warn("Audio capture failed", error)
                    mutable.update { it.copy(listening = false, starting = true, error = message, status = "Input unavailable · retry $failures/3", levels = List(72) { 0f }) }
                    if (failures >= 3) {
                        activity("Microphone stopped", message, false)
                        mutable.update { it.copy(starting = false, status = "Select an input and try again") }
                        break
                    }
                    delay(2000)
                    pipeline.reset()
                    previousFrameTime = null
                    lastUiTime = 0
                } finally { capture.stop() }
            }
        }
    }

    fun pause() { scope.launch { lifecycle.withLock { stopMonitorLocked() } } }
    private suspend fun stopMonitorLocked() {
        monitorJob?.cancel()
        capture.stop()
        monitorJob?.join()
        monitorJob = null
        mutable.update { it.copy(listening = false, starting = false, status = "Monitoring is off", levels = List(72) { 0f }) }
    }
    private suspend fun restartMonitoring() { lifecycle.withLock { stopMonitorLocked(); startMonitorLocked() } }

    fun testAction(trigger: TriggerConfiguration) { scope.launch {
        activity("Action test", "Running ${trigger.action.type.label()}…")
        val result = dispatcher.dispatch(Detection(trigger.gesture, 1.0, System.nanoTime() / 1_000_000), trigger, manual = true)
        if (result != null) activity("Action test · ${trigger.action.type.label()}", result.message, result.success)
    } }

    fun beginCalibration(minimumSamples: Int) {
        if (state.value.training) return
        scope.launch { lifecycle.withLock {
            val wasRunning = monitorJob?.isActive == true
            stopMonitorLocked()
            calibration = CalibrationSession(state.value.configuration.microphoneId, "My microphone", minimumSamples)
            mutable.update { it.copy(calibrating = true, candidate = null, sampleCounts = emptyMap(), validation = null) }
            if (wasRunning) startMonitorLocked()
        } }
    }
    fun endCalibration() {
        if (state.value.training) return
        scope.launch { lifecycle.withLock {
            val wasRunning = monitorJob?.isActive == true
            stopMonitorLocked()
            mutable.update { it.copy(calibrating = false, candidate = null) }
            if (wasRunning) startMonitorLocked()
        } }
    }
    fun rejectCandidate() { mutable.update { it.copy(candidate = null) } }
    fun acceptCandidate(label: AcousticClass) {
        if (state.value.training || !state.value.calibrating) return
        val candidate = state.value.candidate ?: return
        if (calibration?.microphoneId != state.value.configuration.microphoneId) { report("Calibration microphone has changed"); return }
        runCatching { calibration?.accept(label, candidate) }.onSuccess {
            mutable.update { it.copy(candidate = null, sampleCounts = calibration?.samples?.groupingBy { sample -> sample.label }?.eachCount() ?: emptyMap()) }
        }.onFailure { report(it.message ?: "Sample could not be accepted") }
    }

    fun trainProfile() {
        if (!trainingGate.tryLock()) return
        mutable.update { it.copy(training = true) }
        scope.launch { try {
        runCatching {
            val session = calibration ?: error("Start a calibration session first")
            check(session.microphoneId == state.value.configuration.microphoneId) { "Calibration microphone has changed" }
            val evaluation = session.evaluate()
            val passed = evaluation.perClass.values.all { (it.precision ?: 0.0) >= 0.8 && (it.recall ?: 0.0) >= 0.8 && (it.falsePositiveRate ?: 1.0) <= 0.1 }
            if (!passed) {
                mutable.update { it.copy(validation = evaluation.describe(saved = false)) }
                report("Validation failed the per-class precision/recall 80% and FPR 10% gates. Previous profile retained; collect more varied examples.")
                return@runCatching
            }
            val trained = session.train()
            withContext(Dispatchers.IO) { CalibrationProfileStore(dataDirectory.resolve("calibration.json")).save(trained) }
            lifecycle.withLock {
                val wasRunning = monitorJob?.isActive == true
                stopMonitorLocked()
                profile = trained
                mutable.update { it.copy(profileName = trained.name, profileMicrophoneId = trained.microphoneId, validation = evaluation.describe(), calibrating = false, candidate = null) }
                if (wasRunning) startMonitorLocked()
            }
        }.onFailure { report("Calibration needs more examples or failed: ${it.message}") }
        } finally { mutable.update { it.copy(training = false) }; trainingGate.unlock() } }
    }
    fun importProfile(path: Path) { scope.launch(Dispatchers.IO) {
        runCatching { CalibrationProfileStore(path).load() }.onSuccess { imported ->
            if (imported.microphoneId != state.value.configuration.microphoneId) {
                report("This profile belongs to a different input. Select its microphone before importing."); return@onSuccess
            }
            runCatching { CalibrationProfileStore(dataDirectory.resolve("calibration.json")).save(imported) }.onSuccess {
                profile = imported
                mutable.update { it.copy(profileName = imported.name, profileMicrophoneId = imported.microphoneId, validation = "Imported profile. Validate with fresh recordings.") }
                if (monitorJob?.isActive == true) restartMonitoring()
            }.onFailure { report("Profile was not saved: ${it.message}") }
        }.onFailure { report("Invalid profile: ${it.message}") }
    } }
    fun exportProfile(path: Path) { scope.launch(Dispatchers.IO) {
        runCatching { CalibrationProfileStore(path).save(profile ?: error("Create a calibration profile first")) }
            .onSuccess { activity("Profile exported", path.toString()) }.onFailure { report("Export failed: ${it.message}") }
    } }
    fun clearActivity() { mutable.update { it.copy(activity = emptyList()) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun report(message: String) { mutable.update { it.copy(error = message) }; log.warn(message) }
    private fun activity(title: String, detail: String, success: Boolean = true) {
        val entry = ActivityEntry(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), title, detail, success)
        mutable.update { it.copy(activity = (listOf(entry) + it.activity).take(200)) }
    }
    suspend fun shutdown() { lifecycle.withLock { stopMonitorLocked() }; scope.coroutineContext[Job]?.cancelAndJoin() }
}

fun Gesture.label() = when (this) { Gesture.DOUBLE_CLAP -> "Double clap"; Gesture.FINGER_SNAP -> "Finger snap" }
fun ActionType.label() = when (this) {
    ActionType.OPEN_TERMINAL -> "Open terminal"; ActionType.SCREENSHOT -> "Take screenshot"
    ActionType.LOCK_SCREEN -> "Lock screen"; ActionType.APP_LAUNCHER -> "Application launcher"
    ActionType.MEDIA_PLAY_PAUSE -> "Play / pause media"; ActionType.TOGGLE_MUTE -> "Toggle system mute"
    ActionType.SWITCH_WORKSPACE -> "Switch workspace"; ActionType.CUSTOM_COMMAND -> "Custom command"
}

private fun EvaluationReport.describe(saved: Boolean = true): String = buildString {
    fun percentage(value: Double?) = value?.let { "%.1f%%".format(it * 100) } ?: "N/A"
    appendLine("$folds-fold held-out validation · $totalSamples samples")
    perClass.forEach { (label, metrics) -> appendLine("$label: precision ${percentage(metrics.precision)} · recall ${percentage(metrics.recall)} · FPR ${percentage(metrics.falsePositiveRate)}") }
    appendLine("Confusion (actual → predicted CLAP / SNAP / NEGATIVE):")
    confusion.forEach { (label, row) -> appendLine("$label → ${listOf("CLAP", "SNAP", "NEGATIVE").joinToString(" / ") { row[it].toString() }}") }
    appendLine("Latency: not measured. Scores describe held-out feature classification only.")
    append(if (saved) "Saved profile. Test with fresh recordings before relying on actions." else "Validation failed. Previous profile was preserved.")
}
