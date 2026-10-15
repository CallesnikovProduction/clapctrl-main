package dev.clapctrl.core.configuration

import dev.clapctrl.core.ActionType
import dev.clapctrl.core.AppConfiguration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

class ConfigurationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object ConfigurationValidation {
    fun validate(configuration: AppConfiguration) {
        fun check(valid: Boolean, message: String) {
            if (!valid) throw ConfigurationException(message)
        }
        check(configuration.schemaVersion == 1, "Unsupported configuration schema ${configuration.schemaVersion}")
        check(configuration.microphoneId.isNotBlank() && configuration.microphoneId.length <= 4096 && '\u0000' !in configuration.microphoneId,
            "Invalid microphone identifier")
        with(configuration.detector) {
            check(sensitivity.isFinite() && sensitivity in 0.0..1.0, "Sensitivity must be between 0 and 1")
            check(doubleClapMinMs in 50..2000 && doubleClapMaxMs in 100..3000 && doubleClapMinMs < doubleClapMaxMs,
                "Double-clap interval must be ordered and between 50 and 3000 ms")
            check(refractoryMs in 20..1000 && refractoryMs < doubleClapMinMs, "Refractory period must be shorter than the clap interval")
        }
        check(configuration.triggers.size == 2 && configuration.triggers.map { it.gesture }.distinct().size == 2,
            "Configuration must contain exactly one trigger for each gesture")
        configuration.triggers.forEach { trigger ->
            check(trigger.confidence.isFinite() && trigger.confidence in 0.0..1.0, "Confidence must be between 0 and 1")
            check(trigger.cooldownMs in 100..600_000, "Cooldown must be between 100 ms and 10 minutes")
            with(trigger.action) {
                check(timeoutMs in 100..120_000, "Action timeout must be between 100 ms and 2 minutes")
                check(workspace in 1..999, "Workspace must be between 1 and 999")
                check(command.size <= 128 && command.all { it.length <= 32768 && '\u0000' !in it }, "Invalid command arguments")
                if (type == ActionType.CUSTOM_COMMAND) {
                    check(command.isNotEmpty() && command.first().isNotBlank(), "Custom command needs an executable")
                    check(approved, "Custom command requires explicit approval")
                }
            }
        }
    }
}

/** Strict decoding deliberately preserves a corrupt/unsupported file for user recovery. */
class ConfigurationStore(private val path: Path) {
    fun load(): AppConfiguration {
        if (Files.notExists(path, NOFOLLOW_LINKS)) return AppConfiguration()
        return try {
            val configuration = storageJson.decodeFromString<AppConfiguration>(readBoundedJson(path))
            ConfigurationValidation.validate(configuration)
            configuration
        } catch (e: Exception) {
            throw ConfigurationException("Cannot load configuration at $path: ${e.message}", e)
        }
    }

    fun save(configuration: AppConfiguration) {
        ConfigurationValidation.validate(configuration)
        atomicWriteJson(path, storageJson.encodeToString(configuration))
    }
}

internal val storageJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

internal fun readBoundedJson(path: Path): String {
    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Expected a regular file, not a symbolic link: $path" }
    require(Files.size(path) <= 4 * 1024 * 1024) { "JSON file exceeds 4 MiB limit" }
    return Files.readString(path)
}

/** No non-atomic fallback: an unsupported filesystem fails without replacing the previous file. */
internal fun atomicWriteJson(path: Path, text: String) {
    val target = path.toAbsolutePath().normalize()
    require(!Files.isSymbolicLink(target)) { "Refusing to overwrite a symbolic link: $target" }
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".clapctrl-", ".tmp")
    try {
        FileChannel.open(temporary, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
