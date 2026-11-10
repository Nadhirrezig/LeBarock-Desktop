package com.lebarock.lebarock_desktop.ui.login

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lebarock.lebarock_desktop.data.remote.AccountDto
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppTopBar
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.ErrorBox
import com.lebarock.lebarock_desktop.ui.common.Initials
import com.lebarock.lebarock_desktop.ui.common.LoadingBox
import com.lebarock.lebarock_desktop.ui.common.TextInput
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.theme.Dimens

@Composable
fun LoginScreen(
    model: LoginModel,
    restaurantName: String,
    server: ServerAddress,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = restaurantName.ifBlank { "AltosPOS" },
            subtitle = "Serveur : ${server.label}",
        ) {
            AppButton("Réglages", onOpenSettings, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Settings)
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 1200.dp).fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.l),
                verticalArrangement = Arrangement.spacedBy(Dimens.m),
            ) {
                model.notice?.let { Banner(Tone.INFO, it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Qui se connecte ?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    AppButton("Actualiser", model::refresh, kind = ButtonKind.TEXT, icon = Icons.Outlined.Refresh, loading = model.loading)
                }
                // Offline step 3: the caisse does not answer — the Caisse's « Hors ligne » account and its PIN.
                if (model.caisseDown) {
                    Banner(
                        Tone.WARNING,
                        "La caisse ne répond pas",
                        detail = if (model.offlineAvailable) {
                            "Connectez-vous hors ligne avec le code PIN de la caisse : commandes, additions et paiements (avec le code de paiement) sont envoyés à la caisse dès qu'elle répond."
                        } else {
                            com.lebarock.lebarock_desktop.data.offline.OfflinePins.NONE
                        },
                        action = if (model.offlineAvailable) {
                            { AppButton("Connexion hors ligne", model::openOfflineSignIn, icon = Icons.Outlined.CloudOff) }
                        } else {
                            null
                        },
                    )
                }
                SearchField(model)
                val error = model.listError
                if (error != null && model.accounts.isNotEmpty() && !model.caisseDown) {
                    Banner(
                        Tone.WARNING,
                        "Liste peut-être pas à jour : ${error.message}",
                        detail = error.hint,
                        action = { AppButton("Réessayer", model::refresh, kind = ButtonKind.TEXT) },
                    )
                }
                when {
                    model.accounts.isEmpty() && model.loading -> LoadingBox(label = "Lecture des comptes de la caisse…")
                    model.accounts.isEmpty() && error != null && model.caisseDown -> Unit
                    model.accounts.isEmpty() && error != null -> ErrorBox(
                        error.message ?: "La caisse ne répond pas.",
                        error.hint,
                        onRetry = model::refresh,
                        extra = { AppButton("Ouvrir les réglages", onOpenSettings, kind = ButtonKind.SECONDARY) },
                    )
                    model.visibleAccounts.isEmpty() -> Text(
                        if (model.query.isBlank()) "Aucun compte sur cette caisse." else "Aucun compte ne correspond à « ${model.query} ».",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimens.l),
                    )
                    else -> AccountGrid(model)
                }
            }
        }
    }

    model.dialog?.let { PinDialogView(model, it) }
    model.offlinePin?.let { entry ->
        com.lebarock.lebarock_desktop.ui.offline.PinWindow(
            title = "Connexion hors ligne",
            subtitle = "Code PIN hors ligne de la caisse",
            entry = entry,
            onChange = model::editOfflinePin,
            onSubmit = model::submitOfflinePin,
            onDismiss = model::dismissOfflineSignIn,
        )
    }
}

@Composable
private fun SearchField(model: LoginModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TextInput(
        model.query,
        model::onQuery,
        Modifier.widthIn(max = 520.dp).fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                model.openSingleMatch(); true
            } else {
                false
            }
        },
        placeholder = "Rechercher un nom…",
        leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { model.openSingleMatch() }),
    )
}

@Composable
private fun AccountGrid(model: LoginModel) {
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(Dimens.m),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
            contentPadding = PaddingValues(bottom = Dimens.l, end = Dimens.m),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(model.visibleAccounts, key = { it.id }) { account ->
                AccountCard(account, last = account.id == model.lastUserId, onClick = { model.pick(account) })
            }
        }
        androidx.compose.foundation.VerticalScrollbar(
            androidx.compose.foundation.rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd).padding(vertical = Dimens.xs),
        )
    }
}

