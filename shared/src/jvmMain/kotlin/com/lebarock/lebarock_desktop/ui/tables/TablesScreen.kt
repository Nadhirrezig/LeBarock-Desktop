package com.lebarock.lebarock_desktop.ui.tables

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.data.Money
import com.lebarock.lebarock_desktop.data.remote.AreaDto
import com.lebarock.lebarock_desktop.data.remote.TableDto
import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import com.lebarock.lebarock_desktop.pos.tableLabel
import com.lebarock.lebarock_desktop.ui.check.FloorState
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppDialog
import com.lebarock.lebarock_desktop.ui.common.AppTopBar
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.Choice
import com.lebarock.lebarock_desktop.ui.common.ChoiceRow
import com.lebarock.lebarock_desktop.ui.common.ErrorBox
import com.lebarock.lebarock_desktop.ui.common.LoadingBox
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlin.math.max
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The floor (D4), as the web's Tables page: tabs per area, a legend, a coloured tile per table. */
@Composable
fun TablesScreen(model: TablesModel, caisseName: String?, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Tables",
                subtitle = listOfNotNull(caisseName?.let { "Caisse : $it" }, updatedLabel(model.updatedAt)).joinToString(" · ").ifEmpty { null },
                onBack = onBack,
                actions = { AppButton("Actualiser", model::refresh, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Refresh) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val state = model.areas) {
                UiState.Loading -> LoadingBox(label = "Chargement des tables…")
                is UiState.Error -> ErrorBox(state.message, state.hint, onRetry = model::refresh)
                is UiState.Success -> Floor(model, state.data)
            }
        }
    }

    FloorDialogs(model)
}

