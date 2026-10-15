package dev.clapctrl.app

import dev.clapctrl.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** One action at a time; no queue of stale detections and no process storms. */
class TriggerDispatcher(private val adapter: PlatformAdapter) {
    private val gate = Mutex()
    private val lastRun = mutableMapOf<Gesture, Long>()

    suspend fun dispatch(detection: Detection, trigger: TriggerConfiguration, manual: Boolean = false): ActionResult? {
        if (detection.gesture != trigger.gesture) return null
        if (!detection.confidence.isFinite() || detection.confidence !in 0.0..1.0) return null
        if (!manual && (!trigger.enabled || detection.confidence < trigger.confidence)) return null
        if (!gate.tryLock()) return ActionResult(false, "Another action is running; event skipped")
        try {
            val previous = lastRun[trigger.gesture]
            if (!manual && previous != null && detection.timestampMs - previous < trigger.cooldownMs) return null
            lastRun[trigger.gesture] = detection.timestampMs
            return adapter.execute(trigger.action)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ActionResult(false, error.message ?: error.javaClass.simpleName)
        } finally { gate.unlock() }
    }
}
