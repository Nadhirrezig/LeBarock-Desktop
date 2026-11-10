package com.lebarock.lebarock_desktop.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.lebarock.lebarock_desktop.data.offline.OfflineStatus
import com.lebarock.lebarock_desktop.ui.theme.Dimens
import com.lebarock.lebarock_desktop.ui.theme.Info
import com.lebarock.lebarock_desktop.ui.theme.Warning

/** What the banner says (the handheld's words), or null when there is nothing to say. */
fun bannerText(status: OfflineStatus): String? {
    if (!status.active) return null
    val actions = when (status.pending) {
        0 -> "aucune action en attente"
        1 -> "1 action en attente"
        else -> "${status.pending} actions en attente"
    }
    return when {
        status.offline -> "Hors ligne — $actions"
        status.keyRefused && status.pending > 0 -> "Synchronisation impossible : connectez-vous une fois en ligne sur ce poste ($actions)."
        status.syncing -> "Synchronisation… ($actions)"
        else -> null
    }
}

/**
 * Shown above every screen: this PC is working without the caisse (orange), or catching up (blue). Nothing while
 * the caisse answers and nothing waits.
 */
@Composable
fun OfflineBanner(status: OfflineStatus, modifier: Modifier = Modifier) {
    val text = bannerText(status) ?: return
    val (color, icon) = when {
        status.offline -> Warning to Icons.Outlined.CloudOff
        status.keyRefused -> Warning to Icons.Outlined.SyncProblem
        else -> Info to Icons.Outlined.Sync
    }
    Row(
        modifier.fillMaxWidth().background(color).padding(horizontal = Dimens.l, vertical = Dimens.s),
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(Dimens.icon))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
