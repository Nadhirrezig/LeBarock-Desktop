package com.lebarock.lebarock_desktop.ui.orders

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.remote.OrderDetailDto
import com.lebarock.lebarock_desktop.data.remote.OrdersResponse
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.ui.check.CheckActions
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.idle.IdleMonitor
import com.lebarock.lebarock_desktop.ui.payment.PaymentFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The status chips (client, 2026-11-08: today's orders, as on the handheld), with the caisse's French names. */
enum class OrderFilter(val api: String?, val label: String) {
    ALL(null, "Toutes"),
    RUNNING("kot", "En cours"),
    BILLED("billed", "Facturées"),
    PAID("paid", "Payées"),
    CREDIT("payment_due", "À crédit"),
    CANCELED("canceled", "Annulées"),
    /** D5: what is still to pay, whatever its day — billed checks and credits (web « Paiements dus »). Encaisser only. */
    TO_PAY(null, "À encaisser"),
}

fun statusLabel(status: String): String = when (status) {
    "kot" -> "En cours"
    "billed" -> "Facturée"
    "paid" -> "Payée"
    "payment_due" -> "À crédit"
    "canceled" -> "Annulée"
    "delivered" -> "Livrée"
    else -> status
}

/** A kitchen round's state as the kitchen screen names it (C1). */
fun kotLabel(status: String?): String = when (status) {
    "pending_confirmation" -> "Nouveau"
    "in_kitchen" -> "En préparation"
    "food_ready" -> "Prêt"
    "served" -> "Servi"
    "cancelled" -> "Annulé"
    else -> status ?: ""
}

/**
 * Commandes (D3): today's orders with the status chips, and the selected order's detail with what
 * the counter does to it: « Nouveau ticket », the bill (with or without its ticket), a reprint, a
 * round waiting for stock sent again (or forced) and « Servi ». Since D4 the check's other actions
 * ([check]): table, merge, split, waiter, customer, charges, payment fix, « Vérifié », a line taken
 * off and « Annuler la commande » (kept as « Annulée » with its reason; money taken goes back first).
 */
class OrdersModel(
    private val orders: OrdersRepository,
    private val sessions: SessionHolder,
    idle: IdleMonitor,
    initialMessage: String?,
    private val onNewRound: (Long) -> Unit,
    /** D5: opened from Accueil « Encaisser »: the checks to pay first (Facturées). */
    initialFilter: OrderFilter = OrderFilter.ALL,
) : ScreenModel() {

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    var filter by mutableStateOf(initialFilter)
        private set
    var list by mutableStateOf<UiState<OrdersResponse>>(UiState.Loading)
        private set
    var selectedId by mutableStateOf<Long?>(null)
        private set
    var detail by mutableStateOf<UiState<OrderDetailDto>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var servingKot by mutableStateOf<Long?>(null)
        private set
    var printProblem by mutableStateOf<String?>(null)
        private set
    var noCaisse by mutableStateOf<String?>(null)
        private set

    val rights get() = sessions.current?.rights
    val currency get() = orders.currency

    /**
     * D4: what the web order panel does besides sending and billing (table, merge, split, waiter,
     * customer, charges, payment fix, « Vérifié », cancel, a line taken off). A merge answers the
     * other table's check: Commandes follows it.
     */
    val check = CheckActions(
        orders, sessions, scope,
        onChanged = { change ->
            selectedId = change.order.id
            detail = UiState.Success(change.order)
            messageChannel.trySend(change.message)
            refreshList()
        },
        onClosed = { message, orderId ->
            messageChannel.trySend(message)
            if (orderId == null) {
                selectedId = null
                detail = null
            }
            refresh()
        },
    )

    /** D5: « Encaisser » (web: on a billed check, or one on credit), the web's payment window. */
    val payment = PaymentFlow(orders, idle, scope) { _, _, message ->
        message?.let { messageChannel.trySend(it) }
        refresh()
    }

    /** The web shows « Encaisser » on a billed check and on one put on credit (paid later). */
    fun canPay(order: OrderDetailDto): Boolean =
        rights?.takePayment == true && (order.status == "billed" || order.status == "payment_due")

    fun pay() {
        val order = current ?: return
        if (busy || !canPay(order)) return
        payment.open(order.id, order.orderNumber)
    }

    override fun onDispose() {
        payment.dispose()
    }

    init {
        initialMessage?.let { messageChannel.trySend(it) }
        refresh()
    }

    fun refresh() {
        refreshList()
        selectedId?.let { loadDetail(it, quiet = true) }
    }

    private fun refreshList() {
        val keep = list is UiState.Success
        if (!keep) list = UiState.Loading
        scope.launch {
            orders.orders(filter.api, toPay = filter == OrderFilter.TO_PAY)
                .onSuccess { list = UiState.Success(it) }
                .onFailure { if (!keep) list = UiState.Error(it.message ?: "Liste indisponible.", (it as? ApiFailure)?.hint) else messageChannel.trySend(it.message ?: "Liste indisponible.") }
        }
    }

    fun chooseFilter(f: OrderFilter) {
        if (f == filter) return
        filter = f
        list = UiState.Loading
        refresh()
    }

    fun select(id: Long) {
        selectedId = id
        loadDetail(id, quiet = false)
    }

    private fun loadDetail(id: Long, quiet: Boolean) {
        if (!quiet) detail = UiState.Loading
        scope.launch {
            orders.orderDetail(id)
                .onSuccess { if (selectedId == id) detail = UiState.Success(it) }
                .onFailure {
                    if (selectedId != id) return@onFailure
                    // Read again after a change and gone (its last line taken off): nothing selected any more.
                    if (quiet && (it as? ApiFailure)?.httpCode == 404) {
                        selectedId = null
                        detail = null
                    } else {
                        detail = UiState.Error(it.message ?: "Commande introuvable.", (it as? ApiFailure)?.hint)
                    }
                }
        }
    }

    val current: OrderDetailDto? get() = (detail as? UiState.Success)?.data

    // ---- Actions ----

    fun newRound() {
        current?.let { onNewRound(it.id) }
    }

    fun bill(print: Boolean) {
        val order = current ?: return
        perform { orders.bill(order.id, print).map { billed -> afterBill(order, billed.print) } }
    }

    private fun afterBill(order: OrderDetailDto, print: com.lebarock.lebarock_desktop.data.PrintOutcome?) {
        when {
            print == null -> messageChannel.trySend("Commande #${order.orderNumber} facturée.")
            print.ok -> messageChannel.trySend("Commande #${order.orderNumber} facturée. ${print.message}")
            else -> printProblem = print.message
        }
    }

    fun reprint() {
        val order = current ?: return
        if (busy) return
        busy = true
        scope.launch {
            orders.reprint(order.id)
                .onSuccess { messageChannel.trySend(it) }
                .onFailure { e -> if ((e as? ApiFailure)?.noCaisse == true) noCaisse = e.message else printProblem = e.message ?: "Le ticket n'a pas été imprimé." }
            busy = false
        }
    }

    fun sendWaiting(force: Boolean) {
        val order = current ?: return
        perform {
            orders.sendWaiting(order.id, force).map {
                messageChannel.trySend(if (force) "Envoi forcé : commande envoyée en cuisine." else "Commande envoyée en cuisine.")
                Unit
            }
        }
    }

    fun served(kotId: Long) {
        if (servingKot != null) return
        servingKot = kotId
        scope.launch {
            orders.served(kotId)
                .onSuccess { messageChannel.trySend("Ticket marqué servi."); refresh() }
                .onFailure { messageChannel.trySend(it.message ?: "Impossible de marquer le ticket servi.") }
            servingKot = null
        }
    }

    fun closePrintProblem() {
        printProblem = null
    }

    fun closeNoCaisse() {
        noCaisse = null
    }

    /** One action at a time; the list and the order are read again after it. */
    private fun perform(block: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        scope.launch {
            block()
                .onFailure { e ->
                    val f = e as? ApiFailure
                    when {
                        f?.noCaisse == true -> noCaisse = f.message
                        else -> messageChannel.trySend(e.message ?: "Action impossible.")
                    }
                }
            busy = false
            refresh()
        }
    }
}
