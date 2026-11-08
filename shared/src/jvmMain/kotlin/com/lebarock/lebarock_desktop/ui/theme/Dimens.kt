package com.lebarock.lebarock_desktop.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * One set of sizes for every screen (client, 2026-11-08: margins, paddings and controls must be
 * consistent). Spacing goes in steps of 8; every button and field is [control] tall, a screen's
 * main action [primary]; one corner radius.
 */
object Dimens {
    val xs = 4.dp
    val s = 8.dp
    val sm = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /** Buttons, text fields, choice rows. */
    val control = 48.dp

    /** The main action of a screen or a window; keypad keys. */
    val primary = 56.dp

    val topBar = 72.dp
    val radius = 10.dp
    val cardRadius = 14.dp
    val icon = 24.dp

    /** Readable line length for forms and text. */
    val contentMaxWidth = 960.dp

    /** The PIN keypad: three keys of [keyWidth] and two gaps of [s]. */
    val keyWidth = 112.dp
    val keypadWidth = 352.dp

    /** Label column of label / value rows. */
    val labelColumn = 220.dp

    val shape = RoundedCornerShape(radius)
    val cardShape = RoundedCornerShape(cardRadius)
}
