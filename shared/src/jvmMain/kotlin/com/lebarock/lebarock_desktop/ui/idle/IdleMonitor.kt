package com.lebarock.lebarock_desktop.ui.idle

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The counter's idle lock (client, 2026-11-08): after N minutes with no click, key or touch, the
 * session is closed and the sign-in screen comes back. Never while something would be lost: a
 * cart with lines not sent, an open payment window. Those screens [hold] the lock while they are
 * open (POS from D3, payment from D5).
 *
 * Every mouse, touch and key event of the app's windows reaches AWT first, so one AWT listener
 * sees all activity.
 */
class IdleMonitor(private val clock: () -> Long = System::currentTimeMillis) {

    private val lastActivity = AtomicLong(clock())
    private val holds = ConcurrentHashMap<Long, String>()
    private val nextHold = AtomicLong(1)
    @Volatile private var listener: AWTEventListener? = null

    /** A reason the session must not close now; [release] it when the screen closes or the reason ends. */
    inner class Hold internal constructor(private val id: Long) {
        fun release() {
            holds.remove(id)
            // The countdown restarts from the moment the reason ended, not from the last click before it.
            touch()
        }
    }

    fun touch() {
        lastActivity.set(clock())
    }

    fun hold(reason: String): Hold {
        val id = nextHold.getAndIncrement()
        holds[id] = reason
        return Hold(id)
    }

    val heldBy: Collection<String> get() = holds.values

    /**
     * Milliseconds left before the lock, or null when the lock does not apply now (turned off,
     * or held).
     */
    fun remainingMillis(minutes: Int): Long? {
        if (minutes <= 0 || holds.isNotEmpty()) return null
        val elapsed = clock() - lastActivity.get()
        return (minutes * 60_000L - elapsed).coerceAtLeast(0)
    }

    fun installAwtListener() {
        if (listener != null) return
        val l = AWTEventListener { touch() }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            l,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.KEY_EVENT_MASK,
        )
        listener = l
    }

    companion object {
        /** The warning strip shows this long before the lock. */
        const val WARNING_MILLIS = 30_000L
    }
}
