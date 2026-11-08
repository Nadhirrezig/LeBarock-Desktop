package com.lebarock.lebarock_desktop.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.AppInfo
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.WindowsStartup
import com.lebarock.lebarock_desktop.data.ConnectionCheck
import com.lebarock.lebarock_desktop.data.FailureKind
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.TrustStore
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The result of the last "Tester la connexion", for the address it was made with. */
sealed interface TestResult {
    val address: ServerAddress

    data class Ok(override val address: ServerAddress, val check: ConnectionCheck) : TestResult
    data class Failed(override val address: ServerAddress, val failure: ApiFailure) : TestResult
}

class SettingsModel(
    private val repository: CaisseRepository,
    private val settings: SettingsStore,
    private val api: ApiClient,
    val firstRun: Boolean,
    private val onSavedFirstRun: () -> Unit,
) : ScreenModel() {

    private val saved = settings.current

    var https by mutableStateOf(saved.server.https)
        private set
    var host by mutableStateOf(saved.server.host)
        private set
    var port by mutableStateOf(saved.server.port.toString())
        private set
    var hostError by mutableStateOf<String?>(null)
        private set
    var portError by mutableStateOf<String?>(null)
        private set

    var testing by mutableStateOf(false)
        private set
    var result by mutableStateOf<TestResult?>(null)
        private set

    /** Shown after "Enregistrer" (or a failed test when saving: offer "Enregistrer quand même"). */
    var saveNotice by mutableStateOf<String?>(null)
        private set
    var offerForceSave by mutableStateOf(false)
        private set

    var idleMinutes by mutableStateOf(saved.idleLockMinutes)
        private set

    var certificates by mutableStateOf(TrustStore.imported())
        private set

    /** Réglages → Application → "Démarrer avec Windows" (installed app only). */
    val startupAvailable: Boolean get() = WindowsStartup.available
    var startWithWindows by mutableStateOf(WindowsStartup.isOn())
        private set
    var startupError by mutableStateOf<String?>(null)
        private set
    var certNotice by mutableStateOf<Pair<Boolean, String>?>(null)
        private set

    val windowsStore: Boolean get() = AppInfo.isWindows

    val savedAddress: ServerAddress get() = settings.current.server

    /** The address as typed, or null while a field is wrong. */
    private fun typed(): ServerAddress? {
        hostError = ServerAddress.hostError(host)
        portError = ServerAddress.portError(port)
        if (hostError != null || portError != null) return null
        return ServerAddress(https = https, host = host.trim(), port = port.trim().toInt())
    }

    /** The current result applies to what is typed now (else it is stale and hidden). */
    val resultIsCurrent: Boolean
        get() = result?.address == runCatching {
            ServerAddress(https = https, host = host.trim(), port = port.trim().toInt())
        }.getOrNull()

    fun onProtocol(useHttps: Boolean) {
        if (useHttps == https) return
        // The usual port follows the protocol unless someone typed another one.
        if (port.trim() == (if (https) "443" else "80")) port = if (useHttps) "443" else "80"
        https = useHttps
        clearMessages()
    }

    fun onHost(text: String) {
        val pasted = ServerAddress.parsePasted(text)
        if (pasted != null) {
            https = pasted.https
            host = pasted.host
            port = pasted.port.toString()
        } else {
            host = text.trim()
        }
        hostError = null
        clearMessages()
    }

    fun onPort(text: String) {
        port = text.filter { it.isDigit() }.take(5)
        portError = null
        clearMessages()
    }

    private fun clearMessages() {
        saveNotice = null
        offerForceSave = false
    }

    fun test() {
        val address = typed() ?: return
        clearMessages()
        testing = true
        scope.launch {
            result = repository.testConnection(address).fold(
                onSuccess = { TestResult.Ok(address, it) },
                onFailure = { TestResult.Failed(address, it as? ApiFailure ?: ApiFailure.from(it, address.https)) },
            )
            testing = false
        }
    }

    /**
     * "Enregistrer": tests the address first (unless just tested OK), keeps it when the caisse
     * answers. When it does not, the address is kept only on "Enregistrer quand même" (the server
     * may simply be off right now).
     */
    fun save(force: Boolean = false) {
        val address = typed() ?: return
        val current = result
        if (force) {
            repository.saveServer(address, (current as? TestResult.Ok)?.takeIf { it.address == address }?.check)
            done("Adresse enregistrée sans test réussi. Vérifiez-la dès que le serveur est allumé.")
            return
        }
        if (current is TestResult.Ok && current.address == address) {
            repository.saveServer(address, current.check)
            done("Adresse enregistrée : « ${current.check.restaurantName} ».")
            return
        }
        testing = true
        clearMessages()
        scope.launch {
            val tested = repository.testConnection(address)
            testing = false
            tested.onSuccess { check ->
                result = TestResult.Ok(address, check)
                repository.saveServer(address, check)
                done("Adresse enregistrée : « ${check.restaurantName} ».")
            }.onFailure {
                result = TestResult.Failed(address, it as? ApiFailure ?: ApiFailure.from(it, address.https))
                saveNotice = "Pas enregistrée : la caisse ne répond pas à cette adresse."
                offerForceSave = true
            }
        }
    }

    private fun done(message: String) {
        offerForceSave = false
        saveNotice = message
        if (firstRun) onSavedFirstRun()
    }

    fun onIdleMinutes(minutes: Int) {
        idleMinutes = minutes
        repository.saveIdleLock(minutes)
        AppLog.info("Verrouillage après inactivité : ${if (minutes == 0) "jamais" else "$minutes min"}")
    }

    fun importCertificate(file: File) {
        TrustStore.import(file).fold(
            onSuccess = {
                api.reloadTrust()
                certificates = TrustStore.imported()
                certNotice = true to "Certificat importé : « ${it.subject} » (valable jusqu'au ${it.validUntil}). Testez la connexion."
                if ((result as? TestResult.Failed)?.failure?.kind == FailureKind.CERTIFICATE_UNTRUSTED) result = null
            },
            onFailure = { certNotice = false to (it.message ?: "Import impossible.") },
        )
    }

    fun removeCertificate(imported: TrustStore.Imported) {
        TrustStore.remove(imported)
        api.reloadTrust()
        certificates = TrustStore.imported()
        certNotice = true to "Certificat retiré."
    }

    fun onStartWithWindows(on: Boolean) {
        WindowsStartup.set(on).fold(
            onSuccess = {
                startWithWindows = on
                startupError = null
                settings.update { it.copy(startWithWindows = on) }
            },
            onFailure = { startupError = it.message ?: "Réglage impossible." },
        )
    }

    /** Whether "Enregistrer" has something to save (what is typed differs from what is saved). */
    val serverChanged: Boolean
        get() = runCatching { ServerAddress(https = https, host = host.trim(), port = port.trim().toInt()) }.getOrNull() != savedAddress ||
            !savedAddress.isConfigured

    /** Opens a folder in Windows' file explorer. */
    fun open(folder: File) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { java.awt.Desktop.getDesktop().open(folder) }
                    .onFailure { AppLog.warn("Ouverture du dossier impossible : ${folder.path}", it) }
            }
        }
    }
}
