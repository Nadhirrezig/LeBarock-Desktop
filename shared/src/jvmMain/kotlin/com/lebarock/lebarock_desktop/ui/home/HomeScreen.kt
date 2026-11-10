package com.lebarock.lebarock_desktop.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.data.remote.TerminalDto
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppDialog
import com.lebarock.lebarock_desktop.ui.common.AppTopBar
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.Initials
import com.lebarock.lebarock_desktop.ui.common.LoadingBox
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.theme.Danger
import com.lebarock.lebarock_desktop.ui.theme.DangerContainer
import com.lebarock.lebarock_desktop.ui.theme.Dimens
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

const val NO_CAISSE_MESSAGE = "Ce compte n'a pas de caisse. Demandez au gérant de vous en attribuer une."

@Composable
fun HomeScreen(model: HomeModel, restaurantName: String, onSwitchUser: () -> Unit, reviewCount: Int = 0, onOpenReview: () -> Unit = {}) {
    val session by model.session.collectAsState()
    val s = session ?: return
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { HomeTopBar(s, restaurantName, onSwitchUser, model::openPicker) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 1200.dp).fillMaxWidth().padding(Dimens.l),
                verticalArrangement = Arrangement.spacedBy(Dimens.l),
            ) {
                // A « Hors ligne » session is the Caisse's account, not a person.
                Text(if (s.offline) "Bonjour" else "Bonjour ${s.user.name.substringBefore(' ')}", style = MaterialTheme.typography.headlineSmall)
                when {
                    s.offline -> Banner(
                        Tone.WARNING,
                        "Session hors ligne",
                        detail = "Commandes, additions et paiements (avec le code de paiement du gérant) sont gardés sur ce poste et " +
                            "envoyés à la caisse dès qu'elle répond. La caisse, les remises, le crédit et l'annulation attendent son retour.",
                    )
                    s.rights.kitchenOnly -> Banner(
                        Tone.INFO,
                        "Compte cuisine",
                        detail = "L'écran cuisine est sur l'écran 22″ (application AltosPOS Android). Ici, seuls les réglages sont disponibles.",
                    )
                    s.caisse == null -> Banner(
                        Tone.WARNING,
                        "Aucune caisse pour ce compte",
                        detail = if (s.canSwitchCaisse) "Choisissez la caisse de ce comptoir pour prendre des commandes et encaisser." else NO_CAISSE_MESSAGE,
                        action = if (s.canSwitchCaisse) {
                            { AppButton("Choisir une caisse", model::openPicker) }
                        } else {
                            null
                        },
                    )
                }
                com.lebarock.lebarock_desktop.ui.offline.ReviewHomeCard(reviewCount, onOpenReview)
                Tiles(HomeTiles.of(s.rights)) { model.open(it, hasCaisse = s.caisse != null) }
            }
        }
    }

    if (model.noCaisse) NoCaisseDialog(canChoose = s.canSwitchCaisse, onChoose = model::openPicker, onClose = model::closeNoCaisse)
    model.picker?.let { CaissePicker(it, current = s.caisse?.id, switching = model.switching, error = model.pickerError, onChoose = model::choose, onClose = model::closePicker) }
}

