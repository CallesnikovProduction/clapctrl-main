package dev.clapctrl.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class CommandRequest(val argv: List<String>, val timeoutMs: Long = 5000, val launch: Boolean = false, val environment: Map<String, String> = emptyMap())
data class CommandOutcome(val exitCode: Int?, val output: String = "", val launched: Boolean = false, val timedOut: Boolean = false)
fun interface CommandRunner { suspend fun run(request: CommandRequest): CommandOutcome }

/** No shell parsing; custom argv stays argv. All capture and process lifetimes are bounded. */
class ProcessCommandRunner(private val outputLimit: Int = 8192) : CommandRunner {
    private val permits = Semaphore(2)
    override suspend fun run(request: CommandRequest): CommandOutcome = permits.withPermit {
        require(request.argv.isNotEmpty() && request.argv.all { '\u0000' !in it }) { "Invalid command arguments" }
        require(request.timeoutMs in 100..120000) { "Timeout must be 100–120000 ms" }
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val builder = ProcessBuilder(request.argv).redirectErrorStream(true)
            builder.environment().putAll(request.environment)
            if (request.launch) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            val process = builder.start()
            val output = ByteArrayOutputStream()
            val reader = if (request.launch) null else Thread({
                runCatching {
                    process.inputStream.use { stream ->
                        val buffer = ByteArray(2048)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            synchronized(output) { if (output.size() < outputLimit) output.write(buffer, 0, minOf(count, outputLimit - output.size())) }
                        }
                    }
                }
            }, "clapctrl-process-output").apply { isDaemon = true; start() }
            var detached = false
            try {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(if (request.launch) minOf(300, request.timeoutMs) else request.timeoutMs)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (process.waitFor(25, TimeUnit.MILLISECONDS)) {
                        reader?.join(200)
                        return@withContext CommandOutcome(process.exitValue(), synchronized(output) { output.toString(Charsets.UTF_8) })
                    }
                    if (System.nanoTime() >= deadline) {
                        if (request.launch) { detached = true; return@withContext CommandOutcome(null, launched = true) }
                        return@withContext CommandOutcome(null, "Command timed out", timedOut = true)
                    }
                }
                @Suppress("UNREACHABLE_CODE") CommandOutcome(-1)
            } finally {
                if (!detached) {
                    process.descendants().use { descendants -> descendants.forEach { runCatching { it.destroyForcibly() } } }
                    if (process.isAlive) process.destroyForcibly()
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
                runCatching { process.outputStream.close() }
            }
        }
    }
}
