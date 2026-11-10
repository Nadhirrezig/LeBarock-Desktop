package com.lebarock.lebarock_desktop.print

/** One thing the printer does; device-agnostic so the layout is tested without a printer. */
sealed interface PrintOp {
    data class Text(
        val text: String,
        val align: Align = Align.LEFT,
        val bold: Boolean = false,
        val big: Boolean = false,
    ) : PrintOp

    data class Feed(val lines: Int = 1) : PrintOp
    data object Cut : PrintOp
    data class Qr(val data: String) : PrintOp
}

enum class Align { LEFT, CENTER, RIGHT }

/**
 * The paper layout of the caisse's ticket JSON, ported from the handheld app (itself a port of
 * the caisse's ESC/POS receipt): [width] columns, 32 on 58 mm paper and 48 on 80 mm. Bills,
 * receipts and the X / Z all come through here (the X / Z use the same JSON shape).
 */
class ReceiptRenderer(private val width: Int = 48) {

    fun render(r: ReceiptPayload): List<PrintOp> {
        val ops = mutableListOf<PrintOp>()

        // Header: the restaurant's name large, its address / phone / tax ids underneath.
        if (r.restaurant.name.isNotBlank()) {
            wrap(r.restaurant.name, width / 2).forEach { ops += PrintOp.Text(it, Align.CENTER, bold = true, big = true) }
        }
        r.restaurant.lines.forEach { line -> wrap(line, width).forEach { ops += PrintOp.Text(it, Align.CENTER) } }
        ops += separator()

        // Number + date on one line when they fit; then the "Label: value" details.
        // An order's number reads « Ticket #42 »; an X / Z comes titled (« RAPPORT X », « RAPPORT Z N°3 »): kept as is.
        val number = r.order.number.trim()
        val orderNo = when {
            number.isBlank() -> ""
            number.all { it.isDigit() } -> "Ticket #$number"
            else -> number
        }
        val date = r.order.dateTime.orEmpty()
        when {
            orderNo.isEmpty() && date.isEmpty() -> Unit
            date.isEmpty() -> ops += PrintOp.Text(orderNo, bold = true)
            orderNo.isEmpty() -> ops += PrintOp.Text(date)
            orderNo.length + date.length + 1 <= width -> ops += PrintOp.Text(orderNo.padEnd(width - date.length) + date, bold = true)
            else -> {
                ops += PrintOp.Text(orderNo, bold = true)
                ops += PrintOp.Text(date)
            }
        }
        r.details.forEach { d -> wrap(if (d.value.isEmpty()) d.label else "${d.label}: ${d.value}", width).forEach { ops += PrintOp.Text(it) } }
        if (orderNo.isNotEmpty() || date.isNotEmpty() || r.details.isNotEmpty()) ops += separator()

        // Lines: "Nx Name ........ amount"; a long name wraps under a hanging indent.
        if (r.items.isNotEmpty()) {
            r.items.forEach { renderItem(ops, it) }
            ops += separator()
        }

        // Summary rows; a row with no value is a section title (the X / Z use them).
        r.summary.forEach { row ->
            if (row.value.isEmpty()) ops += PrintOp.Text(row.label, bold = true) else ops += row(row.label, row.value)
        }
        if (r.total.label.isNotBlank() || r.total.value.isNotBlank()) {
            ops += heavyRule()
            ops += totalLine(r.total.label, r.total.value)
            ops += heavyRule()
        }

        // What was paid, the change handed back, what is left to pay.
        val f = r.footer
        if (f.payments.isNotEmpty() || f.change != null || f.remainingDue != null) {
            f.payments.forEach { p -> ops += row("Payé · ${p.method}".trim(), p.amount) }
            f.change?.let { ops += row("Rendu", it, bold = true) }
            f.remainingDue?.let { ops += row(it.label, it.amount, bold = true) }
            ops += separator()
        }

        f.message?.takeIf { it.isNotBlank() }?.let { msg -> wrap(msg, width).forEach { ops += PrintOp.Text(it, Align.CENTER) } }
        r.qr?.takeIf { it.isNotBlank() }?.let { ops += PrintOp.Qr(it) }

        ops += PrintOp.Feed(3)
        ops += PrintOp.Cut
        return ops
    }

