package com.lebarock.lebarock_desktop.pos

import com.lebarock.lebarock_desktop.data.remote.PaymentContextDto
import com.lebarock.lebarock_desktop.data.remote.PaymentRequest
import com.lebarock.lebarock_desktop.data.remote.SplitItemDto
import com.lebarock.lebarock_desktop.data.remote.SplitPartDto
import com.lebarock.lebarock_desktop.data.remote.SplitPartItemDto
import com.lebarock.lebarock_desktop.data.remote.SplitPaymentDto
import com.lebarock.lebarock_desktop.pos.CartMath.round2

/** The web window's methods (`Order/AddPayment`): Espèces, Carte (TPE), À crédit (« Give Credit »). */
enum class PayMethod(val api: String, val label: String) {
    CASH("cash", "Espèces"),
    CARD("card", "Carte (TPE)"),
    CREDIT("due", "À crédit"),
}

/** How the bill is divided (web: « Partage égal », « Montants libres », « Par article »). */
enum class SplitType(val api: String, val label: String, val hint: String) {
    EQUAL("equal", "Partage égal", "Diviser à parts égales"),
    CUSTOM("custom", "Montants libres", "Diviser par montant"),
    ITEMS("items", "Par article", "Diviser par plat"),
}

/** One part of an equal or custom split: its amount (typed for a custom one) and how it is paid. */
data class SplitPart(val method: PayMethod = PayMethod.CASH, val amountText: String = "")

/** One part of a split by article: the articles it takes (order line id → quantity) and how it is paid. */
data class ItemsPart(val id: Int, val method: PayMethod = PayMethod.CASH, val lines: Map<Long, Int> = emptyMap())

/** À crédit: the customer picked from the search, or a new one typed (name + phone, same phone = same customer). */
data class CreditCustomer(val pickedId: Long? = null, val name: String = "", val phone: String = "", val search: String = "")

/**
 * The web's payment window as data (`AddPayment`): « Paiement complet » with a method, the amount on the keypad
 * (cash only: the TPE and the credit always take the whole due, the amount locked) and the change; or « Partager
 * l'addition » in equal parts, free amounts or by article, every part cash or card. Every rule here is the web's;
 * the caisse checks them again (OrderPayments).
 */
