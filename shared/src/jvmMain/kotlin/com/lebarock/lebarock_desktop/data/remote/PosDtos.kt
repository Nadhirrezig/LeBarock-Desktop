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
