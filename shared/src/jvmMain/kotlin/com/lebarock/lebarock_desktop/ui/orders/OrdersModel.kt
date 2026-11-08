package com.lebarock.lebarock_desktop.ui.orders

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.remote.CancelReasonDto
import com.lebarock.lebarock_desktop.data.remote.OrderDetailDto
import com.lebarock.lebarock_desktop.data.remote.OrdersResponse
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import com.lebarock.lebarock_desktop.ui.common.UiState
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

/** « Annuler la commande »: the reason picked from the list and / or typed, as on the web. */
data class CancelForm(
    val reasons: UiState<List<CancelReasonDto>> = UiState.Loading,
    val reasonId: Long? = null,
    val text: String = "",
    val error: String? = null,
)

/**
 * Commandes (D3): today's orders with the status chips, and the selected order's detail with what
 * the counter does to it: « Nouveau ticket », the bill (with or without its ticket), a reprint, a
 * round waiting for stock sent again (or forced), a waiting line taken off, « Servi », and
 * « Annuler la commande » (kept as « Annulée » with its reason; money taken goes back first).
 */
class OrdersModel(
    private val orders: OrdersRepository,
    private val sessions: SessionHolder,
    initialMessage: String?,
    private val onNewRound: (Long) -> Unit,
) : ScreenModel() {

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    var filter by mutableStateOf(OrderFilter.ALL)
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
    var cancelForm by mutableStateOf<CancelForm?>(null)
        private set
    var printProblem by mutableStateOf<String?>(null)
        private set
    var noCaisse by mutableStateOf<String?>(null)
        private set

    val rights get() = sessions.current?.rights
    val currency get() = orders.currency

    init {
        initialMessage?.let { messageChannel.trySend(it) }
        refresh()
    }

    fun refresh() {
        val keep = list is UiState.Success
        if (!keep) list = UiState.Loading
        scope.launch {
            orders.orders(filter.api)
                .onSuccess { list = UiState.Success(it) }
                .onFailure { if (!keep) list = UiState.Error(it.message ?: "Liste indisponible.", (it as? ApiFailure)?.hint) else messageChannel.trySend(it.message ?: "Liste indisponible.") }
        }
        selectedId?.let { loadDetail(it, quiet = true) }
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
                .onFailure { if (selectedId == id) detail = UiState.Error(it.message ?: "Commande introuvable.", (it as? ApiFailure)?.hint) }
        }
    }

    private val current: OrderDetailDto? get() = (detail as? UiState.Success)?.data

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

    fun removeLine(itemId: Long) {
        val order = current ?: return
        if (busy) return
        busy = true
        scope.launch {
            orders.removeItem(order.id, itemId)
                .onSuccess { stillOpen ->
                    if (stillOpen) {
                        messageChannel.trySend("Article retiré de la commande.")
                    } else {
                        messageChannel.trySend("Plus aucun article : la commande est supprimée.")
                        selectedId = null
                        detail = null
                    }
                    refresh()
                }
                .onFailure { messageChannel.trySend(it.message ?: "Action impossible.") }
            busy = false
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

    // ---- Annuler la commande ----

    fun openCancel() {
        cancelForm = CancelForm()
        scope.launch {
            val reasons = orders.cancelReasons().fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Motifs indisponibles.", (it as? ApiFailure)?.hint) },
            )
            cancelForm = cancelForm?.copy(reasons = reasons)
        }
    }

    fun closeCancel() {
        if (!busy) cancelForm = null
    }

    fun cancelReason(id: Long?) {
        cancelForm = cancelForm?.copy(reasonId = id, error = null)
    }

    fun cancelText(text: String) {
        cancelForm = cancelForm?.copy(text = text, error = null)
    }

    /** As the web asks: a reason from the list, or a comment saying why. */
    fun confirmCancel() {
        val form = cancelForm ?: return
        val order = current ?: return
        if (form.reasonId == null && form.text.isBlank()) {
            cancelForm = form.copy(error = "Choisissez un motif d'annulation, ou écrivez pourquoi.")
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            orders.cancel(order.id, form.reasonId, form.text)
                .onSuccess {
                    cancelForm = null
                    messageChannel.trySend("Commande #${order.orderNumber} annulée.")
                    refresh()
                }
                .onFailure { cancelForm = form.copy(error = it.message ?: "Annulation impossible.") }
            busy = false
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
