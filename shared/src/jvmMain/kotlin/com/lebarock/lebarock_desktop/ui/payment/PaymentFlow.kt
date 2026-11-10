package com.lebarock.lebarock_desktop.ui.payment

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.Money
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.PrintOutcome
import com.lebarock.lebarock_desktop.data.remote.CustomerDto
import com.lebarock.lebarock_desktop.pos.CartMath.round2
import com.lebarock.lebarock_desktop.pos.CreditCustomer
import com.lebarock.lebarock_desktop.pos.PayMethod
import com.lebarock.lebarock_desktop.pos.PaymentForm
import com.lebarock.lebarock_desktop.pos.TipForm
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.idle.IdleMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The check is settled (paid, or put on credit): what the window says before it closes. */
data class PaymentDone(
    val status: String,
    val title: String,
    /** Cash above the due: the change to give back (shown large). */
    val change: Double,
    val detail: String?,
    /** The receipt printed by itself (client, 2026-11-10): where, or why not. */
    val print: PrintOutcome?,
)

/** The payment window open on one check. */
data class PaymentState(
    val orderId: Long,
    val orderNumber: String,
    val form: UiState<PaymentForm> = UiState.Loading,
    val busy: Boolean = false,
    /** The caisse's refusal, or what stops « Valider ». */
    val error: String? = null,
    /** A partial payment was recorded: the window stays on the check for the next guest (web). */
    val notice: String? = null,
    val tip: TipForm? = null,
    val tipError: String? = null,
    val customers: UiState<List<CustomerDto>>? = null,
    val done: PaymentDone? = null,
    val reprinting: Boolean = false,
    /** Offline (step 3): the manager's payment PIN asked before the payment goes; null when not asked. */
    val paymentPin: com.lebarock.lebarock_desktop.ui.offline.PinEntry? = null,
)

/**
 * « Encaisser » (D5): the web's payment window (`Order/AddPayment`) on a check, for the POS (« Encaisser Ticket »)
 * and Commandes (« Encaisser »). It reads the caisse's payment context, records through `/orders/{id}/payments`
 * (OrderPayments: cash with change, the TPE for the whole due, credit to a named customer, a split in one go),
 * sets the tip, and the receipt prints by itself once the check is settled. A partial payment keeps the window on
 * the check. The idle lock never closes the session while it is open (client, 2026-11-08).
 *
 * [onFinished]: the window closed on [orderId]; [settled] when the check was paid or put on credit, with the
 * message for the screen behind.
 */
