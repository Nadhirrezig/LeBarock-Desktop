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

    /** Offline mode: whether the caisse answers, seen on every call to it. */
    val connection = com.lebarock.lebarock_desktop.data.offline.ConnectionMonitor()
    val api = ApiClient(tokenProvider = { sessions.token }, watch = connection.interceptor).apply { imageCacheDir = File(dataDir, "cache/images") }
    val repository = CaisseRepository(api, settings, sessions)
    val idle = IdleMonitor()
    val secrets = SecretBox(dataDir)

    /** Offline mode: this PC's own store (`offline.db`) and what keeps it in step with the caisse, for the app's life. */
    val offlineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    val offlineStore = com.lebarock.lebarock_desktop.data.offline.OfflineStore.open(File(dataDir, "offline.db"))
    val offline = com.lebarock.lebarock_desktop.data.offline.OfflineSync(offlineStore, api, settings, sessions, secrets, connection, offlineScope)
    val ticketPrinter = TicketPrinter(settings)
    val agentClient = AgentClient { api.agentHttp() }
    /** Offline step 2: orders taken on this PC while the caisse does not answer, printed on its own printer. */
    val offlineOrders = com.lebarock.lebarock_desktop.data.offline.OfflineOrders(offlineStore, offline, sessions, ticketPrinter, api.json)
    /** Offline step 3: the Caisse's offline PIN (« Hors ligne » sign-in) and the payment PIN, checked on this PC. */
    val offlinePins = com.lebarock.lebarock_desktop.data.offline.OfflinePins(offlineStore, secrets)
    val orders = com.lebarock.lebarock_desktop.data.OrdersRepository(repository, settings, ticketPrinter, api.json, offlineOrders, offline, offlinePins)
    val cashRegister = com.lebarock.lebarock_desktop.data.CashRegisterRepository(repository, settings, ticketPrinter, api.json)
    val itemImages = com.lebarock.lebarock_desktop.ui.pos.ItemImages(
        http = { api.images() }, baseUrl = { settings.current.server.baseUrl }, offline = { connection.offline.value },
    )

    /** Ask the window to end the session and show the sign-in (window closed to the tray). */
    val lockRequests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val printAgent = PrintAgent(settings, secrets, ticketPrinter, agentClient, liveHttp = { api.liveHttp() })

    /** Live updates (2026-11-11): the signed-in account's floor channel (floor, « À encaisser »). */
    val live = com.lebarock.lebarock_desktop.data.realtime.LiveUpdates(api, settings, sessions)
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
