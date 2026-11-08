package com.lebarock.lebarock_desktop.data

import com.lebarock.lebarock_desktop.data.remote.AddItemsRequest
import com.lebarock.lebarock_desktop.data.remote.AreaDto
import com.lebarock.lebarock_desktop.data.remote.BillRequest
import com.lebarock.lebarock_desktop.data.remote.CancelReasonDto
import com.lebarock.lebarock_desktop.data.remote.CancelRequest
import com.lebarock.lebarock_desktop.data.remote.CustomerDto
import com.lebarock.lebarock_desktop.data.remote.CustomerRequest
import com.lebarock.lebarock_desktop.data.remote.NewOrderRefDto
import com.lebarock.lebarock_desktop.data.remote.PaymentMethodRequest
import com.lebarock.lebarock_desktop.data.remote.SplitLine
import com.lebarock.lebarock_desktop.data.remote.SplitRequest
import com.lebarock.lebarock_desktop.data.remote.TableIdRequest
import com.lebarock.lebarock_desktop.data.remote.WaiterRequest
import com.lebarock.lebarock_desktop.data.remote.Envelope
import com.lebarock.lebarock_desktop.data.remote.MenuDto
import com.lebarock.lebarock_desktop.data.remote.MenuItemDto
import com.lebarock.lebarock_desktop.data.remote.OrderActionDto
import com.lebarock.lebarock_desktop.data.remote.OrderDetailDto
import com.lebarock.lebarock_desktop.data.remote.OrdersResponse
import com.lebarock.lebarock_desktop.data.remote.PosContextDto
import com.lebarock.lebarock_desktop.data.remote.PrintRequest
import com.lebarock.lebarock_desktop.data.remote.SendOrderRequest
import com.lebarock.lebarock_desktop.data.remote.SendWaitingRequest
import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import com.lebarock.lebarock_desktop.data.settings.PrintTarget
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.print.ReceiptPayload
import com.lebarock.lebarock_desktop.print.TicketPrinter
import kotlinx.serialization.json.Json

/** What happened to a ticket asked with a bill or a reprint: where it came out, or why not. */
data class PrintOutcome(val ok: Boolean, val message: String)

/** A caisse answer and, when a ticket was asked for, its [print] outcome. */
data class Printed<T>(val value: T, val print: PrintOutcome?)

/** What a table / check operation answers (D4): the check as it is now, and the caisse's French message. */
data class CheckChange(val order: OrderDetailDto, val message: String, val newOrder: NewOrderRefDto? = null)

/**
 * Orders at the counter (D3), as the handheld's AltosRepository: the menu, the tables, the order
 * lifecycle (send, add a round, bill, reprint, send again, cancel) on the same `/api/mobile`
 * contract. Where a ticket prints follows Réglages → « Impression des tickets »: « Sur cet appareil »
 * prints the caisse's ticket here (D2), « Imprimante de la caisse » lets the caisse print it.
 */