class PaymentFlow(
    private val orders: OrdersRepository,
    private val idle: IdleMonitor,
    private val scope: CoroutineScope,
    private val onFinished: (orderId: Long, settled: Boolean, message: String?) -> Unit,
) {
    var state by mutableStateOf<PaymentState?>(null)
        private set

    private var hold: IdleMonitor.Hold? = null
    private var search: Job? = null

    /** Something was recorded while the window was open (a part paid, a tip): the screen behind reads the check again. */
    private var changed = false

    val currency get() = orders.currency

    fun open(orderId: Long, orderNumber: String) {
        if (hold == null) hold = idle.hold("encaissement en cours")
        changed = false
        state = PaymentState(orderId, orderNumber)
        load(notice = null)
    }

    private fun load(notice: String?) {
        val s = state ?: return
        scope.launch {
            val form = orders.paymentContext(s.orderId).fold(
                onSuccess = { ctx -> UiState.Success(PaymentForm(ctx)) },
                onFailure = { UiState.Error(it.message ?: "Paiement indisponible.", (it as? ApiFailure)?.hint) },
            )
            state = state?.takeIf { it.orderId == s.orderId }?.copy(
                form = form, busy = false, notice = notice,
                orderNumber = (form as? UiState.Success)?.data?.context?.orderNumber?.ifBlank { null } ?: s.orderNumber,
            )
        }
    }

    fun retry() {
        state = state?.copy(form = UiState.Loading, error = null)
        load(state?.notice)
    }

    /** « Fermer » (or Échap): only when nothing is on its way. */
    fun close() {
        val s = state ?: return
        if (s.busy) return
        val done = s.done
        state = null
        search?.cancel()
        hold?.release()
        hold = null
        when {
            done != null -> onFinished(s.orderId, true, listOfNotNull(done.title, done.detail).joinToString(" "))
            else -> onFinished(s.orderId, false, if (changed) s.notice else null)
        }
    }

    fun dispose() {
        search?.cancel()
        hold?.release()
        hold = null
    }

    private fun edit(block: (PaymentForm) -> PaymentForm) {
        val s = state ?: return
        val form = (s.form as? UiState.Success)?.data ?: return
        if (s.busy) return
        state = s.copy(form = UiState.Success(block(form)), error = null)
    }

    // ---- The form (each a web action) ----

    fun choose(m: PayMethod) = edit { it.choose(m) }
    fun key(k: Char) = edit { it.key(k) }
    fun clearAmount() = edit { it.clearAmount() }
    fun backspace() = edit { it.backspace() }
    fun exact() = edit { it.exact() }
    fun quick(v: Double) = edit { it.quick(v) }
    fun typed(text: String) = edit { it.typed(text) }
    fun splitMode(on: Boolean) = edit { it.splitMode(on) }
    fun splitType(t: com.lebarock.lebarock_desktop.pos.SplitType?) = edit { it.splitType(t) }
    /** The split's parts (add, remove, method, amount, articles): each a [PaymentForm] step. */
    fun update(change: (PaymentForm) -> PaymentForm) = edit(change)

    // ---- À crédit: the customer ----

    fun creditSearch(text: String) {
        edit { it.creditChanged(it.credit.copy(search = text)) }
        search?.cancel()
        if (text.trim().length < 2) {
            state = state?.copy(customers = null)
            return
        }
        search = scope.launch {
            delay(300)
            val found = orders.customers(text).fold(
                onSuccess = { UiState.Success(it.take(5)) },
                onFailure = { UiState.Error(it.message ?: "Recherche impossible.", null) },
            )
            state = state?.copy(customers = found)
        }
    }

    fun pickCustomer(c: CustomerDto) {
        search?.cancel()
        edit { it.creditChanged(CreditCustomer(pickedId = c.id, name = c.name, phone = c.phone.orEmpty())) }
        state = state?.copy(customers = null)
    }

    /** « Changer »: back to the search and the new-customer fields. */
    fun clearCustomer() = edit { it.creditChanged(CreditCustomer()) }

    fun creditName(text: String) = edit { it.creditChanged(it.credit.copy(pickedId = null, name = text)) }
    fun creditPhone(text: String) = edit { it.creditChanged(it.credit.copy(pickedId = null, phone = text)) }

    // ---- « Valider le paiement » / « Mettre à crédit » ----

    fun submit() {
        val s = state ?: return
        val form = (s.form as? UiState.Success)?.data ?: return
        if (s.busy) return
        form.problem?.let {
            state = s.copy(error = it)
            return
        }
        state = s.copy(busy = true, error = null)
        scope.launch {
            orders.pay(s.orderId, form.request())
                .onSuccess { result ->
                    val paid = result.value
                    changed = true
                    when (paid.status) {
                        "paid" -> settled(PaymentDone(
                            status = paid.status,
                            title = "Ticket réglé.",
                            change = paid.change,
                            detail = if (paid.change > 0) "Monnaie à rendre : ${Money.format(paid.change, currency)}" else null,
                            print = result.print,
                        ))
                        "payment_due" -> settled(PaymentDone(
                            status = paid.status,
                            title = "Mis à crédit.",
                            change = 0.0,
                            detail = "${form.credit.name.ifBlank { "Le client" }} : ${Money.format(paid.due, currency)}",
                            print = result.print,
                        ))
                        // Partly paid: the check stays open and the window on it, for the next guest (web).
                        else -> {
                            val change = if (paid.change > 0) " Monnaie à rendre : ${Money.format(paid.change, currency)}." else ""
                            load("Paiement enregistré. Reste à payer : ${Money.format(paid.due, currency)}.$change")
                        }
                    }
                }
                .onFailure { e ->
                    // Offline: the payment PIN first, then the same payment again.
                    if ((e as? ApiFailure)?.code == com.lebarock.lebarock_desktop.data.offline.OfflineOrders.PAYMENT_PIN_REQUIRED) {
                        state = state?.copy(busy = false, paymentPin = com.lebarock.lebarock_desktop.ui.offline.PinEntry())
                    } else {
                        state = state?.copy(busy = false, error = e.message ?: "Paiement refusé.")
                    }
                }
        }
    }

    // ---- Offline: the payment PIN (Admin / Gérant), once per check for 5 minutes ----

    fun editPaymentPin(entry: com.lebarock.lebarock_desktop.ui.offline.PinEntry) {
        val s = state ?: return
        if (s.paymentPin?.busy != true) state = s.copy(paymentPin = entry)
    }

    fun dismissPaymentPin() {
        val s = state ?: return
        if (s.paymentPin?.busy != true) state = s.copy(paymentPin = null)
    }

    /** The PIN is checked on this PC; right, the waiting payment goes. */
    fun submitPaymentPin() {
        val s = state ?: return
        val entry = s.paymentPin ?: return
        if (entry.busy || entry.pin.length < 4) return
        state = s.copy(paymentPin = entry.copy(busy = true, error = null))
        scope.launch {
            orders.unlockOfflinePayment(s.orderId, entry.pin)
                .onSuccess {
                    state = state?.copy(paymentPin = null)
                    submit()
                }
                .onFailure { e -> state = state?.copy(paymentPin = entry.copy(pin = "", busy = false, error = e.message ?: "Code PIN incorrect.")) }
        }
    }

    private fun settled(done: PaymentDone) {
        state = state?.copy(busy = false, done = done, notice = null)
    }

    /** « Réimprimer »: the receipt again, where Réglages says. */
    fun reprint() {
        val s = state ?: return
        if (s.reprinting) return
        state = s.copy(reprinting = true)
        scope.launch {
            val outcome = orders.reprint(s.orderId).fold(
                onSuccess = { PrintOutcome(true, it) },
                onFailure = { PrintOutcome(false, it.message ?: "Le ticket n'a pas été imprimé.") },
            )
            // The receipt's banner says what the last try did.
            state = state?.let { it.copy(reprinting = false, done = it.done?.copy(print = outcome)) }
        }
    }

    // ---- « Pourboire » (the web's tip window) ----

    fun openTip() {
        val s = state ?: return
        val ctx = (s.form as? UiState.Success)?.data?.context ?: return
        if (!ctx.canAddTip || s.busy) return
        state = s.copy(
            tip = TipForm(
                baseTotal = round2(ctx.total - ctx.tipAmount),
                amountText = if (ctx.tipAmount > 0) PaymentForm.plainAmount(ctx.tipAmount) else "",
                note = ctx.tipNote.orEmpty(),
            ),
            tipError = null,
        )
    }

    fun editTip(block: (TipForm) -> TipForm) {
        val s = state ?: return
        val t = s.tip ?: return
        state = s.copy(tip = block(t), tipError = null)
    }

    fun closeTip() {
        if (state?.busy != true) state = state?.copy(tip = null, tipError = null)
    }

    fun saveTip() {
        val s = state ?: return
        val t = s.tip ?: return
        if (s.busy) return
        state = s.copy(busy = true, tipError = null)
        scope.launch {
            orders.tip(s.orderId, t.amount, t.note)
                .onSuccess {
                    changed = true
                    state = state?.copy(tip = null)
                    load(if (t.amount > 0) "Pourboire ajouté : ${Money.format(t.amount, currency)}." else "Pourboire retiré.")
                }
                .onFailure { e -> state = state?.copy(busy = false, tipError = e.message ?: "Pourboire refusé.") }
        }
    }
}
