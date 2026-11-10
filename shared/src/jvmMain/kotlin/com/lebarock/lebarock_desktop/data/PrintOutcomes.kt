package com.lebarock.lebarock_desktop.data

import com.lebarock.lebarock_desktop.data.remote.Envelope
import com.lebarock.lebarock_desktop.print.ReceiptPayload
import com.lebarock.lebarock_desktop.print.TicketPrinter
import kotlinx.serialization.json.Json

/**
 * What became of a ticket asked with a call (bill, receipt, X / Z): « Sur cet appareil » → the caisse sent its
 * content and this PC prints it; « Imprimante de la caisse » → what the caisse did with it. Null when no ticket.
 */
class PrintOutcomes(private val printer: TicketPrinter, private val json: Json) {

    suspend fun of(envelope: Envelope<*>): PrintOutcome? {
        envelope.print?.let { instruction ->
            val payload = runCatching { json.decodeFromJsonElement(ReceiptPayload.serializer(), instruction.receipt) }.getOrElse {
                return PrintOutcome(false, "Le ticket reçu de la caisse est illisible : il n'a pas été imprimé.")
            }
            return printer.printReceipt(payload).fold(
                onSuccess = { PrintOutcome(true, it) },
                onFailure = { PrintOutcome(false, it.message ?: "Le ticket n'a pas été imprimé.") },
            )
        }
        val result = envelope.printResult ?: return null
        return when (result.status) {
            "printed", "queued" -> PrintOutcome(true, result.message ?: "Ticket envoyé à l'imprimante de la caisse.")
            "failed" -> PrintOutcome(false, result.message ?: "Le ticket n'a pas été imprimé.")
            else -> PrintOutcome(false, "La caisse n'a pas préparé le ticket pour ce poste.")
        }
    }
}
