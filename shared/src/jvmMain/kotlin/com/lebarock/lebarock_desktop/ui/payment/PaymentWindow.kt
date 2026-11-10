package com.lebarock.lebarock_desktop.ui.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.data.Money
import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import com.lebarock.lebarock_desktop.pos.PayMethod
import com.lebarock.lebarock_desktop.pos.PaymentForm
import com.lebarock.lebarock_desktop.pos.SplitType
import com.lebarock.lebarock_desktop.pos.TipForm
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppDialog
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.Choice
import com.lebarock.lebarock_desktop.ui.common.ChoiceRow
import com.lebarock.lebarock_desktop.ui.common.ErrorBox
import com.lebarock.lebarock_desktop.ui.common.FormField
import com.lebarock.lebarock_desktop.ui.common.LoadingBox
import com.lebarock.lebarock_desktop.ui.common.ScrollArea
import com.lebarock.lebarock_desktop.ui.common.TextInput
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.theme.Danger
import com.lebarock.lebarock_desktop.ui.theme.Dimens
import com.lebarock.lebarock_desktop.ui.theme.Success
import com.lebarock.lebarock_desktop.ui.theme.SuccessContainer

/** The payment window when one is open (POS and Commandes draw it the same way). */
@Composable
fun PaymentWindow(flow: PaymentFlow) {
    val s = flow.state ?: return
    val currency = flow.currency
    // The window's height decides how much can show at once (1366 × 768 at 100 %; 125 % is the responsive case).
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val bodyMax = (windowHeight - 220.dp).coerceAtLeast(260.dp)
    val compact = windowHeight < 700.dp
    val form = (s.form as? UiState.Success)?.data
    val total = form?.context?.total

    when {
        s.done != null -> DoneWindow(flow, s, currency)
        s.tip != null && form != null -> TipWindow(flow, s, s.tip, currency)
        else -> AppDialog(
            title = "Paiement · Commande #${s.orderNumber}" + (total?.let { " · ${Money.format(it, currency)}" } ?: ""),
            onDismiss = flow::close,
            dismissible = !s.busy,
            width = 920.dp,
            actions = {
                AppButton("Fermer", flow::close, kind = ButtonKind.SECONDARY, enabled = !s.busy)
                if (form != null) {
                    AppButton(
                        if (!form.split && form.method == PayMethod.CREDIT) "Mettre à crédit" else "Valider le paiement",
                        flow::submit, large = true, loading = s.busy, modifier = Modifier.widthIn(min = 280.dp),
                    )
                }
            },
        ) {
            when (val state = s.form) {
                is UiState.Loading -> Box(Modifier.height(240.dp)) { LoadingBox(label = "Lecture du ticket…") }
                is UiState.Error -> Box(Modifier.height(240.dp)) { ErrorBox(state.message, state.hint, onRetry = flow::retry) }
                is UiState.Success -> {
                    val f = state.data
                    if (f.context.cashSessionRequired && !f.context.cashSessionOpen) {
                        Banner(
                            Tone.WARNING, "Aucune session de caisse ouverte" + (f.context.caisse?.let { " sur « ${it.name} »" } ?: ""),
                            detail = "La caisse refusera l'encaissement : ouvrez d'abord la session de caisse.",
                        )
                    }
                    s.notice?.let { Banner(Tone.SUCCESS, it) }
                    s.error?.let { Banner(Tone.DANGER, it) }
                    ScrollArea(maxHeight = bodyMax) {
                        if (!f.split) FullPayment(flow, f, s, currency, compact) else SplitPayment(flow, f, currency)
                    }
                }
            }
        }
    }
    s.paymentPin?.let { entry ->
        com.lebarock.lebarock_desktop.ui.offline.PinWindow(
            title = "Code de paiement",
            subtitle = "Hors ligne : le code du gérant ouvre les paiements de ce ticket pour 5 minutes",
            entry = entry,
            onChange = flow::editPaymentPin,
            onSubmit = flow::submitPaymentPin,
            onDismiss = flow::dismissPaymentPin,
        )
    }
}

// ── « Paiement complet » ─────────────────────────────────────────────────

@Composable
private fun ModeToggle(flow: PaymentFlow, split: Boolean) {
    ChoiceRow(
        listOf(Choice(false, "Paiement complet"), Choice(true, "Partager l'addition")),
        // A wide item width: the two boxes share the whole row, on the column's left edge.
        selected = split, onSelect = { flow.splitMode(it) }, modifier = Modifier.fillMaxWidth(), itemWidth = 640.dp,
    )
}

