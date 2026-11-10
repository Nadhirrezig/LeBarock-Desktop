package com.lebarock.lebarock_desktop.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Step 4, D6: the account's Caisse (`/api/mobile/cash-register`) and the expenses (`/api/mobile/expenses`).

/** `GET /cash-register`: the web « Caisse » page's state for the account's own Caisse. */
@Serializable
data class CashRegisterStateDto(
    val enabled: Boolean = false,
    @SerialName("enforce_session") val enforceSession: Boolean = false,
    val caisse: CaisseDto = CaisseDto(),
    val session: CashSessionDto? = null,
    /** The last Z waits for its approval on the web: no new session until then. */
    @SerialName("waiting_approval") val waitingApproval: CashSessionDto? = null,
    val kpi: CashKpiDto? = null,
    val transactions: List<CashTransactionDto> = emptyList(),
    /** The notes and coins counted at the Z (Settings → closing count values), high to low. */
    @SerialName("count_values") val countValues: List<Int> = emptyList(),
    val offline: CashOfflineDto = CashOfflineDto(),
)

@Serializable
data class CashSessionDto(
    val id: Long,
    val code: String = "",
    @SerialName("z_number") val zNumber: Int = 0,
    val status: String = "active",
    @SerialName("opened_at") val openedAt: String? = null,
    @SerialName("opened_by") val openedBy: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("closed_by") val closedBy: String? = null,
    @SerialName("opening_cash") val openingCash: Double = 0.0,
    @SerialName("expected_cash") val expectedCash: Double = 0.0,
    @SerialName("counted_cash") val countedCash: Double? = null,
    val discrepancy: Double? = null,
)

@Serializable
data class CashKpiDto(
    @SerialName("opening_cash") val openingCash: Double = 0.0,
    @SerialName("sales_cash") val salesCash: Double = 0.0,
    @SerialName("refunds_cash") val refundsCash: Double = 0.0,
    @SerialName("cash_in") val cashIn: Double = 0.0,
    @SerialName("cash_out") val cashOut: Double = 0.0,
    @SerialName("safe_drops") val safeDrops: Double = 0.0,
    @SerialName("expected_cash") val expectedCash: Double = 0.0,
    val transactions: Int = 0,
)

@Serializable
data class CashTransactionDto(
    val id: Long,
    val type: String = "",
    val label: String = "",
    val amount: Double = 0.0,
    val decreases: Boolean = false,
    val reason: String? = null,
    val reference: String? = null,
    val user: String? = null,
    @SerialName("occurred_at") val occurredAt: String? = null,
)

@Serializable
data class CashOfflineDto(
    val devices: List<OfflineDeviceDto> = emptyList(),
    val waiting: OfflineWaitingDto = OfflineWaitingDto(),
)

@Serializable
data class OfflineDeviceDto(val label: String = "", val pending: Int = 0, val seen: String? = null, val text: String = "")

@Serializable
data class OfflineWaitingDto(val count: Int = 0, val amount: Double = 0.0)

/** An X or a Z of this Caisse; `ticket` (the Sunmi-shaped JSON) is what prints, shown on the screen. */
@Serializable
data class CashReportDto(
    val id: Long,
    val type: String = "x",
    val number: String? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
    @SerialName("generated_by") val generatedBy: String? = null,
    @SerialName("session_code") val sessionCode: String? = null,
    @SerialName("session_status") val sessionStatus: String? = null,
    val total: Double = 0.0,
    val stock: ReportStockDto? = null,
    val ticket: JsonObject? = null,
    /** After the Z: the session as it closed (counted, expected, difference). */
    val session: CashSessionDto? = null,
)

/** The Z's stock part (stock on): the department's lines and why there are none. */
@Serializable
data class ReportStockDto(
    val ok: Boolean = false,
    @SerialName("till_linked") val tillLinked: Boolean = false,
    val department: String? = null,
    val message: String? = null,
    val lines: List<ReportStockLineDto> = emptyList(),
)

@Serializable
data class ReportStockLineDto(
    val name: String = "",
    val unit: String? = null,
    val sold: Double = 0.0,
    val left: Double = 0.0,
    @SerialName("to_refill") val toRefill: Double = 0.0,
)

@Serializable
data class OpenSessionRequest(@SerialName("opening_cash") val openingCash: Double)

/** cash_in | cash_out | safe_drop */
@Serializable
data class CashMovementRequest(val type: String, val amount: Double, val reason: String)

@Serializable
data class CloseSessionRequest(
    /** value of a note or coin → how many were counted. */
    val breakdown: Map<String, Int>,
    /** « Reste (pièces) »: the amount counted besides the values. */
    val rest: Double? = null,
    val notes: String? = null,
    @SerialName("print_target") val printTarget: String? = null,
)

@Serializable
data class PrintTargetRequest(@SerialName("print_target") val printTarget: String? = null)

/** `GET /expenses`: the day's expenses with the form's lists. */
@Serializable
data class ExpensesResponse(
    val status: String = "success",
    val date: String? = null,
    val total: Double = 0.0,
    val categories: List<ExpenseCategoryDto> = emptyList(),
    val methods: List<ExpenseMethodDto> = emptyList(),
    val data: List<ExpenseDto> = emptyList(),
)

@Serializable
data class ExpenseCategoryDto(val id: Long, val name: String = "")

@Serializable
data class ExpenseMethodDto(val value: String, val label: String = "")

@Serializable
data class ExpenseDto(
    val id: Long,
    val title: String = "",
    val category: String? = null,
    val amount: Double = 0.0,
    val date: String? = null,
    /** pending | paid */
    val status: String = "pending",
    val method: String? = null,
    @SerialName("method_label") val methodLabel: String? = null,
    val description: String? = null,
    @SerialName("from_drawer") val fromDrawer: Boolean = false,
)

/** The web's « Ajouter une dépense » form. */
@Serializable
data class ExpenseRequest(
    @SerialName("expense_title") val title: String,
    @SerialName("expense_category_id") val categoryId: Long,
    val amount: Double,
    @SerialName("expense_date") val date: String,
    @SerialName("payment_status") val status: String,
    @SerialName("payment_method") val method: String? = null,
    val description: String? = null,
)
