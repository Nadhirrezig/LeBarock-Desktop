package com.lebarock.lebarock_desktop.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Envelope<T>(
    val status: String = "success",
    val message: String? = null,
    val data: T? = null,
    // The ticket the caisse hands back for print_target "device": the desktop prints it
    // itself on the counter printer (D2), as the handheld does on its Sunmi printer.
    val print: SunmiPrintDto? = null,
    // What the caisse did with a receipt sent with a print_target (2026-11-04).
    @SerialName("print_result") val printResult: PrintResultDto? = null,
)

/**
 * The caisse's answer about a receipt asked with a print_target:
 * status device (the ticket is in `print`, for this device) | printed (network
 * printer of the account's Caisse) | queued (direct printer, the counter PC
 * prints it) | failed (code print_unavailable | print_failed | no_caisse,
 * with the French [message]).
 */
@Serializable
data class PrintResultDto(
    val target: String? = null,
    val status: String? = null,
    val code: String? = null,
    val message: String? = null,
    val printer: String? = null,
)

/** POST /orders/{id}/print: where to print (device | caisse). */
@Serializable
data class PrintRequest(
    @SerialName("print_target") val printTarget: String? = null,
)

/**
 * Server-built receipt to POST to the on-device Sunmi print service, exactly
 * like the web's `sunmi-print` browser-event listener does.
 */
