package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.ui.idle.IdleMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The idle lock (client, 2026-11-08): 5 min by default, never while something would be lost. */
class IdleMonitorTest {

    private var now = 1_000_000L
    private val idle = IdleMonitor { now }

    @Test
    fun the_lock_comes_after_the_minutes_without_activity() {
        idle.touch()
        now += 4 * 60_000
        assertEquals(60_000L, idle.remainingMillis(5))
        now += 60_000
        assertEquals(0L, idle.remainingMillis(5))
    }

    @Test
    fun any_activity_starts_the_count_again() {
        idle.touch()
        now += 4 * 60_000
        idle.touch()
        now += 4 * 60_000
        assertEquals(60_000L, idle.remainingMillis(5))
    }

    @Test
    fun zero_minutes_means_never() {
        now += 24 * 3_600_000L
        assertNull(idle.remainingMillis(0))
    }

    @Test
    fun a_cart_or_a_payment_holds_the_lock_and_the_count_restarts_after_it() {
        idle.touch()
        val cart = idle.hold("panier non envoyé")
        val payment = idle.hold("encaissement")
        now += 60 * 60_000
        assertNull(idle.remainingMillis(5))
        cart.release()
        assertNull(idle.remainingMillis(5), "still held by the payment")
        now += 2 * 60_000
        payment.release()
        assertEquals(5 * 60_000L, idle.remainingMillis(5), "counted from the moment the last hold ended")
    }
}
