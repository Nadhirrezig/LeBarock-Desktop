package com.lebarock.lebarock_desktop.ui.offline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.offline.OfflineOrders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The manager's payment PIN for the check's changes offline (O4b, client 2026-11-11): an action refused with
 * [OfflineOrders.PAYMENT_PIN_REQUIRED] opens the PIN window; the right PIN opens the check for 5 minutes and the action
 * is done again. Used by the check's actions (POS, Commandes) and the POS cart.
 */
class ManagerPin(private val orders: OrdersRepository, private val scope: CoroutineScope) {
    var entry by mutableStateOf<PinEntry?>(null)
        private set

    private var orderId = OfflineOrders.NEW_ORDER
    private var retry: (() -> Unit)? = null

    /** True when [e] asks the PIN: the window opens for [orderId] and [again] runs once it is right. */
    fun asks(e: Throwable, orderId: Long, again: () -> Unit): Boolean {
        if ((e as? ApiFailure)?.code != OfflineOrders.PAYMENT_PIN_REQUIRED) return false
        this.orderId = orderId
        retry = again
        entry = PinEntry()
        return true
    }

    fun edit(next: PinEntry) {
        if (entry?.busy != true) entry = next
    }

    fun dismiss() {
        if (entry?.busy == true) return
        entry = null
        retry = null
    }

    fun submit() {
        val e = entry ?: return
        if (e.busy) return
        entry = e.copy(busy = true, error = null)
        scope.launch {
            orders.unlockOfflinePayment(orderId, e.pin)
                .onSuccess {
                    entry = null
                    val again = retry
                    retry = null
                    again?.invoke()
                }
                .onFailure { x -> entry = e.copy(pin = "", busy = false, error = x.message ?: "Code PIN incorrect.") }
        }
    }
}

/** The PIN window of [pin], when it is open. */
@Composable
fun ManagerPinWindow(pin: ManagerPin) {
    val entry = pin.entry ?: return
    PinWindow(
        title = "Code du gérant",
        subtitle = "Hors ligne : remise, annulation, serveur, frais et article retiré demandent le code de paiement du gérant (valable 5 min sur cette commande).",
        entry = entry,
        onChange = pin::edit,
        onSubmit = pin::submit,
        onDismiss = pin::dismiss,
    )
}