@Composable
private fun FullPayment(flow: PaymentFlow, f: PaymentForm, s: PaymentState, currency: CurrencyInfo, compact: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.l)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            ModeToggle(flow, false)
            // Espèces / Carte (TPE) / À crédit (« Give Credit ») / Pourboire, as the web's tiles.
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                f.methods.forEach { m ->
                    MethodTile(m.label, iconOf(m), selected = f.method == m, onClick = { flow.choose(m) }, modifier = Modifier.weight(1f))
                }
                if (f.context.canAddTip) {
                    val tip = f.context.tipAmount
                    MethodTile(
                        if (tip > 0) "Pourboire · ${Money.plain(tip, currency)}" else "Pourboire",
                        Icons.Outlined.VolunteerActivism, selected = tip > 0, dashed = tip <= 0, onClick = flow::openTip, modifier = Modifier.weight(1f),
                    )
                }
            }
            FormField(
                "Montant",
                help = when (f.method) {
                    PayMethod.CARD -> "Toute la somme restante passe sur le TPE. Pour n'en payer qu'une partie par carte : « Partager l'addition »."
                    PayMethod.CREDIT -> "Toute la somme restante va au compte du client ; le ticket est clôturé et apparaît dans « À crédit »."
                    PayMethod.CASH -> null
                },
            ) {
                TextInput(
                    value = if (f.amountLocked) PaymentForm.plainAmount(f.due) else f.amountText,
                    onValueChange = flow::typed, enabled = !f.amountLocked, large = true,
                    placeholder = PaymentForm.plainAmount(f.due),
                    keyboardActions = KeyboardActions(onDone = { flow.submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (f.method == PayMethod.CREDIT) CreditFields(flow, f, s)
            Summary(f, currency)
        }
        Keypad(flow, f, currency, compact, Modifier.width(300.dp))
    }
}

private fun iconOf(m: PayMethod): ImageVector = when (m) {
    PayMethod.CASH -> Icons.Outlined.Payments
    PayMethod.CARD -> Icons.Outlined.CreditCard
    PayMethod.CREDIT -> Icons.Outlined.Schedule
}

@Composable
private fun MethodTile(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dashed: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.heightIn(min = 72.dp).clip(Dimens.shape)
            .background(if (selected) colors.primaryContainer else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else if (dashed) colors.outlineVariant else colors.outline, Dimens.shape)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Dimens.s, vertical = Dimens.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.onSurfaceVariant)
        Spacer(Modifier.height(Dimens.xs))
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** À crédit: the customer already on the check, one picked from the search, or a new one (name + phone). */
@Composable
private fun CreditFields(flow: PaymentFlow, f: PaymentForm, s: PaymentState) {
    val c = f.credit
    if (c.pickedId != null) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = Dimens.shape, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = Dimens.m, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (c.phone.isNotBlank()) Text(c.phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppButton("Changer", flow::clearCustomer, kind = ButtonKind.TEXT)
            }
        }
        return
    }
    FormField("Client existant") {
        TextInput(c.search, flow::creditSearch, placeholder = "Nom ou téléphone", modifier = Modifier.fillMaxWidth())
    }
    when (val found = s.customers) {
        is UiState.Success -> if (found.data.isEmpty()) {
            Text("Aucun client trouvé.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, Dimens.shape)) {
                found.data.forEachIndexed { i, cust ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.control).clickable { flow.pickCustomer(cust) }.pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = Dimens.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(cust.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(cust.phone.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        is UiState.Error -> Text(found.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        FormField("Ou nouveau client : nom", Modifier.weight(1f)) {
            TextInput(c.name, flow::creditName, modifier = Modifier.fillMaxWidth())
        }
        FormField("Téléphone", Modifier.weight(1f)) {
            TextInput(c.phone, flow::creditPhone, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** « Addition »: total (and tip), paid, then the change or what stays to pay — as the web's summary. */
@Composable
private fun Summary(f: PaymentForm, currency: CurrencyInfo) {
    val ctx = f.context
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = Dimens.shape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text("ADDITION · #${ctx.orderNumber}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            if (ctx.discountAmount > 0) {
                SumRow("Remise" + (if (ctx.discountType == "percent") " (${PaymentForm.plainAmount(ctx.discountValue ?: 0.0)} %)" else ""), "− " + Money.format(ctx.discountAmount, currency), color = Success)
            }
            if (ctx.tipAmount > 0) {
                SumRow("Total", Money.format(ctx.total - ctx.tipAmount, currency))
                SumRow("Pourboire", "+ " + Money.format(ctx.tipAmount, currency), color = Success)
            }
            SumRow("Total de l'addition", Money.format(ctx.total, currency), strong = true)
            SumRow("Déjà payé", Money.format(ctx.amountPaid, currency))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (f.change > 0) {
                SumRow("Monnaie à rendre", Money.format(f.change, currency), color = Success, strong = true)
            } else {
                SumRow("Reste à payer", Money.format(f.balance, currency), color = Danger, strong = true)
            }
        }
    }
}

@Composable
private fun SumRow(label: String, value: String, color: Color = Color.Unspecified, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.weight(1f))
        Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, color = color, fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium)
    }
}

/** The web's keypad: « Montant exact », four quick notes, digits; greyed for the TPE and the credit. */
@Composable
private fun Keypad(flow: PaymentFlow, f: PaymentForm, currency: CurrencyInfo, compact: Boolean, modifier: Modifier) {
    val locked = f.amountLocked
    val key = if (compact) Dimens.control else Dimens.primary
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = Dimens.shape, modifier = modifier.alpha(if (locked) 0.4f else 1f)) {
        Column(Modifier.padding(Dimens.sm), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            KeyButton("Montant exact · ${Money.format(f.due, currency)}", enabled = !locked, height = Dimens.control, highlighted = true, onClick = flow::exact)
            PaymentForm.QUICK.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    row.forEach { v -> KeyButton(Money.format(v, currency), enabled = !locked, height = Dimens.control, onClick = { flow.quick(v) }, modifier = Modifier.weight(1f)) }
                }
            }
            listOf("123", "456", "789", ".0<").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    row.forEach { c ->
                        if (c == '<') {
                            KeyButton("", icon = Icons.AutoMirrored.Outlined.Backspace, enabled = !locked, height = key, onClick = flow::backspace, modifier = Modifier.weight(1f))
                        } else {
                            KeyButton(c.toString(), enabled = !locked, height = key, large = true, onClick = { flow.key(c) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            KeyButton("Effacer", enabled = !locked, height = Dimens.control, onClick = flow::clearAmount)
        }
    }
}

@Composable
private fun KeyButton(
    text: String,
    enabled: Boolean,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    large: Boolean = false,
    highlighted: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.height(height).clip(Dimens.shape)
            .background(if (highlighted) colors.primaryContainer else colors.surface)
            .border(1.dp, if (highlighted) colors.primary else colors.outline, Dimens.shape)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = "Effacer un chiffre", tint = colors.onSurface)
        } else {
            Text(
                text, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                color = if (highlighted) colors.primary else colors.onSurface, maxLines = 1,
            )
        }
    }
}

// ── « Partager l'addition » ──────────────────────────────────────────────

@Composable
private fun SplitPayment(flow: PaymentFlow, f: PaymentForm, currency: CurrencyInfo) {
    ModeToggle(flow, true)
    val type = f.splitType
    if (type == null) {
        Text("Comment partager l'addition ?", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            SplitType.entries.forEach { t ->
                Column(
                    Modifier.weight(1f).heightIn(min = 96.dp).clip(Dimens.shape).border(1.dp, MaterialTheme.colorScheme.outline, Dimens.shape)
                        .clickable { flow.splitType(t) }.pointerHoverIcon(PointerIcon.Hand).padding(Dimens.m),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text(t.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Dimens.xs))
                    Text(t.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Chaque part se règle en espèces ou par carte (TPE).", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(type.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        AppButton("Changer de partage", { flow.splitType(null) }, kind = ButtonKind.TEXT)
    }
    when (type) {
        SplitType.EQUAL -> {
            f.equalParts.forEachIndexed { i, part ->
                PartRow("Part ${i + 1}", part.method, onMethod = { m -> flow.update { it.equalMethod(i, m) } }, onRemove = if (f.equalParts.size > 2) ({ flow.update { it.removeEqualPart(i) } }) else null) {
                    Text(Money.format(f.equalAmounts[i], currency), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End, modifier = Modifier.width(160.dp))
                }
            }
            AppButton("Nouvelle part", { flow.update { it.addEqualPart() } }, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Add)
            SplitSummary(f, currency, parts = f.due, balance = null)
        }
        SplitType.CUSTOM -> {
            f.customParts.forEachIndexed { i, part ->
                PartRow("Part ${i + 1}", part.method, onMethod = { m -> flow.update { it.customMethod(i, m) } }, onRemove = if (f.customParts.size > 2) ({ flow.update { it.removeCustomPart(i) } }) else null) {
                    TextInput(part.amountText, { t -> flow.update { it.customAmount(i, t) } }, placeholder = "0", modifier = Modifier.width(160.dp))
                }
            }
            AppButton("Nouvelle part", { flow.update { it.addCustomPart() } }, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Add)
            SplitSummary(f, currency, parts = f.customTotal, balance = if (f.customBalance > 0) "Reste à payer" to f.customBalance else if (f.customExcess > 0) "En trop" to f.customExcess else null)
        }
        SplitType.ITEMS -> ItemsSplit(flow, f, currency)
    }
}

@Composable
private fun PartRow(label: String, method: PayMethod, onMethod: (PayMethod) -> Unit, onRemove: (() -> Unit)?, amount: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = Dimens.shape, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = Dimens.m, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
            amount()
            Spacer(Modifier.weight(1f))
            ChoiceRow(listOf(Choice(PayMethod.CASH, "Espèces"), Choice(PayMethod.CARD, "Carte (TPE)")), selected = method, onSelect = onMethod, itemWidth = 150.dp)
            if (onRemove != null) {
                Box(Modifier.size(Dimens.control).clip(Dimens.shape).clickable(onClick = onRemove).pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, contentDescription = "Retirer cette part", tint = Danger)
                }
            } else {
                Spacer(Modifier.width(Dimens.control))
            }
        }
    }
}

@Composable
private fun SplitSummary(f: PaymentForm, currency: CurrencyInfo, parts: Double, balance: Pair<String, Double>?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = Dimens.shape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            SumRow("Total de l'addition", Money.format(f.context.total, currency))
            if (f.context.amountPaid > 0) SumRow("Déjà payé", Money.format(f.context.amountPaid, currency))
            SumRow("Montant des parts", Money.format(parts, currency), strong = true)
            balance?.let { (label, value) -> SumRow(label, Money.format(value, currency), color = if (label == "Reste à payer") Danger else Success, strong = true) }
        }
    }
}

