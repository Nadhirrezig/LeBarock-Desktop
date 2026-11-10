package com.lebarock.lebarock_desktop.print

import kotlinx.serialization.Serializable

/**
 * The ticket the caisse builds (SunmiReceiptBuilder for bills and receipts, SunmiReportBuilder for
 * the X / Z: same shape), copied from the handheld app. The caisse owns the content (French labels,
 * the currency as set in its settings, which sections appear); this app owns the paper layout.
 * The handheld's NFC credit is gone; `remainingDue` (a partly paid check) is printed here.
 * (The kitchen ticket below is built here, offline; the payload follows it.)
 */

/**
 * A kitchen ticket built on this PC while the caisse does not answer (offline step 2), one per station, printed on
 * the counter's printer — the handheld's KitchenTicketPrint.
 */
data class KitchenTicketPrint(
    val station: String,
    /** « Table T5 », « À emporter » or « #D1-007 ». */
    val left: String,
    /** « Ticket D1-007 ». */
    val right: String,
    val items: List<KitchenTicketLine>,
    /** « Quantité totale : 3 ». */
    val totalQuantity: String,
    val note: String?,
    val dateTime: String,
)

data class KitchenTicketLine(
    val name: String,
    val quantity: Int,
    /** Variation, options, note. */
    val details: List<String>,
)

@Serializable
data class ReceiptPayload(
    val restaurant: ReceiptRestaurant = ReceiptRestaurant(),
    val order: ReceiptOrder = ReceiptOrder(),
    val details: List<ReceiptRow> = emptyList(),
    val items: List<ReceiptItem> = emptyList(),
    val summary: List<ReceiptRow> = emptyList(),
    val total: ReceiptRow = ReceiptRow(),
    val footer: ReceiptFooter = ReceiptFooter(),
    val qr: String? = null,
)

@Serializable
data class ReceiptRestaurant(
    val name: String = "",
    val lines: List<String> = emptyList(),
)

@Serializable
data class ReceiptOrder(
    val number: String = "",
    val dateTime: String? = null,
)

@Serializable
data class ReceiptRow(
    val label: String = "",
    val value: String = "",
)

@Serializable
data class ReceiptItem(
    val qty: String = "",
    val name: String = "",
    val price: String = "",
    val amount: String = "",
    val variation: String? = null,
    val modifiers: List<String> = emptyList(),
)

@Serializable
data class ReceiptFooter(
    val message: String? = null,
    val payments: List<ReceiptPayment> = emptyList(),
    val change: String? = null,
    val remainingDue: ReceiptRemaining? = null,
)

@Serializable
data class ReceiptPayment(
    val amount: String = "",
    val method: String = "",
    val dateTime: String? = null,
)

@Serializable
data class ReceiptRemaining(
    val label: String = "",
    val amount: String = "",
)
