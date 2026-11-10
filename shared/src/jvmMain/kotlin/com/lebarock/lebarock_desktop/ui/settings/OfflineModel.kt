package com.lebarock.lebarock_desktop.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.offline.OfflineStatus
import com.lebarock.lebarock_desktop.data.offline.OfflineSync
import com.lebarock.lebarock_desktop.data.offline.SnapshotSummary
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Réglages → « Mode hors ligne » (the handheld's section): this PC's code at the caisse, the copy it holds for
 * working without the caisse, what waits to be sent. « Synchroniser maintenant » refreshes everything; « Effacer les
 * données hors ligne » drops the copy (never the actions still waiting).
 */
class OfflineModel(private val offline: OfflineSync, private val sessions: SessionHolder) : ScreenModel() {

    var status by mutableStateOf(offline.status.value)
        private set
    var code by mutableStateOf(offline.deviceCode.value)
        private set
    var lastContact by mutableStateOf(offline.lastContactAt.value)
        private set
    var copy by mutableStateOf(offline.snapshot.value)
        private set

    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var confirmClear by mutableStateOf(false)
        private set

    val storeOnDisk: Boolean get() = offline.storeOnDisk

    init {
        scope.launch { offline.status.collect { status = it } }
        scope.launch { offline.deviceCode.collect { code = it } }
        scope.launch { offline.lastContactAt.collect { lastContact = it } }
        scope.launch { offline.snapshot.collect { copy = it } }
    }

    val deviceLine: String get() = code?.let { "Caisse PC $it" } ?: "pas encore enregistré (connectez-vous une fois)"

    val copyLine: String get() = copyLine(copy)

    val stateLine: String get() = stateLine(status)

    fun syncNow() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            offline.refreshAll()
            message = when {
                offline.status.value.offline -> "La caisse ne répond pas : réessayez plus tard."
                sessions.current == null -> "Poste signalé à la caisse. La copie se met à jour quand un compte est connecté."
                else -> "Synchronisé."
            }
            busy = false
        }
    }

    fun askClear() {
        confirmClear = true
    }

    fun dismissClear() {
        confirmClear = false
    }

    fun clear() {
        confirmClear = false
        scope.launch {
            message = offline.clearCopy().fold(onSuccess = { "Données hors ligne effacées." }, onFailure = { it.message })
        }
    }

    companion object {
        private val time = DateTimeFormatter.ofPattern("dd/MM HH:mm")

        fun format(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(time)

        fun copyLine(copy: SnapshotSummary): String = copy.savedAt?.let {
            "${copy.items} articles, ${copy.tables} tables, ${copy.openChecks} commandes ouvertes" +
                (copy.caisseName?.let { name -> " · $name" } ?: "") + " — ${format(it)}"
        } ?: "pas encore téléchargées"

        fun stateLine(status: OfflineStatus): String = when {
            status.offline -> "hors ligne (la caisse ne répond pas)"
            status.keyRefused -> "clé refusée : connectez-vous une fois en ligne"
            status.syncing -> "synchronisation…"
            else -> "en ligne"
        }
    }
}
