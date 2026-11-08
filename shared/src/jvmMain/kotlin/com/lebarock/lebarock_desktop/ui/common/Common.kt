package com.lebarock.lebarock_desktop.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.ui.theme.Danger
import com.lebarock.lebarock_desktop.ui.theme.DangerContainer
import com.lebarock.lebarock_desktop.ui.theme.Info
import com.lebarock.lebarock_desktop.ui.theme.InfoContainer
import com.lebarock.lebarock_desktop.ui.theme.Success
import com.lebarock.lebarock_desktop.ui.theme.SuccessContainer
import com.lebarock.lebarock_desktop.ui.theme.Warning
import com.lebarock.lebarock_desktop.ui.theme.WarningContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Async state of a screen part. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String, val hint: String? = null) : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
}

/**
 * A screen's state holder, alive while its screen is shown (the desktop needs no Android
 * ViewModel): [scope] runs on the UI thread and is cancelled when the screen goes.
 */
abstract class ScreenModel {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    open fun onDispose() {}
    fun dispose() {
        onDispose()
        scope.cancel()
    }
}

@Composable
fun <T : ScreenModel> rememberScreenModel(key: Any? = Unit, create: () -> T): T {
    val model = remember(key) { create() }
    DisposableEffect(model) { onDispose { model.dispose() } }
    return model
}

/** The AltosPOS logo (window icon, top bars). */
object Logo {
    val bitmap: ImageBitmap by lazy {
        val bytes = Logo::class.java.getResourceAsStream("/altospos.png")!!.use { it.readBytes() }
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }
}

@Composable
fun LogoImage(size: Dp = 40.dp) {
    Image(Logo.bitmap, contentDescription = "AltosPOS", modifier = Modifier.size(size))
}

enum class Tone { SUCCESS, WARNING, DANGER, INFO }

/** A coloured message strip: what happened, and what to do about it. */
@Composable
fun Banner(
    tone: Tone,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val (fg, bg, icon) = when (tone) {
        Tone.SUCCESS -> Triple(Success, SuccessContainer, Icons.Outlined.CheckCircle)
        Tone.WARNING -> Triple(Warning, WarningContainer, Icons.Outlined.WarningAmber)
        Tone.DANGER -> Triple(Danger, DangerContainer, Icons.Outlined.ErrorOutline)
        Tone.INFO -> Triple(Info, InfoContainer, Icons.Outlined.Info)
    }
    Surface(color = bg, shape = com.lebarock.lebarock_desktop.ui.theme.Dimens.shape, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = fg, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(detail, color = Color(0xFF374151), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (action != null) {
                Spacer(Modifier.width(12.dp))
                action()
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        if (label != null) {
            Spacer(Modifier.height(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ErrorBox(message: String, hint: String?, onRetry: (() -> Unit)?, modifier: Modifier = Modifier, extra: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Danger, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onRetry != null) AppButton("Réessayer", onRetry)
            extra?.invoke()
        }
    }
}

/** Initials in a coloured circle (accounts have no photo on the counter). */
@Composable
fun Initials(name: String, seed: Long, size: Dp = 56.dp) {
    val palette = listOf(
        Color(0xFF16A34A), Color(0xFF2563EB), Color(0xFF9333EA), Color(0xFFDB2777),
        Color(0xFFEA580C), Color(0xFF0891B2), Color(0xFF4F46E5), Color(0xFF65A30D),
    )
    val color = palette[(seed % palette.size).toInt().let { if (it < 0) -it else it }]
    val letters = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(letters, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}