/** By article: the parts as tabs, the articles still to share on the left, the open part's on the right. */
@Composable
private fun ItemsSplit(flow: PaymentFlow, f: PaymentForm, currency: CurrencyInfo) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        f.itemParts.forEach { part ->
            val on = part.id == f.activeItemsPart
            Row(
                Modifier.height(Dimens.control).clip(Dimens.shape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, Dimens.shape)
                    .clickable { flow.update { it.selectItemsPart(part.id) } }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = Dimens.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Part ${part.id} · ${Money.plain(f.partTotal(part), currency)}", style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
            if (f.itemParts.size > 1 && part.id != f.itemParts.first().id) {
                Box(Modifier.size(Dimens.control).clip(Dimens.shape).clickable { flow.update { it.removeItemsPart(part.id) } }.pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, contentDescription = "Retirer la part ${part.id}", tint = Danger)
                }
            }
        }
        AppButton("Nouvelle part", { flow.update { it.addItemsPart() } }, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Add)
    }
    val active = f.activePart ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text("Articles à partager · cliquez pour ajouter à la part ${active.id}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            f.items.forEach { line ->
                val left = f.remaining(line)
                Row(Modifier.fillMaxWidth().alpha(if (left == 0) 0.45f else 1f), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Money.format(line.unitPrice, currency)} · reste $left / ${line.quantity}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AppButton("+1", { flow.update { it.addToPart(line, 1) } }, kind = ButtonKind.SECONDARY, enabled = left > 0)
                    Spacer(Modifier.width(Dimens.s))
                    AppButton("Tout", { flow.update { it.addToPart(line, left) } }, kind = ButtonKind.SECONDARY, enabled = left > 0)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = Dimens.shape, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Part ${active.id}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(Money.format(f.partTotal(active), currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                ChoiceRow(listOf(Choice(PayMethod.CASH, "Espèces"), Choice(PayMethod.CARD, "Carte (TPE)")), selected = active.method, onSelect = { m -> flow.update { it.itemsMethod(active.id, m) } }, modifier = Modifier.fillMaxWidth(), itemWidth = 640.dp)
                if (active.lines.isEmpty()) {
                    Text("Aucun article dans cette part.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                active.lines.forEach { (id, qty) ->
                    val line = f.items.firstOrNull { it.orderItemId == id } ?: return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${line.name} ×$qty", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Money.plain(line.unitPrice * qty, currency), style = MaterialTheme.typography.bodyMedium)
                        Box(Modifier.size(Dimens.control).clip(Dimens.shape).clickable { flow.update { it.takeFromPart(id) } }.pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Remove, contentDescription = "Un de moins")
                        }
                    }
                }
            }
        }
    }
    SplitSummary(f, currency, parts = f.itemsTotal, balance = if (f.itemsTotal < f.due - 0.0101) "Reste à payer" to (f.due - f.itemsTotal) else null)
}

