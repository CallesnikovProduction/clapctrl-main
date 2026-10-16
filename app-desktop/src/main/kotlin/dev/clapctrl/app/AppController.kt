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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10

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
    val lastAudioTimestampMs: Long = 0,
    val inputRms: Double = 0.0,
    val inputOpenedInMs: Long? = null,
    val lastGesture: String = "No detections yet",
    val activity: List<ActivityEntry> = emptyList(),
    val calibrating: Boolean = false,
    val candidate: AcousticCandidate? = null,
    val sampleCounts: Map<AcousticClass, Int> = emptyMap(),
    val profileName: String? = null,
    val profileMicrophoneId: String? = null,
    val validation: String? = null,
    val training: Boolean = false,
    val calibrationMinimumSamples: Int = 10,
    val calibrationReadyAtMs: Long = 0,
    val calibrationAvailable: Boolean = false,
    val calibrationSaved: Boolean = false,
    val testingRecognition: Boolean = false,
    val lastAcousticEvent: String = "No acoustic event yet",
    val actionFeedback: String = "No action executed yet",
) {
    val activeProfileName get() = profileName.takeIf { profileMicrophoneId == configuration.microphoneId }
    val readyToTrain get() = calibrationAvailable && AcousticClass.entries.all { (sampleCounts[it] ?: 0) >= calibrationMinimumSamples }
    val hasUnsavedCalibration get() = calibrationAvailable && !calibrationSaved && sampleCounts.values.any { it > 0 }
    val calibrationProgress get() = AcousticClass.entries.joinToString(" · ") {
        "${when (it) { AcousticClass.CLAP -> "Handclap"; AcousticClass.SNAP -> "Finger snap"; AcousticClass.NEGATIVE -> "Other sounds" }} ${sampleCounts[it] ?: 0}/$calibrationMinimumSamples"
    }
    val calibrationWaitingMs get() = if (calibrating) (calibrationReadyAtMs - lastAudioTimestampMs).coerceAtLeast(0) else 0
    val inputDb get() = if (inputRms <= 0.0) "−∞ dBFS" else "%.0f dBFS".format(20 * log10(inputRms))
}