data class PaymentForm(
    val context: PaymentContextDto,
    val split: Boolean = false,
    val method: PayMethod = PayMethod.CASH,
    /** The cash amount as typed; starts on the whole due (web `paymentAmount = dueAmount`). */
    val amountText: String = plainAmount(context.due),
    /** The next key replaces the amount shown (it was filled in, not typed). */
    val freshAmount: Boolean = true,
    val credit: CreditCustomer = CreditCustomer(
        pickedId = context.customer?.id,
        name = context.customer?.name.orEmpty(),
        phone = context.customer?.phone.orEmpty(),
    ),
    val splitType: SplitType? = null,
    val equalParts: List<SplitPart> = listOf(SplitPart(), SplitPart()),
    val customParts: List<SplitPart> = listOf(SplitPart(), SplitPart()),
    val itemParts: List<ItemsPart> = listOf(ItemsPart(1)),
    val activeItemsPart: Int = 1,
) {
    val due: Double get() = round2(context.due)

    val canCredit: Boolean get() = context.canGiveCredit && PayMethod.CREDIT.api in context.paymentMethods

    val methods: List<PayMethod> get() = if (canCredit) PayMethod.entries.toList() else listOf(PayMethod.CASH, PayMethod.CARD)

    /** The TPE and the credit take the whole remaining amount: the field and the keypad cannot change it. */
    val amountLocked: Boolean get() = !split && method != PayMethod.CASH

    val amount: Double get() = if (amountLocked) due else round2(amountText.toDoubleOrNull() ?: 0.0)

    /** Cash above the due: the change given back. */
    val change: Double get() = if (method == PayMethod.CASH && amount > due) round2(amount - due) else 0.0

    /** What stays to pay after this payment (a partial payment keeps the check open). */
    val balance: Double get() = if (amount < due) round2(due - amount) else 0.0

    // ---- « Paiement complet » ----

    fun choose(m: PayMethod): PaymentForm =
        if (m == PayMethod.CREDIT && !canCredit) this else copy(method = m, amountText = plainAmount(due), freshAmount = true)

    /** A keypad key: a digit or « . » (two decimals at most). */
    fun key(k: Char): PaymentForm {
        if (amountLocked) return this
        val current = if (freshAmount) "" else amountText
        if (k == '.') {
            if (current.contains('.')) return this
            return copy(amountText = if (current.isEmpty()) "0." else "$current.", freshAmount = false)
        }
        if (!k.isDigit()) return this
        val decimals = current.substringAfter('.', "")
        if (current.contains('.') && decimals.length >= 2) return this
        val next = if (current == "0") k.toString() else current + k
        return copy(amountText = next, freshAmount = false)
    }

    fun clearAmount(): PaymentForm = if (amountLocked) this else copy(amountText = "", freshAmount = false)

    fun backspace(): PaymentForm = if (amountLocked) this else copy(amountText = (if (freshAmount) "" else amountText).dropLast(1), freshAmount = false)

    /** « Montant exact »: the whole due. */
    fun exact(): PaymentForm = if (amountLocked) this else copy(amountText = plainAmount(due), freshAmount = true)

    /** A note given by the guest (web quick amounts 50 / 100 / 500 / 1000). */
    fun quick(value: Double): PaymentForm = if (amountLocked) this else copy(amountText = plainAmount(value), freshAmount = true)

    /** The amount typed straight in the field (digits and one « . »). */
    fun typed(text: String): PaymentForm {
        if (amountLocked) return this
        val clean = text.filter { it.isDigit() || it == '.' }
        val dot = clean.indexOf('.')
        val fixed = if (dot < 0) clean else clean.substring(0, dot + 1) + clean.substring(dot + 1).replace(".", "").take(2)
        return copy(amountText = fixed, freshAmount = false)
    }

    fun creditChanged(c: CreditCustomer): PaymentForm = copy(credit = c)

    // ---- « Partager l'addition » ----

    fun splitMode(on: Boolean): PaymentForm =
        copy(split = on, splitType = null, method = PayMethod.CASH, amountText = plainAmount(due), freshAmount = true,
            equalParts = listOf(SplitPart(), SplitPart()), customParts = listOf(SplitPart(), SplitPart()),
            itemParts = listOf(ItemsPart(1)), activeItemsPart = 1)

    fun splitType(type: SplitType?): PaymentForm =
        copy(splitType = type, equalParts = listOf(SplitPart(), SplitPart()), customParts = listOf(SplitPart(), SplitPart()),
            itemParts = listOf(ItemsPart(1)), activeItemsPart = 1)

    /** Equal parts of what is left to pay (part of it may be settled): the cents left over go on the first part. */
    val equalAmounts: List<Double>
        get() {
            val n = equalParts.size.coerceAtLeast(1)
            val base = round2(due / n)
            val remainder = round2(due - base * n)
            return List(n) { i -> if (i == 0) round2(base + remainder) else base }
        }

    fun addEqualPart(): PaymentForm = copy(equalParts = equalParts + SplitPart())
    fun removeEqualPart(i: Int): PaymentForm = if (equalParts.size > 2) copy(equalParts = equalParts.filterIndexed { j, _ -> j != i }) else this
    fun equalMethod(i: Int, m: PayMethod): PaymentForm = copy(equalParts = equalParts.mapIndexed { j, p -> if (j == i) p.copy(method = m) else p })

    fun addCustomPart(): PaymentForm = copy(customParts = customParts + SplitPart())
    fun removeCustomPart(i: Int): PaymentForm = if (customParts.size > 2) copy(customParts = customParts.filterIndexed { j, _ -> j != i }) else this
    fun customMethod(i: Int, m: PayMethod): PaymentForm = copy(customParts = customParts.mapIndexed { j, p -> if (j == i) p.copy(method = m) else p })
    fun customAmount(i: Int, text: String): PaymentForm {
        val clean = text.filter { it.isDigit() || it == '.' }
        return copy(customParts = customParts.mapIndexed { j, p -> if (j == i) p.copy(amountText = clean) else p })
    }

    val customTotal: Double get() = round2(customParts.sumOf { it.amountText.toDoubleOrNull() ?: 0.0 })
    val customBalance: Double get() = round2((due - customTotal).coerceAtLeast(0.0))
    val customExcess: Double get() = round2((customTotal - due).coerceAtLeast(0.0))

    // By article: the window's unit prices (base after discount + tax, charges and tip shares), from the caisse.

    val items: List<SplitItemDto> get() = context.items

    fun assigned(lineId: Long): Int = itemParts.sumOf { it.lines[lineId] ?: 0 }

    fun remaining(line: SplitItemDto): Int = (line.quantity - assigned(line.orderItemId)).coerceAtLeast(0)

    fun partTotal(part: ItemsPart): Double =
        round2(part.lines.entries.sumOf { (id, qty) -> (items.firstOrNull { it.orderItemId == id }?.unitPrice ?: 0.0) * qty })

    val itemsTotal: Double get() = round2(itemParts.sumOf { partTotal(it) })

    val activePart: ItemsPart? get() = itemParts.firstOrNull { it.id == activeItemsPart }

    fun selectItemsPart(id: Int): PaymentForm = if (itemParts.any { it.id == id }) copy(activeItemsPart = id) else this

    fun addItemsPart(): PaymentForm {
        val id = (itemParts.maxOfOrNull { it.id } ?: 0) + 1
        return copy(itemParts = itemParts + ItemsPart(id), activeItemsPart = id)
    }

    fun removeItemsPart(id: Int): PaymentForm {
        if (itemParts.size <= 1) return this
        val left = itemParts.filterNot { it.id == id }
        return copy(itemParts = left, activeItemsPart = if (activeItemsPart == id) left.first().id else activeItemsPart)
    }

    fun itemsMethod(id: Int, m: PayMethod): PaymentForm = copy(itemParts = itemParts.map { if (it.id == id) it.copy(method = m) else it })

    /** « +1 » / « Tout »: [count] more of a line in the active part, within what no part has taken yet. */
    fun addToPart(line: SplitItemDto, count: Int): PaymentForm {
        val part = activePart ?: return this
        val take = minOf(count, remaining(line))
        if (take < 1) return this
        val lines = part.lines + (line.orderItemId to ((part.lines[line.orderItemId] ?: 0) + take))
        return copy(itemParts = itemParts.map { if (it.id == part.id) it.copy(lines = lines) else it })
    }

    /** « − » on a line of the active part; at 0 it leaves the part. */
    fun takeFromPart(lineId: Long): PaymentForm {
        val part = activePart ?: return this
        val now = part.lines[lineId] ?: return this
        val lines = if (now > 1) part.lines + (lineId to now - 1) else part.lines - lineId
        return copy(itemParts = itemParts.map { if (it.id == part.id) it.copy(lines = lines) else it })
    }

    fun removeFromPart(lineId: Long): PaymentForm {
        val part = activePart ?: return this
        return copy(itemParts = itemParts.map { if (it.id == part.id) it.copy(lines = part.lines - lineId) else it })
    }

    // ---- « Valider » ----

    /** What stops « Valider » before asking the caisse (the caisse checks again), or null. */
    val problem: String?
        get() = when {
            due < 0.01 -> "Ce ticket est déjà réglé."
            !split && method == PayMethod.CASH && amount < 0.01 -> "Indiquez le montant reçu."
            !split && method == PayMethod.CREDIT && credit.pickedId == null && (credit.name.isBlank() || credit.phone.isBlank()) ->
                "Pour un paiement à crédit, indiquez le client (nom et téléphone)."
            split && splitType == null -> "Choisissez comment partager l'addition."
            split && splitType == SplitType.CUSTOM && kotlin.math.abs(customTotal - due) >= 0.0101 ->
                "La somme des parts ne correspond pas au montant dû."
            split && splitType == SplitType.ITEMS && itemsTotal < 0.01 -> "Ajoutez des articles à une part."
            else -> null
        }

    /** The request the web's « Valider » sends (`AddPayment::submitForm`). */
    fun request(): PaymentRequest = when {
        split && splitType == SplitType.EQUAL -> PaymentRequest(split = SplitPaymentDto("equal", equalParts.zip(equalAmounts) { p, a -> SplitPartDto(p.method.api, amount = a) }))
        split && splitType == SplitType.CUSTOM -> PaymentRequest(split = SplitPaymentDto("custom", customParts.map { SplitPartDto(it.method.api, amount = round2(it.amountText.toDoubleOrNull() ?: 0.0)) }))
        split && splitType == SplitType.ITEMS -> PaymentRequest(split = SplitPaymentDto("items", itemParts.filter { it.lines.isNotEmpty() }.map { part ->
            SplitPartDto(part.method.api, items = part.lines.map { (id, qty) -> SplitPartItemDto(id, qty, items.firstOrNull { it.orderItemId == id }?.unitPrice ?: 0.0) })
        }))
        method == PayMethod.CREDIT -> if (credit.pickedId != null) {
            PaymentRequest(method = "due", customerId = credit.pickedId)
        } else {
            PaymentRequest(method = "due", customerName = credit.name.trim(), customerPhone = credit.phone.trim())
        }
        method == PayMethod.CARD -> PaymentRequest(method = "card")
        else -> PaymentRequest(method = "cash", amount = amount)
    }

    companion object {
        /** The web's quick notes. */
        val QUICK = listOf(50.0, 100.0, 500.0, 1000.0)

        /** An amount as the field shows it: no trailing zeros (45.5, 12, 0.75). */
        fun plainAmount(value: Double): String {
            val v = round2(value)
            return java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
        }
    }
}

/** The web's tip window: « % » or an amount, quick 5 / 10 / 15 / 20, a custom amount and a note. */
data class TipForm(
    /** The check's total before the tip. */
    val baseTotal: Double,
    val percentMode: Boolean = true,
    val amountText: String = "",
    val note: String = "",
    val picked: Double? = null,
) {
    val amount: Double get() = round2(amountText.toDoubleOrNull() ?: 0.0)
    val newTotal: Double get() = round2(baseTotal + amount)

    /** A quick button: a percent of the total, or an amount (web `setTip`). */
    fun quick(value: Double): TipForm =
        copy(picked = value, amountText = PaymentForm.plainAmount(if (percentMode) round2(baseTotal * value / 100.0) else value))

    fun typed(text: String): TipForm = copy(amountText = text.filter { it.isDigit() || it == '.' }, picked = null)

    companion object {
        val QUICK = listOf(5.0, 10.0, 15.0, 20.0)
    }
}
