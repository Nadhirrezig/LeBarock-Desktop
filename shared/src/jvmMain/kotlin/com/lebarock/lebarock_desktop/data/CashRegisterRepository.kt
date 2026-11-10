package com.lebarock.lebarock_desktop.data

import com.lebarock.lebarock_desktop.data.remote.CashMovementRequest
import com.lebarock.lebarock_desktop.data.remote.CashRegisterStateDto
import com.lebarock.lebarock_desktop.data.remote.CashReportDto
import com.lebarock.lebarock_desktop.data.remote.CloseSessionRequest
import com.lebarock.lebarock_desktop.data.remote.ExpenseDto
import com.lebarock.lebarock_desktop.data.remote.ExpenseRequest
import com.lebarock.lebarock_desktop.data.remote.ExpensesResponse
import com.lebarock.lebarock_desktop.data.remote.OpenSessionRequest
import com.lebarock.lebarock_desktop.data.remote.PrintTargetRequest
import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.print.TicketPrinter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** A change on the Caisse: its state now, and the caisse's French message. */
data class CashChange(val state: CashRegisterStateDto, val message: String)

/**
 * The account's Caisse (D6), the web « Caisse » page on `/api/mobile/cash-register`: the session (open with a float,
 * cash in / out / safe drop), the X and the Z printed where Réglages → « Impression des tickets » says, this Caisse's
 * past reports, the Z's replenishment request; and the expenses (the web's form, the day's list).
 */
class CashRegisterRepository(
    private val caisse: CaisseRepository,
    private val settings: SettingsStore,
    printer: TicketPrinter,
    json: Json,
) {
    private val outcomes = PrintOutcomes(printer, json)
    private val target: String get() = settings.current.printer.target.apiValue

    val currency: CurrencyInfo get() = settings.current.currency

    suspend fun state(): Result<CashRegisterStateDto> = caisse.call { caisse.api().cashRegister().data ?: throw unexpected() }

    /** « Ouvrir la caisse » with its float. */
    suspend fun open(openingCash: Double): Result<CashChange> = caisse.call {
        val e = caisse.api().openSession(OpenSessionRequest(openingCash))
        CashChange(e.data ?: throw unexpected(), e.message ?: "Session de caisse ouverte.")
    }

    /** cash_in | cash_out | safe_drop, with its reason. */
    suspend fun movement(type: String, amount: Double, reason: String): Result<CashChange> = caisse.call {
        val e = caisse.api().cashMovement(CashMovementRequest(type, amount, reason.trim()))
        CashChange(e.data ?: throw unexpected(), e.message ?: "Mouvement enregistré.")
    }

    /** « Rapport X », printed. */
    suspend fun x(): Result<Printed<CashReportDto>> = caisse.call {
        val e = caisse.api().xReport(PrintTargetRequest(target))
        Printed(e.data ?: throw unexpected(), outcomes.of(e))
    }

    /** « Fermer la caisse » (Z): the count by value and a note; printed; the session waits for its approval. */
    suspend fun close(breakdown: Map<Int, Int>, notes: String?): Result<Printed<CashReportDto>> = caisse.call {
        val counted = breakdown.filterValues { it > 0 }.mapKeys { it.key.toString() }
        val e = caisse.api().closeSession(CloseSessionRequest(counted, notes?.trim()?.ifEmpty { null }, target))
        Printed(e.data ?: throw unexpected(), outcomes.of(e))
    }

    /** This Caisse's X / Z, newest first; [type] x | z | null. */
    suspend fun reports(type: String? = null): Result<List<CashReportDto>> = caisse.call { caisse.api().cashReports(type).data.orEmpty() }

    /** One report with its ticket. */
    suspend fun report(id: Long): Result<CashReportDto> = caisse.call { caisse.api().cashReport(id).data ?: throw unexpected() }

    /** « Réimprimer »: where it printed, or why not. */
    suspend fun reprint(id: Long): Result<PrintOutcome> = caisse.call {
        outcomes.of(caisse.api().printCashReport(id, PrintTargetRequest(target)))
            ?: PrintOutcome(true, "Rapport envoyé à l'imprimante de la caisse.")
    }

    /** « Envoyer la demande au stock » after the Z: the stock's French answer (a refusal fails with it). */
    suspend fun stockRequest(reportId: Long): Result<String> = caisse.call {
        val e = caisse.api().stockRequest(reportId)
        e.message ?: e.data?.get("message")?.jsonPrimitive?.content ?: "Demande envoyée au stock."
    }

    // ---- Dépenses ----

    suspend fun expenses(): Result<ExpensesResponse> = caisse.call { caisse.api().expenses() }

    suspend fun addExpense(request: ExpenseRequest): Result<Pair<ExpenseDto, String>> = caisse.call {
        val e = caisse.api().addExpense(request)
        (e.data ?: throw unexpected()) to (e.message ?: "Dépense enregistrée.")
    }

    private fun unexpected() = ApiFailure("Réponse inattendue de la caisse.", FailureKind.NOT_A_CAISSE)
}
