package dev.clapctrl.app

import dev.clapctrl.core.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TriggerDispatcherTest {
    private class Adapter(private val run: suspend (ActionConfiguration) -> ActionResult = { ActionResult(true, "Executed") }) : PlatformAdapter {
        val executions = CopyOnWriteArrayList<ActionConfiguration>()
        override fun capabilities() = ActionType.entries.map { ActionCapability(it, true, "Test adapter") }
        override suspend fun execute(action: ActionConfiguration): ActionResult {
            executions += action
            return run(action)
        }
    }

    private val clap = TriggerConfiguration(Gesture.DOUBLE_CLAP, confidence = 0.8, cooldownMs = 1000)
    private val snap = TriggerConfiguration(Gesture.FINGER_SNAP, confidence = 0.8, cooldownMs = 1000,
        action = ActionConfiguration(ActionType.SCREENSHOT))
    private fun detection(gesture: Gesture = Gesture.DOUBLE_CLAP, time: Long = 1000, confidence: Double = 0.9) =
        Detection(gesture, confidence, time)

    @Test fun `disabled and low confidence events do not execute but exact threshold does`(): Unit = runBlocking {
        val adapter = Adapter()
        val dispatcher = TriggerDispatcher(adapter)
        assertNull(dispatcher.dispatch(detection(), clap.copy(enabled = false)))
        assertNull(dispatcher.dispatch(detection(confidence = 0.79), clap))
        assertTrue(dispatcher.dispatch(detection(confidence = 0.8), clap)!!.success)
        assertEquals(listOf(clap.action), adapter.executions.toList())
    }

    @Test fun `cooldowns are independent and accept the exact boundary`(): Unit = runBlocking {
        val adapter = Adapter()
        val dispatcher = TriggerDispatcher(adapter)
        assertTrue(dispatcher.dispatch(detection(), clap)!!.success)
        assertNull(dispatcher.dispatch(detection(time = 1999), clap))
        assertTrue(dispatcher.dispatch(detection(Gesture.FINGER_SNAP, time = 1100), snap)!!.success)
        assertTrue(dispatcher.dispatch(detection(time = 2000), clap)!!.success)
        assertEquals(listOf(ActionType.OPEN_TERMINAL, ActionType.SCREENSHOT, ActionType.OPEN_TERMINAL), adapter.executions.map { it.type })
    }

    @Test fun `manual action test bypasses acoustic gates`(): Unit = runBlocking {
        val adapter = Adapter()
        val dispatcher = TriggerDispatcher(adapter)
        dispatcher.dispatch(detection(), clap)
        val result = dispatcher.dispatch(detection(time = 1001, confidence = 0.0), clap.copy(enabled = false), manual = true)
        assertTrue(result!!.success)
        assertEquals(2, adapter.executions.size)
    }

    @Test fun `busy actions skip concurrent events without queuing processes or spending their cooldown`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executing = AtomicInteger()
        val maximumExecuting = AtomicInteger()
        val adapter = Adapter {
            val count = executing.incrementAndGet()
            maximumExecuting.updateAndGet { maxOf(it, count) }
            entered.complete(Unit)
            try { release.await(); ActionResult(true, "Executed") } finally { executing.decrementAndGet() }
        }
        val dispatcher = TriggerDispatcher(adapter)
        val first = async { dispatcher.dispatch(detection(), clap) }
        withTimeout(2000) { entered.await() }
        val rejected = (1..20).map { async(Dispatchers.Default) { dispatcher.dispatch(detection(Gesture.FINGER_SNAP, 1100), snap) } }.awaitAll()
        assertTrue(rejected.all { it != null && !it.success && it.message.contains("running") })
        assertEquals(1, adapter.executions.size)
        release.complete(Unit)
        assertTrue(withTimeout(2000) { first.await() }!!.success)
        assertTrue(dispatcher.dispatch(detection(Gesture.FINGER_SNAP, 1100), snap)!!.success)
        assertEquals(1, maximumExecuting.get())
        assertEquals(2, adapter.executions.size)
    }

    @Test fun `adapter failure is reported and keeps storm protection`(): Unit = runBlocking {
        val adapter = Adapter { throw IllegalStateException("Unavailable desktop service") }
        val dispatcher = TriggerDispatcher(adapter)
        val result = dispatcher.dispatch(detection(), clap)
        assertFalse(result!!.success)
        assertEquals("Unavailable desktop service", result.message)
        assertNull(dispatcher.dispatch(detection(time = 1100), clap))
        assertEquals(1, adapter.executions.size)
    }

    @Test fun `cancellation releases busy gate and propagates to owner`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val adapter = Adapter {
            if (count.incrementAndGet() == 1) { entered.complete(Unit); awaitCancellation() }
            ActionResult(true, "Recovered")
        }
        val dispatcher = TriggerDispatcher(adapter)
        val first = async { dispatcher.dispatch(detection(), clap) }
        withTimeout(2000) { entered.await() }
        first.cancelAndJoin()
        assertTrue(first.isCancelled)
        assertTrue(dispatcher.dispatch(detection(time = 2000), clap)!!.success)
    }

    @Test fun `gesture mismatch never executes an unrelated configured action`(): Unit = runBlocking {
        val adapter = Adapter()
        val result = TriggerDispatcher(adapter).dispatch(detection(Gesture.FINGER_SNAP), clap)
        assertNull(result)
        assertTrue(adapter.executions.isEmpty())
    }

    @Test fun `nonfinite detection confidence cannot bypass threshold or consume cooldown`(): Unit = runBlocking {
        val adapter = Adapter()
        val dispatcher = TriggerDispatcher(adapter)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(dispatcher.dispatch(detection(confidence = invalid), clap), "confidence=$invalid")
        }
        assertTrue(adapter.executions.isEmpty())
        assertTrue(dispatcher.dispatch(detection(), clap)!!.success)
        assertEquals(1, adapter.executions.size)
    }
}
