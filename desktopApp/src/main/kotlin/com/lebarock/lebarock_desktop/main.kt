package com.lebarock.lebarock_desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.data.WindowsStartup
import com.lebarock.lebarock_desktop.print.AgentState
import com.lebarock.lebarock_desktop.ui.common.Logo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.awt.SystemTray
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    AppLog.start()
    if (!SingleInstance.acquire()) {
        JOptionPane.showMessageDialog(null, "AltosPOS est déjà ouvert sur ce PC (voir l'icône près de l'horloge).", "AltosPOS", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }
    AppLog.info("Démarrage ${AppInfo.NAME} ${AppInfo.version} (Java ${System.getProperty("java.version")}, ${System.getProperty("os.name")})")
    Thread.setDefaultUncaughtExceptionHandler { thread, error -> AppLog.error("Erreur non gérée (${thread.name})", error) }

    val container = AppContainer()
    container.idle.installAwtListener()

    // The tray keeps AltosPOS running with its window closed, for the "Directe" printing (client, 2026-11-08).
    val trayAvailable = runCatching { SystemTray.isSupported() }.getOrDefault(false)
    val startHidden = WindowsStartup.TRAY_ARG in args && trayAvailable

    // First start of the installed app: "Démarrer avec Windows" is on (client, 2026-11-08); Réglages turns it off.
    if (container.settings.current.startWithWindows == null && WindowsStartup.available) {
        WindowsStartup.set(true).onSuccess { container.settings.update { it.copy(startWithWindows = true) } }
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    container.printAgent.start(appScope)
    // Offline mode: this PC says it is there, keeps its copy of the caisse and sends what waits, window open or not.
    container.offline.start()

    application {
        val settings by container.settings.settings.collectAsState()
        val agent by container.printAgent.status.collectAsState()
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1366.dp, 800.dp))
        val icon = remember { BitmapPainter(Logo.bitmap) }
        val trayState = rememberTrayState()
        var visible by remember { mutableStateOf(!startHidden) }
        var toldAboutTray by remember { mutableStateOf(false) }

        fun quit() {
            // The token is deleted on the caisse (2 s at most), then the app ends.
            runBlocking { withTimeoutOrNull(2_000) { container.repository.logout("fermeture de l'application") } }
            AppLog.info("Fermeture")
            exitApplication()
        }

        if (trayAvailable) {
            val agentLabel = when (agent.state) {
                AgentState.OFF, AgentState.NOT_SET -> "arrêtée"
                AgentState.WAITING, AgentState.PRINTING -> "en marche"
                AgentState.CAISSE_DOWN -> "caisse injoignable"
                AgentState.KEY_REFUSED, AgentState.PRINTER_ERROR -> "erreur"
            }
            Tray(
                icon = icon,
                state = trayState,
                tooltip = "AltosPOS — impression « Directe » $agentLabel",
                onAction = { visible = true },
                menu = {
                    Item("Ouvrir AltosPOS", onClick = { visible = true })
                    Item("Impression « Directe » : $agentLabel", enabled = false, onClick = {})
                    Separator()
                    Item("Quitter AltosPOS", onClick = { quit() })
                },
            )
        }

        Window(
            onCloseRequest = {
                if (trayAvailable) {
                    // To the tray: printing goes on, the counter's session ends (shared PC).
                    visible = false
                    container.lockRequests.tryEmit("fenêtre fermée (barre des tâches)")
                    AppLog.info("Fenêtre fermée : AltosPOS reste dans la barre des tâches")
                    if (!toldAboutTray) {
                        toldAboutTray = true
                        trayState.sendNotification(
                            Notification(
                                "AltosPOS reste ouvert",
                                "L'impression « Directe » continue. Clic droit sur l'icône AltosPOS → Quitter pour l'arrêter.",
                                Notification.Type.Info,
                            ),
                        )
                    }
                } else {
                    quit()
                }
            },
            visible = visible,
            state = windowState,
            title = listOf(AppInfo.NAME, settings.restaurantName).filter { it.isNotBlank() }.joinToString(" — "),
            icon = icon,
        ) {
            window.minimumSize = Dimension(1024, 680)
            App(container)
        }
    }
}
