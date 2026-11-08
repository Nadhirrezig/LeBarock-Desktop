package com.lebarock.lebarock_desktop.ui.navigation

import androidx.compose.runtime.mutableStateListOf

/** The app's screens. Sections not built yet (POS, Tables…) come with their steps. */
sealed interface Screen {
    /** Réglages; [firstRun]: no server yet, nothing to go back to. */
    data class Settings(val firstRun: Boolean = false) : Screen

    /** Sign-in; [notice]: why we are here (e.g. "Session fermée après 5 min d'inactivité."). */
    data class Login(val notice: String? = null) : Screen

    data object Home : Screen

    /**
     * The POS; [orderId]: « Nouveau ticket » on that open check (from Commandes or Tables);
     * [table] / [covers]: a new order on a free table picked on the floor (D4).
     */
    data class Pos(val orderId: Long? = null, val table: com.lebarock.lebarock_desktop.data.remote.TableDto? = null, val covers: Int = 1) : Screen

    /** Tables (D4), the floor; [message] to show on arrival. */
    data class Tables(val message: String? = null) : Screen

    /** Commandes; [message] to show on arrival, [select] the order to open. */
    data class Orders(val message: String? = null, val select: Long? = null) : Screen
}

/** A small back stack: the counter has few screens, all inside one window. */
class Navigator(initial: Screen) {
    private val stack = mutableStateListOf(initial)

    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Replaces the screen on top (e.g. Commandes again, with a message and the order to show). */
    fun replaceTop(screen: Screen) {
        stack[stack.lastIndex] = screen
    }

    /** Replaces the whole stack (sign-in, sign-out, lock). */
    fun reset(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }
}
