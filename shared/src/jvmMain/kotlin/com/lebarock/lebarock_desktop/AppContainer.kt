package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.data.CaisseRepository
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
