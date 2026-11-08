package com.lebarock.lebarock_desktop.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.SecretBox
import com.lebarock.lebarock_desktop.data.remote.AccountDto
import com.lebarock.lebarock_desktop.data.settings.AgentConfig
import com.lebarock.lebarock_desktop.data.settings.AgentPrinter
import com.lebarock.lebarock_desktop.data.settings.PrintTarget
import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.PrinterConnection
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.print.AgentClient
import com.lebarock.lebarock_desktop.print.PrintAgent
import com.lebarock.lebarock_desktop.print.TicketCharset
import com.lebarock.lebarock_desktop.print.TicketPrinter
import com.lebarock.lebarock_desktop.print.WindowsPrinterTransport
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Directe" set-up window: an administrator signs in, then the printer is chosen. */
data class AgentSetup(
    val step: Step,
    val accounts: List<AccountDto> = emptyList(),
    val account: AccountDto? = null,
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val printers: List<AgentPrinter> = emptyList(),
) {
    enum class Step { ACCOUNT, PRINTER }
}

/**
 * Réglages → « Impression des tickets » and « Impression Directe »: this PC's printer (saved on
 * each change), "Imprimer un test", and the print agent's set-up.
 */
class PrintingModel(
    private val settings: SettingsStore,
    private val repository: CaisseRepository,
    private val printer: TicketPrinter,
    private val secrets: SecretBox,
    private val agentClient: AgentClient,
    val agent: PrintAgent,
) : ScreenModel() {

    /** Observed by the sections so they redraw on each saved change. */
    val settingsState = settings.settings

    val config: PrinterConfig get() = settings.current.printer
    val agentConfig: AgentConfig get() = settings.current.agent

    var windowsPrinters by mutableStateOf<List<String>>(emptyList())
        private set
    var loadingPrinters by mutableStateOf(false)
        private set

    /** The Ethernet address as typed (saved when valid). */
    var host by mutableStateOf(config.host)
        private set
    var hostError by mutableStateOf<String?>(null)
        private set
    var port by mutableStateOf(config.port.toString())
        private set
    var portError by mutableStateOf<String?>(null)
        private set

    var testing by mutableStateOf(false)
        private set

    /** The last test: ok + message. */
    var testResult by mutableStateOf<Pair<Boolean, String>?>(null)
        private set

    var setup by mutableStateOf<AgentSetup?>(null)
        private set

    init {
        refreshWindowsPrinters()
    }

    private fun update(transform: (PrinterConfig) -> PrinterConfig) {
        settings.update { it.copy(printer = transform(it.printer)) }
        testResult = null
    }

    fun onTarget(target: PrintTarget) {
        update { it.copy(target = target) }
        AppLog.info("Impression des tickets : ${target.label}")
    }

    fun onConnection(connection: PrinterConnection) = update { it.copy(connection = connection) }

    fun onWindowsPrinter(name: String) = update { it.copy(windowsPrinter = name) }

    fun onPaper(mm: Int) = update { it.copy(paperMm = mm) }

    fun onCharset(charset: TicketCharset) = update { it.copy(charset = charset) }

    fun onHost(text: String) {
        val pasted = ServerAddress.parsePasted(text)
        host = (pasted?.host ?: text).trim()
        hostError = ServerAddress.hostError(host)
        if (hostError == null) update { it.copy(host = host) }
    }

    fun onPort(text: String) {
        port = text.filter { it.isDigit() }.take(5)
        portError = ServerAddress.portError(port)?.let { "Le port va de 1 à 65535 (habituellement 9100)." }
        if (portError == null) update { it.copy(port = port.toInt()) }
    }

    fun refreshWindowsPrinters() {
        loadingPrinters = true
        scope.launch {
            windowsPrinters = withContext(Dispatchers.IO) { WindowsPrinterTransport.names() }
            loadingPrinters = false
        }
    }

    fun printTest() {
        if (config.connection == PrinterConnection.NETWORK && (hostError != null || portError != null)) {
            testResult = false to (hostError ?: portError ?: "")
            return
        }
        testing = true
        testResult = null
        scope.launch {
            val result = printer.printTest(settings.current.restaurantName)
            testResult = result.fold(
                onSuccess = { true to "$it Vérifiez le ticket : les accents et la ligne de bord à bord." },
                onFailure = { false to (it.message ?: "Impression impossible.") },
            )
            testing = false
        }
    }

    // ---- Impression « Directe » ----

    fun openSetup() {
        setup = AgentSetup(AgentSetup.Step.ACCOUNT, accounts = repository.cachedAccounts())
        scope.launch {
            repository.accounts().onSuccess { list -> setup = setup?.copy(accounts = list) }
        }
    }

    fun closeSetup() {
        if (setup?.busy != true) setup = null
    }

    fun pickAccount(account: AccountDto) {
        setup = setup?.copy(account = account, password = "", error = null)
    }

    fun onSetupPassword(text: String) {
        setup = setup?.copy(password = text.take(64), error = null)
    }

    fun submitAdmin() {
        val s = setup ?: return
        val account = s.account ?: return
        if (s.password.isEmpty()) {
            setup = s.copy(error = "Saisissez le mot de passe de ce compte.")
            return
        }
        setup = s.copy(busy = true, error = null)
        scope.launch {
            repository.fetchPrintAgentAccess(account.id, s.password).fold(
                onSuccess = { access ->
                    val printers = access.printers.map { AgentPrinter(it.id, it.name, it.printingChoice, it.active, it.caisses) }
                    settings.update {
                        it.copy(agent = it.agent.copy(keySealed = secrets.seal(access.key), branchName = access.branch.name, printers = printers))
                    }
                    setup = AgentSetup(AgentSetup.Step.PRINTER, printers = printers)
                },
                onFailure = { e ->
                    val failure = e as? ApiFailure ?: ApiFailure.from(e)
                    setup = setup?.copy(
                        busy = false,
                        password = "",
                        error = when {
                            failure.httpCode == 403 -> "Ce compte ne peut pas configurer l'impression : il faut un administrateur (droit « Gérer les paramètres »)."
                            failure.httpCode == 422 -> "Mot de passe incorrect."
                            else -> failure.message
                        },
                    )
                },
            )
        }
    }

    /** Change the agent's printer: the list is read again with the key (no administrator needed). */
    fun openPrinterChoice() {
        val cfg = agentConfig
        setup = AgentSetup(AgentSetup.Step.PRINTER, printers = cfg.printers, busy = true)
        scope.launch {
            val key = secrets.open(cfg.keySealed)
            val fresh = if (key == null) null else withContext(Dispatchers.IO) {
                runCatching { agentClient.printers(settings.current.server.baseUrl, key) }.getOrNull()
            }
            if (fresh != null) settings.update { it.copy(agent = it.agent.copy(printers = fresh)) }
            setup = setup?.copy(printers = fresh ?: cfg.printers, busy = false)
        }
    }

    fun choosePrinter(printer: AgentPrinter) {
        settings.update { it.copy(agent = it.agent.copy(printerId = printer.id, enabled = true)) }
        AppLog.info("Impression Directe : imprimante « ${printer.name} » (#${printer.id}), en marche")
        setup = null
    }

    fun onAgentEnabled(on: Boolean) {
        settings.update { it.copy(agent = it.agent.copy(enabled = on)) }
        AppLog.info("Impression Directe : ${if (on) "en marche" else "arrêtée"}")
    }

    fun removeAgent() {
        settings.update { it.copy(agent = AgentConfig()) }
        AppLog.info("Impression Directe : clé retirée de ce poste")
    }
}
