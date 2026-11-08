package com.lebarock.lebarock_desktop.data

import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Amounts written exactly as the caisse writes them (`currency_format_exact`): at least 2 decimals
 * (3 for the dinar), its decimal and thousand separators, the symbol where its settings put it
 * (« 8.630 DT »). The web POS shows plain numbers on lines and the currency on totals only.
 */
object Money {

    fun format(amount: Double, currency: CurrencyInfo): String {
        val number = plain(amount, currency)
        val symbol = currency.symbol
        return when (currency.position) {
            "right" -> number + symbol
            "left_with_space" -> "$symbol $number"
            "right_with_space" -> "$number $symbol"
            else -> symbol + number
        }
    }

    /** The number alone, as on the cart's lines and options. */
    fun plain(amount: Double, currency: CurrencyInfo): String {
        val decimals = maxOf(2, currency.decimals)
        // PHP's number_format rounds half away from zero.
        val rounded = BigDecimal(amount.toString()).setScale(decimals, RoundingMode.HALF_UP)
        val negative = rounded.signum() < 0
        val digits = rounded.abs().toPlainString()
        val intPart = digits.substringBefore('.')
        val fraction = digits.substringAfter('.', "")
        val grouped = if (currency.thousandSeparator.isEmpty()) {
            intPart
        } else {
            intPart.reversed().chunked(3).map { it.reversed() }.reversed().joinToString(currency.thousandSeparator)
        }
        val body = if (fraction.isEmpty()) grouped else grouped + currency.decimalSeparator + fraction
        return if (negative && rounded.signum() != 0) "-$body" else body
    }
}
