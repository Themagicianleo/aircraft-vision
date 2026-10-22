package com.themagicianleo.aircraftvision

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Aviation palette: deep navy sky, light sky-blue accent, warm runway amber.
private val Navy = Color(0xFF0B1F3A)
private val NavyDeep = Color(0xFF06142A)
private val SkyBlueDark = Color(0xFF8CCBFF)
private val Amber = Color(0xFFFFB547)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B63B6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF4F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE3EAF3),
    onSurfaceVariant = Color(0xFF44505F),
    outlineVariant = Color(0xFFC6D0DC),
)

private val DarkColors = darkColorScheme(
    primary = SkyBlueDark,
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF0F3E6E),
    onPrimaryContainer = Color(0xFFD3E6FF),
    secondary = Amber,
    secondaryContainer = Color(0xFF5A3D00),
    onSecondaryContainer = Color(0xFFFFE0B2),
    background = NavyDeep,
    surface = Color(0xFF0F2340),
    surfaceVariant = Color(0xFF1A3150),
    onSurfaceVariant = Color(0xFFB7C4D6),
    outlineVariant = Color(0xFF2C4466),
)

/** The header gradient: night sky fading into dawn blue. */
val SkyGradient = Brush.verticalGradient(listOf(NavyDeep, Navy, Color(0xFF184C86)))

val ConfidenceHigh = Color(0xFF2EB872)
val ConfidenceMid = Amber
val ConfidenceLow = Color(0xFFE5534B)

fun confidenceColor(confidence: Float): Color = when {
    confidence >= 0.7f -> ConfidenceHigh
    confidence >= 0.4f -> ConfidenceMid
    else -> ConfidenceLow
}

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    )
}

@Composable
fun AircraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