@Composable
private fun HomeTopBar(s: Session, restaurantName: String, onSwitchUser: () -> Unit, onPickCaisse: () -> Unit) {
    AppTopBar(title = restaurantName.ifBlank { "AltosPOS" }, subtitle = clockText()) {
        CaisseChip(s, onPickCaisse)
        Spacer(Modifier.width(Dimens.s))
        Initials(s.user.name, s.user.id, 40.dp)
        Column(Modifier.widthIn(max = 220.dp)) {
            Text(s.user.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.roleLabel.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.width(Dimens.s))
        AppButton("Changer d'utilisateur", onSwitchUser, kind = ButtonKind.SECONDARY, icon = Icons.AutoMirrored.Outlined.Logout)
    }
}

/** The account's Caisse; red when there is none; a manager clicks it to choose another. */
@Composable
private fun CaisseChip(s: Session, onPick: () -> Unit) {
    val caisse = s.caisse
    val colors = MaterialTheme.colorScheme
    val fg = if (caisse == null) Danger else colors.onSurface
    Row(
        Modifier.height(Dimens.control).clip(Dimens.shape)
            .background(if (caisse == null) DangerContainer else colors.surface)
            .border(1.dp, if (caisse == null) Danger.copy(alpha = 0.4f) else colors.outline, Dimens.shape)
            .clickable(enabled = s.canSwitchCaisse, onClick = onPick)
            .pointerHoverIcon(if (s.canSwitchCaisse) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Storefront, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Dimens.s))
        Text(if (caisse != null) "Caisse : ${caisse.name}" else "Aucune caisse", style = MaterialTheme.typography.labelLarge, color = fg)
        if (s.canSwitchCaisse) {
            Spacer(Modifier.width(Dimens.s))
            Icon(Icons.Outlined.SwapHoriz, contentDescription = "Changer de caisse", tint = fg, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun clockText(): String {
    val format = remember { DateTimeFormatter.ofPattern("EEEE d MMMM · HH:mm", Locale.FRANCE) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000L * (60 - now.second).coerceAtLeast(1))
        }
    }
    return now.format(format).replaceFirstChar { it.titlecase(Locale.FRANCE) }
}

private fun Section.icon(): ImageVector = when (this) {
    Section.POS -> Icons.Outlined.PointOfSale
    Section.TABLES -> Icons.Outlined.TableRestaurant
    Section.ORDERS -> Icons.AutoMirrored.Outlined.ReceiptLong
    Section.PAYMENT -> Icons.Outlined.Payments
    Section.CASH_REGISTER -> Icons.Outlined.AccountBalanceWallet
    Section.SETTINGS -> Icons.Outlined.Settings
}

@Composable
private fun Tiles(sections: List<Section>, onOpen: (Section) -> Unit) {
    // A scrollbar shows there is more below (125 %, or a banner above the tiles; client rule, 2026-11-08).
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    Box {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.m),
        verticalArrangement = Arrangement.spacedBy(Dimens.m),
        contentPadding = PaddingValues(bottom = Dimens.l, end = Dimens.m),
        state = grid,
    ) {
        items(sections, key = { it.name }) { section ->
            Card(
                onClick = { onOpen(section) },
                shape = Dimens.cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                // One height for every tile: a row never has a shorter card.
                modifier = Modifier.fillMaxWidth().height(196.dp).pointerHoverIcon(PointerIcon.Hand),
            ) {
                Column(Modifier.fillMaxWidth().padding(Dimens.l)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(56.dp).clip(Dimens.shape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(section.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        section.comesIn?.let {
                            Surface(shape = Dimens.shape, color = MaterialTheme.colorScheme.surfaceContainer) {
                                Text("Étape $it", Modifier.padding(horizontal = Dimens.sm, vertical = Dimens.xs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(Dimens.m))
                    Text(section.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(Dimens.xs))
                    Text(section.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    androidx.compose.foundation.VerticalScrollbar(
        androidx.compose.foundation.rememberScrollbarAdapter(grid),
        Modifier.align(Alignment.CenterEnd).matchParentSize().wrapContentWidth(Alignment.End),
        style = androidx.compose.foundation.LocalScrollbarStyle.current,
    )
    }
}

@Composable
private fun NoCaisseDialog(canChoose: Boolean, onChoose: () -> Unit, onClose: () -> Unit) {
    AppDialog(
        "Pas de caisse",
        onDismiss = onClose,
        actions = {
            AppButton("Fermer", onClose, kind = ButtonKind.SECONDARY)
            if (canChoose) AppButton("Choisir une caisse", onChoose)
        },
    ) {
        Text(
            if (canChoose) "Ce compte n'a pas encore de caisse. Choisissez la caisse de ce comptoir." else NO_CAISSE_MESSAGE,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun CaissePicker(
    state: UiState<List<TerminalDto>>,
    current: Long?,
    switching: Boolean,
    error: String?,
    onChoose: (TerminalDto) -> Unit,
    onClose: () -> Unit,
) {
    AppDialog(
        "Choisir la caisse",
        onDismiss = onClose,
        dismissible = !switching,
        subtitle = "La caisse du compte : son menu, son tiroir et sa session de caisse. Ce choix reste sur le compte.",
        actions = { AppButton("Fermer", onClose, kind = ButtonKind.SECONDARY, enabled = !switching) },
    ) {
        when (state) {
            UiState.Loading -> Box(Modifier.fillMaxWidth().height(120.dp)) { LoadingBox() }
            is UiState.Error -> Banner(Tone.DANGER, state.message, detail = state.hint)
            is UiState.Success -> Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                if (state.data.isEmpty()) Text("Aucune caisse active dans cette succursale.", style = MaterialTheme.typography.bodyLarge)
                state.data.forEach { t ->
                    val selected = t.id == current
                    Row(
                        Modifier.fillMaxWidth().height(Dimens.primary).clip(Dimens.shape)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, Dimens.shape)
                            .clickable(enabled = !switching) { onChoose(t) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = Dimens.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(Dimens.sm))
                        Text(t.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (selected) Text("Actuelle", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        error?.let { Banner(Tone.DANGER, it) }
    }
}
