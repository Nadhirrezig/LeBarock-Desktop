package com.lebarock.lebarock_desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppTopBar
import com.lebarock.lebarock_desktop.ui.common.AutoSavedNote
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.Choice
import com.lebarock.lebarock_desktop.ui.common.ChoiceRow
import com.lebarock.lebarock_desktop.ui.common.FormField
import com.lebarock.lebarock_desktop.ui.common.InfoRow
import com.lebarock.lebarock_desktop.ui.common.ScrollPage
import com.lebarock.lebarock_desktop.ui.common.SectionCard
import com.lebarock.lebarock_desktop.ui.common.TextInput
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.theme.Dimens
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Réglages, one page (client, 2026-11-08): Serveur, Certificat, Impression des tickets,
 * Impression « Directe », Session, Application. Only the server address has an "Enregistrer"
 * button; every other section saves on each change and says so.
 */
@Composable
fun SettingsScreen(model: SettingsModel, printing: PrintingModel?, canGoBack: Boolean, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && canGoBack) {
                onBack(); true
            } else {
                false
            }
        },
    ) {
        AppTopBar(
            title = if (model.firstRun) "Configuration" else "Réglages",
            subtitle = if (model.firstRun) "Premier démarrage de ce poste" else "Ce poste : serveur, impression, session",
            onBack = if (canGoBack) onBack else null,
        )
        ScrollPage {
            if (model.firstRun) {
                Banner(
                    Tone.INFO,
                    "Bienvenue dans AltosPOS",
                    detail = "Saisissez l'adresse de la caisse sur le réseau local (la même que sur les Sunmi), " +
                        "puis « Tester la connexion » et « Enregistrer ».",
                )
            }
            ServerSection(model)
            CertificateSection(model)
            if (printing != null) {
                PrintingSection(printing)
                DirectPrintingSection(printing)
            }
            if (!model.firstRun) SessionSection(model)
            ApplicationSection(model)
        }
    }
    printing?.setup?.let { AgentSetupDialog(printing, it) }
}

@Composable
private fun ServerSection(model: SettingsModel) {
    SectionCard(
        "Serveur",
        Icons.Outlined.Dns,
        description = "La caisse (le PC serveur) sur le réseau local. Adresse enregistrée : ${model.savedAddress.label}",
    ) {
        FormField("Protocole", help = "La caisse est en HTTPS sur le port 443, sauf réglage particulier.") {
            ChoiceRow(
                listOf(Choice(true, "HTTPS (recommandé)"), Choice(false, "HTTP")),
                selected = model.https,
                onSelect = model::onProtocol,
                itemWidth = 220.dp,
            )
        }
        val enterTests = Modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                model.test(); true
            } else {
                false
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
            FormField(
                "Adresse IP du serveur",
                Modifier.weight(1f),
                help = "Celle du PC serveur, par exemple 192.168.1.20",
                error = model.hostError,
            ) {
                TextInput(
                    model.host, model::onHost, Modifier.fillMaxWidth().then(enterTests),
                    placeholder = "192.168.1.20",
                    isError = model.hostError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.test() }),
                )
            }
            FormField("Port", Modifier.width(160.dp), help = if (model.https) "HTTPS : 443" else "HTTP : 80", error = model.portError) {
                TextInput(
                    model.port, model::onPort, Modifier.fillMaxWidth().then(enterTests),
                    isError = model.portError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.test() }),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
            AppButton("Tester la connexion", model::test, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.NetworkCheck, enabled = !model.testing)
            AppButton("Enregistrer", { model.save() }, icon = Icons.Outlined.Save, enabled = !model.testing && model.serverChanged)
            if (model.testing) {
                Spacer(Modifier.width(Dimens.xs))
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text("Connexion à la caisse…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TestResultView(model)
        model.saveNotice?.let { notice ->
            Banner(
                if (model.offerForceSave) Tone.WARNING else Tone.SUCCESS,
                notice,
                action = if (model.offerForceSave) {
                    { AppButton("Enregistrer quand même", { model.save(force = true) }, kind = ButtonKind.TEXT) }
                } else {
                    null
                },
            )
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
        description = if (model.windowsStore) {
            "La caisse est en HTTPS avec un certificat du réseau local (mkcert). Si rootCA.pem est installé dans Windows " +
                "(double-clic → Installer le certificat → Autorités de certification racines de confiance), il n'y a rien à faire ici ; " +
                "sinon importez-le ci-dessous."
        } else {
            "La caisse est en HTTPS avec un certificat du réseau local (mkcert). Ce système n'a pas le magasin de certificats " +
                "de Windows : importez rootCA.pem ci-dessous."
        },
    ) {
        if (model.certificates.isNotEmpty()) {
            Column {
                model.certificates.forEachIndexed { i, cert ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cert.subject, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Importé · valable jusqu'au ${cert.validUntil}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AppButton("Retirer", { model.removeCertificate(cert) }, kind = ButtonKind.TEXT)
                    }
                }
            }
        }
        Row {
            AppButton(
                "Importer rootCA.pem…",
                { chooseFile("Choisir le certificat racine (rootCA.pem)")?.let(model::importCertificate) },
                kind = ButtonKind.SECONDARY,
                icon = Icons.Outlined.UploadFile,
            )
        }
        model.certNotice?.let { (ok, message) -> Banner(if (ok) Tone.SUCCESS else Tone.DANGER, message) }
    }
}

@Composable
private fun SessionSection(model: SettingsModel) {
    SectionCard(
        "Session",
        Icons.Outlined.Lock,
        description = "Comptoir partagé : la session se ferme quand personne ne touche la caisse.",
        trailing = { AutoSavedNote() },
    ) {
        FormField(
            "Fermer la session après",
            help = "Un avertissement s'affiche 30 s avant. Jamais pendant une commande pas encore envoyée ni pendant un " +
                "encaissement. Commandes, paiements et session de caisse restent sur la caisse : rien n'est perdu.",
        ) {
            ChoiceRow(
                AppSettings.IDLE_CHOICES.map { Choice(it, if (it == 0) "Jamais" else "$it min") },
                selected = model.idleMinutes,
                onSelect = model::onIdleMinutes,
                itemWidth = 108.dp,
            )
        }
    }
}

@Composable
private fun ApplicationSection(model: SettingsModel) {
    SectionCard("Application", Icons.Outlined.Info) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            InfoRow("Version", "${AppInfo.NAME} ${AppInfo.version}")
            InfoRow("Java", "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
            InfoRow("Dossier des données", AppInfo.dataDir.path)
        }
        FormField(
            "Démarrer avec Windows",
            help = if (model.startupAvailable) {
                "AltosPOS démarre dans la barre des tâches à l'ouverture de la session Windows, pour l'impression « Directe »."
            } else {
                "Disponible avec l'application installée sous Windows."
            },
            error = model.startupError,
        ) {
            ChoiceRow(
                listOf(Choice(true, "Oui"), Choice(false, "Non")),
                selected = model.startWithWindows,
                onSelect = model::onStartWithWindows,
                itemWidth = 108.dp,
                enabled = model.startupAvailable,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            AppButton("Ouvrir le dossier", { model.open(AppInfo.dataDir) }, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.FolderOpen)
            AppButton("Ouvrir les journaux", { model.open(AppInfo.logsDir) }, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.FolderOpen)
        }
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
