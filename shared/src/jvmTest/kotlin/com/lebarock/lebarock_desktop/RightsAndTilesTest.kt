package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.remote.AbilitiesDto
import com.lebarock.lebarock_desktop.data.session.Rights
import com.lebarock.lebarock_desktop.ui.home.HomeTiles
import com.lebarock.lebarock_desktop.ui.home.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Accueil buttons follow the account's rights (client, 2026-11-08). */
class RightsAndTilesTest {

    private val waiter = AbilitiesDto(
        kitchen = false, orders = true, takePayment = false, cancelOrder = false,
        showOrders = true, cashRegister = false, useCashRegister = false, applyDiscount = false,
        giveCredit = false, assignWaiter = false, createExpense = false, manageSettings = false,
    )
    private val cashier = waiter.copy(takePayment = true, cashRegister = true, useCashRegister = true, applyDiscount = true, assignWaiter = true)
    private val admin = cashier.copy(kitchen = true, cancelOrder = true, giveCredit = true, createExpense = true, manageSettings = true)
    private val chef = AbilitiesDto(kitchen = true, orders = false, takePayment = false, cancelOrder = false, showOrders = true, cashRegister = false)

    @Test
    fun a_waiter_takes_orders_but_sees_no_money_buttons() {
        assertEquals(
            listOf(Section.POS, Section.TABLES, Section.ORDERS, Section.SETTINGS),
            HomeTiles.of(Rights.of(waiter)),
        )
    }

    @Test
    fun a_cashier_also_takes_payments_and_runs_the_cash_register() {
        assertEquals(
            listOf(Section.POS, Section.TABLES, Section.ORDERS, Section.PAYMENT, Section.CASH_REGISTER, Section.SETTINGS),
            HomeTiles.of(Rights.of(cashier)),
        )
    }

    @Test
    fun the_admin_sees_everything_even_with_the_kitchen_right() {
        val rights = Rights.of(admin)
        assertFalse(rights.kitchenOnly)
        assertEquals(6, HomeTiles.of(rights).size)
        assertTrue(rights.manageSettings && rights.giveCredit && rights.createExpense)
    }

    @Test
    fun a_kitchen_account_only_gets_the_settings() {
        val rights = Rights.of(chef)
        assertTrue(rights.kitchenOnly)
        assertEquals(listOf(Section.SETTINGS), HomeTiles.of(rights))
    }

    @Test
    fun a_caisse_older_than_step_4_falls_back_on_the_older_rights() {
        val old = AbilitiesDto(kitchen = false, orders = true, takePayment = true, cancelOrder = false)
        val rights = Rights.of(old)
        assertTrue(rights.showOrders)
        assertTrue(rights.cashRegister)
        assertTrue(rights.applyDiscount)
        assertFalse(rights.giveCredit)
        assertFalse(rights.manageSettings)
        // No abilities at all (before Plan C): a waiter-like account that may take money, as the handheld did.
        assertTrue(Rights.of(null).orders)
    }

    @Test
    fun d4_brings_the_tables() {
        val built = setOf(Section.SETTINGS, Section.POS, Section.ORDERS, Section.TABLES)
        Section.entries.forEach { assertEquals(it in built, it.available, it.name) }
        assertTrue(Section.POS.needsCaisse && Section.PAYMENT.needsCaisse && Section.CASH_REGISTER.needsCaisse)
        assertFalse(Section.ORDERS.needsCaisse, "reading the day's orders needs no Caisse")
    }
}
