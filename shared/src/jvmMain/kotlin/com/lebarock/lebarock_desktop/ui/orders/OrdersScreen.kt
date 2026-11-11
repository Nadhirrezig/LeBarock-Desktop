package com.lebarock.lebarock_desktop.ui.orders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.data.Money
import com.lebarock.lebarock_desktop.data.remote.KotDto
import com.lebarock.lebarock_desktop.data.remote.OrderDetailDto
import com.lebarock.lebarock_desktop.data.remote.OrderDto
import com.lebarock.lebarock_desktop.data.remote.OrderItemDto
import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import com.lebarock.lebarock_desktop.pos.tableLabel
import com.lebarock.lebarock_desktop.ui.check.CheckAction
import com.lebarock.lebarock_desktop.ui.check.CheckActionsButton
import com.lebarock.lebarock_desktop.ui.check.CheckDialogs
import com.lebarock.lebarock_desktop.ui.check.methodLabel
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppDialog
import com.lebarock.lebarock_desktop.ui.common.AppTopBar
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.ErrorBox
import com.lebarock.lebarock_desktop.ui.common.FormField
import com.lebarock.lebarock_desktop.ui.common.LoadingBox
import com.lebarock.lebarock_desktop.ui.common.ScrollArea
import com.lebarock.lebarock_desktop.ui.common.TextInput
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.pos.IconBox
import com.lebarock.lebarock_desktop.ui.pos.Pill
import com.lebarock.lebarock_desktop.ui.theme.Danger
import com.lebarock.lebarock_desktop.ui.theme.DangerContainer
import com.lebarock.lebarock_desktop.ui.theme.Dimens
import com.lebarock.lebarock_desktop.ui.theme.Info
import com.lebarock.lebarock_desktop.ui.theme.InfoContainer
import com.lebarock.lebarock_desktop.ui.theme.Success
import com.lebarock.lebarock_desktop.ui.theme.SuccessContainer
import com.lebarock.lebarock_desktop.ui.theme.Warning
import com.lebarock.lebarock_desktop.ui.theme.WarningContainer
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Commandes: today's orders on the left (status chips), the selected one on the right with its actions. */
@Composable
fun OrdersScreen(model: OrdersModel, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            AppTopBar("Commandes", subtitle = if (model.filter == OrderFilter.TO_PAY) "Les tickets à encaisser, tous les jours" else "Les commandes du jour", onBack = onBack) {
                AppButton("Actualiser", model::refresh, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Refresh)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val listWidth = if (maxWidth < 1200.dp) 400.dp else 460.dp
            Row(Modifier.fillMaxSize().padding(Dimens.m), horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                OrderList(model, Modifier.width(listWidth).fillMaxHeight())
                Surface(Modifier.weight(1f).fillMaxHeight(), shape = Dimens.cardShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    when (val d = model.detail) {
                        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Choisissez une commande à gauche.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        UiState.Loading -> LoadingBox()
                        is UiState.Error -> ErrorBox(d.message, d.hint, onRetry = { model.selectedId?.let(model::select) })
                        is UiState.Success -> OrderDetail(model, d.data)
                    }
                }
            }
        }
    }

    OrderDialogs(model)
}

