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
 * working without the caisse, what waits to be sent. O4a: the branch's « Clé de synchronisation » registers the PC. « Synchroniser maintenant » refreshes everything; « Effacer les
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
    var reviewCount by mutableStateOf(offline.reviewCount.value)
        private set

    /** O4a: the branch's « Clé de synchronisation » typed to register this PC. */
    var enrolKey by mutableStateOf("")
        private set
    var enrolError by mutableStateOf<String?>(null)
        private set
    var enrolling by mutableStateOf(false)
        private set
    var showEnrol by mutableStateOf(false)
        private set

    /** The key field: on a PC not registered yet or whose key was refused, or when asked. */
    val enrolOpen: Boolean get() = showEnrol || !status.registered || status.keyRefused

    val storeOnDisk: Boolean get() = offline.storeOnDisk

    init {
        scope.launch { offline.status.collect { status = it } }
        scope.launch { offline.deviceCode.collect { code = it } }
        scope.launch { offline.lastContactAt.collect { lastContact = it } }
        scope.launch { offline.snapshot.collect { copy = it } }
        scope.launch { offline.reviewCount.collect { reviewCount = it } }
    }

    val deviceLine: String get() = code?.takeIf { status.registered }?.let { "Caisse PC $it" }
        ?: "pas encore enregistré (clé de synchronisation ci-dessous)"

    val copyLine: String get() = copyLine(copy)

    val stateLine: String get() = stateLine(status)

    /** Kept as typed, in capitals (adding the dashes while typing would move the cursor); sent grouped by four. */
    fun onEnrolKey(value: String) {
        enrolKey = value.filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.uppercase().take(20)
        enrolError = null
    }

    /** The key has its 12 letters and digits (dashes and spaces do not count). */
    val enrolReady: Boolean get() = enrolKey.count { it.isLetterOrDigit() } == 12

    fun askEnrol() {
        showEnrol = true
        message = null
    }

    fun enrol() {
        if (enrolling) return
        enrolling = true
        enrolError = null
        message = null
        scope.launch {
            offline.enrol(enrolKey).fold(
                onSuccess = { code ->
                    enrolKey = ""
                    showEnrol = false
                    message = "Poste enregistré sur la caisse : « Caisse PC $code ». La copie se met à jour toute seule, connecté ou non."
                },
                onFailure = { enrolError = it.message },
            )
            enrolling = false
        }
    }

    fun syncNow() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            offline.refreshAll()
            message = when {
                offline.status.value.offline -> "La caisse ne répond pas : réessayez plus tard."
                !offline.status.value.registered -> "Enregistrez d'abord ce poste avec la clé de synchronisation."
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
            status.keyRefused -> "clé refusée : enregistrez ce poste à nouveau avec la clé de synchronisation"
            status.syncing -> "synchronisation…"
            else -> "en ligne"
        }
    }
}
