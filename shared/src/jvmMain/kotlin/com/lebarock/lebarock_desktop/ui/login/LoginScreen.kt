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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lebarock.lebarock_desktop.data.remote.AccountDto
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.ErrorBox
import com.lebarock.lebarock_desktop.ui.common.Initials
import com.lebarock.lebarock_desktop.ui.common.LoadingBox
import com.lebarock.lebarock_desktop.ui.common.LogoImage
import com.lebarock.lebarock_desktop.ui.common.Tone

@Composable
fun LoginScreen(
    model: LoginModel,
    restaurantName: String,
    server: ServerAddress,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogoImage(44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(restaurantName.ifBlank { "AltosPOS" }, style = MaterialTheme.typography.titleLarge)
                    Text("Serveur : ${server.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Outlined.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Réglages")
                }
            }
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 1200.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                model.notice?.let { Banner(Tone.INFO, it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Qui se connecte ?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (model.loading) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    TextButton(onClick = model::refresh, enabled = !model.loading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Actualiser")
                    }
                }
                SearchField(model)
                val error = model.listError
                if (error != null && model.accounts.isNotEmpty()) {
                    Banner(
                        Tone.WARNING,
                        "Liste peut-être pas à jour : ${error.message}",
                        detail = error.hint,
                        action = { TextButton(onClick = model::refresh) { Text("Réessayer") } },
                    )
                }
                when {
                    model.accounts.isEmpty() && model.loading -> LoadingBox(label = "Lecture des comptes de la caisse…")
                    model.accounts.isEmpty() && error != null -> ErrorBox(
                        error.message ?: "La caisse ne répond pas.",
                        error.hint,
                        onRetry = model::refresh,
                        extra = { OutlinedButton(onClick = onOpenSettings) { Text("Ouvrir les réglages") } },
                    )
                    model.visibleAccounts.isEmpty() -> Text(
                        if (model.query.isBlank()) "Aucun compte sur cette caisse." else "Aucun compte ne correspond à « ${model.query} ».",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                    else -> AccountGrid(model)
                }
            }
        }
    }

    model.dialog?.let { PinDialogView(model, it) }
}

@Composable
private fun SearchField(model: LoginModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = model.query,
        onValueChange = model::onQuery,
        placeholder = { Text("Rechercher un nom…") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { model.openSingleMatch() }),
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                model.openSingleMatch(); true
            } else {
                false
            }
        },
    )
}

@Composable
private fun AccountGrid(model: LoginModel) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 210.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(model.visibleAccounts, key = { it.id }) { account ->
            AccountCard(account, last = account.id == model.lastUserId, onClick = { model.pick(account) })
        }
    }
}

@Composable
private fun AccountCard(account: AccountDto, last: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, hoveredElevation = 4.dp),
        border = if (last) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().height(170.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Initials(account.name, account.id, 64.dp)
            Spacer(Modifier.height(12.dp))
            Text(account.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                account.roles.joinToString(", ").ifBlank { " " },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (last) {
                Text("Dernier connecté", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * The password / PIN window: the keyboard works (Enter = Valider, Esc = Annuler), and a keypad
 * for the touch screen. The caisse checks it (POST /api/mobile/login).
 */
@Composable
private fun PinDialogView(model: LoginModel, d: PinDialog) {
    Dialog(onDismissRequest = model::dismiss, properties = DialogProperties(dismissOnClickOutside = !d.submitting)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(
                Modifier.width(420.dp).padding(28.dp).onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (it.key) {
                        Key.Enter, Key.NumPadEnter -> { model.submit(); true }
                        Key.Escape -> { model.dismiss(); true }
                        else -> false
                    }
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Initials(d.account.name, d.account.id, 72.dp)
                Spacer(Modifier.height(10.dp))
                Text(d.account.name, style = MaterialTheme.typography.titleLarge)
                Text(d.account.roles.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))

                var visible by remember { mutableStateOf(false) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(d.error, d.submitting) { if (!d.submitting) runCatching { focus.requestFocus() } }
                OutlinedTextField(
                    value = d.password,
                    onValueChange = model::onPassword,
                    label = { Text("Mot de passe / PIN") },
                    singleLine = true,
                    enabled = !d.submitting,
                    isError = d.error != null,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.submit() }),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (visible) "Masquer" else "Afficher",
                            )
                        }
                    },
                    textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 4.sp),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Box(Modifier.height(28.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    d.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
                }
                Keypad(enabled = !d.submitting, onDigit = model::key, onBackspace = model::backspace, onClear = model::clear)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = model::dismiss,
                        enabled = !d.submitting,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Annuler") }
                    Button(
                        onClick = model::submit,
                        enabled = !d.submitting,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        if (d.submitting) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Valider")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c -> KeyButton(c.toString(), enabled) { onDigit(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyButton("Effacer", enabled, small = true, onClick = onClear)
            KeyButton("0", enabled) { onDigit('0') }
            OutlinedButton(onClick = onBackspace, enabled = enabled, modifier = Modifier.size(width = 112.dp, height = 60.dp).focusProperties { canFocus = false }) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = "Effacer un chiffre")
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean, small: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        // Not focusable: the password field keeps the keyboard while the keypad is used.
        modifier = Modifier.size(width = 112.dp, height = 60.dp).focusProperties { canFocus = false },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, style = if (small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge)
    }
}
