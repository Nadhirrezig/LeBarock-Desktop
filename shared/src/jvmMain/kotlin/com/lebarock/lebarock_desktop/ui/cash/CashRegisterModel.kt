package com.lebarock.lebarock_desktop.ui.cash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CashChange
import com.lebarock.lebarock_desktop.data.CashRegisterRepository
import com.lebarock.lebarock_desktop.data.PrintOutcome
import com.lebarock.lebarock_desktop.data.remote.CashRegisterStateDto
import com.lebarock.lebarock_desktop.data.remote.CashReportDto
import com.lebarock.lebarock_desktop.data.remote.ExpenseRequest
import com.lebarock.lebarock_desktop.data.remote.ExpensesResponse
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.pos.CashCount
import com.lebarock.lebarock_desktop.pos.CounterDate
import com.lebarock.lebarock_desktop.pos.parseAmount
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.idle.IdleMonitor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class CashTab(val label: String) { SESSION("Session"), REPORTS("Rapports X / Z"), EXPENSES("Dépenses") }

/** The web's three drawer movements, under the web's names, with its reason hints. */
enum class Movement(val api: String, val title: String, val hint: String, val verb: String) {
    CASH_IN("cash_in", "Entrée de caisse", "Ex. monnaie ajoutée au fond de caisse", "entrer dans le tiroir"),
    CASH_OUT("cash_out", "Sortie de caisse", "Ex. dépôt à la banque", "sortir du tiroir"),
    SAFE_DROP("safe_drop", "Prélèvement coffre", "Ex. trop d'espèces dans le tiroir", "mettre au coffre"),
}

/** A window on the Caisse: the amount (and reason) first, then the web's « Confirmer » step. */
sealed interface CashDialog {
    val confirm: Boolean
    val error: String?
    val busy: Boolean

    data class Open(val amount: String = "", override val confirm: Boolean = false, override val error: String? = null, override val busy: Boolean = false) : CashDialog
    data class Move(val kind: Movement, val amount: String = "", val reason: String = "", override val confirm: Boolean = false, override val error: String? = null, override val busy: Boolean = false) : CashDialog
    data class Close(val count: CashCount, val notes: String = "", override val confirm: Boolean = false, override val error: String? = null, override val busy: Boolean = false) : CashDialog
}

/** An X or Z just made (or opened from the list): its ticket, where it printed, and after a Z its stock request. */
data class ReportShown(
    val report: CashReportDto,
    val print: PrintOutcome?,
    val reprinting: Boolean = false,
    val stockBusy: Boolean = false,
    val stockResult: PrintOutcome? = null,
)

