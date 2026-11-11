package com.lebarock.lebarock_desktop.data.offline

import com.lebarock.lebarock_desktop.data.remote.ChargeDto
import com.lebarock.lebarock_desktop.data.remote.CurrencyDto
import com.lebarock.lebarock_desktop.data.remote.MenuDto
import com.lebarock.lebarock_desktop.data.remote.MenuItemDto
import com.lebarock.lebarock_desktop.data.remote.TaxDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The « static » part of the copy read into what the offline screens, the bill and the kitchen tickets need (offline
 * step 2, the handheld's OfflineCatalog): the Caisse's menu, tables, stations, receipt settings and labels, taxes and
 * charges, the currency, the « Hors ligne » account. The caisse wrote it; nothing here is invented on the PC.
 *
 * O4a: the copy is the branch's (`format` 2, every Caisse); [wantedCaisseId] picks the one of the session (the signed-in
 * account's Caisse, or the Caisse of the « Hors ligne » PIN typed). A copy of one Caisse (format 1) is read as before.
 */
class OfflineCatalog(root: JsonObject, json: Json, wantedCaisseId: Long? = null) {

    data class TableRef(val id: Long, val code: String, val seats: Int)
    data class AreaRef(val id: Long, val name: String, val tables: List<TableRef>)

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.arr(key: String): List<JsonObject> = (this?.get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject?.str(key: String): String? = this?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject?.long(key: String): Long? = this?.get(key)?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
    private fun JsonObject?.dbl(key: String): Double? = this?.get(key)?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
    private fun JsonObject?.bool(key: String): Boolean = this?.get(key)?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false

    /** The branch's copy (O4a): every Caisse, each with its menu and its « Hors ligne » account. */
    val branchWide: Boolean = root.long("format") == 2L

    private val caisses = root.arr("caisses")
    private val caisse: JsonObject? = if (branchWide) {
        caisses.firstOrNull { it.long("id") == wantedCaisseId } ?: caisses.singleOrNull()
    } else {
        root.obj("caisse")
    }
    val caisseId: Long = caisse.long("id") ?: 0
    val caisseName: String = caisse.str("name").orEmpty()
    val caisseMenuId: Long? = caisse.long("menu_id")
    val deviceCode: String? = root.obj("device").str("code")

    /** The menu items, as GET /api/mobile/menu-items gives them; a Caisse with its own menu sells only that menu. */
    val items: List<MenuItemDto> = (root.obj("menu")?.get("items") as? JsonArray).orEmpty()
        .mapNotNull { runCatching { json.decodeFromJsonElement(MenuItemDto.serializer(), it) }.getOrNull() }
        .filter { !branchWide || caisseMenuId == null || it.menuId == caisseMenuId }

    /** Each item's kitchen station (null: no kitchen, e.g. a drink). */
    val stationOf: Map<Long, Long?> = (root.obj("menu")?.get("items") as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .mapNotNull { item -> item.long("id")?.let { it to item.long("kot_place_id") } }
        .toMap()

    val menus: List<MenuDto> = root.arr("menus").mapNotNull { menu ->
        val id = menu.long("id") ?: return@mapNotNull null
        MenuDto(id = id, name = menu.str("name").orEmpty(), itemsCount = menu["items_count"]?.jsonPrimitive?.intOrNull ?: 0)
    }.filter { menu -> items.any { it.menuId == menu.id } }.ifEmpty {
        // A copy taken before the caisse sent the menus' names.
        items.mapNotNull { it.menuId }.distinct().map { id -> MenuDto(id, if (id == caisseMenuId) caisseName else "Menu", items.count { it.menuId == id }) }
    }

    val stations: Map<Long, String> = root.arr("stations").mapNotNull { s -> s.long("id")?.let { it to s.str("name").orEmpty() } }.toMap()

    val areas: List<AreaRef> = root.arr("areas").map { area ->
        AreaRef(
            id = area.long("id") ?: 0,
            name = area.str("name").orEmpty(),
            tables = area.arr("tables").map { TableRef(it.long("id") ?: 0, it.str("code").orEmpty(), it["seats"]?.jsonPrimitive?.intOrNull ?: 0) },
        )
    }

    fun table(id: Long?): TableRef? = id?.let { wanted -> areas.firstNotNullOfOrNull { a -> a.tables.firstOrNull { it.id == wanted } } }

    private val restaurant = root.obj("restaurant")
    val restaurantName: String = restaurant.str("name").orEmpty()
    val restaurantLines: List<String> = (restaurant?.get("lines") as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    val timezone: String = restaurant.str("timezone") ?: TimeZone.getDefault().id

    private val receipt = restaurant.obj("receipt")
    val showTableNumber = receipt.bool("show_table_number")
    val showTotalGuest = receipt.bool("show_total_guest")
    val showWaiter = receipt.bool("show_waiter")
    val showCustomerName = receipt.bool("show_customer_name")

    private val labels = restaurant.obj("labels")

    /** A French label from the caisse, its ":name" placeholders filled in. */
    fun label(key: String, fallback: String, vararg values: Pair<String, String>): String {
        var text = labels.str(key)?.takeIf { it.isNotBlank() } ?: fallback
        values.forEach { (name, value) -> text = text.replace(":$name", value) }
        return text
    }

    private val currency = restaurant.obj("currency")
    private val symbol = currency.str("symbol").orEmpty()
    private val position = currency.str("position") ?: "left"
    val decimals: Int = maxOf(2, currency?.get("decimals")?.jsonPrimitive?.intOrNull ?: 2)
    private val thousandSeparator = currency.str("thousand_separator").orEmpty()
    private val decimalSeparator = currency.str("decimal_separator") ?: "."

    /** The currency as the POS's cart reads it (the caisse's POS context). */
    val currencyDto: CurrencyDto = CurrencyDto(
        code = currency.str("code"), symbol = symbol.ifEmpty { null }, position = position, decimals = decimals,
        decimalSeparator = decimalSeparator, thousandSeparator = thousandSeparator,
    )

    /** The caisse's currency_format_exact: the symbol only where asked (the total). */
    fun money(amount: Double, withSymbol: Boolean = false): String {
        val fixed = BigDecimal.valueOf(amount).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        val negative = fixed.startsWith("-")
        val digits = fixed.removePrefix("-")
        val whole = digits.substringBefore('.')
        val fraction = digits.substringAfter('.', "")
        val grouped = whole.reversed().chunked(3).joinToString(thousandSeparator).reversed()
        val number = (if (negative) "-" else "") + grouped + (if (fraction.isNotEmpty()) decimalSeparator + fraction else "")
        if (!withSymbol || symbol.isEmpty()) return number
        return when (position) {
            "right" -> number + symbol
            "left_with_space" -> "$symbol $number"
            "right_with_space" -> "$number $symbol"
            else -> symbol + number
        }
    }

    /** "7 nov. 2026 14:32" in the restaurant's time zone (the bill's date). */
    fun billDate(millis: Long): String = SimpleDateFormat("d MMM yyyy HH:mm", Locale.FRENCH)
        .apply { timeZone = TimeZone.getTimeZone(timezone) }.format(Date(millis))

    /** "07/11/2026 14:32" (the kitchen ticket's date). */
    fun kitchenDate(millis: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        .apply { timeZone = TimeZone.getTimeZone(timezone) }.format(Date(millis))

    val taxes: List<TaxDto> = root.arr("taxes").map { TaxDto(it.long("id") ?: 0, it.str("name").orEmpty(), it.dbl("percent") ?: 0.0) }

    val charges: List<ChargeDto> = root.arr("charges").map { charge ->
        ChargeDto(
            id = charge.long("id") ?: 0,
            name = charge.str("name").orEmpty(),
            type = charge.str("type").orEmpty(),
            value = charge.dbl("value") ?: 0.0,
            orderTypes = (charge["order_types"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() },
        )
    }

    private val offline = root.obj("offline")

    /**
     * The Caisse's « Hors ligne » account: everything done offline is recorded as it (client, 2026-11-10: as on the
     * handheld). Null: no offline PIN set at the caisse.
     */
    private val account: JsonObject? = if (branchWide) caisse.obj("offline_account") else offline.obj("account")
    val offlineAccountId: Long? = account.long("id")
    val offlineAccountName: String? = account.str("name")

    /** A check's totals the caisse's way (OrderTotals): taxes and charges on (sub-total − discount), then the tip. */
    data class Totals(
        val subTotal: Double,
        val discount: Double,
        val taxes: List<Pair<String, Double>>,
        val charges: List<Pair<String, Double>>,
        val tip: Double,
        val total: Double,
    )

    fun totals(subTotal: Double, discountType: String?, discountValue: Double?, tip: Double, orderType: String): Totals {
        val sub = round2(subTotal)
        val discount = when (discountType) {
            "percent" -> round2(sub * (discountValue ?: 0.0) / 100)
            "fixed" -> round2(minOf(discountValue ?: 0.0, sub))
            else -> 0.0
        }
        val base = maxOf(0.0, round2(sub - discount))
        val taxRows = taxes.map { "${it.name} (${trim(it.percent)}%)" to round2(it.percent / 100 * base) }
        val chargeRows = charges.filter { orderType in it.orderTypes }.map { charge ->
            val label = if (charge.type == "percent") "${charge.name} (${trim(charge.value)}%)" else charge.name
            label to round2(if (charge.type == "percent") base * charge.value / 100 else charge.value)
        }
        val total = round2(base + taxRows.sumOf { it.second } + chargeRows.sumOf { it.second } + round2(tip))
        return Totals(sub, discount, taxRows, chargeRows, round2(tip), total)
    }

    private fun trim(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    companion object {
        fun round2(value: Double): Double = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()
    }
}
