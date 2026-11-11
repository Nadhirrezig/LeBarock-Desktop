package com.lebarock.lebarock_desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppDialog
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.FormField
import com.lebarock.lebarock_desktop.ui.common.TextInput
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.InfoRow
import com.lebarock.lebarock_desktop.ui.common.SectionCard
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.theme.Dimens

/** Réglages → « Mode hors ligne ». */
@Composable
fun OfflineSection(model: OfflineModel, onOpenReview: () -> Unit = {}) {
    SectionCard(
        "Mode hors ligne",
        Icons.Outlined.CloudSync,
        description = "Ce que ce poste garde pour continuer quand la caisse ne répond pas : le menu de chaque caisse, les tables et les commandes ouvertes, à jour connecté ou non.",
    ) {
        if (!model.storeOnDisk) {
            Banner(
                Tone.WARNING,
                "Les données hors ligne ne sont pas enregistrées sur ce poste",
                detail = "Le fichier offline.db du dossier des données n'a pas pu être ouvert. Ouvrez les journaux et contactez le support.",
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            InfoRow("Ce poste", model.deviceLine)
            InfoRow("Données hors ligne", model.copyLine)
            model.copy.checksSavedAt?.let { InfoRow("Commandes à jour", OfflineModel.format(it)) }
            InfoRow("Actions en attente", if (model.status.pending == 0) "aucune" else model.status.pending.toString())
            InfoRow("Dernier contact", model.lastContact?.let { OfflineModel.format(it) } ?: "jamais")
            InfoRow("État", model.stateLine)
        }
        if (model.enrolOpen) {
            if (model.status.keyRefused) {
                Banner(Tone.WARNING, "La caisse a refusé la clé de ce poste", detail = "Enregistrez-le à nouveau avec la clé de synchronisation.")
            }
            FormField(
                "Clé de synchronisation",
                Modifier.width(440.dp),
                help = if (model.enrolError == null) "Sur la caisse : Paramètres → Mode hors ligne (Admin)." else null,
                error = model.enrolError,
            ) {
                TextInput(
                    model.enrolKey, model::onEnrolKey, Modifier.fillMaxWidth(),
                    placeholder = "K7QM-2XRT-9HPB",
                    isError = model.enrolError != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.enrol() }),
                )
            }
            AppButton("Enregistrer ce poste", model::enrol, icon = Icons.Outlined.Key, loading = model.enrolling, enabled = model.enrolReady)
        }
        model.message?.let { Banner(Tone.INFO, it) }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            AppButton("Synchroniser maintenant", model::syncNow, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.Sync, loading = model.busy)
            AppButton(if (model.reviewCount > 0) "À vérifier (${model.reviewCount})" else "À vérifier", onOpenReview, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.ReportProblem)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            if (!model.enrolOpen) {
                AppButton("Enregistrer à nouveau avec une clé", model::askEnrol, kind = ButtonKind.TEXT, icon = Icons.Outlined.Key)
            }
            AppButton("Effacer les données hors ligne", model::askClear, kind = ButtonKind.TEXT, icon = Icons.Outlined.DeleteOutline, enabled = !model.busy)
        }
    }

    if (model.confirmClear) {
        AppDialog(
            "Effacer les données hors ligne ?",
            onDismiss = model::dismissClear,
            actions = {
                AppButton("Annuler", model::dismissClear, kind = ButtonKind.SECONDARY)
                AppButton("Effacer", model::clear, kind = ButtonKind.DANGER)
            },
        ) {
            Text(
                "Le menu, les tables, les commandes ouvertes et les codes hors ligne gardés sur ce poste sont effacés. " +
                    "Ils sont téléchargés à nouveau dans la minute quand la caisse répond ; d'ici là, ce poste ne peut pas travailler hors ligne.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