// ── « Pourboire » ────────────────────────────────────────────────────────

@Composable
private fun TipWindow(flow: PaymentFlow, s: PaymentState, t: TipForm, currency: CurrencyInfo) {
    AppDialog(
        title = "Pourboire · Commande #${s.orderNumber}",
        onDismiss = flow::closeTip,
        dismissible = !s.busy,
        width = 520.dp,
        actions = {
            AppButton("Annuler", flow::closeTip, kind = ButtonKind.SECONDARY, enabled = !s.busy)
            AppButton("Enregistrer", flow::saveTip, loading = s.busy)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pourboire suggéré", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            ChoiceRow(listOf(Choice(true, "%"), Choice(false, currency.symbol.ifBlank { "Montant" })), selected = t.percentMode, onSelect = { p -> flow.editTip { it.copy(percentMode = p, picked = null) } }, itemWidth = 72.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            TipForm.QUICK.forEach { v ->
                val label = if (t.percentMode) "${PaymentForm.plainAmount(v)} %" else Money.format(v, currency)
                val on = t.picked == v
                Box(
                    Modifier.weight(1f).height(Dimens.primary).clip(Dimens.shape)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, Dimens.shape)
                        .clickable { flow.editTip { it.quick(v) } }.pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.titleSmall, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        FormField("Montant personnalisé", help = "0 retire le pourboire.") {
            TextInput(t.amountText, { v -> flow.editTip { it.typed(v) } }, placeholder = "0", modifier = Modifier.fillMaxWidth())
        }
        FormField("Note du pourboire (facultatif)") {
            TextInput(t.note, { v -> flow.editTip { it.copy(note = v) } }, modifier = Modifier.fillMaxWidth())
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = Dimens.shape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                SumRow("Total actuel", Money.format(t.baseTotal, currency))
                SumRow("Pourboire", "+ " + Money.format(t.amount, currency), color = Success)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SumRow("Nouveau total", Money.format(t.newTotal, currency), strong = true)
            }
        }
        s.tipError?.let { Banner(Tone.DANGER, it) }
    }
}

