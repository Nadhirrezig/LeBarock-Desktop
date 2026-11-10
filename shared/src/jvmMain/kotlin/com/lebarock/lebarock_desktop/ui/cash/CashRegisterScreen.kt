package com.lebarock.lebarock_desktop.ui.cash

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lebarock.lebarock_desktop.data.Money
import com.lebarock.lebarock_desktop.data.PrintOutcome
import com.lebarock.lebarock_desktop.data.remote.CashRegisterStateDto
import com.lebarock.lebarock_desktop.data.remote.CashReportDto
import com.lebarock.lebarock_desktop.data.remote.CashSessionDto
import com.lebarock.lebarock_desktop.data.remote.CashTransactionDto
import com.lebarock.lebarock_desktop.data.remote.ExpensesResponse
import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import com.lebarock.lebarock_desktop.print.Align
import com.lebarock.lebarock_desktop.print.PrintOp
import com.lebarock.lebarock_desktop.print.ReceiptPayload
import com.lebarock.lebarock_desktop.print.ReceiptRenderer
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppDialog
import com.lebarock.lebarock_desktop.ui.common.AppTopBar
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.Choice
import com.lebarock.lebarock_desktop.ui.common.ChoiceRow
import com.lebarock.lebarock_desktop.ui.common.ErrorBox
import com.lebarock.lebarock_desktop.ui.common.FormField
import com.lebarock.lebarock_desktop.ui.common.LoadingBox
import com.lebarock.lebarock_desktop.ui.common.ScrollArea
import com.lebarock.lebarock_desktop.ui.common.SelectField
import com.lebarock.lebarock_desktop.ui.common.TextInput
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.orders.timeOf
import com.lebarock.lebarock_desktop.ui.theme.Danger
import com.lebarock.lebarock_desktop.ui.theme.Dimens
import com.lebarock.lebarock_desktop.ui.theme.Success
import com.lebarock.lebarock_desktop.ui.theme.SuccessContainer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val ticketJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

@Composable
fun CashRegisterScreen(model: CashRegisterModel, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
    val st = model.current

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Caisse",
                subtitle = listOfNotNull(
                    st?.caisse?.name?.let { "Caisse : $it" },
                    st?.session?.let { "Session ${it.code} ouverte" } ?: st?.let { "Aucune session ouverte" },
                ).joinToString(" · ").ifEmpty { null },
                onBack = onBack,
                actions = { AppButton("Actualiser", { model.chooseTab(model.tab) }, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Refresh) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.l, vertical = Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            val tabs = CashTab.entries.filter { it != CashTab.EXPENSES || model.canExpenses }
            ChoiceRow(tabs.map { Choice(it, it.label) }, selected = model.tab, onSelect = model::chooseTab)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (model.tab) {
                    CashTab.SESSION -> when (val s = model.state) {
                        UiState.Loading -> LoadingBox(label = "Lecture de la caisse…")
                        is UiState.Error -> ErrorBox(s.message, s.hint, onRetry = model::refresh)
                        is UiState.Success -> SessionTab(model, s.data)
                    }
                    CashTab.REPORTS -> ReportsTab(model)
                    CashTab.EXPENSES -> ExpensesTab(model)
                }
            }
        }
    }

    CashDialogs(model)
}

// ── Session ──────────────────────────────────────────────────────────────

