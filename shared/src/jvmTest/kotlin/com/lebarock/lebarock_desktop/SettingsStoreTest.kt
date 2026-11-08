package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.settings.AppSettings
import com.lebarock.lebarock_desktop.data.settings.CachedAccount
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {

    private fun tempFile(): File = Files.createTempDirectory("altospos-settings").toFile().resolve("settings.json")

    @Test
    fun settings_survive_a_restart() {
        val file = tempFile()
        SettingsStore(file).update {
            it.copy(
                server = ServerAddress(true, "192.168.1.20", 443),
                restaurantName = "Le Baroque",
                idleLockMinutes = 10,
                accounts = listOf(CachedAccount(3, "Amel", listOf("Caissier"))),
                lastUserId = 3,
            )
        }
        val reread = SettingsStore(file).current
        assertEquals("192.168.1.20", reread.server.host)
        assertEquals("Le Baroque", reread.restaurantName)
        assertEquals(10, reread.idleLockMinutes)
        assertEquals(listOf(CachedAccount(3, "Amel", listOf("Caissier"))), reread.accounts)
        assertEquals(3L, reread.lastUserId)
        assertFalse(File(file.path + ".tmp").exists(), "no temporary file left behind")
    }

    @Test
    fun a_damaged_file_is_kept_aside_and_the_defaults_are_used() {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("{ this is not json")
        val store = SettingsStore(file)
        assertEquals(AppSettings(), store.current)
        assertTrue(File(file.path + ".bad").exists())
    }

    @Test
    fun a_file_from_a_newer_version_still_reads() {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("""{"server":{"https":true,"host":"10.0.0.2","port":443},"somethingNew":42}""")
        assertEquals("10.0.0.2", SettingsStore(file).current.server.host)
        assertEquals(AppSettings.DEFAULT_IDLE_MINUTES, SettingsStore(file).current.idleLockMinutes)
    }

    @Test
    fun the_file_never_holds_a_password_or_a_token() {
        val file = tempFile()
        SettingsStore(file).update { it.copy(server = ServerAddress(true, "10.0.0.2", 443)) }
        val text = file.readText().lowercase()
        assertFalse(text.contains("token"))
        assertFalse(text.contains("password"))
    }
}