// ── Réglé ────────────────────────────────────────────────────────────────

/** The check is settled: the change (large), where the receipt printed, « Réimprimer », « Terminer ». */
@Composable
private fun DoneWindow(flow: PaymentFlow, s: PaymentState, currency: CurrencyInfo) {
    val done = s.done ?: return
    AppDialog(
        title = "Commande #${s.orderNumber}",
        onDismiss = flow::close,
        width = 560.dp,
        actions = {
            AppButton("Réimprimer", flow::reprint, kind = ButtonKind.SECONDARY, loading = s.reprinting)
            AppButton("Terminer", flow::close, large = true, modifier = Modifier.widthIn(min = 200.dp))
        },
    ) {
        Surface(color = SuccessContainer, shape = Dimens.cardShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Dimens.l), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(48.dp))
                Text(done.title, style = MaterialTheme.typography.headlineSmall, color = Success, fontWeight = FontWeight.Bold)
                if (done.change > 0) {
                    Text("Monnaie à rendre", style = MaterialTheme.typography.titleMedium)
                    Text(Money.format(done.change, currency), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                } else if (done.detail != null) {
                    Text(done.detail, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
        }
        done.print?.let { p -> Banner(if (p.ok) Tone.SUCCESS else Tone.WARNING, if (p.ok) "Reçu imprimé" else "Reçu non imprimé", detail = p.message) }
    }
}
