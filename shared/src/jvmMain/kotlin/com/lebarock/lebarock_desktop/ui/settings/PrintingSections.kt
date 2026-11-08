package com.lebarock.lebarock_desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.data.settings.AgentPrinter
import com.lebarock.lebarock_desktop.data.settings.PrintTarget
import com.lebarock.lebarock_desktop.data.settings.PrinterConnection
import com.lebarock.lebarock_desktop.print.AgentState
import com.lebarock.lebarock_desktop.print.TicketCharset
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppDialog
import com.lebarock.lebarock_desktop.ui.common.AutoSavedNote
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.Choice
import com.lebarock.lebarock_desktop.ui.common.ChoiceRow
import com.lebarock.lebarock_desktop.ui.common.FormField
import com.lebarock.lebarock_desktop.ui.common.InfoRow
import com.lebarock.lebarock_desktop.ui.common.Initials
import com.lebarock.lebarock_desktop.ui.common.SectionCard
import com.lebarock.lebarock_desktop.ui.common.SelectField
import com.lebarock.lebarock_desktop.ui.common.SubHeading
import com.lebarock.lebarock_desktop.ui.common.TextInput
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.theme.Danger
import com.lebarock.lebarock_desktop.ui.theme.Dimens
import com.lebarock.lebarock_desktop.ui.theme.Success
import com.lebarock.lebarock_desktop.ui.theme.Warning
import java.time.format.DateTimeFormatter

