package com.lebarock.lebarock_desktop.pos

import com.lebarock.lebarock_desktop.data.remote.ChargeDto
import com.lebarock.lebarock_desktop.data.remote.MenuItemDto
import com.lebarock.lebarock_desktop.data.remote.ModifierOptionDto
import com.lebarock.lebarock_desktop.data.remote.SendOrderItem
import com.lebarock.lebarock_desktop.data.remote.TaxDto
import com.lebarock.lebarock_desktop.data.remote.VariationDto
import java.math.BigDecimal
import java.math.RoundingMode

/** Sur place / À emporter, the web POS's order types the restaurant allows (`order_type` on the API). */
enum class OrderType(val api: String, val label: String) {
    DINE_IN("dine_in", "Sur place"),
    PICKUP("pickup", "À emporter"),
}

/**
 * One line of the order being composed (web POS `orderItemList`): an item, its variation and
 * options, a quantity and a note for the kitchen (« sans oignons »). No price on the line: the
 * card shows it and the footer carries the totals, as on the web.
 */
data class CartLine(
    val id: Long,
    val item: MenuItemDto,
    val variation: VariationDto?,
    val modifiers: List<ModifierOptionDto>,
    val quantity: Int,
    val note: String = "",
) {
    val unitPrice: Double get() = (variation?.price ?: item.price) + modifiers.sumOf { it.price }

    /** The web rounds each line to 2 decimals before adding them up (`Pos::calculateTotal`). */
    val amount: Double get() = CartMath.round2(unitPrice * quantity)

    fun sameChoice(item: MenuItemDto, variation: VariationDto?, modifiers: List<ModifierOptionDto>): Boolean =
        this.item.id == item.id && this.variation?.id == variation?.id &&
            this.modifiers.map { it.id }.toSet() == modifiers.map { it.id }.toSet()

    fun toRequest(): SendOrderItem = SendOrderItem(
        menuItemId = item.id,
        menuItemVariationId = variation?.id,
        quantity = quantity,
        modifierOptionIds = modifiers.map { it.id },
        note = note.trim().ifEmpty { null },
    )
}

/** The web POS's « Remise » (`discountType` + `discountValue`): a fixed amount, or a percent of the sub-total. */
data class Discount(val type: String, val value: Double) {
    val isPercent: Boolean get() = type == PERCENT

    /** Percent of the sub-total, or the fixed amount capped at it (`Pos::calculateTotal`). */
    fun amountOn(subTotal: Double): Double =
        if (isPercent) CartMath.round2(subTotal * value / 100.0) else CartMath.round2(minOf(value, subTotal))

    companion object {
        const val FIXED = "fixed"
        const val PERCENT = "percent"
    }
}

data class ChargeLine(val charge: ChargeDto, val amount: Double)
data class TaxLine(val tax: TaxDto, val amount: Double)

/** The cart footer: « Total · n art. », and its breakdown once opened. */
data class CartTotals(
    /** The web counts lines, not quantities (« Total · 3 art. »). */
    val articleCount: Int,
    val subTotal: Double,
    val charges: List<ChargeLine>,
    val taxes: List<TaxLine>,
    val total: Double,
    /** The cart's « Remise » (0 without one). */
    val discount: Double = 0.0,
)

/** The web POS's arithmetic (`Pos::calculateTotal`), kept identical so both screens show the same total. */
object CartMath {

    fun round2(value: Double): Double = BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()

    /** The restaurant's charges for this order type, without those the cashier took off. */
    fun chargesFor(all: List<ChargeDto>, type: OrderType, removed: Set<Long>): List<ChargeDto> =
        all.filter { type.api in it.orderTypes && it.id !in removed }

    /**
     * subtotal = Σ lines (each to 2 decimals); the discount (percent of the subtotal, or fixed capped at it,
     * only with lines); taxes on the total after discount; charges (percent of that total, or fixed) only when
     * the cart has lines.
     */
    fun totals(lines: List<CartLine>, taxes: List<TaxDto>, charges: List<ChargeDto>, discount: Discount? = null): CartTotals {
        val subTotal = round2(lines.sumOf { it.amount })
        val off = if (discount == null || discount.value <= 0.0 || lines.isEmpty()) 0.0 else discount.amountOn(subTotal)
        val discounted = round2(subTotal - off)
        val taxLines = taxes.map { TaxLine(it, round2(it.percent / 100.0 * discounted)) }
        val chargeLines = if (lines.isEmpty()) {
            emptyList()
        } else {
            charges.map { ChargeLine(it, round2(if (it.isPercent) discounted * it.value / 100.0 else it.value)) }
        }
        val total = round2(discounted + taxLines.sumOf { it.amount } + chargeLines.sumOf { it.amount })
        return CartTotals(lines.size, subTotal, chargeLines, taxLines, total, off)
    }
}