class AppController(
    val dataDirectory: Path,
    private val capture: AudioCaptureService = JavaSoundCapture(),
    private val adapter: PlatformAdapter = DesktopPlatformAdapter(dataDirectory = dataDirectory),
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
    @Volatile private var profile: CalibrationProfile? = null
    private var calibration: CalibrationSession? = null
    private val profileReady = CompletableDeferred<Unit>()
    private val pipelineRevision = AtomicLong()

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
                    val previous = state.value.configuration
                    mutable.update { it.copy(configuration = next, error = null) }
                    if (previous.detector != next.detector) pipelineRevision.incrementAndGet()
                    if (previous.microphoneId != next.microphoneId && monitorJob?.isActive == true) restartMonitoring()
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
        mutable.update { it.copy(starting = true, status = "Opening microphone · waiting for PCM…", error = null, inputOpenedInMs = null) }
        monitorJob = scope.launch(Dispatchers.IO) monitor@ {
            var pipeline = AcousticPipeline(config.detector, profile?.takeIf { it.microphoneId == config.microphoneId }?.let { CalibrationClassifier(it) } ?: RuleBasedClassifier())
            var pipelineSettings = config.detector
            var pipelineProfile = profile?.takeIf { it.microphoneId == config.microphoneId }
            var thresholdTriggers = config.triggers
            var confidenceThresholds = thresholdTriggers.acousticThresholds()
            var revision = pipelineRevision.get()
            var lastUiTime = 0L
            var framesSinceUi = 0L
            var previousFrameTime: Long? = null
            var failures = 0
            while (isActive) {
                try {
                    val requestedAt = System.nanoTime()
                    var firstFrameMs: Long? = null
                    capture.capture(config.microphoneId) { frame ->
                        if (!isActive) return@capture
                        if (firstFrameMs == null) firstFrameMs = (System.nanoTime() - requestedAt) / 1_000_000
                        val currentConfiguration = state.value.configuration
                        val currentSettings = currentConfiguration.detector
                        val currentProfile = profile?.takeIf { it.microphoneId == config.microphoneId }
                        val currentRevision = pipelineRevision.get()
                        if (currentSettings != pipelineSettings || currentProfile !== pipelineProfile) {
                            pipeline = AcousticPipeline(currentSettings, currentProfile?.let { CalibrationClassifier(it) } ?: RuleBasedClassifier())
                            pipelineSettings = currentSettings; pipelineProfile = currentProfile
                        } else if (currentRevision != revision) pipeline.resetRecognition()
                        if (currentConfiguration.triggers != thresholdTriggers) {
                            val nextThresholds = currentConfiguration.triggers.acousticThresholds()
                            if (nextThresholds != confidenceThresholds) pipeline.resetRecognition()
                            confidenceThresholds = nextThresholds
                            thresholdTriggers = currentConfiguration.triggers
                        }
                        revision = currentRevision
                        if (previousFrameTime?.let { frame.timestampMs < it } == true) pipeline.reset()
                        previousFrameTime = frame.timestampMs
                        val output = pipeline.process(frame, confidenceThresholds)
                        if (!isActive || revision != pipelineRevision.get()) return@capture
                        framesSinceUi++
                        if (frame.timestampMs - lastUiTime >= 40) {
                            lastUiTime = frame.timestampMs
                            mutable.update { current -> current.copy(
                                listening = true, starting = false, status = "Listening locally", error = null,
                                levels = current.levels.drop(1) + (if (output.rms <= 0) 0f else ((20 * log10(output.rms) + 70) / 70).toFloat().coerceIn(0f, 1f)),
                                noiseFloor = output.noiseFloor, sampleRate = frame.sampleRate,
                                framesProcessed = current.framesProcessed + framesSinceUi,
                                lastAudioTimestampMs = frame.timestampMs, inputRms = output.rms, inputOpenedInMs = firstFrameMs,
                            ) }
                            framesSinceUi = 0
                        }
                        output.candidate?.let { candidate ->
                            val threshold = confidenceThresholds[candidate.classification] ?: 0.0
                            val rejection = if (candidate.confidence < threshold) " · below ${(threshold * 100).toInt()}% trigger threshold" else ""
                            val pairing = if (candidate.classification == AcousticClass.CLAP && rejection.isEmpty() && output.detections.none { it.gesture == Gesture.DOUBLE_CLAP })
                                " · waiting for second clap (${currentSettings.doubleClapMinMs}–${currentSettings.doubleClapMaxMs} ms)" else ""
                            mutable.update { it.copy(lastAcousticEvent = "${candidate.classification.name.lowercase()} · ${(candidate.confidence * 100).toInt()}% score · ${"%.0f".format(candidate.features.durationMs)} ms$rejection$pairing") }
                            mutable.update { current ->
                                if (current.calibrating && current.candidate == null && candidate.timestampMs >= current.calibrationReadyAtMs) current.copy(candidate = candidate) else current
                            }
                        }
                        if (!state.value.calibrating) for (detection in output.detections) {
                            val trigger = state.value.configuration.triggers.firstOrNull { it.gesture == detection.gesture } ?: continue
                            mutable.update { it.copy(lastGesture = "${detection.gesture.label()} · ${(detection.confidence * 100).toInt()}%") }
                            if (state.value.testingRecognition) {
                                val detail = when {
                                    !trigger.enabled -> "Trigger disabled"
                                    detection.confidence < trigger.confidence -> "Below ${ (trigger.confidence * 100).toInt()}% threshold"
                                    else -> "Would run ${trigger.action.type.label()} · no action executed"
                                }
                                activity("Recognition test · ${detection.gesture.label()}", "${(detection.confidence * 100).toInt()}% · $detail")
                                continue
                            }
                            this@monitor.launch action@ {
                                val current = state.value
                                if (!isActive || current.calibrating || current.testingRecognition || currentRevision != pipelineRevision.get()) return@action
                                val freshTrigger = current.configuration.triggers.firstOrNull { it.gesture == detection.gesture } ?: return@action
                                dispatcher.dispatch(detection, freshTrigger, onSkipped = { reason ->
                                    mutable.update { it.copy(actionFeedback = reason) }
                                })?.let { result ->
                                mutable.update { it.copy(actionFeedback = result.message) }
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
        mutable.update { it.copy(listening = false, starting = false, status = "Monitoring is off", levels = List(72) { 0f }, inputRms = 0.0) }
    }
    private suspend fun restartMonitoring() { lifecycle.withLock { stopMonitorLocked(); startMonitorLocked() } }

    fun testAction(trigger: TriggerConfiguration) { scope.launch {
        mutable.update { it.copy(actionFeedback = "Running ${trigger.action.type.label()}…") }
        activity("Action test", "Running ${trigger.action.type.label()}…")
        val result = dispatcher.dispatch(Detection(trigger.gesture, 1.0, System.nanoTime() / 1_000_000), trigger, manual = true)
        if (result != null) {
            mutable.update { it.copy(actionFeedback = result.message) }
            activity("Action test · ${trigger.action.type.label()}", result.message, result.success)
        }
    } }

    fun beginCalibration(minimumSamples: Int) {
        if (state.value.training) return
        scope.launch { lifecycle.withLock {
            if (!state.value.listening) { report("Enable monitoring and wait for live input before starting calibration"); return@withLock }
            if (state.value.calibrationAvailable && state.value.sampleCounts.isNotEmpty()) { report("Resume the current session or reset its samples first"); return@withLock }
            calibration = CalibrationSession(state.value.configuration.microphoneId, "My microphone", minimumSamples)
            pipelineRevision.incrementAndGet()
            mutable.update { it.copy(calibrating = true, testingRecognition = false, candidate = null, sampleCounts = emptyMap(), validation = null,
                calibrationMinimumSamples = minimumSamples, calibrationAvailable = true, calibrationSaved = false,
                calibrationReadyAtMs = it.lastAudioTimestampMs + 350, error = null) }
        } }
    }
    fun endCalibration() {
        if (state.value.training) return
        scope.launch { lifecycle.withLock {
            pipelineRevision.incrementAndGet()
            mutable.update { it.copy(calibrating = false, candidate = null, testingRecognition = true) }
        } }
    }
    fun resumeCalibration() {
        if (state.value.training || !state.value.calibrationAvailable) return
        if (!state.value.listening) { report("Enable monitoring before resuming calibration"); return }
        if (calibration?.microphoneId != state.value.configuration.microphoneId) { report("Select the microphone used for this session or reset samples"); return }
        mutable.update { it.copy(calibrating = true, testingRecognition = false, candidate = null, calibrationReadyAtMs = it.lastAudioTimestampMs + 350) }
        pipelineRevision.incrementAndGet()
    }
    fun resetCalibration() {
        if (state.value.training) return
        pipelineRevision.incrementAndGet()
        calibration = null
        mutable.update { it.copy(calibrating = false, calibrationAvailable = false, calibrationSaved = false, candidate = null, sampleCounts = emptyMap(), validation = null, testingRecognition = true) }
    }
    fun rejectCandidate() {
        if (state.value.training) return
        mutable.update { it.copy(candidate = null, calibrationReadyAtMs = it.lastAudioTimestampMs + 350) }
    }
    fun acceptCandidate(label: AcousticClass) {
        if (state.value.training || !state.value.calibrating) return
        val candidate = state.value.candidate ?: return
        if (calibration?.microphoneId != state.value.configuration.microphoneId) { report("Calibration microphone has changed"); return }
        runCatching { calibration?.accept(label, candidate) }.onSuccess {
            val counts = calibration?.samples?.groupingBy { sample -> sample.label }?.eachCount() ?: emptyMap()
            val complete = AcousticClass.entries.all { (counts[it] ?: 0) >= state.value.calibrationMinimumSamples }
            if (complete) pipelineRevision.incrementAndGet()
            mutable.update { it.copy(candidate = null, sampleCounts = counts, calibrationSaved = false, calibrationReadyAtMs = it.lastAudioTimestampMs + 350,
                calibrating = it.calibrating && !complete, testingRecognition = it.testingRecognition || complete) }
        }.onFailure { report(it.message ?: "Sample could not be accepted") }
    }
    fun undoLastSample() {
        if (state.value.training) return
        val session = calibration ?: return
        if (session.samples.isEmpty()) return
        session.remove(session.samples.lastIndex)
        pipelineRevision.incrementAndGet()
        mutable.update { it.copy(sampleCounts = session.samples.groupingBy { sample -> sample.label }.eachCount(), calibrationSaved = false, candidate = null, calibrationReadyAtMs = it.lastAudioTimestampMs + 350, validation = null, testingRecognition = true) }
    }
    fun enableActions() {
        val current = state.value
        if (current.calibrating || current.training) { report("Finish or pause calibration first"); return }
        if (current.hasUnsavedCalibration) {
            report(if (current.readyToTrain) "Samples are not applied yet. Click Save profile & enable actions."
                else "Calibration is incomplete: ${current.calibrationProgress}. Collect the missing classes, or Reset samples to use the current profile.")
            return
        }
        pipelineRevision.incrementAndGet()
        mutable.update { it.copy(testingRecognition = false, error = null, actionFeedback = "Desktop actions enabled · try TWO claps or ONE finger snap") }
    }
    fun toggleRecognitionTest() {
        if (state.value.calibrating || state.value.training) return
        if (state.value.testingRecognition) { enableActions(); return }
        pipelineRevision.incrementAndGet()
        mutable.update { it.copy(testingRecognition = !it.testingRecognition, lastGesture = "No detections yet", lastAcousticEvent = "Perform two claps or one finger snap") }
    }

    fun trainProfile() {
        if (!state.value.readyToTrain) { report("Collect ${state.value.calibrationMinimumSamples} accepted examples of EACH class: claps, snaps and other sounds"); return }
        if (!trainingGate.tryLock()) return
        mutable.update { it.copy(training = true) }
        scope.launch { try {
        runCatching {
            val session = calibration ?: error("Start a calibration session first")
            check(session.microphoneId == state.value.configuration.microphoneId) { "Calibration microphone has changed" }
            val thresholds = state.value.configuration.triggers.acousticThresholds()
            val evaluation = session.evaluate(confidenceThresholds = thresholds)
            val passed = evaluation.perClass.values.all { (it.precision ?: 0.0) >= 0.8 && (it.recall ?: 0.0) >= 0.8 && (it.falsePositiveRate ?: 1.0) <= 0.1 }
            if (!passed) {
                mutable.update { it.copy(validation = evaluation.describe(saved = false)) }
                val failures = evaluation.perClass.filterValues { (it.precision ?: 0.0) < 0.8 || (it.recall ?: 0.0) < 0.8 || (it.falsePositiveRate ?: 1.0) > 0.1 }
                    .entries.joinToString("; ") { (label, metrics) -> "$label: precision ${metrics.precision?.let { "%.0f%%".format(it * 100) } ?: "N/A"}, recall ${metrics.recall?.let { "%.0f%%".format(it * 100) } ?: "N/A"}" }
                report("Validation failed · profile NOT saved: $failures. Need precision/recall ≥80% and FPR ≤10%. Previous profile retained; undo mislabeled samples or collect clearer examples.")
                return@runCatching
            }
            val trained = session.train()
            withContext(Dispatchers.IO) { CalibrationProfileStore(dataDirectory.resolve("calibration.json")).save(trained) }
            lifecycle.withLock {
                pipelineRevision.incrementAndGet()
                profile = trained
                mutable.update { it.copy(profileName = trained.name, profileMicrophoneId = trained.microphoneId, validation = evaluation.describe(), calibrationSaved = true, calibrating = false, candidate = null, testingRecognition = false, error = null,
                    lastAcousticEvent = "Profile saved and active. Try TWO claps or ONE finger snap.", actionFeedback = "Profile saved · desktop actions enabled") }
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
                pipelineRevision.incrementAndGet()
                profile = imported
                mutable.update { it.copy(profileName = imported.name, profileMicrophoneId = imported.microphoneId, validation = "Imported profile. Validate with fresh recordings.") }
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

private fun List<TriggerConfiguration>.acousticThresholds() = associate { trigger ->
    (if (trigger.gesture == Gesture.DOUBLE_CLAP) AcousticClass.CLAP else AcousticClass.SNAP) to trigger.confidence
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
