package com.lebarock.lebarock_desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.AppInfo
import com.lebarock.lebarock_desktop.data.settings.AppSettings
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.LogoImage
import com.lebarock.lebarock_desktop.ui.common.SectionCard
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.theme.Mono
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Réglages: where the caisse is (address, test), which certificates this PC trusts, the idle
 * lock, and the app's version and folders. Printing comes with D2.
 */
@Composable
fun SettingsScreen(model: SettingsModel, canGoBack: Boolean, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && canGoBack) {
                onBack(); true
            } else {
                false
            }
        },
    ) {
        TopBar(model.firstRun, canGoBack, onBack)
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 980.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (model.firstRun) {
                    Banner(
                        Tone.INFO,
                        "Bienvenue dans AltosPOS",
                        detail = "Pour commencer, saisissez l'adresse de la caisse sur le réseau local (la même que sur les Sunmi), " +
                            "puis « Tester la connexion » et « Enregistrer ».",
                    )
                }
                ServerSection(model)
                CertificateSection(model)
                if (!model.firstRun) SessionSection(model)
                ApplicationSection(model)
            }
        }
    }
}

@Composable
private fun TopBar(firstRun: Boolean, canGoBack: Boolean, onBack: () -> Unit) {
    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                OutlinedButton(onClick = onBack, modifier = Modifier.height(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Retour")
                }
                Spacer(Modifier.width(16.dp))
            }
            LogoImage(40.dp)
            Spacer(Modifier.width(12.dp))
            Text(if (firstRun) "Configuration" else "Réglages", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSection(model: SettingsModel) {
    SectionCard(
        "Serveur",
        Icons.Outlined.Dns,
        subtitle = "La caisse (le PC serveur) sur le réseau local. Enregistrée : ${model.savedAddress.label}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Protocole", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.width(480.dp)) {
                SegmentedButton(
                    selected = model.https,
                    onClick = { model.onProtocol(true) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("HTTPS (recommandé)") }
                SegmentedButton(
                    selected = !model.https,
                    onClick = { model.onProtocol(false) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("HTTP") }
            }
            val submit = KeyboardActions(onDone = { model.test() })
            val enterTests = Modifier.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    model.test(); true
                } else {
                    false
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = model.host,
                    onValueChange = model::onHost,
                    label = { Text("Adresse IP du serveur") },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    isError = model.hostError != null,
                    supportingText = { Text(model.hostError ?: "Celle du PC serveur, par exemple 192.168.1.20") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = submit,
                    modifier = Modifier.weight(1f).then(enterTests),
                )
                OutlinedTextField(
                    value = model.port,
                    onValueChange = model::onPort,
                    label = { Text("Port") },
                    singleLine = true,
                    isError = model.portError != null,
                    supportingText = { Text(model.portError ?: if (model.https) "HTTPS : 443" else "HTTP : 80") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = submit,
                    modifier = Modifier.width(180.dp).then(enterTests),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = model::test, enabled = !model.testing, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Outlined.NetworkCheck, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tester la connexion")
                }
                Button(onClick = { model.save() }, enabled = !model.testing, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Outlined.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enregistrer")
                }
                if (model.testing) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text("Connexion à la caisse…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TestResultView(model)
            model.saveNotice?.let { notice ->
                Banner(
                    if (model.offerForceSave) Tone.WARNING else Tone.SUCCESS,
                    notice,
                    action = if (model.offerForceSave) {
                        { TextButton(onClick = { model.save(force = true) }) { Text("Enregistrer quand même") } }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun TestResultView(model: SettingsModel) {
    if (!model.resultIsCurrent || model.testing) return
    when (val result = model.result) {
        is TestResult.Ok -> {
            val check = result.check
            val time = check.ping.serverTime?.let {
                runCatching {
                    java.time.OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
                }.getOrNull()
            }
            val offset = check.clockOffset?.let { d ->
                val s = d.seconds
                if (kotlin.math.abs(s) < 2) "à l'heure" else "écart ${if (s > 0) "+" else "−"}${formatSeconds(kotlin.math.abs(s))}"
            }
            Banner(
                Tone.SUCCESS,
                "Connecté à « ${check.restaurantName} »",
                detail = listOfNotNull(
                    "Adresse : ${result.address.baseUrl}",
                    time?.let { "Heure du serveur : $it" + (offset?.let { o -> " ($o)" } ?: "") },
                    check.ping.currency?.let { c -> "Devise : ${c.symbol ?: c.code ?: "—"}, ${c.decimals} décimales" },
                ).joinToString("\n"),
            )
            if (check.clockWarning) {
                Banner(
                    Tone.WARNING,
                    "L'heure de ce PC n'est pas celle du serveur",
                    detail = "Réglez l'heure de Windows (Paramètres → Heure et langue → Régler automatiquement).",
                )
            }
        }
        is TestResult.Failed -> Banner(Tone.DANGER, result.failure.message ?: "Connexion impossible.", detail = result.failure.hint)
        null -> Unit
    }
}

private fun formatSeconds(s: Long): String = when {
    s < 60 -> "$s s"
    s < 3600 -> "${s / 60} min ${s % 60} s"
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}

@Composable
private fun CertificateSection(model: SettingsModel) {
    SectionCard(
        "Certificat",
        Icons.Outlined.VerifiedUser,
        subtitle = "La caisse est en HTTPS avec un certificat du réseau local (mkcert).",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (model.windowsStore) {
                    "Ce PC fait confiance aux certificats installés dans Windows. Si rootCA.pem y est installé " +
                        "(double-clic → Installer le certificat → Autorités de certification racines de confiance), il n'y a rien à faire ici."
                } else {
                    "Ce système n'a pas le magasin de certificats de Windows : importez rootCA.pem ci-dessous."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (model.windowsStore) {
                Text(
                    "Sinon, importez ici le fichier rootCA.pem fourni avec l'installation de la caisse.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            model.certificates.forEach { cert ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cert.subject, fontWeight = FontWeight.SemiBold)
                        Text("Importé · valable jusqu'au ${cert.validUntil}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { model.removeCertificate(cert) }) { Text("Retirer") }
                }
                HorizontalDivider()
            }
            OutlinedButton(
                onClick = { chooseFile("Choisir le certificat racine (rootCA.pem)")?.let(model::importCertificate) },
                modifier = Modifier.height(52.dp),
            ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Importer rootCA.pem…")
            }
            model.certNotice?.let { (ok, message) -> Banner(if (ok) Tone.SUCCESS else Tone.DANGER, message) }
        }
    }
}

@Composable
private fun SessionSection(model: SettingsModel) {
    SectionCard(
        "Session",
        Icons.Outlined.Lock,
        subtitle = "Comptoir partagé : la session se ferme quand personne ne touche la caisse.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Fermer la session après", style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppSettings.IDLE_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected = model.idleMinutes == minutes,
                        onClick = { model.onIdleMinutes(minutes) },
                        label = { Text(if (minutes == 0) "Jamais" else "$minutes min") },
                        leadingIcon = if (model.idleMinutes == minutes) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        modifier = Modifier.height(44.dp),
                    )
                }
            }
            Text(
                "Sans clic, touche ni toucher pendant ce temps, l'écran revient à la connexion (un avertissement s'affiche 30 s avant). " +
                    "Jamais pendant une commande pas encore envoyée ni pendant un encaissement. Les commandes, paiements et la session de caisse " +
                    "appartiennent à la caisse : rien n'est perdu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ApplicationSection(model: SettingsModel) {
    SectionCard("Application", Icons.Outlined.Info) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow("Version", "${AppInfo.NAME} ${AppInfo.version}")
            InfoRow("Java", "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
            InfoRow("Dossier des données", AppInfo.dataDir.path)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { model.open(AppInfo.dataDir) }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ouvrir le dossier")
                }
                OutlinedButton(onClick = { model.open(AppInfo.logsDir) }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ouvrir les journaux")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.width(200.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = Mono.merge(MaterialTheme.typography.bodyMedium))
    }
}

/** Windows' own file dialog (a native window on top of the app). */
private fun chooseFile(title: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".pem") || it.endsWith(".crt") || it.endsWith(".cer") } }
    dialog.file = "*.pem;*.crt;*.cer"
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