class OrdersRepository(
    private val caisse: CaisseRepository,
    private val settings: SettingsStore,
    private val printer: TicketPrinter,
    private val json: Json,
) {
    private val target: PrintTarget get() = settings.current.printer.target

    val currency: CurrencyInfo get() = settings.current.currency

    // ---- Catalogue and tables ----

    suspend fun menus(): Result<List<MenuDto>> = caisse.call { caisse.api().menus().data.orEmpty() }

    suspend fun menuItems(menuId: Long?): Result<List<MenuItemDto>> = caisse.call { caisse.api().menuItems(menuId).data.orEmpty() }

    suspend fun tables(): Result<List<AreaDto>> = caisse.call { caisse.api().tables().data.orEmpty() }

    /**
     * The cart's make-up (order types, next number, taxes, charges, waiters). The currency comes with
     * it, read fresh: a change made in the caisse's settings shows at once, not at the next test.
     */
    suspend fun posContext(): Result<PosContextDto> = caisse.call {
        caisse.api().posContext().data ?: throw unexpected()
    }.onSuccess { context ->
        context.currency?.let(CurrencyInfo::of)?.let { fresh ->
            if (fresh != settings.current.currency) settings.update { it.copy(currency = fresh) }
        }
    }

    suspend fun cancelReasons(): Result<List<CancelReasonDto>> = caisse.call { caisse.api().cancelReasons().data.orEmpty() }

    // ---- Commandes ----

    /** Today's orders (the caisse's day), with the counts per status for the chips. */
    suspend fun orders(status: String? = null): Result<OrdersResponse> = caisse.call { caisse.api().orders(status = status) }

    suspend fun orderDetail(id: Long): Result<OrderDetailDto> = caisse.call { caisse.api().orderDetail(id).data ?: throw unexpected() }

    // ---- The order's lifecycle ----

    /** A new order: kot (Envoyer) | bill (Encaisser Ticket) | bill_print (Imprimer Ticket). */
    suspend fun sendOrder(request: SendOrderRequest): Result<Printed<OrderActionDto>> = caisse.call {
        val body = if (request.action == "bill_print") request.copy(printTarget = target.apiValue) else request
        val envelope = caisse.api().sendOrder(body)
        Printed(envelope.data ?: throw unexpected(), if (request.action == "bill_print") outcome(envelope) else null)
    }

    /** « Nouveau ticket »: a round on an open check. */
    suspend fun addItems(orderId: Long, request: AddItemsRequest): Result<OrderActionDto> = caisse.call {
        caisse.api().addItems(orderId, request).data ?: throw unexpected()
    }

    /** The bill is made whatever the printer does: a ticket that did not come out is in [Printed.print]. */
    suspend fun bill(orderId: Long, print: Boolean, force: Boolean = false): Result<Printed<OrderActionDto>> = caisse.call {
        val envelope = caisse.api().billOrder(orderId, BillRequest(print = print, force = force, printTarget = if (print) target.apiValue else null))
        Printed(envelope.data ?: throw unexpected(), if (print) outcome(envelope) else null)
    }

    /** C5: send again the lines waiting for stock; [force] = « Forcer l'envoi ». */
    suspend fun sendWaiting(orderId: Long, force: Boolean): Result<OrderActionDto> = caisse.call {
        caisse.api().sendWaiting(orderId, SendWaitingRequest(force)).data ?: throw unexpected()
    }

    /** Takes a line not sent off the check; false when it was the last one (the check is gone). */
    suspend fun removeItem(orderId: Long, itemId: Long): Result<Boolean> = caisse.call {
        caisse.api().removeOrderItem(orderId, itemId).data != null
    }

    /**
     * « Imprimer »: the bill again. Returns where it printed; fails with the reason (the caisse's
     * printer refused, or this PC's printer did).
     */
    suspend fun reprint(orderId: Long): Result<String> = caisse.call {
        val envelope = caisse.api().printOrder(orderId, PrintRequest(target.apiValue))
        val result = outcome(envelope) ?: PrintOutcome(true, "Ticket envoyé à l'imprimante de la caisse.")
        if (!result.ok) throw ApiFailure(result.message, FailureKind.OTHER, code = "print_failed")
        result.message
    }

    /** « Annuler la commande »: kept as « Annulée » with its reason; money taken goes back first. */
    suspend fun cancel(orderId: Long, reasonId: Long?, reason: String?): Result<Unit> = caisse.call {
        caisse.api().cancelOrder(orderId, CancelRequest(reasonId, reason?.trim()?.ifEmpty { null }))
        Unit
    }

    /** « Servi » on a round the kitchen has ready. */
    suspend fun served(kotId: Long): Result<Unit> = caisse.call { caisse.api().markServed(kotId); Unit }

    // ---- D4: a table and its check, as on the web ----

    /** « Changer de table »: the check moves to a free table (an occupied one is « Fusionner »). */
    suspend fun moveOrder(orderId: Long, tableId: Long): Result<CheckChange> = change { caisse.api().moveOrder(orderId, TableIdRequest(tableId)) }

    /** « Fusionner »: this table's party joins [tableId]'s check; the answer is that check. */
    suspend fun mergeOrder(orderId: Long, tableId: Long): Result<CheckChange> = change { caisse.api().mergeOrder(orderId, TableIdRequest(tableId)) }

    /** « Séparer »: [quantities] (line id → how many) move to a new check on the same table. */
    suspend fun splitOrder(orderId: Long, quantities: Map<Long, Int>): Result<CheckChange> = caisse.call {
        val answer = caisse.api().splitOrder(orderId, SplitRequest(quantities.map { (id, qty) -> SplitLine(id, qty) }))
        CheckChange(answer.data ?: throw unexpected(), answer.message ?: "Commande séparée.", answer.newOrder)
    }

    suspend fun setWaiter(orderId: Long, waiterId: Long): Result<CheckChange> = change { caisse.api().setWaiter(orderId, WaiterRequest(waiterId)) }

    /** The restaurant's customers matching a name, phone or e-mail (2 characters at least). */
    suspend fun customers(search: String): Result<List<CustomerDto>> = caisse.call { caisse.api().customers(search.trim()).data.orEmpty() }

    suspend fun setCustomer(orderId: Long, request: CustomerRequest): Result<CheckChange> = change { caisse.api().setCustomer(orderId, request) }

    suspend fun removeCharge(orderId: Long, chargeId: Long): Result<CheckChange> = change { caisse.api().removeCharge(orderId, chargeId) }

    /** Corrects a payment recorded with the wrong method, « cash » ⇄ « card »; the drawer follows. */
    suspend fun changePaymentMethod(orderId: Long, paymentId: Long, method: String): Result<CheckChange> =
        change { caisse.api().changePaymentMethod(orderId, paymentId, PaymentMethodRequest(method)) }

    suspend fun markReviewed(orderId: Long): Result<CheckChange> = change { caisse.api().markReviewed(orderId) }

    /** « Libérer »: a table opened (web, handheld) where nobody ordered is free again. */
    suspend fun releaseTable(tableId: Long): Result<String> = caisse.call {
        caisse.api().releaseTable(tableId).message ?: "Table libérée."
    }

    private suspend fun change(block: suspend () -> Envelope<OrderDetailDto>): Result<CheckChange> = caisse.call {
        val envelope = block()
        CheckChange(envelope.data ?: throw unexpected(), envelope.message ?: "Enregistré.")
    }

    /**
     * The ticket's fate: « Sur cet appareil » → the caisse sent its content and this PC prints it;
     * « Imprimante de la caisse » → what the caisse did with it.
     */
    private suspend fun outcome(envelope: Envelope<*>): PrintOutcome? {
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

    private fun unexpected() = ApiFailure("Réponse inattendue de la caisse.", FailureKind.NOT_A_CAISSE)
}
