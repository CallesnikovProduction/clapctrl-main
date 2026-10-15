package dev.clapctrl.core.configuration

import dev.clapctrl.core.ActionConfiguration
import dev.clapctrl.core.ActionType
import dev.clapctrl.core.AppConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigurationStoreTest {
    @TempDir lateinit var directory: Path

    @Test fun `missing file loads safe defaults without creating a file`() {
        val file = directory.resolve("settings.json")
        assertEquals(AppConfiguration(), ConfigurationStore(file).load())
        assertTrue(Files.notExists(file))
    }

    @Test fun `configuration round trips with defaults and consent`() {
        val store = ConfigurationStore(directory.resolve("settings.json"))
        val configuration = AppConfiguration(microphoneId = "USB микрофон", consentGiven = true)
        store.save(configuration)
        assertEquals(configuration, store.load())
        assertEquals(1L, Files.list(directory).use { it.count() })
    }

    @Test fun `corrupt configuration is reported and preserved`() {
        val file = directory.resolve("settings.json")
        Files.writeString(file, "{bad config}")
        assertFailsWith<ConfigurationException> { ConfigurationStore(file).load() }
        assertEquals("{bad config}", Files.readString(file))
    }

    @Test fun `future schema and unknown fields are rejected`() {
        val file = directory.resolve("settings.json")
        listOf("{\"schemaVersion\":2}", "{\"remoteCommands\":true}").forEach {
            Files.writeString(file, it)
            assertFailsWith<ConfigurationException> { ConfigurationStore(file).load() }
        }
    }

    @Test fun `failed validation never replaces last good configuration`() {
        val file = directory.resolve("settings.json")
        val store = ConfigurationStore(file)
        store.save(AppConfiguration())
        val original = Files.readString(file)
        assertFailsWith<ConfigurationException> { store.save(AppConfiguration(detector = AppConfiguration().detector.copy(sensitivity = Double.NaN))) }
        assertEquals(original, Files.readString(file))
    }

    @Test fun `unsafe action and duplicate gesture rejected`() {
        val defaults = AppConfiguration()
        val unsafe = defaults.triggers.first().copy(action = ActionConfiguration(ActionType.CUSTOM_COMMAND, listOf("program")))
        assertFailsWith<ConfigurationException> { ConfigurationValidation.validate(defaults.copy(triggers = listOf(unsafe))) }
        assertFailsWith<ConfigurationException> { ConfigurationValidation.validate(defaults.copy(triggers = listOf(defaults.triggers.first(), defaults.triggers.first()))) }
        ConfigurationValidation.validate(defaults.copy(triggers = listOf(unsafe.copy(action = unsafe.action.copy(approved = true)), defaults.triggers.last())))
        assertFailsWith<ConfigurationException> { ConfigurationValidation.validate(defaults.copy(triggers = emptyList())) }
    }

    @Test fun `invalid timing and command null rejected`() {
        val defaults = AppConfiguration()
        assertFailsWith<ConfigurationException> { ConfigurationValidation.validate(defaults.copy(detector = defaults.detector.copy(doubleClapMinMs = 700, doubleClapMaxMs = 150))) }
        val trigger = defaults.triggers.first().copy(action = ActionConfiguration(ActionType.CUSTOM_COMMAND, listOf("abc\u0000def"), approved = true))
        assertFailsWith<ConfigurationException> { ConfigurationValidation.validate(defaults.copy(triggers = listOf(trigger))) }
    }
}
