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
import com.lebarock.lebarock_desktop.data.remote.DiscountRequest
import com.lebarock.lebarock_desktop.data.remote.PaymentContextDto
import com.lebarock.lebarock_desktop.data.remote.PaymentRequest
import com.lebarock.lebarock_desktop.data.remote.PaymentResultDto
import com.lebarock.lebarock_desktop.data.remote.TipRequest
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

/** What taking a line off left: the check (KEPT), the check « Annulée » (CANCELLED), or nothing (DELETED). */
enum class LineRemoval { KEPT, CANCELLED, DELETED }

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
    /**
     * Offline mode (step 2): while the caisse does not answer, or while actions still wait to be sent, the calls are
     * answered from this PC's copy (the handheld's AltosRepository); null = no offline mode (tests).
     */
    private val offline: com.lebarock.lebarock_desktop.data.offline.OfflineOrders? = null,
    private val sync: com.lebarock.lebarock_desktop.data.offline.OfflineSync? = null,
) {
    private val target: PrintTarget get() = settings.current.printer.target
    private val outcomes = PrintOutcomes(printer, json)

    /** The offline orders, when the screens work from the copy now. */
    private suspend fun offlineNow(): com.lebarock.lebarock_desktop.data.offline.OfflineOrders? = offline?.takeIf { it.active() }

    /** Online again: an order taken offline (negative id) is opened as its real check at the caisse. */
    private suspend fun real(id: Long): Long = if (id < 0) offline?.syncedIdOf(id) ?: id else id

    /** What waits for the caisse offline (client, 2026-11-10): a click says so in French. */
    private suspend fun <T> waits(what: String, online: suspend () -> Result<T>): Result<T> =
        offlineNow()?.unavailable(what) ?: online()

    val currency: CurrencyInfo get() = settings.current.currency

    // ---- Catalogue and tables ----

    suspend fun menus(): Result<List<MenuDto>> = offlineNow()?.menus() ?: caisse.call { caisse.api().menus().data.orEmpty() }

    suspend fun menuItems(menuId: Long?): Result<List<MenuItemDto>> =
        offlineNow()?.menuItems(menuId) ?: caisse.call { caisse.api().menuItems(menuId).data.orEmpty() }

    suspend fun tables(): Result<List<AreaDto>> = offlineNow()?.tables() ?: caisse.call { caisse.api().tables().data.orEmpty() }

    /**
     * The cart's make-up (order types, next number, taxes, charges, waiters). The currency comes with
     * it, read fresh: a change made in the caisse's settings shows at once, not at the next test.
     */
    suspend fun posContext(): Result<PosContextDto> = offlineNow()?.posContext() ?: caisse.call {
        caisse.api().posContext().data ?: throw unexpected()
    }.onSuccess { context ->
        context.currency?.let(CurrencyInfo::of)?.let { fresh ->
            if (fresh != settings.current.currency) settings.update { it.copy(currency = fresh) }
        }
        // Kept for the offline cart (its order types): the caisse gives them to a signed-in account only.
        runCatching { sync?.rememberPosContext(json.encodeToJsonElement(PosContextDto.serializer(), context) as kotlinx.serialization.json.JsonObject) }
    }

    suspend fun cancelReasons(): Result<List<CancelReasonDto>> = waits("annuler une commande") { caisse.call { caisse.api().cancelReasons().data.orEmpty() } }

    // ---- Commandes ----

    /**
     * Today's orders (the caisse's day), with the counts per status for the chips; [toPay]: the checks still to pay
     * (billed or on credit) whatever their day (D5, « À encaisser »).
     */
    suspend fun orders(status: String? = null, toPay: Boolean = false): Result<OrdersResponse> =
        offlineNow()?.orders(status, toPay) ?: caisse.call { caisse.api().orders(status = status, toPay = if (toPay) 1 else null) }

    suspend fun orderDetail(id: Long): Result<OrderDetailDto> =
        offlineNow()?.orderDetail(id) ?: caisse.call { caisse.api().orderDetail(real(id)).data ?: throw unexpected() }

    // ---- The order's lifecycle ----

    /** A new order: kot (Envoyer) | bill (Encaisser Ticket) | bill_print (Imprimer Ticket). */
    suspend fun sendOrder(request: SendOrderRequest): Result<Printed<OrderActionDto>> = offlineNow()?.sendOrder(request) ?: caisse.call {
        val body = if (request.action == "bill_print") request.copy(printTarget = target.apiValue) else request
        val envelope = caisse.api().sendOrder(body)
        Printed(envelope.data ?: throw unexpected(), if (request.action == "bill_print") outcome(envelope) else null)
    }

    /** « Nouveau ticket »: a round on an open check. Offline, [Printed.print] says what the kitchen tickets did. */
    suspend fun addItems(orderId: Long, request: AddItemsRequest): Result<Printed<OrderActionDto>> = offlineNow()?.addItems(orderId, request) ?: caisse.call {
        Printed(caisse.api().addItems(real(orderId), request).data ?: throw unexpected(), null)
    }

    /** The bill is made whatever the printer does: a ticket that did not come out is in [Printed.print]. */
    suspend fun bill(orderId: Long, print: Boolean, force: Boolean = false): Result<Printed<OrderActionDto>> = offlineNow()?.billOrder(orderId, print) ?: caisse.call {
        val envelope = caisse.api().billOrder(real(orderId), BillRequest(print = print, force = force, printTarget = if (print) target.apiValue else null))
        Printed(envelope.data ?: throw unexpected(), if (print) outcome(envelope) else null)
    }

    /** C5: send again the lines waiting for stock; [force] = « Forcer l'envoi ». */
    suspend fun sendWaiting(orderId: Long, force: Boolean): Result<OrderActionDto> = waits("renvoyer des articles en attente de stock") {
        caisse.call { caisse.api().sendWaiting(real(orderId), SendWaitingRequest(force)).data ?: throw unexpected() }
    }

    /**
     * Takes a line off the check (one waiting for stock, or a sent drink with « Annuler une commande »). The last
     * line gone closes the check: kept « Annulée » when something of it was sent, deleted otherwise.
     */
    suspend fun removeItem(orderId: Long, itemId: Long): Result<LineRemoval> = waits("retirer un article") {
        caisse.call {
            val answer = caisse.api().removeOrderItem(real(orderId), itemId)
            when {
                answer.data != null -> LineRemoval.KEPT
                answer.cancelled == true -> LineRemoval.CANCELLED
                else -> LineRemoval.DELETED
            }
        }
    }

    /**
     * « Imprimer »: the bill again. Returns where it printed; fails with the reason (the caisse's
     * printer refused, or this PC's printer did).
     */
    suspend fun reprint(orderId: Long): Result<String> = offlineNow()?.printOrder(orderId) ?: caisse.call {
        val envelope = caisse.api().printOrder(real(orderId), PrintRequest(target.apiValue))
        val result = outcome(envelope) ?: PrintOutcome(true, "Ticket envoyé à l'imprimante de la caisse.")
        if (!result.ok) throw ApiFailure(result.message, FailureKind.OTHER, code = "print_failed")
        result.message
    }

    /** « Annuler la commande »: kept as « Annulée » with its reason; money taken goes back first. */
    suspend fun cancel(orderId: Long, reasonId: Long?, reason: String?): Result<Unit> = waits("annuler une commande") {
        caisse.call {
            caisse.api().cancelOrder(real(orderId), CancelRequest(reasonId, reason?.trim()?.ifEmpty { null }))
            Unit
        }
    }

    /** « Servi » on a round the kitchen has ready. */
    suspend fun served(kotId: Long): Result<Unit> = waits("marquer un ticket servi") { caisse.call { caisse.api().markServed(kotId); Unit } }

    // ---- D4: a table and its check, as on the web ----

    /** « Changer de table »: the check moves to a free table (an occupied one is « Fusionner »). */
    suspend fun moveOrder(orderId: Long, tableId: Long): Result<CheckChange> =
        waits("changer de table") { change { caisse.api().moveOrder(real(orderId), TableIdRequest(tableId)) } }

    /** « Fusionner »: this table's party joins [tableId]'s check; the answer is that check. */
    suspend fun mergeOrder(orderId: Long, tableId: Long): Result<CheckChange> =
        waits("fusionner des tables") { change { caisse.api().mergeOrder(real(orderId), TableIdRequest(tableId)) } }

    /** « Séparer »: [quantities] (line id → how many) move to a new check on the same table. */
    suspend fun splitOrder(orderId: Long, quantities: Map<Long, Int>): Result<CheckChange> = waits("séparer une commande") {
        caisse.call {
            val answer = caisse.api().splitOrder(real(orderId), SplitRequest(quantities.map { (id, qty) -> SplitLine(id, qty) }))
            CheckChange(answer.data ?: throw unexpected(), answer.message ?: "Commande séparée.", answer.newOrder)
        }
    }

    suspend fun setWaiter(orderId: Long, waiterId: Long): Result<CheckChange> =
        waits("changer de serveur") { change { caisse.api().setWaiter(real(orderId), WaiterRequest(waiterId)) } }

    /** The restaurant's customers matching a name, phone or e-mail (2 characters at least). */
    suspend fun customers(search: String): Result<List<CustomerDto>> =
        waits("chercher un client") { caisse.call { caisse.api().customers(search.trim()).data.orEmpty() } }

    suspend fun setCustomer(orderId: Long, request: CustomerRequest): Result<CheckChange> =
        waits("choisir un client") { change { caisse.api().setCustomer(real(orderId), request) } }

    suspend fun removeCharge(orderId: Long, chargeId: Long): Result<CheckChange> =
        waits("retirer un frais") { change { caisse.api().removeCharge(real(orderId), chargeId) } }

    /** Corrects a payment recorded with the wrong method, « cash » ⇄ « card »; the drawer follows. */
    suspend fun changePaymentMethod(orderId: Long, paymentId: Long, method: String): Result<CheckChange> =
        waits("corriger un paiement") { change { caisse.api().changePaymentMethod(real(orderId), paymentId, PaymentMethodRequest(method)) } }

    suspend fun markReviewed(orderId: Long): Result<CheckChange> = waits("marquer une commande vérifiée") { change { caisse.api().markReviewed(real(orderId)) } }

    /** « Libérer »: a table opened (web, handheld) where nobody ordered is free again. */
    suspend fun releaseTable(tableId: Long): Result<String> = waits("libérer une table") {
        caisse.call { caisse.api().releaseTable(tableId).message ?: "Table libérée." }
    }

    // ---- D5: Encaisser, as the web's payment window ----

    /** What the payment window needs: the due, the paid amount, the methods this account may use, the split's lines. */
    suspend fun paymentContext(orderId: Long): Result<PaymentContextDto> = waits("l'encaissement") {
        caisse.call { caisse.api().paymentContext(real(orderId)).data ?: throw unexpected() }
    }

    /**
     * Records a payment (cash, card, credit, or a split in one go). The receipt prints by itself once the check is
     * settled (client, 2026-11-10), where Réglages says: its outcome is in [Printed.print] (null while it stays open).
     */
    suspend fun pay(orderId: Long, request: PaymentRequest): Result<Printed<PaymentResultDto>> = waits("l'encaissement") {
        caisse.call {
            val envelope = caisse.api().pay(real(orderId), request.copy(printTarget = target.apiValue))
            Printed(envelope.data ?: throw unexpected(), outcome(envelope))
        }
    }

    /** « Pourboire »: sets (or, at 0, takes off) the check's tip; the total follows. */
    suspend fun tip(orderId: Long, amount: Double, note: String?): Result<Unit> = waits("ajouter un pourboire") {
        caisse.call {
            caisse.api().tip(real(orderId), TipRequest(amount, note?.trim()?.ifEmpty { null }))
            Unit
        }
    }

    /** « Remise » on an open check (fixed | percent); [type] null takes it off. */
    suspend fun setDiscount(orderId: Long, type: String?, value: Double?): Result<CheckChange> =
        waits("la remise") { change { caisse.api().setDiscount(real(orderId), DiscountRequest(type, value)) } }

    private suspend fun change(block: suspend () -> Envelope<OrderDetailDto>): Result<CheckChange> = caisse.call {
        val envelope = block()
        CheckChange(envelope.data ?: throw unexpected(), envelope.message ?: "Enregistré.")
    }

    /** Where the ticket went (« Sur cet appareil »: printed here; « Imprimante de la caisse »: what the caisse did). */
    private suspend fun outcome(envelope: Envelope<*>): PrintOutcome? = outcomes.of(envelope)

    private fun unexpected() = ApiFailure("Réponse inattendue de la caisse.", FailureKind.NOT_A_CAISSE)
}
