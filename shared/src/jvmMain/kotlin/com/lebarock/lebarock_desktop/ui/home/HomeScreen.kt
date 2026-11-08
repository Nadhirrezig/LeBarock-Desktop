package com.lebarock.lebarock_desktop.ui.home

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lebarock.lebarock_desktop.data.remote.TerminalDto
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.Initials
import com.lebarock.lebarock_desktop.ui.common.LoadingBox
import com.lebarock.lebarock_desktop.ui.common.LogoImage
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.theme.Danger
import com.lebarock.lebarock_desktop.ui.theme.DangerContainer
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

const val NO_CAISSE_MESSAGE = "Ce compte n'a pas de caisse. Demandez au gérant de vous en attribuer une."

@Composable
fun HomeScreen(model: HomeModel, restaurantName: String, onSwitchUser: () -> Unit) {
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
                Modifier.widthIn(max = 1280.dp).fillMaxWidth().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("Bonjour ${s.user.name.substringBefore(' ')}", style = MaterialTheme.typography.headlineSmall)
                when {
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
                            { Button(onClick = model::openPicker) { Text("Choisir une caisse") } }
                        } else {
                            null
                        },
                    )
                }
                Tiles(HomeTiles.of(s.rights)) { model.open(it, hasCaisse = s.caisse != null) }
            }
        }
    }

    if (model.noCaisse) NoCaisseDialog(canChoose = s.canSwitchCaisse, onChoose = model::openPicker, onClose = model::closeNoCaisse)
    model.picker?.let { CaissePicker(it, current = s.caisse?.id, switching = model.switching, error = model.pickerError, onChoose = model::choose, onClose = model::closePicker) }
}

@Composable
private fun HomeTopBar(s: Session, restaurantName: String, onSwitchUser: () -> Unit, onPickCaisse: () -> Unit) {
    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoImage(44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(restaurantName.ifBlank { "AltosPOS" }, style = MaterialTheme.typography.titleLarge)
                Clock()
            }
            val caisse = s.caisse
            AssistChip(
                onClick = { if (s.canSwitchCaisse) onPickCaisse() },
                label = { Text(if (caisse != null) "Caisse : ${caisse.name}" else "Aucune caisse", fontWeight = FontWeight.SemiBold) },
                leadingIcon = { Icon(Icons.Outlined.Storefront, contentDescription = null) },
                trailingIcon = if (s.canSwitchCaisse) {
                    { Icon(Icons.Outlined.SwapHoriz, contentDescription = "Changer de caisse") }
                } else {
                    null
                },
                colors = if (caisse == null) {
                    AssistChipDefaults.assistChipColors(containerColor = DangerContainer, labelColor = Danger, leadingIconContentColor = Danger)
                } else {
                    AssistChipDefaults.assistChipColors()
                },
                modifier = Modifier.height(44.dp),
            )
            Spacer(Modifier.width(20.dp))
            Initials(s.user.name, s.user.id, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(s.user.name, style = MaterialTheme.typography.titleMedium)
                Text(s.roleLabel.ifBlank { "—" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(20.dp))
            OutlinedButton(onClick = onSwitchUser, modifier = Modifier.height(52.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Changer d'utilisateur")
            }
        }
    }
}

@Composable
private fun Clock() {
    val format = remember { DateTimeFormatter.ofPattern("EEEE d MMMM · HH:mm", Locale.FRANCE) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000L * (60 - now.second).coerceAtLeast(1))
        }
    }
    Text(now.format(format).replaceFirstChar { it.titlecase(Locale.FRANCE) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(sections, key = { it.name }) { section ->
            Card(
                onClick = { onOpen(section) },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, hoveredElevation = 5.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(60.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(section.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        section.comesIn?.let {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Text("Étape $it", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(section.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(section.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NoCaisseDialog(canChoose: Boolean, onChoose: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.width(460.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Pas de caisse", style = MaterialTheme.typography.titleLarge)
                Text(if (canChoose) "Ce compte n'a pas encore de caisse. Choisissez la caisse de ce comptoir." else NO_CAISSE_MESSAGE)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onClose) { Text("Fermer") }
                    if (canChoose) Button(onClick = onChoose) { Text("Choisir une caisse") }
                }
            }
        }
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
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.width(520.dp).heightIn(max = 620.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Choisir la caisse", style = MaterialTheme.typography.titleLarge)
                Text(
                    "La caisse du compte : son menu, son tiroir et sa session de caisse. Ce choix reste sur le compte.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    UiState.Loading -> Box(Modifier.height(160.dp)) { LoadingBox() }
                    is UiState.Error -> Banner(Tone.DANGER, state.message, detail = state.hint)
                    is UiState.Success -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.data.isEmpty()) Text("Aucune caisse active dans cette succursale.")
                        state.data.forEach { t ->
                            val selected = t.id == current
                            Card(
                                onClick = { if (!switching) onChoose(t) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                            ) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Storefront, contentDescription = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    if (selected) Text("Actuelle", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                error?.let { Banner(Tone.DANGER, it) }
                TextButton(onClick = onClose, enabled = !switching, modifier = Modifier.align(Alignment.End)) { Text("Fermer") }
            }
        }
    }
}