@Serializable
data class SunmiPrintDto(
    val type: String = "sunmi",
    val url: String = "http://localhost:9100/print",
    val token: String = "",
    val receipt: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PingData(
    val product: String? = null,
    @SerialName("restaurant_name") val restaurantName: String? = null,
    val currency: CurrencyDto? = null,
    @SerialName("theme_hex") val themeHex: String? = null,
    @SerialName("server_time") val serverTime: String? = null,
)

@Serializable
data class CurrencyDto(
    val code: String? = null,
    val symbol: String? = null,
    val position: String? = null,
    val decimals: Int = 2,
)

@Serializable
data class AccountDto(
    val id: Long,
    val name: String = "",
    val username: String? = null,
    val email: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    val roles: List<String> = emptyList(),
)

@Serializable
data class LoginRequest(
    @SerialName("user_id") val userId: Long,
    val password: String,
    @SerialName("device_name") val deviceName: String = "altospos-mobile",
)

@Serializable
data class LoginData(
    val token: String,
    val user: UserDto,
)

@Serializable
data class SwitchTerminalRequest(
    @SerialName("order_place_id") val orderPlaceId: Long,
)

@Serializable
data class UserDto(
    val id: Long,
    val name: String = "",
    val username: String? = null,
    val email: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    val roles: List<String> = emptyList(),
    @SerialName("branch_id") val branchId: Long? = null,
    @SerialName("restaurant_id") val restaurantId: Long? = null,
    @SerialName("can_switch_terminal") val canSwitchTerminal: Boolean = false,
    val terminal: TerminalDto? = null,
    // Shared secret of the on-device print service, served by the backend so
    // the embedded print server always matches the web without a rebuild.
    @SerialName("print_token") val printToken: String? = null,
    // What the account may do; null from a caisse older than Plan C.
    val abilities: AbilitiesDto? = null,
)

/**
 * The account's rights, read by the caisse from its roles: a kitchen account
 * (kitchen, no orders) opens the kitchen screen instead of the POS.
 */
@Serializable
data class AbilitiesDto(
    val kitchen: Boolean = false,
    val orders: Boolean = true,
    // C5: may send a round the stock is short of ("Forcer l'envoi").
    @SerialName("force_send") val forceSend: Boolean = false,
    // Rights (caisse, 2026-10-30): waiters touch no money. A caisse that does not send
    // them is older than the rule and keeps the old behaviour (it refuses what it must).
    @SerialName("take_payment") val takePayment: Boolean = true,
    @SerialName("cancel_order") val cancelOrder: Boolean = true,
    // Step 4 (desktop app): the counter's buttons. Null from a caisse older than step 4: the
    // nearest older right decides (see Rights), and the caisse refuses what it must anyway.
    @SerialName("show_orders") val showOrders: Boolean? = null,
    @SerialName("cash_register") val cashRegister: Boolean? = null,
    @SerialName("use_cash_register") val useCashRegister: Boolean? = null,
    @SerialName("apply_discount") val applyDiscount: Boolean? = null,
    @SerialName("give_credit") val giveCredit: Boolean? = null,
    @SerialName("assign_waiter") val assignWaiter: Boolean? = null,
    @SerialName("create_expense") val createExpense: Boolean? = null,
    @SerialName("manage_settings") val manageSettings: Boolean? = null,
)

@Serializable
data class TerminalDto(
    val id: Long,
    val name: String = "",
    @SerialName("menu_id") val menuId: Long? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class MenuDto(
    val id: Long,
    val name: String = "",
    @SerialName("items_count") val itemsCount: Int = 0,
)

@Serializable
data class CategoryDto(
    val id: Long,
    val name: String = "",
    /** The section order set in the caisse's Menu → Tri (null on an older caisse, or not sorted yet). */
    @SerialName("sort_order") val sortOrder: Int? = null,
)

@Serializable
data class VariationDto(
    val id: Long,
    val name: String = "",
    val price: Double = 0.0,
)

@Serializable
data class ModifierOptionDto(
    val id: Long,
    val name: String = "",
    val price: Double = 0.0,
)

@Serializable
data class ModifierGroupDto(
    val id: Long,
    val name: String = "",
    @SerialName("is_required") val isRequired: Boolean = false,
    @SerialName("allow_multiple_selection") val allowMultipleSelection: Boolean = false,
    @SerialName("min_selections") val minSelections: Int = 0,
    @SerialName("max_selections") val maxSelections: Int? = null,
    val options: List<ModifierOptionDto> = emptyList(),
)

@Serializable
data class MenuItemDto(
    val id: Long,
    val name: String = "",
    val description: String? = null,
    val type: String? = null,
    val price: Double = 0.0,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("in_stock") val inStock: Boolean = true,
    @SerialName("menu_id") val menuId: Long? = null,
    val category: CategoryDto? = null,
    val variations: List<VariationDto> = emptyList(),
    @SerialName("modifier_groups") val modifierGroups: List<ModifierGroupDto> = emptyList(),
)

@Serializable
data class ActiveOrderDto(
    val id: Long,
    @SerialName("order_number") val orderNumber: String? = null,
    val status: String? = null,
    val total: Double = 0.0,
    @SerialName("kot_count") val kotCount: Int = 0,
    @SerialName("items_count") val itemsCount: Int = 0,
    @SerialName("number_of_pax") val numberOfPax: Int? = null,
)

@Serializable
data class TableDto(
    val id: Long,
    val code: String = "",
    val seats: Int = 0,
    val status: String = "available",
    @SerialName("active_order") val activeOrder: ActiveOrderDto? = null,
)

@Serializable
data class AreaDto(
    val id: Long,
    val name: String = "",
    val tables: List<TableDto> = emptyList(),
)

@Serializable
data class OrderCountsDto(
    val all: Int = 0,
    val kot: Int = 0,
    val billed: Int = 0,
    @SerialName("payment_due") val paymentDue: Int = 0,
    val paid: Int = 0,
    val canceled: Int = 0,
    val delivered: Int = 0,
)

@Serializable
data class OrderRefDto(
    val id: Long,
    val code: String? = null,
    val name: String? = null,
)

@Serializable
data class OrderDto(
    val id: Long,
    val uuid: String? = null,
    @SerialName("order_number") val orderNumber: String = "",
    @SerialName("order_type") val orderType: String = "dine_in",
    val status: String = "kot",
    @SerialName("order_status") val orderStatus: String? = null,
    @SerialName("date_time") val dateTime: String? = null,
    @SerialName("number_of_pax") val numberOfPax: Int? = null,
    @SerialName("sub_total") val subTotal: Double = 0.0,
    @SerialName("discount_amount") val discountAmount: Double? = null,
    val total: Double = 0.0,
    @SerialName("items_count") val itemsCount: Int = 0,
    val table: OrderRefDto? = null,
    val waiter: OrderRefDto? = null,
    val customer: String? = null,
    val terminal: String? = null,
    // Step 3: taken offline ("Hors ligne : H2-007"), and to check after the sync.
    @SerialName("offline_number") val offlineNumber: String? = null,
    @SerialName("needs_review") val needsReview: Boolean = false,
)

@Serializable
data class OrdersResponse(
    val status: String = "success",
    val counts: OrderCountsDto = OrderCountsDto(),
    val data: List<OrderDto> = emptyList(),
)

@Serializable
data class OrderItemDto(
    val id: Long,
    val name: String = "",
    val variation: String? = null,
    val quantity: Int = 1,
    val price: Double = 0.0,
    val amount: Double = 0.0,
    val note: String? = null,
    @SerialName("kot_id") val kotId: Long? = null,
    val modifiers: List<ModifierOptionDto> = emptyList(),
    // C5: "taken" by the stock, "waiting" for it (the round did not reach the kitchen), null = not sent.
    @SerialName("stock_state") val stockState: String? = null,
) {
    val waitingForStock: Boolean get() = stockState == "waiting"
}

@Serializable
data class KotDto(
    val id: Long,
    val number: String? = null,
    val status: String? = null,
    @SerialName("items_count") val itemsCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class OrderDetailDto(
    val id: Long,
    val uuid: String? = null,
    @SerialName("order_number") val orderNumber: String = "",
    @SerialName("order_type") val orderType: String = "dine_in",
    val status: String = "kot",
    @SerialName("order_status") val orderStatus: String? = null,
    @SerialName("date_time") val dateTime: String? = null,
    @SerialName("number_of_pax") val numberOfPax: Int? = null,
    @SerialName("sub_total") val subTotal: Double = 0.0,
    @SerialName("discount_amount") val discountAmount: Double? = null,
    @SerialName("discount_type") val discountType: String? = null,
    @SerialName("discount_value") val discountValue: Double? = null,
    @SerialName("tip_amount") val tipAmount: Double? = null,
    @SerialName("amount_paid") val amountPaid: Double = 0.0,
    val total: Double = 0.0,
    @SerialName("items_count") val itemsCount: Int = 0,
    val table: OrderRefDto? = null,
    val waiter: OrderRefDto? = null,
    val customer: String? = null,
    val terminal: String? = null,
    val items: List<OrderItemDto> = emptyList(),
    val kots: List<KotDto> = emptyList(),
    // C5: the stock did not take the last round — its French message, and whether this account may force it.
    @SerialName("stock_waiting") val stockWaiting: StockWaitingDto? = null,
    // Step 3: taken offline ("Hors ligne : H2-007"), and to check after the sync.
    @SerialName("offline_number") val offlineNumber: String? = null,
    @SerialName("needs_review") val needsReview: Boolean = false,
)

/** A round the stock did not take (short, or no answer): it waits, nothing went to the kitchen. */
@Serializable
data class StockWaitingDto(
    val message: String = "",
    @SerialName("can_force") val canForce: Boolean = false,
)

// ---- POS order lifecycle (send / add items / bill) ----

@Serializable
data class SendOrderItem(
    @SerialName("menu_item_id") val menuItemId: Long,
    @SerialName("menu_item_variation_id") val menuItemVariationId: Long? = null,
    val quantity: Int,
    @SerialName("modifier_option_ids") val modifierOptionIds: List<Long> = emptyList(),
    val note: String? = null,
)

@Serializable
data class SendOrderRequest(
    @SerialName("table_id") val tableId: Long? = null,
    @SerialName("order_type") val orderType: String = "dine_in",
    @SerialName("number_of_pax") val numberOfPax: Int? = null,
    @SerialName("waiter_id") val waiterId: Long? = null,
    val note: String? = null,
    // kot | bill | bill_print
    val action: String = "kot",
    val items: List<SendOrderItem> = emptyList(),
    // "Forcer l'envoi" (C5): sent even when the stock is short; refused without the right.
    val force: Boolean = false,
    // bill_print only: device | caisse (Réglages → Impression des tickets).
    @SerialName("print_target") val printTarget: String? = null,
)

@Serializable
data class AddItemsRequest(
    val items: List<SendOrderItem> = emptyList(),
    val note: String? = null,
    val force: Boolean = false,
)

/** POST /orders/{id}/send: the lines waiting for stock, sent again (C5). */
@Serializable
data class SendWaitingRequest(
    val force: Boolean = false,
)

/** Summary returned by the order-lifecycle endpoints. */
@Serializable
data class OrderActionDto(
    val id: Long,
    @SerialName("order_number") val orderNumber: String = "",
    val status: String = "kot",
    val total: Double = 0.0,
    @SerialName("sub_total") val subTotal: Double = 0.0,
    @SerialName("amount_paid") val amountPaid: Double = 0.0,
    @SerialName("table_id") val tableId: Long? = null,
)

// ---- Payment ----

@Serializable
data class SplitItemDto(
    @SerialName("order_item_id") val orderItemId: Long,
    val name: String = "",
    val quantity: Int = 1,
    @SerialName("unit_price") val unitPrice: Double = 0.0,
)

/** The customer already on the order, offered for a credit ("à crédit"). */
@Serializable
data class PaymentCustomerDto(
    val id: Long? = null,
    val name: String? = null,
    val phone: String? = null,
)

@Serializable
data class PaymentContextDto(
    @SerialName("order_id") val orderId: Long,
    @SerialName("order_number") val orderNumber: String = "",
    val status: String = "billed",
    val total: Double = 0.0,
    @SerialName("sub_total") val subTotal: Double = 0.0,
    @SerialName("discount_amount") val discountAmount: Double = 0.0,
    @SerialName("tip_amount") val tipAmount: Double = 0.0,
    @SerialName("amount_paid") val amountPaid: Double = 0.0,
    val due: Double = 0.0,
    @SerialName("currency_decimals") val currencyDecimals: Int = 3,
    @SerialName("can_add_tip") val canAddTip: Boolean = false,
    @SerialName("cash_session_required") val cashSessionRequired: Boolean = false,
    @SerialName("cash_session_open") val cashSessionOpen: Boolean = false,
    // The signed-in account's Caisse (its drawer takes the money); null = none.
    val caisse: CaisseDto? = null,
    // cash | card, plus "due" (credit) only for staff allowed to give it.
    @SerialName("payment_methods") val paymentMethods: List<String> = listOf("cash", "card"),
    @SerialName("can_give_credit") val canGiveCredit: Boolean = false,
    val customer: PaymentCustomerDto? = null,
    val items: List<SplitItemDto> = emptyList(),
)

@Serializable
data class CaisseDto(
    val id: Long = 0,
    val name: String = "",
)

@Serializable
data class PaymentRequest(
    // cash | card | due
    val method: String? = null,
    // Cash: the amount handed over. Card: only read for a split part (a card
    // payment on its own always records the whole remaining due). Due: unused.
    val amount: Double? = null,
    // Set when this payment is one part of a split settled part-by-part
    // (even | custom); the backend stamps the order and its SplitOrder rows.
    @SerialName("split_type") val splitType: String? = null,
    // Credit ("due"): an existing customer, or name + phone (quick-create).
    @SerialName("customer_id") val customerId: Long? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    // Where the receipt prints once the check is settled: device | caisse.
    @SerialName("print_target") val printTarget: String? = null,
)

@Serializable
data class PaymentResultDto(
    @SerialName("order_id") val orderId: Long,
    // paid | payment_due (credit) | the open status (billed / kot) when partly paid
    val status: String = "paid",
    val total: Double = 0.0,
    @SerialName("amount_paid") val amountPaid: Double = 0.0,
    val due: Double = 0.0,
    val change: Double = 0.0,
)

@Serializable
data class TipRequest(
    val amount: Double,
    val note: String? = null,
)

/** What POST /orders/{id}/tip answers. */
@Serializable
data class TipResultDto(
    @SerialName("order_id") val orderId: Long,
    val total: Double = 0.0,
    @SerialName("tip_amount") val tipAmount: Double = 0.0,
)

@Serializable
data class BillRequest(
    val print: Boolean = false,
    val force: Boolean = false,
    // With print: device | caisse.
    @SerialName("print_target") val printTarget: String? = null,
)

