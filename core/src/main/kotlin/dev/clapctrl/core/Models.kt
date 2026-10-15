package dev.clapctrl.core

import kotlinx.serialization.Serializable

enum class AcousticClass { CLAP, SNAP, NEGATIVE }
@Serializable enum class Gesture { DOUBLE_CLAP, FINGER_SNAP }
@Serializable enum class ActionType { OPEN_TERMINAL, SCREENSHOT, LOCK_SCREEN, APP_LAUNCHER, MEDIA_PLAY_PAUSE, TOGGLE_MUTE, SWITCH_WORKSPACE, CUSTOM_COMMAND }

@Serializable data class ActionConfiguration(
    val type: ActionType = ActionType.OPEN_TERMINAL,
    val command: List<String> = emptyList(),
    val timeoutMs: Long = 5000,
    val workspace: Int = 1,
    val approved: Boolean = false,
)
data class ActionResult(val success: Boolean, val message: String)
data class ActionCapability(val type: ActionType, val supported: Boolean, val detail: String)
interface PlatformAdapter {
    fun capabilities(): List<ActionCapability>
    suspend fun execute(action: ActionConfiguration): ActionResult
}

@Serializable data class TriggerConfiguration(
    val gesture: Gesture,
    val enabled: Boolean = true,
    val confidence: Double = 0.75,
    val cooldownMs: Long = 1500,
    val action: ActionConfiguration = ActionConfiguration(),
)
@Serializable data class DetectorSettings(
    val sensitivity: Double = 0.5,
    val doubleClapMinMs: Long = 150,
    val doubleClapMaxMs: Long = 700,
    val refractoryMs: Long = 100,
)
@Serializable data class AppConfiguration(
    val schemaVersion: Int = 1,
    val microphoneId: String = "default",
    val consentGiven: Boolean = false,
    val detector: DetectorSettings = DetectorSettings(),
    val triggers: List<TriggerConfiguration> = listOf(
        TriggerConfiguration(Gesture.DOUBLE_CLAP),
        TriggerConfiguration(Gesture.FINGER_SNAP, action = ActionConfiguration(ActionType.SCREENSHOT)),
    ),
)
data class AudioInputDevice(val id: String, val name: String, val description: String = "")
data class AudioFrame(val samples: FloatArray, val sampleRate: Int, val timestampMs: Long)
interface AudioCaptureService {
    fun listDevices(): List<AudioInputDevice>
    suspend fun capture(deviceId: String, onFrame: (AudioFrame) -> Unit)
    fun stop()
}
data class AcousticFeatures(
    val durationMs: Double,
    val rms: Double,
    val peak: Double,
    val crestFactor: Double,
    val centroid: Double,
    val bandwidth: Double,
    val highFrequencyRatio: Double,
    val zeroCrossingRate: Double,
    val attackMs: Double,
    val decayMs: Double,
    val spectralFlux: Double,
) {
    fun vector(): List<Double> = listOf(durationMs / 150.0, crestFactor / 10.0, centroid, bandwidth, highFrequencyRatio, zeroCrossingRate, attackMs / 50.0, decayMs / 150.0, spectralFlux)
}
data class AcousticCandidate(
    val timestampMs: Long,
    val features: AcousticFeatures,
    val waveform: FloatArray,
    val sampleRate: Int,
    val classification: AcousticClass,
    val confidence: Double,
)
data class Detection(val gesture: Gesture, val confidence: Double, val timestampMs: Long)
data class PipelineOutput(val rms: Double, val noiseFloor: Double, val candidate: AcousticCandidate?, val detections: List<Detection>)
