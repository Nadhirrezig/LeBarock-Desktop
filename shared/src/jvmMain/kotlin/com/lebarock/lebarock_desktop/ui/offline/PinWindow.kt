package com.lebarock.lebarock_desktop.ui.offline

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.login.Keypad
import com.lebarock.lebarock_desktop.ui.theme.Dimens

/** A PIN being typed (offline step 3): the digits, a check on its way, or why it was refused. */
data class PinEntry(val pin: String = "", val busy: Boolean = false, val error: String? = null) {
    fun digit(c: Char): PinEntry = if (busy || pin.length >= 6 || !c.isDigit()) this else copy(pin = pin + c, error = null)
    fun backspace(): PinEntry = if (busy) this else copy(pin = pin.dropLast(1), error = null)
    fun cleared(): PinEntry = if (busy) this else copy(pin = "", error = null)
}

/**
 * The offline PIN window (the « Hors ligne » sign-in, the payment PIN): the digits shown as dots, the sign-in's
 * keypad, the PC's keyboard too (digits, ← and Entrée), « Annuler » / « Valider ».
 */
@Composable
fun PinWindow(
    title: String,
    subtitle: String,
    entry: PinEntry,
    onChange: (PinEntry) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val compact = windowHeight < 760.dp
    val keyHeight = if (compact) Dimens.control else Dimens.primary
    val focus = remember { FocusRequester() }
    LaunchedEffect(entry.busy) { if (!entry.busy) runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = { if (!entry.busy) onDismiss() }, properties = DialogProperties(dismissOnClickOutside = !entry.busy)) {
        Surface(shape = Dimens.cardShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(
                Modifier.width(Dimens.keypadWidth + Dimens.l * 2).padding(Dimens.l)
                    .focusRequester(focus).focusable()
                    .onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            it.key == Key.Enter || it.key == Key.NumPadEnter -> { onSubmit(); true }
                            it.key == Key.Escape -> { if (!entry.busy) onDismiss(); true }
                            it.key == Key.Backspace -> { onChange(entry.backspace()); true }
                            it.utf16CodePoint.toChar().isDigit() -> { onChange(entry.digit(it.utf16CodePoint.toChar())); true }
                            else -> false
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(Dimens.sm))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(if (compact) Dimens.sm else Dimens.l))
                // The digits typed, as dots (a PIN is never shown).
                Text(
                    if (entry.pin.isEmpty()) "– – – –" else "●".repeat(entry.pin.length).toCharArray().joinToString(" "),
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp),
                    color = if (entry.pin.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(top = Dimens.s), contentAlignment = Alignment.TopCenter) {
                    entry.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
                }
                Spacer(Modifier.height(Dimens.s))
                Keypad(
                    enabled = !entry.busy, keyHeight = keyHeight,
                    onDigit = { onChange(entry.digit(it)) }, onBackspace = { onChange(entry.backspace()) }, onClear = { onChange(entry.cleared()) },
                )
                Spacer(Modifier.height(if (compact) Dimens.m else Dimens.l))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), modifier = Modifier.fillMaxWidth()) {
                    AppButton("Annuler", onDismiss, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = !entry.busy, large = !compact)
                    AppButton("Valider", onSubmit, Modifier.weight(1f), loading = entry.busy, enabled = entry.pin.length >= 4, large = !compact)
                }
            }
        }
    }
}