/** The web's « Ajouter une dépense ». */
data class ExpenseForm(
    val title: String = "",
    val categoryId: Long? = null,
    val amount: String = "",
    val date: String = CounterDate.today(),
    val paid: Boolean = true,
    val method: String? = "cash",
    val description: String = "",
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * « Caisse » (D6): the web's « Caisse » page on the account's own Caisse (client, 2026-11-10): the session — open
 * with its float, cash in / out / safe drop, the figures and movements —, the X, the Z counted by value, the Z's
 * stock part with « Envoyer la demande au stock »; this Caisse's past X / Z to see and print again; the expenses
 * (the web's form, the day's list). Every action that moves money or closes the session asks « Confirmer » first,
 * as the web. The idle lock never closes the session while a window is open.
 */
class CashRegisterModel(
    private val cash: CashRegisterRepository,
    private val sessions: SessionHolder,
    private val idle: IdleMonitor,
) : ScreenModel() {

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    val rights get() = sessions.current?.rights
    val currency get() = cash.currency

    var tab by mutableStateOf(CashTab.SESSION)
        private set
    var state by mutableStateOf<UiState<CashRegisterStateDto>>(UiState.Loading)
        private set
    var dialog by mutableStateOf<CashDialog?>(null)
        private set
    var shown by mutableStateOf<ReportShown?>(null)
        private set
    var making by mutableStateOf(false)
        private set

    // Rapports X / Z
    var reportFilter by mutableStateOf<String?>(null)
        private set
    var reports by mutableStateOf<UiState<List<CashReportDto>>>(UiState.Loading)
        private set
    var selected by mutableStateOf<UiState<CashReportDto>?>(null)
        private set
    var selectedReprint by mutableStateOf<PrintOutcome?>(null)
        private set
    var selectedReprinting by mutableStateOf(false)
        private set

    // Dépenses
    var expenses by mutableStateOf<UiState<ExpensesResponse>>(UiState.Loading)
        private set
    var expenseForm by mutableStateOf(ExpenseForm())
        private set

    private var hold: IdleMonitor.Hold? = null

    init {
        refresh()
    }

    override fun onDispose() {
        hold?.release()
        hold = null
    }

    val canExpenses: Boolean get() = rights?.showExpense == true || rights?.createExpense == true

    fun chooseTab(t: CashTab) {
        tab = t
        when (t) {
            CashTab.SESSION -> refresh()
            CashTab.REPORTS -> loadReports()
            CashTab.EXPENSES -> loadExpenses()
        }
    }

    fun refresh() {
        val keep = state is UiState.Success
        scope.launch {
            cash.state()
                .onSuccess { state = UiState.Success(it) }
                .onFailure { e -> if (!keep) state = UiState.Error(e.message ?: "Caisse indisponible.", (e as? ApiFailure)?.hint) else messageChannel.trySend(e.message ?: "Caisse indisponible.") }
        }
    }

    val current: CashRegisterStateDto? get() = (state as? UiState.Success)?.data

    // ---- Windows (each asks « Confirmer » before it runs) ----

    private fun show(d: CashDialog?) {
        dialog = d
        if (d != null && hold == null) hold = idle.hold("caisse en cours")
        if (d == null && shown == null) {
            hold?.release()
            hold = null
        }
    }

    fun openSessionWindow() = show(CashDialog.Open())
    fun movementWindow(kind: Movement) = show(CashDialog.Move(kind))
    fun closeWindow() {
        val values = current?.countValues.orEmpty()
        show(CashDialog.Close(CashCount(values)))
    }

    fun edit(block: (CashDialog) -> CashDialog) {
        val d = dialog ?: return
        if (d.busy) return
        dialog = block(d)
    }

    fun dismiss() {
        val d = dialog ?: return
        if (d.busy) return
        // From the summary step, back to the fields (web: « Retour »); from the fields, closed.
        if (d.confirm) dialog = back(d) else show(null)
    }

    private fun back(d: CashDialog): CashDialog = when (d) {
        is CashDialog.Open -> d.copy(confirm = false)
        is CashDialog.Move -> d.copy(confirm = false)
        is CashDialog.Close -> d.copy(confirm = false)
    }

    /** « Continuer »: the fields checked as the web does, then the summary to confirm. */
    fun next() {
        when (val d = dialog) {
            is CashDialog.Open -> {
                val amount = parseAmount(d.amount.ifBlank { "0" })
                dialog = if (amount == null || amount < 0) d.copy(error = "Indiquez le fond de caisse (0 s'il n'y en a pas).") else d.copy(confirm = true, error = null)
            }
            is CashDialog.Move -> {
                val amount = parseAmount(d.amount)
                dialog = when {
                    amount == null || amount < 0.01 -> d.copy(error = "Indiquez un montant supérieur à zéro.")
                    d.reason.isBlank() -> d.copy(error = "Indiquez le motif.")
                    else -> d.copy(confirm = true, error = null)
                }
            }
            is CashDialog.Close ->
                dialog = if (d.count.restAmount == null) d.copy(error = "Le reste doit être un montant (ex. 3,450).") else d.copy(confirm = true, error = null)
            null -> Unit
        }
    }

    /** « Confirmer »: the action on the caisse. */
    fun confirm() {
        val d = dialog ?: return
        if (d.busy || !d.confirm) return
        when (d) {
            is CashDialog.Open -> run(d.copy(busy = true)) { cash.open(parseAmount(d.amount.ifBlank { "0" }) ?: 0.0) }
            is CashDialog.Move -> run(d.copy(busy = true)) { cash.movement(d.kind.api, parseAmount(d.amount) ?: 0.0, d.reason) }
            is CashDialog.Close -> {
                dialog = d.copy(busy = true)
                scope.launch {
                    cash.close(d.count.breakdown, d.count.restAmount ?: 0.0, d.notes)
                        .onSuccess { printed ->
                            dialog = null
                            shown = ReportShown(printed.value, printed.print)
                            refresh()
                        }
                        .onFailure { e -> dialog = d.copy(busy = false, confirm = false, error = e.message ?: "La caisse n'a pas été fermée.") }
                }
            }
        }
    }

    private fun run(busy: CashDialog, block: suspend () -> Result<CashChange>) {
        dialog = busy
        scope.launch {
            block()
                .onSuccess { change ->
                    state = UiState.Success(change.state)
                    show(null)
                    messageChannel.trySend(change.message)
                }
                .onFailure { e -> dialog = back(busy).let { withError(it, e.message ?: "Refusé par la caisse.") } }
        }
    }

    private fun withError(d: CashDialog, message: String): CashDialog = when (d) {
        is CashDialog.Open -> d.copy(error = message, busy = false)
        is CashDialog.Move -> d.copy(error = message, busy = false)
        is CashDialog.Close -> d.copy(error = message, busy = false)
    }

    // ---- X ----

    fun makeX() {
        if (making) return
        making = true
        scope.launch {
            cash.x()
                .onSuccess { printed ->
                    if (hold == null) hold = idle.hold("rapport X")
                    shown = ReportShown(printed.value, printed.print)
                }
                .onFailure { messageChannel.trySend(it.message ?: "Le rapport X n'a pas été fait.") }
            making = false
        }
    }

    fun closeShown() {
        val s = shown ?: return
        if (s.stockBusy) return
        shown = null
        if (dialog == null) {
            hold?.release()
            hold = null
        }
        refresh()
    }

    fun reprintShown() {
        val s = shown ?: return
        if (s.reprinting) return
        shown = s.copy(reprinting = true)
        scope.launch {
            val outcome = cash.reprint(s.report.id).fold({ it }, { PrintOutcome(false, it.message ?: "Le rapport n'a pas été imprimé.") })
            shown = shown?.copy(reprinting = false, print = outcome)
        }
    }

    /** « Envoyer la demande au stock », right after the Z. */
    fun sendStockRequest() {
        val s = shown ?: return
        if (s.stockBusy) return
        shown = s.copy(stockBusy = true)
        scope.launch {
            val outcome = cash.stockRequest(s.report.id).fold({ PrintOutcome(true, it) }, { PrintOutcome(false, it.message ?: "La demande n'est pas partie.") })
            shown = shown?.copy(stockBusy = false, stockResult = outcome)
        }
    }

    // ---- Rapports X / Z ----

    fun chooseReportFilter(type: String?) {
        reportFilter = type
        loadReports()
    }

    fun loadReports() {
        if (reports !is UiState.Success) reports = UiState.Loading
        scope.launch {
            reports = cash.reports(reportFilter).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Rapports indisponibles.", (it as? ApiFailure)?.hint) },
            )
        }
    }

    fun selectReport(id: Long) {
        selected = UiState.Loading
        selectedReprint = null
        scope.launch {
            selected = cash.report(id).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Rapport introuvable.", (it as? ApiFailure)?.hint) },
            )
        }
    }

    fun reprintSelected() {
        val r = (selected as? UiState.Success)?.data ?: return
        if (selectedReprinting) return
        selectedReprinting = true
        scope.launch {
            selectedReprint = cash.reprint(r.id).fold({ it }, { PrintOutcome(false, it.message ?: "Le rapport n'a pas été imprimé.") })
            selectedReprinting = false
        }
    }

    // ---- Dépenses ----

    fun loadExpenses() {
        if (expenses !is UiState.Success) expenses = UiState.Loading
        scope.launch {
            expenses = cash.expenses().fold(
                onSuccess = { list ->
                    if (expenseForm.categoryId == null) expenseForm = expenseForm.copy(categoryId = list.categories.firstOrNull()?.id)
                    UiState.Success(list)
                },
                onFailure = { UiState.Error(it.message ?: "Dépenses indisponibles.", (it as? ApiFailure)?.hint) },
            )
        }
    }

    fun editExpense(block: (ExpenseForm) -> ExpenseForm) {
        if (!expenseForm.busy) expenseForm = block(expenseForm).copy(error = null)
    }

    fun saveExpense() {
        val f = expenseForm
        if (f.busy) return
        val amount = parseAmount(f.amount)
        val date = CounterDate.toIso(f.date)
        val problem = when {
            f.title.isBlank() -> "Indiquez le titre de la dépense."
            f.categoryId == null -> "Choisissez une catégorie."
            amount == null || amount < 0 -> "Indiquez le montant."
            date == null -> "Indiquez la date (jj/mm/aaaa)."
            f.paid && f.method == null -> "Indiquez le mode de paiement d'une dépense payée."
            else -> null
        }
        if (problem != null) {
            expenseForm = f.copy(error = problem)
            return
        }
        expenseForm = f.copy(busy = true)
        scope.launch {
            cash.addExpense(
                ExpenseRequest(
                    title = f.title.trim(), categoryId = f.categoryId!!, amount = amount!!, date = date!!,
                    status = if (f.paid) "paid" else "pending", method = if (f.paid) f.method else null,
                    description = f.description.trim().ifEmpty { null },
                ),
            )
                .onSuccess { (_, message) ->
                    // A fresh form, keeping the category and the method (several of the same kind in a row).
                    expenseForm = ExpenseForm(categoryId = f.categoryId, method = f.method, paid = f.paid)
                    messageChannel.trySend(message)
                    loadExpenses()
                    refresh()
                }
                .onFailure { e -> expenseForm = f.copy(busy = false, error = e.message ?: "Dépense refusée.") }
        }
    }
}