/** « À jour · il y a 12 s », kept current while the screen is shown. */
@Composable
private fun updatedLabel(at: Long?): String? {
    if (at == null) return null
    val now by produceState(System.currentTimeMillis(), at) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((now - at) / 1000).coerceAtLeast(0)
    return if (seconds < 60) "À jour · il y a $seconds s" else "À jour · il y a ${seconds / 60} min"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Floor(model: TablesModel, all: List<AreaDto>) {
    val withTables = all.filter { it.tables.isNotEmpty() }
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.l, vertical = Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        // Area tabs (web: « Toutes les salles » + each area) and the colour legend.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            if (withTables.size > 1) {
                ChoiceRow(
                    listOf(Choice<Long?>(null, "Toutes les salles")) + withTables.map { Choice<Long?>(it.id, it.name) },
                    model.areaFilter, model::chooseArea,
                )
            }
            Legend(Modifier.align(Alignment.CenterVertically))
        }
        if (withTables.isEmpty()) {
            Text("Aucune table active : ajoutez-en dans la caisse (Tables).", style = MaterialTheme.typography.bodyLarge)
            return
        }
        // The web's grid (lg:grid-cols-4): 4 tiles a row at a laptop's width, fewer when narrower; the
        // tiles of a row share one height, as CSS grid rows do.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val gap = Dimens.m
            // Room left for tiles: the width less the scrollbar's strip.
            val columns = max(1, ((maxWidth - Dimens.m + gap) / (TILE_MIN_WIDTH + gap)).toInt())
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(end = Dimens.m, bottom = Dimens.l),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                model.shownAreas.forEach { area ->
                    AreaHeading(area)
                    area.tables.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { t -> TableTile(model, t, model.currency, Modifier.weight(1f).fillMaxHeight()) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

private val TILE_MIN_WIDTH = 260.dp

@Composable
private fun AreaHeading(area: AreaDto) {
    Row(Modifier.padding(top = Dimens.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Text(area.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(shape = Dimens.shape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Text("${area.tables.size} table" + if (area.tables.size > 1) "s" else "", Modifier.padding(horizontal = Dimens.s, vertical = Dimens.xs), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.m), verticalAlignment = Alignment.CenterVertically) {
        listOf(FloorState.FREE to "Libre", FloorState.BUSY to "Occupée", FloorState.BILL to "Addition demandée", FloorState.RESERVED to "Réservée").forEach { (s, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(s.color))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private val White80 = Color.White.copy(alpha = 0.85f)
private val White20 = Color.White.copy(alpha = 0.22f)

/**
 * A table as the web tile shows it: its code and places, its state, the party (covers, since when),
 * what is open on it (tickets, total), the next reservation; badges « Prêt n » (kitchen tickets ready)
 * and « QR n » (guest rounds waiting); « En attente de stock » when a round is held.
 */
@Composable
private fun TableTile(model: TablesModel, t: TableDto, currency: CurrencyInfo, modifier: Modifier = Modifier) {
    val state = FloorState.of(t)
    val visit = t.session
    val merged = visit?.mergedInto
    // The badges sit on the tile's top edge, as on the web: room above for them.
    Box(modifier.padding(top = 10.dp)) {
        Column(
            Modifier.fillMaxSize().heightIn(min = 150.dp).clip(Dimens.cardShape).background(state.color)
                .clickable { model.tap(t) }.pointerHoverIcon(PointerIcon.Hand)
                .padding(Dimens.m),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(t.code, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${t.seats} place" + if (t.seats > 1) "s" else "", style = MaterialTheme.typography.bodySmall, color = White80, fontWeight = FontWeight.Medium)
                }
                Surface(shape = RoundedCornerShape(50), color = White20) {
                    Text(state.label(t), Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            if (visit != null) {
                Text(
                    "${visit.pax} couv." + (since(visit.openedAt)?.let { if (it == "0 min") " · à l'instant" else " · depuis $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.Medium,
                )
            }
            if (t.openOrders > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (t.openOrders > 1) "${t.openOrders} tickets" else t.activeOrder?.let { "#${it.orderNumber} · ${it.kotCount} ticket" + (if (it.kotCount > 1) "s" else "") + " cuisine" } ?: "1 ticket",
                        style = MaterialTheme.typography.bodyMedium, color = White80, modifier = Modifier.weight(1f).padding(end = Dimens.s), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(Money.format(t.openTotal, currency), style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            t.nextReservation?.let { r ->
                Text(
                    listOfNotNull("Résa " + (clock(r.at) ?: ""), r.name ?: "Client", "${r.partySize} pers.").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = White80, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (t.stockWaiting) {
                Surface(shape = RoundedCornerShape(50), color = Color.White) {
                    Text("En attente de stock", Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFFB45309), fontWeight = FontWeight.Bold)
                }
            }
            // A party opened (web, handheld) with nothing ordered: order for it, or free the table.
            if (visit != null && merged == null && t.activeOrder == null && t.openOrders == 0 && model.rights?.orders == true) {
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    TileButton("Commander") { model.tap(t) }
                    TileButton("Libérer") { model.askRelease(t) }
                }
            }
        }
        if (t.readyTickets > 0) Badge("Prêt ${t.readyTickets}", Color(0xFF15803D), Modifier.align(Alignment.TopStart).offset(x = 12.dp, y = (-10).dp))
        if (t.pendingQrRounds > 0) Badge("QR ${t.pendingQrRounds}", Color(0xFFB45309), Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = (-10).dp))
    }
}

@Composable
private fun TileButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(Dimens.shape).background(White20)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Dimens.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Badge(text: String, fg: Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = Color.White, shadowElevation = 2.dp) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.Bold)
    }
}

private val hourMinute = DateTimeFormatter.ofPattern("HH:mm")

/** The caisse's time (ISO 8601) in the PC's own time, « 20:30 ». */
private fun clock(iso: String?): String? = iso?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(hourMinute) }.getOrNull()
}

/** How long the party has been seated: « 25 min », « 1 h 05 ». */
internal fun since(iso: String?, now: OffsetDateTime = OffsetDateTime.now()): String? {
    val start = iso?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
    val minutes = Duration.between(start, now).toMinutes().coerceAtLeast(0)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h " + "%02d".format(minutes % 60)
}

@Composable
private fun FloorDialogs(model: TablesModel) {
    model.covers?.let { pick ->
        AppDialog(
            "Nombre de couverts", onDismiss = model::closeCovers, width = 420.dp,
            subtitle = "Table ${tableLabel(pick.table.code)} · ${pick.table.seats} places",
            actions = {
                AppButton("Annuler", model::closeCovers, kind = ButtonKind.SECONDARY)
                AppButton("Confirmer", model::confirmCovers)
            },
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = Dimens.m), horizontalArrangement = Arrangement.spacedBy(Dimens.l, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                CoverKey(Icons.Outlined.Remove, "Moins", enabled = pick.count > 1) { model.coversStep(-1) }
                Text("${pick.count}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
                CoverKey(Icons.Outlined.Add, "Plus", enabled = true, primary = true) { model.coversStep(+1) }
            }
        }
    }

    model.releasing?.let { t ->
        AppDialog(
            "Libérer la table ${tableLabel(t.code)} ?", onDismiss = model::closeRelease, width = 480.dp, dismissible = !model.busy,
            actions = {
                AppButton("Annuler", model::closeRelease, kind = ButtonKind.SECONDARY, enabled = !model.busy)
                AppButton("Libérer", model::confirmRelease, kind = ButtonKind.DANGER, loading = model.busy)
            },
        ) {
            Text("Personne n'a commandé à cette table : elle redevient libre.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CoverKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainer
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        Modifier.size(64.dp).clip(Dimens.cardShape).background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (primary && enabled) Color.White else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
    }
}
