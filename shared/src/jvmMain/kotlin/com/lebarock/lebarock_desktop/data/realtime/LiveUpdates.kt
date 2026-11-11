package com.lebarock.lebarock_desktop.data.realtime

import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.RealtimeAuthRequest
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Live updates (client, 2026-11-11), for the signed-in account: one [LiveSocket] on the account's floor channel
 * (checks, tables, payments of its branch). Opened when someone signs in online, closed when they sign out or the
 * session is « Hors ligne ». The floor and the « À encaisser » list read again on each signal, and keep a slow check
 * ([SLOW_CHECK_MILLIS]) while [connected]; when the live connection is down they check as often as before.
 */
class LiveUpdates(
    private val api: ApiClient,
    private val settings: SettingsStore,
    private val sessions: SessionHolder,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val socket = LiveSocket(
        http = { api.liveHttp() },
        name = "session",
        authorize = { socketId, channel ->
            api.apiFor(settings.current.server.baseUrl).realtimeAuth(RealtimeAuthRequest(socketId, channel)).auth
        },
    )

    val connected: StateFlow<Boolean> get() = socket.connected

    /** Checks, tables, payments changed in the branch. */
    val floor: Flow<LiveEvent> get() = socket.events.filter { it.kind == "floor" }

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(sessions.session, settings.settings.map { it.server.baseUrl }) { session, base ->
                if (session == null || session.offline || base.isBlank()) null else Triple(session.user.id, session.token, base)
            }.distinctUntilChanged().collectLatest { who ->
                if (who == null) {
                    socket.disconnect()
                    return@collectLatest
                }
                val base = who.third
                val info = try {
                    api.apiFor(base).realtime().data
                } catch (e: Exception) {
                    // An older caisse without live updates, or not reachable now: the screens keep checking.
                    AppLog.info("Direct : indisponible (${e.message})")
                    null
                }
                val floor = info?.channels?.get("floor")
                if (info == null || !info.enabled || floor == null) {
                    socket.disconnect()
                    return@collectLatest
                }
                socket.connect(LiveSocket.Target(LiveSocket.wsUrl(base, info.path), listOf(floor)))
            }
        }
    }

    companion object {
        /** While the live connection is up, the screens still check now and then (a missed signal, a restart). */
        const val SLOW_CHECK_MILLIS = 30_000L
    }
}
