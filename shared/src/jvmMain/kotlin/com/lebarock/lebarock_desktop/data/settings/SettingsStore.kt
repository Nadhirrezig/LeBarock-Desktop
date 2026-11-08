package com.lebarock.lebarock_desktop.data.settings

import com.lebarock.lebarock_desktop.data.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
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
    val decimalSeparator: String = ".",
    val thousandSeparator: String = "",
) {
    companion object {
        /** As the caisse sends it (ping, POS context); an older caisse sends no separators: "." and none. */
        fun of(dto: com.lebarock.lebarock_desktop.data.remote.CurrencyDto): CurrencyInfo = CurrencyInfo(
            code = dto.code,
            symbol = dto.symbol ?: "DT",
            position = dto.position,
            decimals = dto.decimals,
            decimalSeparator = dto.decimalSeparator?.takeIf { it.isNotEmpty() } ?: ".",
            thousandSeparator = dto.thousandSeparator ?: "",
        )
    }
}

/** Réglages → « Impression des tickets » (as on the handheld): who prints this PC's bills and receipts. */
@Serializable
enum class PrintTarget(val apiValue: String, val label: String, val hint: String) {
    /** This PC prints its own tickets on its printer (the caisse sends the ticket's content). */
    @SerialName("device")
    DEVICE("device", "Sur cet appareil", "L'imprimante de ce poste, réglée ci-dessous."),

    /** The caisse prints on the printer of the signed-in account's Caisse. */
    @SerialName("caisse")
    CAISSE("caisse", "Imprimante de la caisse", "L'imprimante de la caisse du compte connecté, réglée sur la caisse."),
}

@Serializable
enum class PrinterConnection(val label: String) {
    @SerialName("usb")
    USB("USB (Windows)"),

    @SerialName("network")
    NETWORK("Ethernet (réseau)"),
}

/** This PC's ticket printer. */
@Serializable
data class PrinterConfig(
    val target: PrintTarget = PrintTarget.DEVICE,
    val connection: PrinterConnection = PrinterConnection.USB,
    /** USB: the printer's name in Windows (Paramètres → Imprimantes). */
    val windowsPrinter: String = "",
    /** Ethernet: the printer's own IP address and raw port (9100). */
    val host: String = "",
    val port: Int = 9100,
    val paperMm: Int = 80,
    val charset: com.lebarock.lebarock_desktop.print.TicketCharset = com.lebarock.lebarock_desktop.print.TicketCharset.CP858,
) {
    /** What is missing before a ticket can print, or null. */
    val problem: String?
        get() = when (connection) {
            PrinterConnection.USB -> if (windowsPrinter.isBlank()) "Choisissez l'imprimante Windows du comptoir." else null
            PrinterConnection.NETWORK -> when {
                host.isBlank() -> "Saisissez l'adresse IP de l'imprimante."
                port !in 1..65535 -> "Le port de l'imprimante va de 1 à 65535 (habituellement 9100)."
                else -> null
            }
        }

    val label: String
        get() = when (connection) {
            PrinterConnection.USB -> windowsPrinter.ifBlank { "—" } + " (USB)"
            PrinterConnection.NETWORK -> "$host:$port (Ethernet)"
        }
}

/** A printer of the caisse's branch, as the print agent's set-up listed it. */
@Serializable
data class AgentPrinter(
    val id: Long,
    val name: String,
    val choice: String? = null,
    val active: Boolean = true,
    val caisses: List<String> = emptyList(),
) {
    val isDirecte: Boolean get() = choice == "directPrint"
}

/**
 * Impression « Directe »: this PC collects the caisse's tickets for [printerId] and prints them
 * on its own printer. [keySealed] is the branch key, encrypted for this Windows account
 * (SecretBox); it is never shown.
 */
@Serializable
data class AgentConfig(
    val enabled: Boolean = false,
    val keySealed: String? = null,
    val branchName: String = "",
    val printerId: Long? = null,
    val printers: List<AgentPrinter> = emptyList(),
) {
    val configured: Boolean get() = keySealed != null
    val printer: AgentPrinter? get() = printers.firstOrNull { it.id == printerId }
}

/**
 * This PC's settings (`settings.json` in the data folder). A device setting: kept across
 * sign-ins and account changes. No password or token is ever stored here; the print agent's
 * branch key only encrypted for this Windows account ([AgentConfig.keySealed]).
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
    val printer: PrinterConfig = PrinterConfig(),
    val agent: AgentConfig = AgentConfig(),
    /** Start AltosPOS (in the tray) when Windows starts; null = not decided yet (set on at first start). */
    val startWithWindows: Boolean? = null,
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
