package com.lebarock.lebarock_desktop.ui.offline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.data.offline.OfflineSync
import com.lebarock.lebarock_desktop.data.offline.ReviewItemEntity
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppTopBar
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import com.lebarock.lebarock_desktop.ui.common.ScrollPage
import com.lebarock.lebarock_desktop.ui.theme.Danger
import com.lebarock.lebarock_desktop.ui.theme.Dimens
import com.lebarock.lebarock_desktop.ui.theme.Info
import com.lebarock.lebarock_desktop.ui.theme.Success
import com.lebarock.lebarock_desktop.ui.theme.Warning
import com.lebarock.lebarock_desktop.ui.theme.WarningContainer
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** « À vérifier »: what the caisse refused or set aside from this PC's offline actions (the handheld's screen). */
class ReviewModel(private val offline: OfflineSync) : ScreenModel() {
    var items by mutableStateOf<List<ReviewItemEntity>>(emptyList())
        private set

    init {
        scope.launch { offline.reviewItems.collect { items = it } }
    }

    fun dismiss(item: ReviewItemEntity) {
        scope.launch { offline.dismissReview(item.commandId) }
    }
}

/**
 * « À vérifier »: nothing the caisse refused is dropped silently. Staff read each one, deal with it at the caisse,
 * then take it off the list (a payment still waiting for a cash session stays until it is recorded).
 */
@Composable
fun ReviewScreen(model: ReviewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar("À vérifier", subtitle = "Actions hors ligne de ce poste que la caisse a refusées ou mises de côté", onBack = onBack)
        ScrollPage {
            if (model.items.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Success)
                    Text("Rien à vérifier : toutes les actions hors ligne de ce poste sont passées à la caisse.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Text("Réglez chaque point à la caisse, puis retirez-le de la liste.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                model.items.forEach { ReviewCard(it) { model.dismiss(it) } }
            }
        }
    }
}

@Composable
private fun ReviewCard(item: ReviewItemEntity, onDismiss: () -> Unit) {
    val (chip, color) = statusChip(item.status)
    Surface(
        Modifier.fillMaxWidth(),
        shape = Dimens.cardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.label.ifBlank { typeLabel(item.type) }, style = MaterialTheme.typography.titleMedium)
                    Text("${typeLabel(item.type)} · ${formatAt(item.at)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(Dimens.s))
                Surface(color = color, shape = RoundedCornerShape(50)) {
                    Text(chip, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Dimens.sm, vertical = Dimens.xs))
                }
            }
            Text(item.message, style = MaterialTheme.typography.bodyLarge)
            if (item.status != "waiting_session") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AppButton("Vu, retirer de la liste", onDismiss, kind = ButtonKind.SECONDARY)
                }
            }
        }
    }
}

/** Accueil: « À vérifier (n) » while the caisse set aside some of this PC's offline actions. */
@Composable
fun ReviewHomeCard(count: Int, onOpen: () -> Unit) {
    if (count == 0) return
    Surface(Modifier.fillMaxWidth(), shape = Dimens.shape, color = WarningContainer) {
        Row(Modifier.padding(horizontal = Dimens.m, vertical = Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = Warning)
            Spacer(Modifier.width(Dimens.sm))
            Column(Modifier.weight(1f)) {
                Text("À vérifier ($count)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Warning)
                Text("Actions hors ligne de ce poste que la caisse a refusées ou mises de côté.", style = MaterialTheme.typography.bodyMedium)
            }
            AppButton("Voir", onOpen, kind = ButtonKind.SECONDARY)
        }
    }
}

private fun statusChip(status: String): Pair<String, Color> = when (status) {
    "refused" -> "Refusé" to Danger
    "review" -> "À vérifier" to Warning
    "waiting_session" -> "En attente de session" to Info
    "applied" -> "Enregistré" to Success
    else -> status to Color(0xFF4B5563)
}

fun typeLabel(type: String): String = when (type) {
    "order.create" -> "Nouvelle commande"
    "order.round" -> "Tour ajouté"
    "order.bill" -> "Addition"
    "order.remove_line" -> "Article retiré"
    "payment.tender", "payment" -> "Paiement"
    "payment.split" -> "Paiement partagé"
    else -> type
}

private val atFormat = DateTimeFormatter.ofPattern("dd/MM HH:mm")

private fun formatAt(at: String): String = runCatching {
    Instant.parse(at).atZone(ZoneId.systemDefault()).format(atFormat)
}.getOrDefault(at)
