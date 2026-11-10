package com.lebarock.lebarock_desktop.data.session

import com.lebarock.lebarock_desktop.data.remote.AbilitiesDto
import com.lebarock.lebarock_desktop.data.remote.TerminalDto
import com.lebarock.lebarock_desktop.data.remote.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the signed-in account may do, read from the caisse's `abilities` (MobileAuthController).
 * The caisse refuses any action without its right anyway: these only decide what is shown.
 */
data class Rights(
    val kitchen: Boolean = false,
    val orders: Boolean = false,
    val showOrders: Boolean = false,
    val takePayment: Boolean = false,
    val cancelOrder: Boolean = false,
    val forceSend: Boolean = false,
    val cashRegister: Boolean = false,
    val useCashRegister: Boolean = false,
    val applyDiscount: Boolean = false,
    val giveCredit: Boolean = false,
    val assignWaiter: Boolean = false,
    val createExpense: Boolean = false,
    val manageSettings: Boolean = false,
    /** « Mettre à jour la commande »: the check's operations (table, merge, split, customer, charges, Vérifié). */
    val updateOrder: Boolean = false,
) {
    /** A kitchen account: the kitchen screen is the 22" screen's app, not the counter's. */
    val kitchenOnly: Boolean get() = kitchen && !orders

    companion object {
        /**
         * A caisse older than step 4 does not send the counter's rights: each falls back on the
         * nearest older one (the cash register and discounts on "Encaisser"), as the handheld kept
         * the old behaviour for a caisse older than its rights.
         */
        fun of(a: AbilitiesDto?): Rights {
            if (a == null) return Rights(orders = true, showOrders = true, takePayment = true, cancelOrder = true, updateOrder = true)
            return Rights(
                kitchen = a.kitchen,
                orders = a.orders,
                showOrders = a.showOrders ?: a.orders,
                takePayment = a.takePayment,
                cancelOrder = a.cancelOrder,
                forceSend = a.forceSend,
                cashRegister = a.cashRegister ?: a.takePayment,
                useCashRegister = a.useCashRegister ?: a.takePayment,
                applyDiscount = a.applyDiscount ?: a.takePayment,
                giveCredit = a.giveCredit ?: false,
                assignWaiter = a.assignWaiter ?: false,
                createExpense = a.createExpense ?: false,
                manageSettings = a.manageSettings ?: false,
                updateOrder = a.updateOrder ?: a.orders,
            )
        }
    }
}

/** A signed-in account on this PC: its token (memory only, never written to disk) and what the caisse said about it. */
data class Session(val token: String, val user: UserDto) {
    val rights: Rights get() = Rights.of(user.abilities)

    /** The account's Caisse (menu, drawer, session, stock till); null = none, the caisse refuses orders and money. */
    val caisse: TerminalDto? get() = user.terminal

    /** Admin / Gérant: may pick another Caisse (the caisse's terminal switch). */
    val canSwitchCaisse: Boolean get() = user.canSwitchTerminal

    val roleLabel: String get() = user.roles.joinToString(", ")
}

/**
 * The current session. Kept in memory only: each start of the app, a lock after inactivity or
 * "Changer d'utilisateur" goes back to the sign-in screen (shared counter).
 */
class SessionHolder {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    val current: Session? get() = _session.value
    val token: String? get() = _session.value?.token

    fun start(session: Session) {
        _session.value = session
    }

    fun updateUser(user: UserDto) {
        _session.value = _session.value?.copy(user = user)
    }

    fun clear() {
        _session.value = null
    }
}