@Composable
private fun AccountCard(account: AccountDto, last: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = Dimens.cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (last) 2.dp else 1.dp, if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().height(176.dp).pointerHoverIcon(PointerIcon.Hand),
    ) {
        Column(
            Modifier.fillMaxSize().padding(Dimens.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Initials(account.name, account.id, 60.dp)
            Spacer(Modifier.height(Dimens.sm))
            Text(account.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(
                account.roles.joinToString(", ").ifBlank { " " },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                if (last) Text("Dernier connecté", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * The password / PIN window: the keyboard works (Enter = Valider, Esc = Annuler), and a keypad
 * for the touch screen whose keys never take the focus from the field.
 */
@Composable
private fun PinDialogView(model: LoginModel, d: PinDialog) {
    // A short window (Windows at 125 % on a 1366 × 768 screen leaves ~614 dp): a compact layout,
    // same order, so the two buttons always keep their full height.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val compact = windowHeight < 760.dp
    val keyHeight = if (compact) Dimens.control else Dimens.primary
    Dialog(onDismissRequest = model::dismiss, properties = DialogProperties(dismissOnClickOutside = !d.submitting)) {
        Surface(shape = Dimens.cardShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(
                Modifier.width(Dimens.keypadWidth + Dimens.l * 2).padding(Dimens.l).onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (it.key) {
                        Key.Enter, Key.NumPadEnter -> { model.submit(); true }
                        Key.Escape -> { model.dismiss(); true }
                        else -> false
                    }
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (compact) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Initials(d.account.name, d.account.id, 40.dp)
                        Spacer(Modifier.width(Dimens.sm))
                        Column {
                            Text(d.account.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(d.account.roles.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(Dimens.m))
                } else {
                    Initials(d.account.name, d.account.id, 64.dp)
                    Spacer(Modifier.height(Dimens.sm))
                    Text(d.account.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(d.account.roles.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Dimens.l))
                }

                var visible by remember { mutableStateOf(false) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(d.error, d.submitting) { if (!d.submitting) runCatching { focus.requestFocus() } }
                Text("Mot de passe / PIN", style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Dimens.s))
                TextInput(
                    d.password,
                    model::onPassword,
                    Modifier.fillMaxWidth().focusRequester(focus),
                    enabled = !d.submitting,
                    isError = d.error != null,
                    large = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.submit() }),
                    trailing = {
                        IconButton(onClick = { visible = !visible }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (visible) "Masquer" else "Afficher",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                Box(Modifier.fillMaxWidth().heightIn(min = if (compact) 28.dp else 32.dp).padding(top = Dimens.s), contentAlignment = Alignment.TopStart) {
                    d.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
                }
                Spacer(Modifier.height(if (compact) Dimens.xs else Dimens.s))
                Keypad(enabled = !d.submitting, keyHeight = keyHeight, onDigit = model::key, onBackspace = model::backspace, onClear = model::clear)
                Spacer(Modifier.height(if (compact) Dimens.m else Dimens.l))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), modifier = Modifier.fillMaxWidth()) {
                    AppButton("Annuler", model::dismiss, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = !d.submitting, large = !compact)
                    AppButton("Valider", model::submit, Modifier.weight(1f), icon = Icons.AutoMirrored.Outlined.Login, loading = d.submitting, large = !compact)
                }
            }
        }
    }
}

@Composable
internal fun Keypad(enabled: Boolean, keyHeight: androidx.compose.ui.unit.Dp, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                row.forEach { c -> KeyButton(c.toString(), enabled, keyHeight) { onDigit(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            KeyButton("Effacer", enabled, keyHeight, small = true, onClick = onClear)
            KeyButton("0", enabled, keyHeight) { onDigit('0') }
            Box(Modifier.width(Dimens.keyWidth)) {
                AppButton("", onBackspace, Modifier.fillMaxWidth().height(keyHeight), kind = ButtonKind.SECONDARY, icon = Icons.AutoMirrored.Outlined.Backspace, enabled = enabled, focusable = false)
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean, height: androidx.compose.ui.unit.Dp, small: Boolean = false, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = Dimens.shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        // Not focusable: the password field keeps the keyboard while the keypad is used.
        modifier = Modifier.size(width = Dimens.keyWidth, height = height)
            .focusProperties { canFocus = false }
            .pointerHoverIcon(PointerIcon.Hand),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, style = if (small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp))
    }
}
