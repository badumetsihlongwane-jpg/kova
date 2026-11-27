package com.kova.workstation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object KovaPalette {
    val Obsidian = Color(0xFF080C14)
    val SlateDeep = Color(0xFF0F1624)
    val SlateCard = Color(0xFF151E30)
    val SlateElevated = Color(0xFF1C2840)
    val BorderDark = Color(0xFF263552)

    val Cyan = Color(0xFF38BDF8)
    val CyanDeep = Color(0xFF0284C7)
    val Emerald = Color(0xFF2DD4BF)
    val Amber = Color(0xFFFBBF24)
    val Rose = Color(0xFFFB7185)
    val Violet = Color(0xFFA78BFA)

    val InkPrimary = Color(0xFFF1F5F9)
    val InkSecondary = Color(0xFF94A3B8)
    val InkMuted = Color(0xFF64748B)

    // Light Studio palette
    val Alabaster = Color(0xFFF6F8FC)
    val LightCard = Color(0xFFFFFFFF)
    val LightElevated = Color(0xFFEDF2F9)
    val LightBorder = Color(0xFFD5DFEE)
    val LightInk = Color(0xFF0F172A)
    val LightInkSecondary = Color(0xFF475569)
}

private val KovaDarkScheme = darkColorScheme(
    primary = KovaPalette.Cyan,
    onPrimary = Color(0xFF031926),
    primaryContainer = Color(0xFF0C2D48),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = KovaPalette.Emerald,
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF113537),
    onSecondaryContainer = Color(0xFF99F6E4),
    tertiary = KovaPalette.Amber,
    onTertiary = Color(0xFF2B1D00),
    background = KovaPalette.Obsidian,
    onBackground = KovaPalette.InkPrimary,
    surface = KovaPalette.SlateDeep,
    onSurface = KovaPalette.InkPrimary,
    surfaceVariant = KovaPalette.SlateCard,
    onSurfaceVariant = KovaPalette.InkSecondary,
    surfaceContainerHigh = KovaPalette.SlateElevated,
    outline = KovaPalette.BorderDark,
    outlineVariant = Color(0xFF1D2940),
    error = KovaPalette.Rose,
    onError = Color(0xFF2D0610)
)

private val KovaLightScheme = lightColorScheme(
    primary = KovaPalette.CyanDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF075985),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF115E59),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    background = KovaPalette.Alabaster,
    onBackground = KovaPalette.LightInk,
    surface = KovaPalette.LightCard,
    onSurface = KovaPalette.LightInk,
    surfaceVariant = KovaPalette.LightElevated,
    onSurfaceVariant = KovaPalette.LightInkSecondary,
    outline = KovaPalette.LightBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFE11D48),
    onError = Color.White
)

private val KovaTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

@Composable
fun KovaTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) KovaDarkScheme else KovaLightScheme,
        typography = KovaTypography,
        content = content
    )
}
