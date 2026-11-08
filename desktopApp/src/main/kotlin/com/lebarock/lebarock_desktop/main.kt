package com.lebarock.lebarock_desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.ui.common.Logo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main() {
    AppLog.start()
    if (!SingleInstance.acquire()) {
        JOptionPane.showMessageDialog(null, "AltosPOS est déjà ouvert sur ce PC.", "AltosPOS", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }
    AppLog.info("Démarrage ${AppInfo.NAME} ${AppInfo.version} (Java ${System.getProperty("java.version")}, ${System.getProperty("os.name")})")
    Thread.setDefaultUncaughtExceptionHandler { thread, error -> AppLog.error("Erreur non gérée (${thread.name})", error) }

    val container = AppContainer()
    container.idle.installAwtListener()

    application {
        val settings by container.settings.settings.collectAsState()
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1366.dp, 800.dp))
        val icon = remember { BitmapPainter(Logo.bitmap) }

        Window(
            onCloseRequest = {
                // The token is deleted on the caisse (2 s at most), then the app ends.
                runBlocking { withTimeoutOrNull(2_000) { container.repository.logout("fermeture de l'application") } }
                AppLog.info("Fermeture")
                exitApplication()
            },
            state = windowState,
            title = listOf(AppInfo.NAME, settings.restaurantName).filter { it.isNotBlank() }.joinToString(" — "),
            icon = icon,
        ) {
            window.minimumSize = Dimension(1024, 680)
            App(container)
        }
    }
}