/**
 * The cart as the web POS keeps it: the same item with the same variation and options adds to its
 * line; « − » stops at 1 (the bin takes the line off); each line carries its own note.
 */
data class Cart(val lines: List<CartLine> = emptyList(), private val nextId: Long = 1) {

    val isEmpty: Boolean get() = lines.isEmpty()

    fun add(item: MenuItemDto, variation: VariationDto?, modifiers: List<ModifierOptionDto>): Cart {
        val existing = lines.firstOrNull { it.sameChoice(item, variation, modifiers) }
        return if (existing != null) {
            copy(lines = lines.map { if (it.id == existing.id) it.copy(quantity = it.quantity + 1) else it })
        } else {
            copy(lines = lines + CartLine(nextId, item, variation, modifiers, 1), nextId = nextId + 1)
        }
    }

    fun plus(id: Long): Cart = copy(lines = lines.map { if (it.id == id) it.copy(quantity = it.quantity + 1) else it })

    fun minus(id: Long): Cart = copy(lines = lines.map { if (it.id == id) it.copy(quantity = maxOf(1, it.quantity - 1)) else it })

    fun remove(id: Long): Cart = copy(lines = lines.filterNot { it.id == id })

    fun note(id: Long, note: String): Cart = copy(lines = lines.map { if (it.id == id) it.copy(note = note.trim()) else it })

    fun cleared(): Cart = Cart(nextId = nextId)

    fun requestItems(): List<SendOrderItem> = lines.map { it.toRequest() }
}

/** The menu's sections in the caisse's Menu → Tri order (not sorted first, then by id), as on the web and the handheld. */
data class MenuSection(val id: Long, val name: String, val itemCount: Int)

fun sectionsOf(items: List<MenuItemDto>): List<MenuSection> =
    items.mapNotNull { it.category }
        .groupBy { it.id }
        .values
        .sortedWith(compareBy<List<com.lebarock.lebarock_desktop.data.remote.CategoryDto>, Int?>(nullsFirst(naturalOrder())) { it.first().sortOrder }.thenBy { it.first().id })
        .map { cats -> MenuSection(cats.first().id, cats.first().name, cats.size) }

/** Search over every section of the open menu (item name), ignoring case and accents, as the web's search box. */
fun searchItems(items: List<MenuItemDto>, query: String): List<MenuItemDto> {
    val q = fold(query.trim())
    if (q.isEmpty()) return items
    return items.filter { fold(it.name).contains(q) }
}

internal fun fold(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** The options picked for an item, checked like the web's modifier window (required groups, minimum). */
object ModifierRules {
    /** The first group whose choice is not enough, or null when the pick can go to the cart. */
    fun missing(item: MenuItemDto, selected: Map<Long, Set<Long>>): com.lebarock.lebarock_desktop.data.remote.ModifierGroupDto? =
        item.modifierGroups.firstOrNull { group ->
            val count = selected[group.id].orEmpty().size
            (group.isRequired && count == 0) || count < minOf(group.minSelections, group.options.size)
        }

    /** One click on an option: single choice replaces, multiple adds up to the maximum. */
    fun toggle(group: com.lebarock.lebarock_desktop.data.remote.ModifierGroupDto, optionId: Long, current: Set<Long>): Set<Long> = when {
        optionId in current -> current - optionId
        group.allowMultipleSelection -> {
            val max = group.maxSelections
            if (max != null && current.size >= max) current else current + optionId
        }
        else -> setOf(optionId)
    }
}

/** A table as the caisse names it (C1): a digits-only code reads « T12 », a named table keeps its name. */
fun tableLabel(code: String): String = if (code.isNotEmpty() && code.all(Char::isDigit)) "T$code" else code
