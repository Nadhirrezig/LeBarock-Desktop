package com.lebarock.lebarock_desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.rememberScreenModel
import com.lebarock.lebarock_desktop.ui.home.HomeModel
import com.lebarock.lebarock_desktop.ui.home.HomeScreen
import com.lebarock.lebarock_desktop.ui.home.Section
import com.lebarock.lebarock_desktop.ui.orders.OrdersModel
import com.lebarock.lebarock_desktop.ui.orders.OrdersScreen
import com.lebarock.lebarock_desktop.ui.pos.PosModel
import com.lebarock.lebarock_desktop.ui.pos.PosScreen
import com.lebarock.lebarock_desktop.ui.idle.IdleMonitor
import com.lebarock.lebarock_desktop.ui.login.LoginModel
import com.lebarock.lebarock_desktop.ui.login.LoginScreen
import com.lebarock.lebarock_desktop.ui.navigation.Navigator
import com.lebarock.lebarock_desktop.ui.navigation.Screen
import com.lebarock.lebarock_desktop.ui.settings.PrintingModel
import com.lebarock.lebarock_desktop.ui.settings.SettingsModel
import com.lebarock.lebarock_desktop.ui.settings.SettingsScreen
import com.lebarock.lebarock_desktop.ui.theme.AltosTheme
import com.lebarock.lebarock_desktop.ui.theme.Warning
import com.lebarock.lebarock_desktop.ui.theme.WarningContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The window's content: Réglages on the first start, then sign-in → Accueil. */
@Composable
fun App(container: AppContainer) {
    AltosTheme {
        val settings by container.settings.settings.collectAsState()
        val session by container.sessions.session.collectAsState()
        val navigator = remember {
            Navigator(if (container.settings.current.server.isConfigured) Screen.Login() else Screen.Settings(firstRun = true))
        }
        val scope = rememberCoroutineScope()

        // The session ended under us (the caisse refused the token: 401): back to the sign-in.
        var hadSession by remember { mutableStateOf(false) }
        LaunchedEffect(session) {
            if (session != null) {
                hadSession = true
            } else if (hadSession) {
                hadSession = false
                if (navigator.current !is Screen.Login) navigator.reset(Screen.Login("Session expirée : reconnectez-vous."))
            }
        }

        // The window went to the tray: the session ends (shared counter), the sign-in waits behind.
        LaunchedEffect(Unit) {
            container.lockRequests.collect { reason ->
                if (container.sessions.current != null) {
                    hadSession = false
                    container.repository.logout(reason)
                    navigator.reset(Screen.Login("Session fermée quand la fenêtre a été fermée."))
                }
            }
        }

        // The idle lock (Réglages → Session): closes the session, never while something is held.
        var idleLeft by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(session?.token, settings.idleLockMinutes) {
            idleLeft = null
            if (session == null) return@LaunchedEffect
            container.idle.touch()
            val minutes = settings.idleLockMinutes
            while (true) {
                val left = container.idle.remainingMillis(minutes)
                idleLeft = left
                if (left == 0L) {
                    hadSession = false
                    container.repository.logout("inactivité ($minutes min)")
                    navigator.reset(Screen.Login("Session fermée après $minutes min sans activité."))
                    break
                }
                delay(1_000)
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (val screen = navigator.current) {
                is Screen.Settings -> {
                    val model = rememberScreenModel(screen) {
                        SettingsModel(container.repository, container.settings, container.api, screen.firstRun) {
                            navigator.reset(Screen.Login())
                        }
                    }
                    val printing = if (screen.firstRun) {
                        null
                    } else {
                        rememberScreenModel(screen to "printing") {
                            PrintingModel(container.settings, container.repository, container.ticketPrinter, container.secrets, container.agentClient, container.printAgent)
                        }
                    }
                    SettingsScreen(model, printing, canGoBack = navigator.canGoBack, onBack = navigator::back)
                }
                is Screen.Login -> {
                    val model = rememberScreenModel(screen to settings.server.baseUrl) {
                        LoginModel(container.repository, screen.notice) { navigator.reset(Screen.Home) }
                    }
                    LoginScreen(
                        model,
                        restaurantName = settings.restaurantName,
                        server = settings.server,
                        onOpenSettings = { navigator.push(Screen.Settings()) },
                    )
                }
                Screen.Home -> {
                    val model = rememberScreenModel(Screen.Home) {
                        HomeModel(container.repository, container.sessions, onOpenSettings = { navigator.push(Screen.Settings()) }) { section ->
                            when (section) {
                                Section.POS -> navigator.push(Screen.Pos())
                                Section.ORDERS -> navigator.push(Screen.Orders())
                                else -> Unit
                            }
                        }
                    }
                    HomeScreen(model, restaurantName = settings.restaurantName, onSwitchUser = {
                        scope.launch {
                            hadSession = false
                            container.repository.logout("changement d'utilisateur")
                            navigator.reset(Screen.Login())
                        }
                    })
                }
                is Screen.Pos -> {
                    val model = rememberScreenModel(screen) {
                        PosModel(container.orders, container.sessions, container.idle, screen.orderId) { message ->
                            navigator.back()
                            // From Commandes: back on the same order, with what happened.
                            if (screen.orderId != null && navigator.current is Screen.Orders) {
                                navigator.replaceTop(Screen.Orders(message, select = screen.orderId))
                            }
                        }
                    }
                    PosScreen(model, caisseName = session?.caisse?.name, images = container.itemImages)
                }
                is Screen.Orders -> {
                    val model = rememberScreenModel(screen) {
                        OrdersModel(container.orders, container.sessions, screen.message) { id ->
                            navigator.replaceTop(Screen.Orders(select = id))
                            navigator.push(Screen.Pos(orderId = id))
                        }
                    }
                    LaunchedEffect(model) { screen.select?.let(model::select) }
                    OrdersScreen(model, onBack = navigator::back)
                }
            }

            val left = idleLeft
            if (left != null && left in 1..IdleMonitor.WARNING_MILLIS) {
                IdleWarning(left, onStay = container.idle::touch, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** 30 s before the lock: a strip that any click, key or touch removes. */
@Composable
private fun IdleWarning(leftMillis: Long, onStay: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(24.dp).widthIn(max = 760.dp).fillMaxWidth(),
        shape = com.lebarock.lebarock_desktop.ui.theme.Dimens.cardShape,
        color = WarningContainer,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LockClock, contentDescription = null, tint = Warning)
            Spacer(Modifier.width(12.dp))
            Text(
                "Session fermée dans ${(leftMillis + 999) / 1000} s faute d'activité.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF78350F),
                modifier = Modifier.weight(1f),
            )
            AppButton("Rester connecté", onStay)
        }
    }
}
