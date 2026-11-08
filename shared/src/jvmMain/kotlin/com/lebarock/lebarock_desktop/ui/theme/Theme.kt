package com.lebarock.lebarock_desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Brand: the handheld app's AltosPOS green (web theme #86EFAC, deepened for contrast).
val BrandGreen = Color(0xFF16A34A)
val BrandGreenDark = Color(0xFF15803D)
val BrandGreenContainer = Color(0xFFBBF7D0)
val OnBrandGreenContainer = Color(0xFF052E16)
val BrandNavy = Color(0xFF1E3A6E)

// States, as on the web and the handheld.
val Success = Color(0xFF16A34A)
val SuccessContainer = Color(0xFFDCFCE7)
val Warning = Color(0xFFB45309)
val WarningContainer = Color(0xFFFEF3C7)
val Danger = Color(0xFFDC2626)
val DangerContainer = Color(0xFFFEE2E2)
val Info = Color(0xFF2563EB)
val InfoContainer = Color(0xFFDBEAFE)

private val Colors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = BrandGreenContainer,
    onPrimaryContainer = OnBrandGreenContainer,
    secondary = BrandNavy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE6F6),
    onSecondaryContainer = Color(0xFF0F1F3D),
    tertiary = Info,
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFECEEF1),
    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB),
    error = Danger,
    errorContainer = DangerContainer,
)

/**
 * A counter screen read from arm's length: one step larger than Material's defaults, and
 * nothing below 14 sp (client, 2026-11-08: help text was too small to read).
 */
private val CounterTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
        labelLarge = base.labelLarge.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
    )
}

/**
 * One fixed scheme, light: a POS must look the same on every PC, whatever Windows' theme.
 */
@Composable
fun AltosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = CounterTypography) {
        // A scrollbar wide enough to see and to grab with a finger.
        CompositionLocalProvider(
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 48.dp,
                thickness = 10.dp,
                shape = RoundedCornerShape(5.dp),
                hoverDurationMillis = 300,
                unhoverColor = Color.Black.copy(alpha = 0.22f),
                hoverColor = Color.Black.copy(alpha = 0.45f),
            ),
            content = content,
        )
    }
}

val Mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
