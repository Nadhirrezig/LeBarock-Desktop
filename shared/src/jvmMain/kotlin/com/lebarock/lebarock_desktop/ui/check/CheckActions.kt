package com.lebarock.lebarock_desktop.ui.check

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CheckChange
import com.lebarock.lebarock_desktop.data.LineRemoval
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.remote.AreaDto
import com.lebarock.lebarock_desktop.data.remote.CancelReasonDto
import com.lebarock.lebarock_desktop.data.remote.CustomerDto
import com.lebarock.lebarock_desktop.data.remote.CustomerRequest
import com.lebarock.lebarock_desktop.data.remote.OrderDetailDto
import com.lebarock.lebarock_desktop.data.remote.OrderItemDto
import com.lebarock.lebarock_desktop.data.remote.PaymentLineDto
import com.lebarock.lebarock_desktop.data.remote.TableDto
import com.lebarock.lebarock_desktop.data.remote.WaiterDto
import com.lebarock.lebarock_desktop.data.session.Rights
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.ui.common.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the counter can do to a check besides sending and billing it (D4, the web order panel). */
enum class CheckAction(val label: String) {
    MOVE("Changer de table"),
    MERGE("Fusionner"),
    SPLIT("Séparer"),
    WAITER("Changer de serveur"),
    CUSTOMER("Client"),
    CHARGES("Retirer des frais"),
    REVIEWED("Vérifié"),
    CANCEL("Annuler la commande"),
    ;

    companion object {
        /**
         * The actions [order] offers to an account with [rights], as the web shows them: table moves,
         * merge and split on a running check at a table (« Mettre à jour la commande »), the waiter with
         * « Choisir le serveur », the customer while the check is not cancelled, the extra charges while
         * it is open, « Vérifié » on an offline check, the cancel with « Annuler une commande ».
         */
        fun available(order: OrderDetailDto, rights: Rights?): List<CheckAction> {
            if (rights == null) return emptyList()
            val open = order.status == "kot" || order.status == "billed"
            val atTable = order.orderType == "dine_in" && order.table != null
            // The caisse's routes need « Mettre à jour la commande » itself, not just « Créer une commande ».
            val updates = rights.updateOrder
            return buildList {
                if (updates && open && order.orderType == "dine_in") add(MOVE)
                if (updates && open && atTable) add(MERGE)
                if (updates && open && atTable && order.items.sumOf { it.quantity } > 1) add(SPLIT)
                if (rights.assignWaiter && open) add(WAITER)
                if (updates && order.status != "canceled") add(CUSTOMER)
                if (updates && order.charges.any { it.removable }) add(CHARGES)
                if (updates && order.needsReview) add(REVIEWED)
                if (rights.cancelOrder && order.status != "canceled") add(CANCEL)
            }
        }

        /** A line the account may take off: waiting for stock (orders), or sent to no kitchen (cancel right). */
        fun canRemove(order: OrderDetailDto, line: OrderItemDto, rights: Rights?): Boolean {
            if (rights == null || order.status == "canceled" || order.status == "paid" || order.status == "payment_due") return false
            return (line.waitingForStock && rights.orders) || (line.sentNotFired && rights.cancelOrder)
        }
    }
}

/** « Annuler la commande »: the reason picked from the list and / or typed, as on the web. */
data class CancelForm(
    val reasons: UiState<List<CancelReasonDto>> = UiState.Loading,
    val reasonId: Long? = null,
    val text: String = "",
    val error: String? = null,
)

/** The web's customer form: search the restaurant's customers, or type a new one. */
data class CustomerForm(
    val search: String = "",
    val results: UiState<List<CustomerDto>>? = null,
    val pickedId: Long? = null,
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val error: String? = null,
)

enum class TableMode { MOVE, MERGE }

/** The window open on the check. */
sealed interface CheckDialog {
    val order: OrderDetailDto
    val error: String?

    data class Tables(override val order: OrderDetailDto, val mode: TableMode, val areas: UiState<List<AreaDto>> = UiState.Loading, override val error: String? = null) : CheckDialog
    data class Split(override val order: OrderDetailDto, val quantities: Map<Long, Int> = emptyMap(), override val error: String? = null) : CheckDialog
    data class Waiter(override val order: OrderDetailDto, val waiters: UiState<List<WaiterDto>> = UiState.Loading, val selected: Long? = null, override val error: String? = null) : CheckDialog
    data class Customer(override val order: OrderDetailDto, val form: CustomerForm, override val error: String? = null) : CheckDialog
    data class Charges(override val order: OrderDetailDto, override val error: String? = null) : CheckDialog
    data class PaymentFix(override val order: OrderDetailDto, val payment: PaymentLineDto, override val error: String? = null) : CheckDialog
    data class Cancel(override val order: OrderDetailDto, val form: CancelForm = CancelForm(), override val error: String? = null) : CheckDialog
    /** [form]: offline (O4b) the line's reason is asked, from the cancel reasons or typed; null online. */
    data class RemoveLine(override val order: OrderDetailDto, val line: OrderItemDto, val form: CancelForm? = null, override val error: String? = null) : CheckDialog
}

