package com.lebarock.lebarock_desktop.ui.check

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.data.Money
import com.lebarock.lebarock_desktop.data.remote.TableDto
import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppDialog
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.FormField
import com.lebarock.lebarock_desktop.ui.common.LoadingBox
import com.lebarock.lebarock_desktop.ui.common.ScrollArea
import com.lebarock.lebarock_desktop.ui.common.TextInput
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.theme.Danger
import com.lebarock.lebarock_desktop.ui.theme.Dimens

private fun CheckAction.icon(): ImageVector = when (this) {
    CheckAction.MOVE -> Icons.Outlined.SwapHoriz
    CheckAction.MERGE -> Icons.AutoMirrored.Outlined.CallMerge
    CheckAction.SPLIT -> Icons.AutoMirrored.Outlined.CallSplit
    CheckAction.WAITER -> Icons.Outlined.Person
    CheckAction.CUSTOMER -> Icons.Outlined.PersonAdd
    CheckAction.CHARGES -> Icons.Outlined.RemoveCircleOutline
    CheckAction.REVIEWED -> Icons.Outlined.Verified
    CheckAction.CANCEL -> Icons.Outlined.Block
}

/** « Plus »: the check's other actions in a menu (the web's header buttons of the order panel). */
@Composable
fun CheckActionsButton(actions: List<CheckAction>, enabled: Boolean, onPick: (CheckAction) -> Unit, modifier: Modifier = Modifier, label: String = "Plus") {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        AppButton(label, { open = true }, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.MoreVert, enabled = enabled)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                val tint = if (action == CheckAction.CANCEL) Danger else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(action.label, style = MaterialTheme.typography.bodyLarge, color = tint) },
                    leadingIcon = { Icon(action.icon(), contentDescription = null, tint = tint) },
                    onClick = { open = false; onPick(action) },
                    modifier = Modifier.height(Dimens.control).pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}