/** « Impression des tickets »: who prints this PC's tickets, and this PC's printer with its test. */
@Composable
fun PrintingSection(model: PrintingModel) {
    // The settings flow redraws this when anything is saved.
    val all by model.settingsState.collectAsState()
    val cfg = all.printer
    SectionCard(
        "Impression des tickets",
        Icons.Outlined.Print,
        description = "Les additions, reçus, X et Z de ce poste. Les tickets cuisine ne changent pas : ils sortent sur les imprimantes des postes de cuisine.",
        trailing = { AutoSavedNote() },
    ) {
        FormField("Qui imprime les tickets de ce poste", help = cfg.target.hint) {
            ChoiceRow(
                PrintTarget.entries.map { Choice(it, it.label) },
                selected = cfg.target,
                onSelect = model::onTarget,
                itemWidth = 260.dp,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SubHeading(
            "Imprimante de ce poste",
            hint = if (cfg.target == PrintTarget.DEVICE) {
                "Les tickets de ce poste sortent ici, ainsi que ceux de l'impression « Directe » ci-dessous."
            } else {
                "Utilisée par l'impression « Directe » ci-dessous, et par ce poste si vous choisissez « Sur cet appareil »."
            },
        )
        FormField("Branchement") {
            ChoiceRow(
                PrinterConnection.entries.map { Choice(it, it.label) },
                selected = cfg.connection,
                onSelect = model::onConnection,
                itemWidth = 220.dp,
            )
        }
        when (cfg.connection) {
            PrinterConnection.USB -> FormField(
                "Imprimante Windows",
                help = if (model.windowsPrinters.isEmpty() && !model.loadingPrinters) {
                    "Aucune imprimante trouvée dans Windows : branchez-la, installez son pilote (ou « Generic / Text Only »), puis « Actualiser »."
                } else {
                    "Celle du comptoir, telle qu'elle apparaît dans Windows (Paramètres → Imprimantes et scanners)."
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
                    SelectField(
                        model.windowsPrinters,
                        cfg.windowsPrinter.takeIf { it.isNotBlank() },
                        model::onWindowsPrinter,
                        Modifier.weight(1f),
                        placeholder = "Choisir l'imprimante…",
                    )
                    AppButton("Actualiser", model::refreshWindowsPrinters, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Refresh, loading = model.loadingPrinters)
                }
            }
            PrinterConnection.NETWORK -> Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                FormField("Adresse IP de l'imprimante", Modifier.weight(1f), help = "Imprimée sur le ticket de configuration de l'imprimante.", error = model.hostError) {
                    TextInput(
                        model.host, model::onHost, Modifier.fillMaxWidth(),
                        placeholder = "192.168.1.50",
                        isError = model.hostError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                FormField("Port", Modifier.width(160.dp), help = "Habituellement 9100", error = model.portError) {
                    TextInput(
                        model.port, model::onPort, Modifier.fillMaxWidth(),
                        isError = model.portError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        }
        FormField("Largeur du papier", help = "${if (cfg.paperMm == 58) 32 else 48} caractères par ligne.") {
            ChoiceRow(listOf(Choice(80, "80 mm"), Choice(58, "58 mm")), selected = cfg.paperMm, onSelect = model::onPaper, itemWidth = 140.dp)
        }
        FormField("Accents", help = cfg.charset.hint + ". Le ticket de test montre ce qui s'imprime.") {
            ChoiceRow(TicketCharset.entries.map { Choice(it, it.label) }, selected = cfg.charset, onSelect = model::onCharset, itemWidth = 220.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            AppButton("Imprimer un test", model::printTest, icon = Icons.Outlined.Print, loading = model.testing, enabled = cfg.problem == null)
            cfg.problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        model.testResult?.let { (ok, message) -> Banner(if (ok) Tone.SUCCESS else Tone.DANGER, if (ok) "Ticket de test envoyé" else "Ticket de test non imprimé", detail = message) }
    }
}

private fun choiceLabel(choice: String?): String = when (choice) {
    "directPrint" -> "Directe (USB sur un poste)"
    "networkPrint" -> "Réseau (Ethernet)"
    "sunmiPrint" -> "Sunmi"
    "browserPopupPrint" -> "Navigateur"
    null, "" -> "Non réglée"
    else -> choice
}

/** « Impression Directe »: this PC prints the tickets the caisse queues for the counter's USB printer. */
@Composable
fun DirectPrintingSection(model: PrintingModel) {
    val status by model.agent.status.collectAsState()
    val all by model.settingsState.collectAsState()
    val cfg = all.agent
    SectionCard(
        "Impression « Directe »",
        Icons.Outlined.SettingsEthernet,
        description = "Quand l'imprimante du comptoir est branchée en USB, la caisse web et les Sunmi (« Imprimante de la caisse ») " +
            "ne peuvent pas l'atteindre : la caisse garde leurs tickets en attente et ce poste les imprime. Inutile si l'imprimante est en Ethernet.",
        trailing = if (cfg.configured) {
            { AutoSavedNote() }
        } else {
            null
        },
    ) {
        if (!cfg.configured) {
            Text(
                "Un administrateur se connecte une fois : ce poste reçoit la clé de la succursale, gardée chiffrée pour ce compte Windows.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row { AppButton("Configurer", model::openSetup, icon = Icons.Outlined.SettingsEthernet) }
            return@SectionCard
        }

        val chosen = cfg.printer
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            InfoRow("Succursale", cfg.branchName.ifBlank { "—" })
            InfoRow("Imprimante de la caisse", chosen?.let { "${it.name} · ${choiceLabel(it.choice)}" } ?: "Aucune choisie")
            InfoRow("Imprime sur", all.printer.label)
            InfoRow(
                "Tickets imprimés",
                if (status.printedCount == 0) "Aucun depuis le démarrage" else "${status.printedCount} depuis le démarrage, dernier à " +
                    (status.lastPrintedAt?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "—"),
            )
        }
        StatusLine(status.state, status.message)
        if (chosen != null && !chosen.isDirecte) {
            Banner(
                Tone.WARNING,
                "« ${chosen.name} » n'est pas en « Directe » sur la caisse",
                detail = "Ses tickets ne passent pas par ce poste. Sur la caisse : Paramètres → Imprimantes → « ${chosen.name} » → Directe.",
            )
        }
        all.printer.problem?.let { Banner(Tone.WARNING, "Imprimante de ce poste à régler", detail = it) }
        FormField("État") {
            ChoiceRow(
                listOf(Choice(true, "En marche"), Choice(false, "Arrêtée")),
                selected = cfg.enabled,
                onSelect = model::onAgentEnabled,
                itemWidth = 160.dp,
                enabled = chosen != null,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            AppButton("Changer d'imprimante", model::openPrinterChoice, kind = ButtonKind.SECONDARY)
            AppButton("Configurer à nouveau", model::openSetup, kind = ButtonKind.SECONDARY)
            AppButton("Retirer de ce poste", model::removeAgent, kind = ButtonKind.TEXT)
        }
    }
}

@Composable
private fun StatusLine(state: AgentState, message: String) {
    val color = when (state) {
        AgentState.WAITING, AgentState.PRINTING -> Success
        AgentState.OFF, AgentState.NOT_SET -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentState.CAISSE_DOWN -> Warning
        AgentState.KEY_REFUSED, AgentState.PRINTER_ERROR -> Danger
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Dimens.s))
        Text(message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (color == Success) MaterialTheme.colorScheme.onSurface else color)
    }
}

/** Set-up window: an administrator signs in, then the caisse printer this PC prints is chosen. */
@Composable
fun AgentSetupDialog(model: PrintingModel, setup: AgentSetup) {
    when (setup.step) {
        AgentSetup.Step.ACCOUNT -> AppDialog(
            "Impression « Directe »",
            onDismiss = model::closeSetup,
            width = 560.dp,
            dismissible = !setup.busy,
            subtitle = "Un administrateur se connecte une fois. Sa session sur la caisse est fermée aussitôt la clé reçue.",
            actions = {
                AppButton("Annuler", model::closeSetup, kind = ButtonKind.SECONDARY, enabled = !setup.busy)
                AppButton("Continuer", model::submitAdmin, loading = setup.busy, enabled = setup.account != null)
            },
        ) {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                if (setup.accounts.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Dimens.s))
                        Text("Lecture des comptes de la caisse…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                setup.accounts.forEach { account ->
                    val on = account.id == setup.account?.id
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.primary).clip(Dimens.shape)
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, Dimens.shape)
                            .clickable(enabled = !setup.busy) { model.pickAccount(account) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = Dimens.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Initials(account.name, account.id, 36.dp)
                        Spacer(Modifier.width(Dimens.sm))
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(account.roles.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (setup.account != null) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(setup.account.id) { runCatching { focus.requestFocus() } }
                FormField("Mot de passe de ${setup.account.name}", error = setup.error) {
                    TextInput(
                        setup.password, model::onSetupPassword, Modifier.fillMaxWidth().focusRequester(focus),
                        enabled = !setup.busy,
                        isError = setup.error != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { model.submitAdmin() }),
                    )
                }
            } else {
                setup.error?.let { Banner(Tone.DANGER, it) }
            }
        }
        AgentSetup.Step.PRINTER -> {
            val all by model.settingsState.collectAsState()
            AppDialog(
            "Imprimante de la caisse",
            onDismiss = model::closeSetup,
            width = 600.dp,
            dismissible = !setup.busy,
            subtitle = "Choisissez l'imprimante que ce poste imprime : celle du comptoir, réglée en « Directe » sur la caisse.",
            actions = { AppButton("Fermer", model::closeSetup, kind = ButtonKind.SECONDARY) },
        ) {
            if (setup.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Dimens.s))
                    Text("Lecture des imprimantes de la caisse…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!setup.busy && setup.printers.isEmpty()) {
                Banner(Tone.WARNING, "Aucune imprimante sur la caisse", detail = "Ajoutez-la sur la caisse : Paramètres → Imprimantes, type « Directe ».")
            }
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                setup.printers.sortedWith(compareBy<AgentPrinter>({ !it.isDirecte }, { it.name.lowercase() })).forEach { printer ->
                    PrinterRow(printer, selected = printer.id == all.agent.printerId) { model.choosePrinter(printer) }
                }
            }
        }
        }
    }
}

@Composable
private fun PrinterRow(printer: AgentPrinter, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(Dimens.shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, Dimens.shape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Dimens.m, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(printer.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    choiceLabel(printer.choice),
                    printer.caisses.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Caisses : "),
                    if (!printer.active) "désactivée" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (printer.isDirecte) {
            Text("Recommandée", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
