package com.lebarock.lebarock_desktop.data.settings

import com.lebarock.lebarock_desktop.data.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** An account of the sign-in list, kept so the list shows at once at the next start. */
@Serializable
data class CachedAccount(
    val id: Long,
    val name: String,
    val roles: List<String> = emptyList(),
)

/** The caisse's currency, read with "Tester la connexion" (used from D3 for prices). */
@Serializable
data class CurrencyInfo(
    val code: String? = null,
    val symbol: String = "DT",
    val position: String? = null,
    val decimals: Int = 3,
)

/**
 * This PC's settings (`settings.json` in the data folder). A device setting: kept across
 * sign-ins and account changes. No password, token or key is ever stored here.
 */
@Serializable
data class AppSettings(
    val server: ServerAddress = ServerAddress(),
    /** The restaurant's name as the caisse gave it at the last successful test ("Le Baroque"). */
    val restaurantName: String = "",
    val currency: CurrencyInfo = CurrencyInfo(),
    /** Minutes without a click, key or touch before the session is closed; 0 = never. */
    val idleLockMinutes: Int = DEFAULT_IDLE_MINUTES,
    val accounts: List<CachedAccount> = emptyList(),
    /** The last account signed in on this PC, shown first in the list. */
    val lastUserId: Long? = null,
) {
    companion object {
        const val DEFAULT_IDLE_MINUTES = 5
        val IDLE_CHOICES = listOf(0, 2, 5, 10, 15, 30)
    }
}

/**
 * Reads and writes [AppSettings] as JSON. Each change is written to a temporary file then moved
 * over the old one, so a crash or a power cut never leaves half a file. A file that cannot be
 * read is kept aside (`settings.json.bad`) and the defaults are used.
 */
class SettingsStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value)
        if (next != _settings.value) {
            write(next)
            _settings.value = next
        }
        return next
    }

    private fun load(): AppSettings {
        if (!file.exists()) return AppSettings()
        return try {
            json.decodeFromString(AppSettings.serializer(), file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            AppLog.warn("Réglages illisibles, valeurs par défaut utilisées (copie : ${file.name}.bad)", e)
            runCatching { Files.copy(file.toPath(), File(file.path + ".bad").toPath(), StandardCopyOption.REPLACE_EXISTING) }
            AppSettings()
        }
    }

    private fun write(settings: AppSettings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(AppSettings.serializer(), settings), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