/** The table a check can move to: free, or opened by staff with nothing on it yet (not merged into another). */
fun canMoveTo(t: TableDto, from: Long?): Boolean = t.id != from && t.activeOrder == null && t.openOrders == 0 && t.session?.mergedInto == null

/** A table whose party a check can join: one with a party of its own (web merge targets). */
fun canMergeInto(t: TableDto, from: Long?): Boolean = t.id != from && t.session != null && t.session.mergedInto == null

/**
 * The check's actions and their windows, shared by the POS (a table's check) and Commandes (D4).
 * [onChanged]: the check as the caisse has it now, with its French message (it may be another check:
 * a merge answers the table's own). [onClosed]: the check is gone (cancelled, or emptied).
 */
class CheckActions(
    private val orders: OrdersRepository,
    private val sessions: SessionHolder,
    private val scope: CoroutineScope,
    private val onChanged: (CheckChange) -> Unit,
    /** The check is done with: [orderId] still exists (cancelled, kept « Annulée ») or null when it is gone. */
    private val onClosed: (String, Long?) -> Unit,
) {
    var dialog by mutableStateOf<CheckDialog?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    val rights: Rights? get() = sessions.current?.rights

    /** O4b: the manager's payment PIN, asked offline before the check's changes. */
    val pin = com.lebarock.lebarock_desktop.ui.offline.ManagerPin(orders, scope)

    fun actions(order: OrderDetailDto): List<CheckAction> = CheckAction.available(order, rights)

    fun open(action: CheckAction, order: OrderDetailDto) {
        when (action) {
            CheckAction.MOVE -> openTables(order, TableMode.MOVE)
            CheckAction.MERGE -> openTables(order, TableMode.MERGE)
            CheckAction.SPLIT -> dialog = CheckDialog.Split(order)
            CheckAction.WAITER -> openWaiter(order)
            CheckAction.CUSTOMER -> dialog = CheckDialog.Customer(order, customerFormOf(order))
            CheckAction.CHARGES -> dialog = CheckDialog.Charges(order)
            CheckAction.REVIEWED -> run(order) { orders.markReviewed(order.id) }
            CheckAction.CANCEL -> openCancel(order)
        }
    }

    fun close() {
        if (!busy) dialog = null
    }

    // ---- Tables: move / merge ----

    private fun openTables(order: OrderDetailDto, mode: TableMode) {
        dialog = CheckDialog.Tables(order, mode)
        scope.launch {
            val areas = orders.tables().fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Tables indisponibles.", (it as? ApiFailure)?.hint) },
            )
            (dialog as? CheckDialog.Tables)?.let { dialog = it.copy(areas = areas) }
        }
    }

    fun pickTable(t: TableDto) {
        val d = dialog as? CheckDialog.Tables ?: return
        val from = d.order.table?.id
        when (d.mode) {
            TableMode.MOVE -> if (canMoveTo(t, from)) run(d.order) { orders.moveOrder(d.order.id, t.id) }
            TableMode.MERGE -> if (canMergeInto(t, from)) run(d.order) { orders.mergeOrder(d.order.id, t.id) }
        }
    }

    // ---- Split by item ----

    fun splitStep(line: OrderItemDto, delta: Int) {
        val d = dialog as? CheckDialog.Split ?: return
        val now = (d.quantities[line.id] ?: 0) + delta
        dialog = d.copy(quantities = d.quantities + (line.id to now.coerceIn(0, line.quantity)), error = null)
    }

    fun confirmSplit() {
        val d = dialog as? CheckDialog.Split ?: return
        val moves = d.quantities.filterValues { it > 0 }
        if (moves.isEmpty()) {
            dialog = d.copy(error = "Choisissez au moins un article à séparer.")
            return
        }
        run(d.order) { orders.splitOrder(d.order.id, moves) }
    }

    // ---- Waiter ----

    private fun openWaiter(order: OrderDetailDto) {
        dialog = CheckDialog.Waiter(order, selected = order.waiter?.id)
        scope.launch {
            val waiters = orders.posContext().fold(
                onSuccess = { UiState.Success(it.waiters) },
                onFailure = { UiState.Error(it.message ?: "Serveurs indisponibles.", (it as? ApiFailure)?.hint) },
            )
            (dialog as? CheckDialog.Waiter)?.let { dialog = it.copy(waiters = waiters) }
        }
    }

    fun chooseWaiter(id: Long) {
        val d = dialog as? CheckDialog.Waiter ?: return
        dialog = d.copy(selected = id, error = null)
    }

    fun confirmWaiter() {
        val d = dialog as? CheckDialog.Waiter ?: return
        val id = d.selected ?: return
        if (id == d.order.waiter?.id) {
            dialog = null
            return
        }
        run(d.order) { orders.setWaiter(d.order.id, id) }
    }

    // ---- Customer (the web's form) ----

    private fun customerFormOf(order: OrderDetailDto): CustomerForm {
        val c = order.customerInfo ?: return CustomerForm()
        return CustomerForm(pickedId = c.id, name = c.name, phone = c.phone.orEmpty(), email = c.email.orEmpty())
    }

    private var searchJob: Job? = null

    fun customerSearch(text: String) {
        val d = dialog as? CheckDialog.Customer ?: return
        dialog = d.copy(form = d.form.copy(search = text, results = if (text.trim().length < 2) null else d.form.results), error = null)
        searchJob?.cancel()
        if (text.trim().length < 2) return
        searchJob = scope.launch {
            delay(250)
            val results = orders.customers(text).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Recherche impossible.", (it as? ApiFailure)?.hint) },
            )
            (dialog as? CheckDialog.Customer)?.let { cur -> if (cur.form.search == text) dialog = cur.copy(form = cur.form.copy(results = results)) }
        }
    }

    fun pickCustomer(c: CustomerDto) {
        val d = dialog as? CheckDialog.Customer ?: return
        dialog = d.copy(form = d.form.copy(pickedId = c.id, name = c.name, phone = c.phone.orEmpty(), email = c.email.orEmpty(), search = "", results = null), error = null)
    }

    // Typing over a picked customer makes it someone else: the pick is dropped (the caisse then finds the
    // customer by e-mail or phone, or makes a new one — it never renames the one picked).
    fun customerName(text: String) = editCustomer { it.copy(name = text, pickedId = null) }
    fun customerPhone(text: String) = editCustomer { it.copy(phone = text, pickedId = null) }
    fun customerEmail(text: String) = editCustomer { it.copy(email = text, pickedId = null) }

    private fun editCustomer(change: (CustomerForm) -> CustomerForm) {
        val d = dialog as? CheckDialog.Customer ?: return
        dialog = d.copy(form = change(d.form), error = null)
    }

    fun saveCustomer() {
        val d = dialog as? CheckDialog.Customer ?: return
        val f = d.form
        if (f.name.isBlank()) {
            dialog = d.copy(error = "Le nom du client est obligatoire.")
            return
        }
        run(d.order) {
            orders.setCustomer(d.order.id, CustomerRequest(f.pickedId, f.name.trim(), f.phone.trim().ifEmpty { null }, f.email.trim().ifEmpty { null }))
        }
    }

    // ---- Extra charges ----

    fun removeCharge(chargeId: Long) {
        val d = dialog as? CheckDialog.Charges ?: return
        run(d.order, keepOpen = { it.order.charges.any { c -> c.removable } }) { orders.removeCharge(d.order.id, chargeId) }
    }

    // ---- Payment cash ⇄ card ----

    fun openPaymentFix(order: OrderDetailDto, payment: PaymentLineDto) {
        dialog = CheckDialog.PaymentFix(order, payment)
    }

    fun confirmPaymentFix() {
        val d = dialog as? CheckDialog.PaymentFix ?: return
        val to = if (d.payment.method == "cash") "card" else "cash"
        run(d.order) { orders.changePaymentMethod(d.order.id, d.payment.id, to) }
    }

    // ---- A line taken off ----

    fun askRemoveLine(order: OrderDetailDto, line: OrderItemDto) {
        dialog = CheckDialog.RemoveLine(order, line)
        // Offline, the line's reason is asked (client, 2026-11-11), from the copy's cancel reasons.
        scope.launch {
            if (!orders.offlineActive()) return@launch
            val reasons = orders.cancelReasons().fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Motifs indisponibles.", (it as? ApiFailure)?.hint) },
            )
            (dialog as? CheckDialog.RemoveLine)?.let { if (it.line.id == line.id) dialog = it.copy(form = CancelForm(reasons = reasons)) }
        }
    }

    fun removeReason(id: Long?) {
        val d = dialog as? CheckDialog.RemoveLine ?: return
        dialog = d.copy(form = d.form?.copy(reasonId = id), error = null)
    }

    fun removeText(text: String) {
        val d = dialog as? CheckDialog.RemoveLine ?: return
        dialog = d.copy(form = d.form?.copy(text = text), error = null)
    }

    fun confirmRemoveLine() {
        val d = dialog as? CheckDialog.RemoveLine ?: return
        if (d.form != null && d.form.reasonId == null && d.form.text.isBlank()) {
            dialog = d.copy(error = "Choisissez un motif, ou écrivez pourquoi l'article est retiré.")
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            orders.removeItem(d.order.id, d.line.id, d.form?.reasonId, d.form?.text)
                .onSuccess { result ->
                    dialog = null
                    when (result) {
                        LineRemoval.KEPT -> orders.orderDetail(d.order.id)
                            .onSuccess { onChanged(CheckChange(it, "« ${d.line.name} » retiré de la commande.")) }
                            .onFailure { onClosed("« ${d.line.name} » retiré de la commande.", null) }
                        LineRemoval.CANCELLED -> onClosed("Plus aucun article : commande #${d.order.orderNumber} annulée.", d.order.id)
                        LineRemoval.DELETED -> onClosed("Plus aucun article : la commande est supprimée.", null)
                    }
                }
                .onFailure { e -> if (!pin.asks(e, d.order.id, ::confirmRemoveLine)) dialog = d.copy(error = e.message ?: "Action impossible.") }
            busy = false
        }
    }

    // ---- Annuler la commande ----

    private fun openCancel(order: OrderDetailDto) {
        dialog = CheckDialog.Cancel(order)
        scope.launch {
            val reasons = orders.cancelReasons().fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Motifs indisponibles.", (it as? ApiFailure)?.hint) },
            )
            (dialog as? CheckDialog.Cancel)?.let { dialog = it.copy(form = it.form.copy(reasons = reasons)) }
        }
    }

    fun cancelReason(id: Long?) {
        val d = dialog as? CheckDialog.Cancel ?: return
        dialog = d.copy(form = d.form.copy(reasonId = id), error = null)
    }

    fun cancelText(text: String) {
        val d = dialog as? CheckDialog.Cancel ?: return
        dialog = d.copy(form = d.form.copy(text = text), error = null)
    }

    /** As the web asks: a reason from the list, or a comment saying why. */
    fun confirmCancel() {
        val d = dialog as? CheckDialog.Cancel ?: return
        if (d.form.reasonId == null && d.form.text.isBlank()) {
            dialog = d.copy(error = "Choisissez un motif d'annulation, ou écrivez pourquoi.")
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            orders.cancel(d.order.id, d.form.reasonId, d.form.text)
                .onSuccess {
                    dialog = null
                    onClosed("Commande #${d.order.orderNumber} annulée.", d.order.id)
                }
                .onFailure { e -> if (!pin.asks(e, d.order.id, ::confirmCancel)) dialog = d.copy(error = e.message ?: "Annulation impossible.") }
            busy = false
        }
    }

    /**
     * One change at a time: the window shows the caisse's refusal and stays; on success it closes
     * ([keepOpen]: unless there is more to do in it) and the host gets the check as it is now.
     */
    private fun run(order: OrderDetailDto, keepOpen: (CheckChange) -> Boolean = { false }, call: suspend () -> Result<CheckChange>) {
        if (busy) return
        busy = true
        scope.launch {
            call()
                .onSuccess { change ->
                    val d = dialog
                    dialog = if (d != null && keepOpen(change) && d is CheckDialog.Charges) d.copy(order = change.order, error = null) else null
                    onChanged(change)
                }
                .onFailure { e ->
                    // Offline (O4b): the manager's PIN first, then the same change again.
                    if (pin.asks(e, order.id) { run(order, keepOpen, call) }) {
                        busy = false
                        return@launch
                    }
                    val message = e.message ?: "Action impossible."
                    dialog = when (val d = dialog) {
                        is CheckDialog.Tables -> d.copy(error = message)
                        is CheckDialog.Split -> d.copy(error = message)
                        is CheckDialog.Waiter -> d.copy(error = message)
                        is CheckDialog.Customer -> d.copy(error = message)
                        is CheckDialog.Charges -> d.copy(error = message)
                        is CheckDialog.PaymentFix -> d.copy(error = message)
                        is CheckDialog.Cancel -> d.copy(error = message)
                        is CheckDialog.RemoveLine -> d.copy(error = message)
                        null -> null.also { onChanged(CheckChange(order, message)) }
                    }
                }
            busy = false
        }
    }
}
