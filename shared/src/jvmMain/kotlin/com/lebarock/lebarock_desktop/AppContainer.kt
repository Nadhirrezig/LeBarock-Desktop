package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.SecretBox
import com.lebarock.lebarock_desktop.print.AgentClient
import com.lebarock.lebarock_desktop.print.PrintAgent
import com.lebarock.lebarock_desktop.print.TicketPrinter
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.ui.idle.IdleMonitor
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** Everything the screens share, built once at start-up (the handheld's AppContainer). */
class AppContainer(dataDir: File = AppInfo.dataDir) {
    val settings = SettingsStore(File(dataDir, "settings.json"))
    val sessions = SessionHolder()
    val api = ApiClient(tokenProvider = { sessions.token })
    val repository = CaisseRepository(api, settings, sessions)
    val idle = IdleMonitor()
    val secrets = SecretBox(dataDir)
    val ticketPrinter = TicketPrinter(settings)
    val agentClient = AgentClient { api.agentHttp() }
    val orders = com.lebarock.lebarock_desktop.data.OrdersRepository(repository, settings, ticketPrinter, api.json)

    /** Ask the window to end the session and show the sign-in (window closed to the tray). */
    val lockRequests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val printAgent = PrintAgent(settings, secrets, ticketPrinter, agentClient)
}

/**
 * One AltosPOS per Windows session: a second copy would sign in twice and, from D2, print the
 * caisse's tickets twice. The lock file is released when the app ends (even after a crash).
 */
object SingleInstance {
    @Volatile private var lock: FileLock? = null

    fun acquire(dataDir: File = AppInfo.dataDir): Boolean {
        return try {
            val channel = RandomAccessFile(File(dataDir, "altospos.lock"), "rw").channel
            val acquired = channel.tryLock()
            if (acquired == null) {
                channel.close()
                false
            } else {
                lock = acquired
                true
            }
        } catch (e: Exception) {
            AppLog.warn("Verrou d'instance unique impossible", e)
            true
        }
    }
}