    /**
     * A kitchen ticket printed on this PC while the caisse does not answer (offline step 2), the handheld's layout (a
     * port of the caisse's compact ticket, PrinterSetting::printCompactKot): the station big at the top, « HORS LIGNE »,
     * table and number, each dish big with its quantity, its details small underneath, no prices; the total quantity,
     * the note, the time.
     */
    fun renderKitchen(t: KitchenTicketPrint): List<PrintOp> {
        val ops = mutableListOf<PrintOp>()
        val bigCols = width / 2

        ops += PrintOp.Text(t.station.uppercase(), Align.CENTER, bold = true, big = true)
        ops += PrintOp.Text("HORS LIGNE", Align.CENTER, bold = true)
        ops += PrintOp.Feed(1)
        ops += PrintOp.Text(padBetween(t.left, t.right, width))
        ops += separator()

        t.items.forEach { item ->
            val qty = "x${item.quantity}"
            val nameLines = wrap(item.name, maxOf(1, bigCols - qty.length - 1))
            ops += PrintOp.Text(padBetween(nameLines.first(), qty, bigCols), bold = true, big = true)
            nameLines.drop(1).forEach { ops += PrintOp.Text(it, bold = true, big = true) }
            item.details.forEach { detail -> wrap(detail, width - 5).forEachIndexed { i, part -> ops += PrintOp.Text((if (i == 0) "   - " else "     ") + part) } }
        }

        ops += separator()
        ops += PrintOp.Text(t.totalQuantity, bold = true)
        t.note?.takeIf { it.isNotBlank() }?.let { note -> wrap(note, width).forEach { ops += PrintOp.Text(it, bold = true) } }
        ops += PrintOp.Text(t.dateTime)
        ops += PrintOp.Feed(3)
        ops += PrintOp.Cut
        return ops
    }

    private fun padBetween(left: String, right: String, cols: Int): String {
        val gap = maxOf(1, cols - left.length - right.length)
        return left + " ".repeat(gap) + right
    }

    /** The test ticket of Réglages → "Imprimer un test". */
    fun renderTest(restaurant: String, lines: List<Pair<String, String>>): List<PrintOp> = buildList {
        add(PrintOp.Text("AltosPOS", Align.CENTER, bold = true, big = true))
        if (restaurant.isNotBlank()) add(PrintOp.Text(restaurant, Align.CENTER, bold = true))
        add(PrintOp.Text("Test d'impression", Align.CENTER))
        add(separator())
        lines.forEach { (label, value) -> add(row(label, value)) }
        add(separator())
        add(PrintOp.Text("Accents : é è ê à â ç ô û ï €"))
        add(PrintOp.Text("Majuscules : É È À Ç Ô Û"))
        add(row("Exemple", "8.630 DT"))
        add(heavyRule())
        add(totalLine("Total", "8.630 DT"))
        add(heavyRule())
        add(PrintOp.Text("1".padEnd(width - 1, '.') + "|"))
        add(PrintOp.Text("La ligne ci-dessus touche les deux bords.", Align.CENTER))
        add(PrintOp.Text("Imprimante opérationnelle", Align.CENTER, bold = true))
        add(PrintOp.Feed(3))
        add(PrintOp.Cut)
    }

    private fun renderItem(ops: MutableList<PrintOp>, item: ReceiptItem) {
        val prefix = if (item.qty.isBlank()) "" else "${item.qty}x "
        val indent = " ".repeat(prefix.length.coerceAtMost(6))
        val nameLines = wrap(item.name, (width - prefix.length).coerceAtLeast(8))

        if (nameLines.size == 1 && prefix.length + nameLines[0].length + 1 + item.amount.length <= width) {
            ops += PrintOp.Text((prefix + nameLines[0]).padEnd(width - item.amount.length) + item.amount)
        } else {
            ops += PrintOp.Text(prefix + nameLines.first())
            nameLines.drop(1).forEach { ops += PrintOp.Text(indent + it) }
            if (item.amount.isNotBlank()) ops += PrintOp.Text(item.amount.padStart(width))
        }
        // The unit price only says something when more than one was ordered.
        if (item.qty != "1" && item.price.isNotBlank()) ops += PrintOp.Text(indent + "@ ${item.price}")
        item.variation?.takeIf { it.isNotBlank() }?.let { ops += PrintOp.Text(indent + "• $it") }
        item.modifiers.forEach { ops += PrintOp.Text(indent + "- $it") }
    }

    /** TOTAL double size when it fits half the columns, else bold. */
    private fun totalLine(label: String, value: String): PrintOp.Text {
        val lbl = label.uppercase()
        val bigCols = width / 2
        return if (lbl.length + value.length + 1 <= bigCols) {
            PrintOp.Text(lbl.padEnd(bigCols - value.length) + value, bold = true, big = true)
        } else {
            row(lbl, value, bold = true)
        }
    }

    private fun separator() = PrintOp.Text("-".repeat(width))

    private fun heavyRule() = PrintOp.Text("=".repeat(width))

    /** Label left, value right; the label is cut short rather than overflowing. */
    private fun row(label: String, value: String, bold: Boolean = false): PrintOp.Text {
        val maxLabel = (width - value.length - 1).coerceAtLeast(1)
        val lbl = if (label.length > maxLabel) label.take(maxLabel) else label
        return PrintOp.Text(lbl.padEnd(width - value.length) + value, bold = bold)
    }

    /** Word-wraps to [max] columns, hard-breaking words longer than a line. */
    internal fun wrap(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        text.split(' ').forEach { word ->
            var w = word
            while (w.length > max) {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                }
                lines += w.take(max)
                w = w.drop(max)
            }
            when {
                current.isEmpty() -> current.append(w)
                current.length + 1 + w.length <= max -> current.append(' ').append(w)
                else -> {
                    lines += current.toString()
                    current = StringBuilder(w)
                }
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    companion object {
        fun columnsFor(paperMm: Int): Int = if (paperMm == 58) 32 else 48
    }
}