@Composable
private fun SessionTab(model: CashRegisterModel, st: CashRegisterStateDto) {
    val currency = model.currency
    val rights = model.rights
    val session = st.session
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        OfflineWarnings(st, currency)
        if (!st.enabled) {
            Banner(Tone.INFO, "Les sessions de caisse sont désactivées", detail = "Paramètres → Sessions de caisse, sur le web.")
        }
        if (session == null) {
            NoSession(model, st)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Session ${session.code}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(session.openedBy?.let { "Ouverte par $it" }, timeOf(session.openedAt)?.let { "à $it" }).joinToString(" "),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Espèces attendues", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Money.format(st.kpi?.expectedCash ?: session.expectedCash, currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Success)
                        }
                    }
                }
                st.kpi?.let { k ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Kpi("Fond de caisse", Money.format(k.openingCash, currency), Modifier.weight(1f))
                        Kpi("Ventes espèces", Money.format(k.salesCash, currency), Modifier.weight(1f))
                        Kpi("Entrées", Money.format(k.cashIn, currency), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Kpi("Sorties", Money.format(k.cashOut, currency), Modifier.weight(1f))
                        Kpi("Dépôts au coffre", Money.format(k.safeDrops, currency), Modifier.weight(1f))
                        Kpi("Remboursements", Money.format(k.refundsCash, currency), Modifier.weight(1f))
                    }
                }
                if (rights?.useCashRegister == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Movement.entries.forEach { m ->
                            AppButton(m.title, { model.movementWindow(m) }, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    AppButton("Rapport X", model::makeX, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Print, loading = model.making, modifier = Modifier.weight(1f))
                    if (rights?.useCashRegister == true) {
                        AppButton("Fermer la caisse (Z)", model::closeWindow, kind = ButtonKind.DANGER, icon = Icons.Outlined.Lock, modifier = Modifier.weight(1f))
                    }
                }
            }
            Movements(st.transactions, currency, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun NoSession(model: CashRegisterModel, st: CashRegisterStateDto) {
    val waiting = st.waitingApproval
    Card {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Icon(Icons.Outlined.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            if (waiting != null) {
                Text("La session ${waiting.code} attend sa validation", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Fermée" + (waiting.closedBy?.let { " par $it" } ?: "") + (timeOf(waiting.closedAt)?.let { " à $it" } ?: "") +
                        ". Un gérant la valide sur le web (Caisse → Validations) ; une nouvelle session pourra ensuite s'ouvrir.",
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Aucune session ouverte sur « ${st.caisse.name} »", style = MaterialTheme.typography.titleMedium)
                Text("Ouvrez la caisse avec son fond pour encaisser en espèces.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (model.rights?.useCashRegister == true) {
                    AppButton("Ouvrir la caisse", model::openSessionWindow, icon = Icons.Outlined.LockOpen, large = true)
                }
            }
        }
    }
}

@Composable
private fun OfflineWarnings(st: CashRegisterStateDto, currency: CurrencyInfo) {
    st.offline.devices.forEach { Banner(Tone.WARNING, it.text) }
    if (st.offline.waiting.count > 0) {
        Banner(
            Tone.WARNING,
            "${st.offline.waiting.count} paiement(s) hors ligne attendent une session sur cette caisse",
            detail = "Total ${Money.format(st.offline.waiting.amount, currency)} : ils seront enregistrés à l'ouverture de la session.",
        )
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = Dimens.cardShape, color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.padding(Dimens.l)) { content() }
    }
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = Dimens.shape, color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = Dimens.m, vertical = Dimens.sm)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The session's drawer movements, newest first (web « Transactions »). */
@Composable
private fun Movements(list: List<CashTransactionDto>, currency: CurrencyInfo, modifier: Modifier) {
    Surface(modifier, shape = Dimens.cardShape, color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Text("Mouvements du tiroir", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Dimens.l, end = Dimens.l, top = Dimens.m, bottom = Dimens.s))
            if (list.isEmpty()) {
                Text("Aucun mouvement pour l'instant.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.l))
            }
            val state = rememberLazyListState()
            Box(Modifier.weight(1f)) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { t ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(timeOf(t.occurredAt), t.reason ?: t.reference?.let { "Commande #$it" }, t.user).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text((if (t.decreases) "−" else "+") + Money.format(t.amount, currency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = if (t.decreases) Danger else Success)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

// ── Rapports X / Z ───────────────────────────────────────────────────────

@Composable
private fun ReportsTab(model: CashRegisterModel) {
    val currency = model.currency
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.width(440.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            ChoiceRow(listOf(Choice<String?>(null, "Tous"), Choice<String?>("x", "X"), Choice<String?>("z", "Z")), selected = model.reportFilter, onSelect = model::chooseReportFilter, itemWidth = 640.dp, modifier = Modifier.fillMaxWidth())
            when (val r = model.reports) {
                UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(r.message, r.hint, onRetry = model::loadReports)
                is UiState.Success -> if (r.data.isEmpty()) {
                    Text("Aucun rapport sur cette caisse.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.m))
                } else {
                    val selectedId = (model.selected as? UiState.Success)?.data?.id
                    val state = rememberLazyListState()
                    Box(Modifier.weight(1f)) {
                        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(Dimens.s), modifier = Modifier.fillMaxSize().padding(end = Dimens.sm)) {
                            items(r.data, key = { it.id }) { rep -> ReportRow(rep, currency, rep.id == selectedId) { model.selectReport(rep.id) } }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
        Surface(Modifier.weight(1f).fillMaxHeight(), shape = Dimens.cardShape, color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            when (val s = model.selected) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Choisissez un rapport à gauche.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(s.message, s.hint, onRetry = null)
                is UiState.Success -> Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(reportTitle(s.data), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        AppButton("Réimprimer", model::reprintSelected, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Print, loading = model.selectedReprinting)
                    }
                    model.selectedReprint?.let { PrintBanner(it) }
                    TicketPreview(s.data.ticket, Modifier.weight(1f))
                }
            }
        }
    }
}

private fun reportTitle(r: CashReportDto): String =
    (if (r.type == "z") "Rapport ${r.number ?: "Z"}" else "Rapport X") + (timeOf(r.generatedAt)?.let { " · $it" } ?: "")

@Composable
private fun ReportRow(r: CashReportDto, currency: CurrencyInfo, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(Dimens.cardShape)
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.35f) else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, Dimens.cardShape)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Dimens.m, vertical = Dimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(Dimens.shape).background(if (r.type == "z") colors.errorContainer else colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(r.type.uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(Dimens.sm))
        Column(Modifier.weight(1f)) {
            Text(if (r.type == "z") r.number ?: "Z" else "Rapport X", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(timeOf(r.generatedAt), r.generatedBy, r.sessionCode?.let { "Session $it" }, if (r.sessionStatus == "pending_approval" && r.type == "z") "à valider" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(Money.format(r.total, currency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The ticket as it prints (client, 2026-11-08: « the desktop screen shows exactly what prints »): the caisse's X / Z
 * JSON laid out by the same renderer as the paper, 48 columns, in a fixed-width font.
 */
@Composable
private fun TicketPreview(ticket: JsonObject?, modifier: Modifier = Modifier) {
    val ops = remember(ticket) {
        ticket?.let { t -> runCatching { ReceiptRenderer(48).render(ticketJson.decodeFromJsonElement(ReceiptPayload.serializer(), t)) }.getOrNull() }
    }
    Box(modifier.fillMaxWidth().clip(Dimens.shape).background(Color(0xFFF9FAFB)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, Dimens.shape)) {
        if (ops == null) {
            Text("Ticket indisponible.", Modifier.padding(Dimens.m), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Box
        }
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(Dimens.m), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.width(8.8.dp * 48)) {
                ops.forEach { op ->
                    when (op) {
                        is PrintOp.Text -> Text(
                            op.text,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = if (op.big) 18.sp else 14.sp, fontWeight = if (op.bold || op.big) FontWeight.Bold else FontWeight.Normal),
                            textAlign = when (op.align) { Align.CENTER -> TextAlign.Center; Align.RIGHT -> TextAlign.End; Align.LEFT -> TextAlign.Start },
                            softWrap = false, maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        is PrintOp.Feed -> Spacer(Modifier.height((op.lines * 18).dp))
                        PrintOp.Cut -> Text("✂ ─ ─ ─ ─ ─ ─ ─ ─", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is PrintOp.Qr -> Text("[QR]", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp))
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun PrintBanner(p: PrintOutcome) {
    Banner(if (p.ok) Tone.SUCCESS else Tone.WARNING, if (p.ok) "Rapport imprimé" else "Rapport non imprimé", detail = p.message)
}

// ── Dépenses ─────────────────────────────────────────────────────────────

@Composable
private fun ExpensesTab(model: CashRegisterModel) {
    val currency = model.currency
    when (val e = model.expenses) {
        UiState.Loading -> LoadingBox(label = "Lecture des dépenses…")
        is UiState.Error -> ErrorBox(e.message, e.hint, onRetry = model::loadExpenses)
        is UiState.Success -> Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m), modifier = Modifier.fillMaxSize()) {
            if (model.rights?.createExpense == true) {
                Surface(Modifier.width(500.dp).fillMaxHeight(), shape = Dimens.cardShape, color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    ExpenseFormView(model, e.data)
                }
            }
            ExpenseList(e.data, currency, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ExpenseFormView(model: CashRegisterModel, data: ExpensesResponse) {
    val f = model.expenseForm
    val scroll = rememberScrollState()
    Box {
        Column(Modifier.verticalScroll(scroll).padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Text("Ajouter une dépense", style = MaterialTheme.typography.titleMedium)
            FormField("Titre") { TextInput(f.title, { v -> model.editExpense { it.copy(title = v) } }, placeholder = "Ex. Pain, glaçons", modifier = Modifier.fillMaxWidth()) }
            FormField("Catégorie") {
                SelectField(
                    data.categories.map { it.name }, data.categories.firstOrNull { it.id == f.categoryId }?.name,
                    { name -> model.editExpense { it.copy(categoryId = data.categories.firstOrNull { c -> c.name == name }?.id) } },
                    placeholder = if (data.categories.isEmpty()) "Aucune catégorie (à créer sur le web)" else "Choisir…", modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                FormField("Montant (${model.currency.symbol})", Modifier.weight(1f)) {
                    TextInput(f.amount, { v -> model.editExpense { it.copy(amount = v.filter { c -> c.isDigit() || c == '.' || c == ',' }) } }, placeholder = "0", modifier = Modifier.fillMaxWidth())
                }
                FormField("Date", Modifier.weight(1f)) {
                    TextInput(f.date, { v -> model.editExpense { it.copy(date = v.filter { c -> c.isDigit() || c == '/' }.take(10)) } }, placeholder = "jj/mm/aaaa", modifier = Modifier.fillMaxWidth())
                }
            }
            FormField("Statut du paiement") {
                ChoiceRow(listOf(Choice(true, "Payée"), Choice(false, "En attente")), selected = f.paid, onSelect = { p -> model.editExpense { it.copy(paid = p) } }, itemWidth = 640.dp, modifier = Modifier.fillMaxWidth())
            }
            if (f.paid) {
                FormField("Mode de paiement", help = if (f.method == "cash") "Payée en espèces : sortie du tiroir de cette caisse." else null) {
                    SelectField(
                        data.methods.map { it.label }, data.methods.firstOrNull { it.value == f.method }?.label,
                        { label -> model.editExpense { it.copy(method = data.methods.firstOrNull { m -> m.label == label }?.value) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            FormField("Description (facultatif)") { TextInput(f.description, { v -> model.editExpense { it.copy(description = v) } }, modifier = Modifier.fillMaxWidth()) }
            f.error?.let { Banner(Tone.DANGER, it) }
            AppButton("Enregistrer la dépense", model::saveExpense, icon = Icons.Outlined.Add, loading = f.busy, modifier = Modifier.fillMaxWidth())
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun ExpenseList(data: ExpensesResponse, currency: CurrencyInfo, modifier: Modifier) {
    Surface(modifier, shape = Dimens.cardShape, color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.padding(start = Dimens.l, end = Dimens.l, top = Dimens.m, bottom = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                Text("Dépenses du jour", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("Total ${Money.format(data.total, currency)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (data.data.isEmpty()) {
                Text("Aucune dépense aujourd'hui.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.l))
            }
            val state = rememberLazyListState()
            Box(Modifier.weight(1f)) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(data.data, key = { it.id }) { x ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(x.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(x.category, if (x.status == "paid") x.methodLabel ?: "Payée" else "En attente", if (x.fromDrawer) "sortie du tiroir" else null).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(Money.format(x.amount, currency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

// ── Windows ──────────────────────────────────────────────────────────────

@Composable
private fun CashDialogs(model: CashRegisterModel) {
    val currency = model.currency
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    when (val d = model.dialog) {
        is CashDialog.Open -> AmountDialog(
            model, d, title = "Ouvrir la caisse", amountLabel = "Fond de caisse (${currency.symbol})", amount = d.amount, reason = null,
            onAmount = { v -> model.edit { (it as CashDialog.Open).copy(amount = v, error = null) } },
            summary = "Ouvrir une session sur « ${model.current?.caisse?.name ?: ""} » avec un fond de caisse de ${Money.format(com.lebarock.lebarock_desktop.pos.parseAmount(d.amount.ifBlank { "0" }) ?: 0.0, currency)} ?",
            confirmLabel = "Ouvrir la caisse",
        )
        is CashDialog.Move -> AmountDialog(
            model, d, title = d.kind.title, amountLabel = "Montant (${currency.symbol})", amount = d.amount, reason = d.reason, reasonHint = d.kind.hint,
            onAmount = { v -> model.edit { (it as CashDialog.Move).copy(amount = v, error = null) } },
            onReason = { v -> model.edit { (it as CashDialog.Move).copy(reason = v, error = null) } },
            summary = "${d.kind.title} : ${Money.format(com.lebarock.lebarock_desktop.pos.parseAmount(d.amount) ?: 0.0, currency)} à ${d.kind.verb}. Motif : « ${d.reason.trim()} ».",
            confirmLabel = "Confirmer",
            expected = model.current?.kpi?.expectedCash,
        )
        is CashDialog.Close -> CloseDialog(model, d, currency, (windowHeight - 300.dp).coerceAtLeast(220.dp))
        null -> Unit
    }
    model.shown?.let { shown ->
        // What the window's other parts take (title, print line, actions, paddings; the Z's figures): the ticket gets the rest.
        val taken = 300.dp + (if (shown.report.type == "z") 110.dp else 0.dp) + (if (shown.report.stock != null) 220.dp else 0.dp)
        ShownDialog(model, shown, currency, (windowHeight - taken).coerceAtLeast(160.dp))
    }
}

@Composable
private fun AmountDialog(
    model: CashRegisterModel,
    d: CashDialog,
    title: String,
    amountLabel: String,
    amount: String,
    reason: String?,
    onAmount: (String) -> Unit,
    summary: String,
    confirmLabel: String,
    reasonHint: String? = null,
    onReason: ((String) -> Unit)? = null,
    expected: Double? = null,
) {
    AppDialog(
        title, onDismiss = model::dismiss, dismissible = !d.busy,
        actions = {
            if (d.confirm) {
                AppButton("Retour", model::dismiss, kind = ButtonKind.SECONDARY, enabled = !d.busy)
                AppButton(confirmLabel, model::confirm, loading = d.busy)
            } else {
                AppButton("Annuler", model::dismiss, kind = ButtonKind.SECONDARY)
                AppButton("Continuer", model::next)
            }
        },
    ) {
        if (d.confirm) {
            Text(summary, style = MaterialTheme.typography.bodyLarge)
        } else {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            FormField(amountLabel, help = expected?.let { "Dans le tiroir : ${Money.format(it, model.currency)}" }) {
                TextInput(
                    amount, { onAmount(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) }, placeholder = "0", large = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent(enterTo(model::next)),
                )
            }
            if (reason != null && onReason != null) {
                FormField("Motif") { TextInput(reason, onReason, placeholder = reasonHint, modifier = Modifier.fillMaxWidth().onPreviewKeyEvent(enterTo(model::next))) }
            }
        }
        d.error?.let { Banner(Tone.DANGER, it) }
    }
}

/** « Fermer la caisse »: the cash expected, the count by value (− / +), the difference, a note; then « Confirmer ». */
@Composable
private fun CloseDialog(model: CashRegisterModel, d: CashDialog.Close, currency: CurrencyInfo, bodyMax: androidx.compose.ui.unit.Dp) {
    val st = model.current
    val expected = st?.kpi?.expectedCash ?: st?.session?.expectedCash ?: 0.0
    val diff = d.count.difference(expected)
    AppDialog(
        "Fermer la caisse (rapport Z)", onDismiss = model::dismiss, dismissible = !d.busy, width = 640.dp,
        actions = {
            if (d.confirm) {
                AppButton("Retour", model::dismiss, kind = ButtonKind.SECONDARY, enabled = !d.busy)
                AppButton("Fermer la session", model::confirm, kind = ButtonKind.DANGER, loading = d.busy)
            } else {
                AppButton("Annuler", model::dismiss, kind = ButtonKind.SECONDARY)
                AppButton("Continuer", model::next)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Kpi("Espèces attendues", Money.format(expected, currency), Modifier.weight(1f))
            Kpi("Compté", Money.format(d.count.total, currency), Modifier.weight(1f))
            Surface(Modifier.weight(1f), shape = Dimens.shape, color = if (kotlin.math.abs(diff) < 0.005) SuccessContainer else MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(horizontal = Dimens.m, vertical = Dimens.sm)) {
                    Text("Écart", style = MaterialTheme.typography.bodyMedium)
                    Text((if (diff > 0) "+" else "") + Money.format(diff, currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (kotlin.math.abs(diff) < 0.005) Success else Danger)
                }
            }
        }
        if (d.confirm) {
            Text(
                "Fermer la session ${st?.session?.code ?: ""} ? Le rapport Z sera imprimé et la session attendra sa validation sur le web ; une nouvelle session s'ouvrira après.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (d.notes.isNotBlank()) Text("Note : « ${d.notes.trim()} »", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            st?.let { OfflineWarnings(it, currency) }
            ScrollArea(maxHeight = bodyMax) {
                Text("Comptez le tiroir par valeur.", style = MaterialTheme.typography.labelMedium)
                d.count.values.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Text(Money.format(v.toDouble(), currency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(140.dp))
                        StepBox(Icons.Outlined.Remove, "Un de moins", enabled = d.count.count(v) > 0) { model.edit { (it as CashDialog.Close).copy(count = it.count.minus(v)) } }
                        TextInput(d.count.count(v).toString(), { t -> model.edit { (it as CashDialog.Close).copy(count = it.count.set(v, t)) } }, modifier = Modifier.width(88.dp))
                        StepBox(Icons.Outlined.Add, "Un de plus") { model.edit { (it as CashDialog.Close).copy(count = it.count.plus(v)) } }
                        Spacer(Modifier.weight(1f))
                        Text(Money.format(v.toDouble() * d.count.count(v), currency), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FormField("Note (facultatif)") { TextInput(d.notes, { v -> model.edit { (it as CashDialog.Close).copy(notes = v) } }, modifier = Modifier.fillMaxWidth()) }
            }
        }
        d.error?.let { Banner(Tone.DANGER, it) }
    }
}

/** Enter in a field: the window's next step (« Continuer »), as its button. */
private fun enterTo(action: () -> Unit): (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { e ->
    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Enter) {
        action()
        true
    } else {
        false
    }
}

@Composable
private fun StepBox(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(Dimens.control).clip(Dimens.shape).border(1.dp, MaterialTheme.colorScheme.outline, Dimens.shape)
            .clickable(enabled = enabled, onClick = onClick).pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

/** An X or a Z just made: where it printed, the ticket; after a Z the count and the stock part with its request. */
@Composable
private fun ShownDialog(model: CashRegisterModel, s: ReportShown, currency: CurrencyInfo, bodyMax: androidx.compose.ui.unit.Dp) {
    val r = s.report
    val stock = r.stock
    val canSend = stock != null && stock.tillLinked && s.stockResult?.ok != true && model.rights?.useCashRegister == true
    AppDialog(
        if (r.type == "z") "Rapport ${r.number ?: "Z"} — session fermée" else "Rapport X",
        onDismiss = model::closeShown, dismissible = !s.stockBusy, width = 720.dp,
        actions = {
            AppButton("Réimprimer", model::reprintShown, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Print, loading = s.reprinting)
            if (canSend) AppButton("Envoyer la demande au stock", model::sendStockRequest, icon = Icons.Outlined.Inventory2, loading = s.stockBusy)
            AppButton(if (canSend) "Plus tard" else "Fermer", model::closeShown, kind = if (canSend) ButtonKind.SECONDARY else ButtonKind.PRIMARY, enabled = !s.stockBusy)
        },
    ) {
        s.print?.let { PrintBanner(it) }
        r.session?.takeIf { r.type == "z" }?.let { z -> ZFigures(z, currency) }
        if (stock != null) {
            ScrollArea(maxHeight = 200.dp) { StockPart(stock, s.stockResult) }
        }
        TicketPreview(r.ticket, Modifier.height(bodyMax.coerceAtMost(480.dp)))
    }
}

@Composable
private fun ZFigures(z: CashSessionDto, currency: CurrencyInfo) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Kpi("Espèces attendues", Money.format(z.expectedCash, currency), Modifier.weight(1f))
        Kpi("Compté", Money.format(z.countedCash ?: 0.0, currency), Modifier.weight(1f))
        Kpi("Écart", (if ((z.discrepancy ?: 0.0) > 0) "+" else "") + Money.format(z.discrepancy ?: 0.0, currency), Modifier.weight(1f))
    }
    Text("La session attend sa validation sur le web (Caisse → Validations).", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The Z's stock summary (web C7): what was sold, what is left, what to refill, and the request's answer. */
@Composable
private fun StockPart(stock: com.lebarock.lebarock_desktop.data.remote.ReportStockDto, result: PrintOutcome?) {
    Text("Stock" + (stock.department?.let { " — $it" } ?: ""), style = MaterialTheme.typography.titleMedium)
    when {
        !stock.ok || !stock.tillLinked -> Banner(Tone.WARNING, stock.message ?: "Stock indisponible.")
        stock.lines.isEmpty() -> Text("Pas de stock fix pour ce département : rien à réapprovisionner.", style = MaterialTheme.typography.bodyMedium)
        else -> {
            val toRefill = stock.lines.count { it.toRefill > 0 }
            Text("$toRefill article(s) à réapprovisionner sur ${stock.lines.size}. La demande arrive au stock, où elle est vérifiée et confirmée.", style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, Dimens.shape).padding(Dimens.s)) {
                Row(Modifier.fillMaxWidth().padding(vertical = Dimens.xs)) {
                    Text("Article", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    listOf("Vendu", "Restant", "À réappro.").forEach { Text(it, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.width(110.dp)) }
                }
                stock.lines.forEach { l ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = Dimens.xs)) {
                        Text(l.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(qty(l.sold), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.width(110.dp))
                        Text(qty(l.left) + (l.unit?.let { " $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.width(110.dp))
                        Text(if (l.toRefill > 0) qty(l.toRefill) + (l.unit?.let { " $it" } ?: "") else "—", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(110.dp))
                    }
                }
            }
        }
    }
    result?.let { Banner(if (it.ok) Tone.SUCCESS else Tone.DANGER, it.message) }
}

private fun qty(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.3f".format(v).trimEnd('0').trimEnd('.', ',')
