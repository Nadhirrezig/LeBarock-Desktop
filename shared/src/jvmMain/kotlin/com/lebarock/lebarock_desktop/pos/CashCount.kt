package com.lebarock.lebarock_desktop.pos

import com.lebarock.lebarock_desktop.pos.CartMath.round2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The Z's count, as the web's « Compter par valeur » (`Dashboard::closeBreakdown`): one line per note or coin value
 * set in Settings (high to low), − / + per line; the counted total, and against the cash expected the difference
 * (positive = more than expected).
 */
data class CashCount(val values: List<Int>, val counts: Map<Int, Int> = emptyMap()) {

    fun count(value: Int): Int = counts[value] ?: 0

    fun plus(value: Int): CashCount = if (value in values) copy(counts = counts + (value to count(value) + 1)) else this

    fun minus(value: Int): CashCount = if (value in values && count(value) > 0) copy(counts = counts + (value to count(value) - 1)) else this

    /** A number typed on a line (empty = 0; never below 0). */
    fun set(value: Int, text: String): CashCount =
        if (value in values) copy(counts = counts + (value to (text.filter(Char::isDigit).take(5).toIntOrNull() ?: 0))) else this

    val total: Double get() = round2(values.sumOf { it.toDouble() * count(it) })

    fun difference(expected: Double): Double = round2(total - expected)

    /** What is sent: only the values counted. */
    val breakdown: Map<Int, Int> get() = counts.filter { it.key in values && it.value > 0 }
}

/** An amount typed in a field (« 12,5 » or « 12.5 »), or null when it is not one. */
fun parseAmount(text: String): Double? = text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let(::round2)

/** The day as the counter writes it (« 10/11/2026 ») ⇄ as the caisse reads it (« 2026-11-10 »). */
object CounterDate {
    private val shown = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun today(): String = LocalDate.now().format(shown)

    /** « 10/11/2026 » → « 2026-11-10 », null when it is not a date. */
    fun toIso(text: String): String? = try {
        LocalDate.parse(text.trim(), shown).toString()
    } catch (_: DateTimeParseException) {
        null
    }
}