/** The window open on the check, whichever it is. */
@Composable
fun CheckDialogs(model: CheckActions, currency: CurrencyInfo) {
    when (val d = model.dialog) {
        null -> Unit
        is CheckDialog.Tables -> TablesDialog(model, d, currency)
        is CheckDialog.Split -> SplitDialog(model, d, currency)
        is CheckDialog.Waiter -> WaiterDialog(model, d)
        is CheckDialog.Customer -> CustomerDialog(model, d)
        is CheckDialog.Charges -> ChargesDialog(model, d, currency)
        is CheckDialog.PaymentFix -> PaymentFixDialog(model, d, currency)
        is CheckDialog.Cancel -> CancelDialog(model, d)
        is CheckDialog.RemoveLine -> RemoveLineDialog(model, d, currency)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TablesDialog(model: CheckActions, d: CheckDialog.Tables, currency: CurrencyInfo) {
    val from = d.order.table?.id
    val move = d.mode == TableMode.MOVE
    AppDialog(
        if (move) "Changer de table" else "Fusionner avec une table",
        onDismiss = model::close, width = 880.dp, dismissible = !model.busy,
        subtitle = if (move) {
            "Commande #${d.order.orderNumber} : choisissez une table libre. Pour une table occupée, utilisez « Fusionner »."
        } else {
            "La commande #${d.order.orderNumber} rejoint celle de la table choisie : un seul ticket pour les deux tables."
        },
        actions = { AppButton("Fermer", model::close, kind = ButtonKind.SECONDARY, enabled = !model.busy) },
    ) {
        d.error?.let { Banner(Tone.DANGER, it) }
        when (val state = d.areas) {
            UiState.Loading -> Box(Modifier.fillMaxWidth().height(160.dp)) { LoadingBox() }
            is UiState.Error -> Banner(Tone.DANGER, state.message, detail = state.hint)
            is UiState.Success -> ScrollArea(460.dp) {
                val areas = state.data.filter { it.tables.isNotEmpty() }
                if (areas.isEmpty()) Text("Aucune table active.", style = MaterialTheme.typography.bodyLarge)
                areas.forEach { area ->
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Text(area.name, style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                            area.tables.forEach { t ->
                                val ok = if (move) canMoveTo(t, from) else canMergeInto(t, from)
                                PickTile(t, current = t.id == from, enabled = ok && !model.busy, currency = currency) { model.pickTable(t) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A table in a pick list: its state colour, what is on it; tables that cannot take the check are greyed. */
@Composable
private fun PickTile(t: TableDto, current: Boolean, enabled: Boolean, currency: CurrencyInfo, onClick: () -> Unit) {
    val state = FloorState.of(t)
    val alpha = if (enabled || current) 1f else 0.45f
    Column(
        Modifier.width(152.dp).height(104.dp).clip(Dimens.cardShape)
            .background(state.color.copy(alpha = alpha))
            .border(if (current) 3.dp else 0.dp, if (current) MaterialTheme.colorScheme.onSurface else Color.Transparent, Dimens.cardShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(Dimens.sm),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text(if (current) "Actuelle" else if (state == FloorState.BILL && t.session?.mergedInto == null) "Addition" else state.label(t), style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (t.openOrders > 0) Money.format(t.openTotal, currency) else "${t.seats} place" + if (t.seats > 1) "s" else "",
            style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1,
        )
    }
}

@Composable
private fun SplitDialog(model: CheckActions, d: CheckDialog.Split, currency: CurrencyInfo) {
    val moved = d.quantities.values.sum()
    AppDialog(
        "Séparer la commande #${d.order.orderNumber}", onDismiss = model::close, width = 620.dp, dismissible = !model.busy,
        subtitle = "Les quantités choisies passent sur un nouveau ticket, à la même table.",
        actions = {
            AppButton("Annuler", model::close, kind = ButtonKind.SECONDARY, enabled = !model.busy)
            AppButton("Créer le nouveau ticket", model::confirmSplit, enabled = moved > 0, loading = model.busy)
        },
    ) {
        ScrollArea(380.dp) {
            d.order.items.forEach { line ->
                val qty = d.quantities[line.id] ?: 0
                Row(Modifier.fillMaxWidth().height(Dimens.control), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name + (line.variation?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${line.quantity} sur la commande · ${Money.plain(line.amount, currency)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Step(qty, line.quantity, onMinus = { model.splitStep(line, -1) }, onPlus = { model.splitStep(line, +1) })
                }
            }
        }
        Text(if (moved > 0) "$moved article" + (if (moved > 1) "s" else "") + " sur le nouveau ticket." else "Choisissez les articles à séparer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        d.error?.let { Banner(Tone.DANGER, it) }
    }
}

@Composable
private fun Step(value: Int, max: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        StepKey(Icons.Outlined.Remove, "Moins", enabled = value > 0, onClick = onMinus)
        Text("$value", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.width(40.dp))
        StepKey(Icons.Outlined.Add, "Plus", enabled = value < max, onClick = onPlus)
    }
}

@Composable
private fun StepKey(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(Dimens.control).clip(Dimens.shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, Dimens.shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun WaiterDialog(model: CheckActions, d: CheckDialog.Waiter) {
    AppDialog(
        "Serveur de la commande #${d.order.orderNumber}", onDismiss = model::close, width = 520.dp, dismissible = !model.busy,
        actions = {
            AppButton("Annuler", model::close, kind = ButtonKind.SECONDARY, enabled = !model.busy)
            AppButton("Enregistrer", model::confirmWaiter, enabled = d.selected != null, loading = model.busy)
        },
    ) {
        when (val state = d.waiters) {
            UiState.Loading -> Box(Modifier.fillMaxWidth().height(120.dp)) { LoadingBox() }
            is UiState.Error -> Banner(Tone.DANGER, state.message, detail = state.hint)
            is UiState.Success -> ScrollArea(360.dp) {
                state.data.forEach { w -> PickRow(w.name, selected = d.selected == w.id) { model.chooseWaiter(w.id) } }
            }
        }
        d.error?.let { Banner(Tone.DANGER, it) }
    }
}

/** A choice in a list: the chosen one green with a check (the app's choice style). */
@Composable
private fun PickRow(text: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(if (detail != null) 56.dp else Dimens.control).clip(Dimens.shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, Dimens.shape)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Dimens.s))
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun CustomerDialog(model: CheckActions, d: CheckDialog.Customer) {
    val f = d.form
    AppDialog(
        "Client de la commande #${d.order.orderNumber}", onDismiss = model::close, width = 560.dp, dismissible = !model.busy,
        subtitle = "Cherchez un client, ou saisissez-en un nouveau (même téléphone = même client).",
        actions = {
            AppButton("Annuler", model::close, kind = ButtonKind.SECONDARY, enabled = !model.busy)
            AppButton("Enregistrer", model::saveCustomer, loading = model.busy)
        },
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        TextInput(
            f.search, model::customerSearch, modifier = Modifier.fillMaxWidth().focusRequester(focus), placeholder = "Rechercher : nom, téléphone ou e-mail",
            leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        when (val results = f.results) {
            null -> Unit
            UiState.Loading -> Unit
            is UiState.Error -> Banner(Tone.DANGER, results.message, detail = results.hint)
            is UiState.Success -> if (results.data.isEmpty()) {
                Text("Aucun client trouvé : saisissez-le ci-dessous.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ScrollArea(200.dp) {
                    results.data.forEach { c ->
                        PickRow(c.name, selected = f.pickedId == c.id, detail = listOfNotNull(c.phone, c.email).joinToString(" · ").ifEmpty { null }) { model.pickCustomer(c) }
                    }
                }
            }
        }
        FormField("Nom") { TextInput(f.name, model::customerName, modifier = Modifier.fillMaxWidth(), placeholder = "Nom du client") }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            FormField("Téléphone (facultatif)", Modifier.weight(1f)) {
                TextInput(f.phone, model::customerPhone, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            }
            FormField("E-mail (facultatif)", Modifier.weight(1f)) {
                TextInput(
                    f.email, model::customerEmail, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.saveCustomer() }),
                )
            }
        }
        d.error?.let { Banner(Tone.DANGER, it) }
    }
}

@Composable
private fun ChargesDialog(model: CheckActions, d: CheckDialog.Charges, currency: CurrencyInfo) {
    AppDialog(
        "Frais de la commande #${d.order.orderNumber}", onDismiss = model::close, width = 520.dp, dismissible = !model.busy,
        subtitle = "Un frais retiré n'est plus compté dans le total de cette commande.",
        actions = { AppButton("Fermer", model::close, kind = ButtonKind.SECONDARY, enabled = !model.busy) },
    ) {
        d.order.charges.forEach { c ->
            Row(Modifier.fillMaxWidth().height(Dimens.control), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name + if (c.type == "percent") " (${pct(c.value)} %)" else "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(Money.format(c.amount, currency), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(Dimens.sm))
                if (c.removable) AppButton("Retirer", { model.removeCharge(c.id) }, kind = ButtonKind.DANGER, enabled = !model.busy)
            }
        }
        d.error?.let { Banner(Tone.DANGER, it) }
    }
}

private fun pct(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

@Composable
private fun PaymentFixDialog(model: CheckActions, d: CheckDialog.PaymentFix, currency: CurrencyInfo) {
    val toCard = d.payment.method == "cash"
    AppDialog(
        "Corriger le paiement", onDismiss = model::close, width = 520.dp, dismissible = !model.busy,
        actions = {
            AppButton("Annuler", model::close, kind = ButtonKind.SECONDARY, enabled = !model.busy)
            AppButton(if (toCard) "Passer en carte" else "Passer en espèces", model::confirmPaymentFix, loading = model.busy)
        },
    ) {
        Text(
            "${Money.format(d.payment.amount, currency)} enregistré en ${methodLabel(d.payment.method)} sur la commande #${d.order.orderNumber} : " +
                if (toCard) "il passe en carte (TPE) et sort du tiroir." else "il passe en espèces et entre dans le tiroir.",
            style = MaterialTheme.typography.bodyLarge,
        )
        d.error?.let { Banner(Tone.DANGER, it) }
    }
}

fun methodLabel(method: String): String = when (method) {
    "cash" -> "espèces"
    "card" -> "carte (TPE)"
    "due" -> "à crédit"
    else -> method
}

@Composable
private fun CancelDialog(model: CheckActions, d: CheckDialog.Cancel) {
    val form = d.form
    AppDialog(
        "Annuler la commande #${d.order.orderNumber}", onDismiss = model::close, width = 560.dp, dismissible = !model.busy,
        subtitle = "La commande sera annulée et ses paiements remboursés. Continuer ?",
        actions = {
            AppButton("Retour", model::close, kind = ButtonKind.SECONDARY, enabled = !model.busy)
            AppButton("Annuler la commande", model::confirmCancel, kind = ButtonKind.DANGER, loading = model.busy)
        },
    ) {
        FormField("Choisir le motif d'annulation") {
            when (val reasons = form.reasons) {
                UiState.Loading -> Box(Modifier.fillMaxWidth().height(Dimens.control)) { LoadingBox() }
                is UiState.Error -> Banner(Tone.DANGER, reasons.message, detail = reasons.hint)
                is UiState.Success -> ScrollArea(240.dp) {
                    if (reasons.data.isEmpty()) Text("Aucun motif dans Paramètres → Motifs d'annulation : écrivez pourquoi ci-dessous.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    reasons.data.forEach { r ->
                        val on = form.reasonId == r.id
                        PickRow(r.reason, selected = on) { model.cancelReason(if (on) null else r.id) }
                    }
                }
            }
        }
        FormField("Commentaire (facultatif)") {
            TextInput(form.text, model::cancelText, modifier = Modifier.fillMaxWidth(), placeholder = "Ex. client parti avant d'être servi")
        }
        d.error?.let { Banner(Tone.DANGER, it) }
    }
}

@Composable
private fun RemoveLineDialog(model: CheckActions, d: CheckDialog.RemoveLine, currency: CurrencyInfo) {
    val line = d.line
    AppDialog(
        "Retirer cet article ?", onDismiss = model::close, width = 520.dp, dismissible = !model.busy,
        actions = {
            AppButton("Garder", model::close, kind = ButtonKind.SECONDARY, enabled = !model.busy)
            AppButton("Retirer", model::confirmRemoveLine, kind = ButtonKind.DANGER, loading = model.busy)
        },
    ) {
        Text("${line.quantity} × ${line.name}" + (line.variation?.let { " — $it" } ?: "") + " · ${Money.format(line.amount, currency)}", style = MaterialTheme.typography.titleMedium)
        Text(
            if (line.sentNotFired) "Cet article est déjà envoyé : il est annulé et son stock est rendu." else "Cet article n'est pas encore parti en cuisine.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        d.error?.let { Banner(Tone.DANGER, it) }
    }
}

/** The web floor's four colours and the label of a tile's state (`tables.blade.php`). */
enum class FloorState(val color: Color) {
    FREE(Color(0xFF22C55E)),
    BUSY(Color(0xFF3B82F6)),
    BILL(Color(0xFFF59E0B)),
    RESERVED(Color(0xFFEF4444)),
    ;

    fun label(t: TableDto): String = t.session?.mergedInto?.let { "Avec ${it.code ?: ""}".trim() } ?: when (this) {
        FREE -> "Libre"
        BUSY -> "Occupée"
        BILL -> "Addition demandée"
        RESERVED -> "Réservée"
    }

    companion object {
        fun of(t: TableDto): FloorState = when {
            t.session?.billRequested == true -> BILL
            t.status == "running" -> BUSY
            t.status == "reserved" -> RESERVED
            else -> FREE
        }
    }
}
