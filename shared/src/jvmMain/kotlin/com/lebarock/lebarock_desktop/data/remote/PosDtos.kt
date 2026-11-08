package com.lebarock.lebarock_desktop.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/mobile/pos-context` (caisse, step 4 D3): what the POS cart is made of on the web
 * (`Pos::calculateTotal`): the order types the restaurant takes, the next order number (a preview),
 * the taxes and extra charges of the total, the people an order can be put in the name of, and the
 * currency as the caisse writes it.
 */
@Serializable
data class PosContextDto(
    @SerialName("order_types") val orderTypes: OrderTypesDto = OrderTypesDto(),
    @SerialName("next_order_number") val nextOrderNumber: String? = null,
    val currency: CurrencyDto? = null,
    val taxes: List<TaxDto> = emptyList(),
    val charges: List<ChargeDto> = emptyList(),
    val waiters: List<WaiterDto> = emptyList(),
)

@Serializable
data class OrderTypesDto(
    @SerialName("dine_in") val dineIn: Boolean = true,
    val pickup: Boolean = false,
)

@Serializable
data class TaxDto(
    val id: Long,
    val name: String = "",
    val percent: Double = 0.0,
)

/** A restaurant extra charge: [type] percent (of the total after discount) or fixed, for [orderTypes]. */
@Serializable
data class ChargeDto(
    val id: Long,
    val name: String = "",
    val type: String = "fixed",
    val value: Double = 0.0,
    @SerialName("order_types") val orderTypes: List<String> = emptyList(),
) {
    val isPercent: Boolean get() = type == "percent"
}

@Serializable
data class WaiterDto(
    val id: Long,
    val name: String = "",
)

/** Paramètres → Motifs d'annulation (those for a whole order). */
@Serializable
data class CancelReasonDto(
    val id: Long,
    val reason: String = "",
)

/** POST /orders/{id}/cancel: kept as « Annulée » with its reason (D3, as on the web). */
@Serializable
data class CancelRequest(
    @SerialName("reason_id") val reasonId: Long? = null,
    val reason: String? = null,
)

// ---- D4: a table and its check (caisse MobileCheckController) ----

/** An extra charge on a check (service…): [id] is the check's own charge line. */
@Serializable
data class OrderChargeDto(
    val id: Long,
    val name: String = "",
    val type: String = "fixed",
    val value: Double = 0.0,
    val amount: Double = 0.0,
    val removable: Boolean = false,
)

/** A payment on a check: cash and card can be corrected one into the other. */
@Serializable
data class PaymentLineDto(
    val id: Long,
    val method: String = "cash",
    val amount: Double = 0.0,
    val balance: Double? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("offline_at") val offlineAt: String? = null,
)

@Serializable
data class CustomerDto(
    val id: Long,
    val name: String = "",
    val phone: String? = null,
    val email: String? = null,
)

@Serializable
data class TableIdRequest(@SerialName("table_id") val tableId: Long)

@Serializable
data class SplitLine(val id: Long, val quantity: Int)

@Serializable
data class SplitRequest(val lines: List<SplitLine>)

@Serializable
data class WaiterRequest(@SerialName("waiter_id") val waiterId: Long)

@Serializable
data class CustomerRequest(
    @SerialName("customer_id") val customerId: Long? = null,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
)

@Serializable
data class PaymentMethodRequest(val method: String)

/** The split's answer: the first check as it is now, and the new one. */
@Serializable
data class SplitResponse(
    val status: String = "success",
    val message: String? = null,
    val data: OrderDetailDto? = null,
    @SerialName("new_order") val newOrder: NewOrderRefDto? = null,
)

@Serializable
data class NewOrderRefDto(
    val id: Long,
    @SerialName("order_number") val orderNumber: String = "",
    val total: Double = 0.0,
)