// ── The list ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderList(model: OrdersModel, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        val counts = (model.list as? UiState.Success)?.data?.counts
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            OrderFilter.entries.filter { it != OrderFilter.TO_PAY || model.rights?.takePayment == true }.forEach { f ->
                val count = when (f) {
                    OrderFilter.ALL -> counts?.all
                    OrderFilter.RUNNING -> counts?.kot
                    OrderFilter.BILLED -> counts?.billed
                    OrderFilter.PAID -> counts?.paid
                    OrderFilter.CREDIT -> counts?.paymentDue
                    OrderFilter.CANCELED -> counts?.canceled
                    OrderFilter.TO_PAY -> counts?.toPay
                }
                FilterChip(f.label, count, selected = model.filter == f) { model.chooseFilter(f) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val state = model.list) {
                UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(state.message, state.hint, onRetry = model::refresh)
                is UiState.Success -> if (state.data.data.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when (model.filter) {
                                OrderFilter.ALL -> "Aucune commande aujourd'hui."
                                OrderFilter.TO_PAY -> "Aucun ticket à encaisser."
                                else -> "Aucune commande « ${model.filter.label} » aujourd'hui."
                            },
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxSize().padding(end = Dimens.sm), state = listState, verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        items(state.data.data, key = { it.id }) { order -> OrderCard(order, model.currency, selected = order.id == model.selectedId) { model.select(order.id) } }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.height(Dimens.control).clip(Dimens.shape)
            .background(if (selected) colors.primaryContainer else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline, Dimens.shape)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimaryContainer else colors.onSurface)
        if (count != null && count > 0) {
            Spacer(Modifier.width(Dimens.s))
            Text("$count", style = MaterialTheme.typography.labelLarge, color = if (selected) colors.primary else colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OrderCard(order: OrderDto, currency: CurrencyInfo, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(Dimens.cardShape)
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.35f) else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, Dimens.cardShape)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(Dimens.sm),
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text("#${order.orderNumber}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusPill(order.status)
            if (order.needsReview) Pill("À vérifier", Warning, WarningContainer)
            Spacer(Modifier.weight(1f))
            Text(Money.format(order.total, currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            listOfNotNull(
                order.table?.code?.let { tableLabel(it) } ?: if (order.orderType == "pickup") "À emporter" else "Sans table",
                "${order.itemsCount} art.",
                order.waiter?.name,
                timeOf(order.dateTime),
                order.offlineNumber?.let { "Hors ligne : $it" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusPill(status: String) {
    val (fg, bg) = when (status) {
        "kot" -> Info to InfoContainer
        "billed" -> Warning to WarningContainer
        "paid" -> Success to SuccessContainer
        "payment_due", "canceled" -> Danger to DangerContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainer
    }
    Pill(statusLabel(status), fg, bg)
}

// ── The detail (web order panel) ─────────────────────────────────────────

@Composable
private fun OrderDetail(model: OrdersModel, order: OrderDetailDto) {
    val currency = model.currency
    val rights = model.rights
    Column(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    Text("Commande #${order.orderNumber}", style = MaterialTheme.typography.headlineSmall)
                    StatusPill(order.status)
                    if (order.needsReview) Pill("À vérifier", Warning, WarningContainer)
                }
                Text(
                    listOfNotNull(
                        order.table?.code?.let { "Table " + tableLabel(it) },
                        order.numberOfPax?.takeIf { order.table != null }?.let { "$it couvert" + if (it > 1) "s" else "" },
                        if (order.orderType == "pickup") "À emporter" else "Sur place",
                        order.waiter?.name?.let { "Serveur : $it" },
                        timeOf(order.dateTime),
                        order.terminal?.let { "Caisse : $it" },
                        order.customer?.let { "Client : $it" },
                        order.offlineNumber?.let { "Hors ligne : $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                order.cancelReason?.let { Banner(Tone.DANGER, "Commande annulée", detail = "Motif : $it") }
                order.orderNote?.takeIf { it.isNotBlank() }?.let { Banner(Tone.INFO, "Note de commande", detail = it) }
                order.stockWaiting?.let { waiting ->
                    Banner(Tone.WARNING, "En attente de stock", detail = waiting.message, action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                            AppButton("Envoyer", { model.sendWaiting(false) }, icon = Icons.AutoMirrored.Outlined.Send, enabled = !model.busy)
                            if (waiting.canForce) AppButton("Forcer l'envoi", { model.sendWaiting(true) }, kind = ButtonKind.DANGER, enabled = !model.busy)
                        }
                    })
                }

                // Lines by kitchen ticket, then those not sent yet (web order panel).
                order.kots.forEach { kot ->
                    val lines = order.items.filter { it.kotId == kot.id }
                    if (lines.isNotEmpty() || kot.status != "cancelled") KotGroup(model, kot, lines, currency)
                }
                // Lines « Séparer » moved here keep the kitchen ticket of the check they came from: shown on their own.
                val ownKots = order.kots.map { it.id }.toSet()
                val fromElsewhere = order.items.filter { it.kotId != null && it.kotId !in ownKots }
                if (fromElsewhere.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                        GroupHeader("Envoyés en cuisine (séparés d'une autre commande)", null)
                        fromElsewhere.forEach { line ->
                            LineRow(line, currency) {
                                if (CheckAction.canRemove(order, line, rights)) {
                                    IconBox(Icons.Outlined.DeleteOutline, "Retirer cet article", tint = Danger, enabled = !model.check.busy, onClick = { model.check.askRemoveLine(order, line) })
                                }
                            }
                        }
                    }
                }
                val unsent = order.items.filter { it.kotId == null }
                if (unsent.isNotEmpty()) {
                    val waiting = unsent.any { it.waitingForStock }
                    GroupHeader(
                        when {
                            waiting -> "Pas encore en cuisine — en attente de stock"
                            order.status == "kot" -> "Pas encore en cuisine"
                            else -> "Sans ticket cuisine"
                        },
                        null,
                    )
                    unsent.forEach { line ->
                        LineRow(line, currency) {
                            if (CheckAction.canRemove(order, line, rights)) {
                                IconBox(Icons.Outlined.DeleteOutline, "Retirer cet article", tint = Danger, enabled = !model.check.busy, onClick = { model.check.askRemoveLine(order, line) })
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Totals(order, currency)
                if (order.payments.isNotEmpty()) Payments(model, order, currency)
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        // A cancelled order has nothing left to do: no empty bar.
        if (order.status != "canceled") {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ActionBar(model, order)
        }
    }
}

@Composable
private fun KotGroup(model: OrdersModel, kot: KotDto, lines: List<OrderItemDto>, currency: CurrencyInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        GroupHeader("Ticket cuisine #${kot.number ?: kot.id} · ${kotLabel(kot.status)}" + (timeOf(kot.createdAt)?.let { " · $it" } ?: ""), kot.status) {
            if (kot.status == "food_ready") {
                AppButton("Servi", { model.served(kot.id) }, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Check, loading = model.servingKot == kot.id)
            }
        }
        lines.forEach { line ->
            LineRow(line, currency) {
                if (CheckAction.canRemove(model.current ?: return@LineRow, line, model.rights)) {
                    IconBox(Icons.Outlined.DeleteOutline, "Retirer cet article", tint = Danger, enabled = !model.check.busy, onClick = { model.current?.let { model.check.askRemoveLine(it, line) } })
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String, kotStatus: String?, trailing: @Composable () -> Unit = {}) {
    val tone = when (kotStatus) {
        "food_ready" -> Success
        "in_kitchen" -> Info
        "cancelled" -> Danger
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.control), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = tone, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun LineRow(line: OrderItemDto, currency: CurrencyInfo, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        Text("${line.quantity} ×", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(line.name + (line.variation?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
            line.modifiers.forEach { Text("+ ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            line.note?.takeIf { it.isNotBlank() }?.let { Text("« $it »", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.primary) }
        }
        if (line.waitingForStock) {
            Pill("En attente de stock", Warning, WarningContainer)
            Spacer(Modifier.width(Dimens.s))
        }
        Text(Money.plain(line.amount, currency), style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

@Composable
private fun Totals(order: OrderDetailDto, currency: CurrencyInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        TotalLine("Sous-total", Money.format(order.subTotal, currency))
        order.discountAmount?.takeIf { it > 0 }?.let { TotalLine("Remise", "-" + Money.format(it, currency)) }
        order.charges.forEach { c -> TotalLine(c.name + if (c.type == "percent") " (${pct(c.value)} %)" else "", Money.format(c.amount, currency)) }
        order.taxes.forEach { t -> TotalLine("${t.name} (${pct(t.percent)} %)", Money.format(t.amount, currency)) }
        order.tipAmount?.takeIf { it > 0 }?.let { TotalLine("Pourboire", Money.format(it, currency)) }
        TotalLine("Total", Money.format(order.total, currency), strong = true)
        if (order.amountPaid > 0) TotalLine("Payé", Money.format(order.amountPaid, currency))
        val due = order.total - order.amountPaid
        if (order.status != "canceled" && order.amountPaid > 0 && due > 0.005) TotalLine("Reste dû", Money.format(due, currency), strong = true)
    }
}

private fun pct(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** The payments taken on the check; cash and card can be corrected one into the other (« Encaisser » right). */
@Composable
private fun Payments(model: OrdersModel, order: OrderDetailDto, currency: CurrencyInfo) {
    val canFix = model.rights?.takePayment == true && order.status != "canceled"
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Text("Paiements", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        order.payments.forEach { p ->
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.control), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(methodLabel(p.method).replaceFirstChar { it.uppercase() } + (timeOf(p.createdAt)?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                    p.offlineAt?.let { Text("Encaissé hors ligne" + (timeOf(it)?.let { t -> " à $t" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(Money.format(p.amount, currency), style = MaterialTheme.typography.bodyLarge)
                if (canFix && (p.method == "cash" || p.method == "card")) {
                    Spacer(Modifier.width(Dimens.s))
                    AppButton(if (p.method == "cash") "Corriger : carte" else "Corriger : espèces", { model.check.openPaymentFix(order, p) }, kind = ButtonKind.TEXT, enabled = !model.check.busy)
                }
            }
        }
    }
}

@Composable
private fun TotalLine(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
    }
}

/** What the counter does to the order, by its state and the account's rights (web order panel). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionBar(model: OrdersModel, order: OrderDetailDto) {
    val rights = model.rights
    val running = order.status == "kot"
    val open = order.status == "kot" || order.status == "billed"
    FlowRow(Modifier.fillMaxWidth().padding(Dimens.m), horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        if (open && rights?.orders == true) AppButton("Nouveau ticket", model::newRound, icon = Icons.Outlined.Add, enabled = !model.busy)
        if (running && rights?.orders == true && order.stockWaiting == null) {
            AppButton("Addition", { model.bill(false) }, kind = ButtonKind.SECONDARY, icon = Icons.AutoMirrored.Outlined.ReceiptLong, enabled = !model.busy)
            AppButton("Addition et impression", { model.bill(true) }, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Print, enabled = !model.busy)
        }
        if (!running && order.status != "canceled") AppButton("Réimprimer", model::reprint, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Print, loading = model.busy)
        if (model.canPay(order)) AppButton("Encaisser", model::pay, icon = Icons.Outlined.Payments, enabled = !model.busy && !model.check.busy)
        val more = model.check.actions(order).filter { it != CheckAction.CANCEL }
        CheckActionsButton(more, enabled = !model.busy && !model.check.busy, onPick = { model.check.open(it, order) })
        if (CheckAction.CANCEL in model.check.actions(order)) {
            Spacer(Modifier.weight(1f))
            AppButton("Annuler la commande", { model.check.open(CheckAction.CANCEL, order) }, kind = ButtonKind.DANGER, icon = Icons.Outlined.Block, enabled = !model.busy && !model.check.busy)
        }
    }
}

// ── Windows ──────────────────────────────────────────────────────────────

@Composable
private fun OrderDialogs(model: OrdersModel) {
    CheckDialogs(model.check, model.currency)
    com.lebarock.lebarock_desktop.ui.payment.PaymentWindow(model.payment)

    model.printProblem?.let { message ->
        AppDialog("Ticket non imprimé", onDismiss = model::closePrintProblem, actions = { AppButton("Compris", model::closePrintProblem) }) {
            Banner(Tone.WARNING, message)
        }
    }

    model.noCaisse?.let { message ->
        AppDialog("Pas de caisse", onDismiss = model::closeNoCaisse, actions = { AppButton("Fermer", model::closeNoCaisse) }) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private val hourMinute = DateTimeFormatter.ofPattern("HH:mm")
private val dayHourMinute = DateTimeFormatter.ofPattern("dd/MM HH:mm")

/**
 * The caisse sends UTC (ISO 8601); the counter PC shows its own local time — with the day when it is not today
 * (« À encaisser » lists checks of every day).
 */
internal fun timeOf(iso: String?): String? = iso?.let {
    runCatching {
        val local = OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
        local.format(if (local.toLocalDate() == java.time.LocalDate.now()) hourMinute else dayHourMinute)
    }.getOrNull()
}
