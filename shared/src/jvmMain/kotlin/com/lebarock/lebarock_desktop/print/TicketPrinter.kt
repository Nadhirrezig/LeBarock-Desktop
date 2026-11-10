package com.lebarock.lebarock_desktop.print

import com.lebarock.lebarock_desktop.AppInfo
import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * This PC's ticket printer (Réglages → Imprimante de ce poste): lays out the caisse's ticket JSON
 * and sends the bytes over USB or Ethernet. One ticket at a time: the print agent and a bill
 * printed at the counter never mix their bytes on the paper.
 */
class TicketPrinter(
    private val settings: SettingsStore,
    private val transportFor: (PrinterConfig) -> TicketTransport = TicketTransport::of,
) {
    private val lock = Mutex()

    val config: PrinterConfig get() = settings.current.printer

    /** A bill, a receipt or an X / Z from the caisse. */
    suspend fun printReceipt(payload: ReceiptPayload): Result<String> {
        val cfg = config
        return send(cfg, "ticket") {
            EscPosEncoder(cfg.charset).encode(ReceiptRenderer(ReceiptRenderer.columnsFor(cfg.paperMm)).render(payload))
        }
    }

    /**
     * A kitchen ticket built on this PC while the caisse does not answer (offline step 2): on this PC's printer,
     * whatever Réglages says about bills (« Imprimante de la caisse » cannot print while the caisse is down).
     */
    suspend fun printKitchen(ticket: KitchenTicketPrint): Result<String> {
        val cfg = config
        return send(cfg, "cuisine hors ligne") {
            EscPosEncoder(cfg.charset).encode(ReceiptRenderer(ReceiptRenderer.columnsFor(cfg.paperMm)).renderKitchen(ticket))
        }
    }

    /** Réglages → "Imprimer un test", with what is being tested on the paper. */
    suspend fun printTest(restaurant: String, cfg: PrinterConfig = config): Result<String> = send(cfg, "test") {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        val pc = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("—")
        val lines = listOf(
            "Poste" to pc,
            "Imprimante" to cfg.label,
            "Papier" to "${cfg.paperMm} mm (${ReceiptRenderer.columnsFor(cfg.paperMm)} col.)",
            "Accents" to cfg.charset.label,
            "Date" to now,
            "Version" to AppInfo.version,
        )
        EscPosEncoder(cfg.charset).encode(ReceiptRenderer(ReceiptRenderer.columnsFor(cfg.paperMm)).renderTest(restaurant, lines))
    }

    /**
     * Bytes the caisse already laid out (a "Directe" print job): sent as they are, with a cut
     * added when the job asks for one and its bytes have none.
     */
    suspend fun printRaw(bytes: ByteArray, cut: Boolean): Result<String> {
        val cfg = config
        return send(cfg, "Directe") {
            if (cut && !containsCut(bytes)) bytes + byteArrayOf(0x1B, 'd'.code.toByte(), 3) + EscPosEncoder.CUT else bytes
        }
    }

    private suspend fun send(cfg: PrinterConfig, what: String, bytes: () -> ByteArray): Result<String> = withContext(Dispatchers.IO) {
        cfg.problem?.let { return@withContext Result.failure(PrintFailure(it)) }
        lock.withLock {
            runCatching { transportFor(cfg).send(bytes()) }
                .onSuccess { AppLog.info("Impression ($what) : $it") }
                .onFailure { AppLog.warn("Impression ($what) échouée sur ${cfg.label} : ${it.message}") }
        }
    }

    companion object {
        /** GS V anywhere in the bytes. */
        fun containsCut(bytes: ByteArray): Boolean =
            (0 until bytes.size - 1).any { bytes[it] == 0x1D.toByte() && bytes[it + 1] == 'V'.code.toByte() }
    }
}
