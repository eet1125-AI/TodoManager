package com.eric.todo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Orange = Color(0xFFE8833A)      // burnt orange, readable on dark
val OrangeDark = Color(0xFFCC5500)  // burnt orange
val BusinessBlue = Color(0xFF4FA8FF)
val PersonalGreen = Color(0xFF4CD08A)
val UrgentRed = Color(0xFFFF5C61)

private val Dark = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF1E0B00),
    primaryContainer = Color(0xFF5A2A0C),
    onPrimaryContainer = Color(0xFFFFDCC7),
    secondaryContainer = Color(0xFF22324F),
    onSecondaryContainer = Color(0xFFDCE6F7),
    background = Color(0xFF070D1C),
    onBackground = Color(0xFFF4F7FC),
    surface = Color(0xFF070D1C),
    onSurface = Color(0xFFF4F7FC),
    surfaceVariant = Color(0xFF1A2742),
    onSurfaceVariant = Color(0xFFB3BFD4),
    surfaceContainerLowest = Color(0xFF0A1226),
    surfaceContainerLow = Color(0xFF111C35),
    surfaceContainer = Color(0xFF16223B),
    surfaceContainerHigh = Color(0xFF1A2742),
    surfaceContainerHighest = Color(0xFF15213A),
    outline = Color(0xFF4A5A76),
    outlineVariant = Color(0x33FFFFFF),
    error = UrgentRed,
)

private val Light = lightColorScheme(
    primary = OrangeDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC7),
    onPrimaryContainer = Color(0xFF3A1600),
    secondaryContainer = Color(0xFFDCE6F7),
    onSecondaryContainer = Color(0xFF0E1830),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF0B0F17),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF0B0F17),
    surfaceVariant = Color(0xFFE3E7EE),
    onSurfaceVariant = Color(0xFF3B4558),
    surfaceContainerHighest = Color.White,
    surfaceContainerHigh = Color(0xFFEDEFF3),
    surfaceContainer = Color(0xFFEDEFF3),
    outline = Color(0xFF8A94A6),
    error = Color(0xFFD12F3A),
)

/** Task text size picked in Settings (sp). */
val LocalTextSize = staticCompositionLocalOf { 24 }

@Composable
fun TodoTheme(light: Boolean, textSize: Int, content: @Composable () -> Unit) {
    val base = Typography()
    val typography = base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
        labelLarge = base.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontSize = 18.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalTextSize provides textSize) {
        MaterialTheme(colorScheme = if (light) Light else Dark, typography = typography, content = content)
    }
}

fun listColor(list: String) = if (list == com.eric.todo.data.PERSONAL) PersonalGreen else BusinessBlue

@Composable
fun taskTextStyle(): TextStyle {
    val size = LocalTextSize.current
    return TextStyle(fontSize = size.sp, lineHeight = (size * 1.25).sp, fontWeight = FontWeight.SemiBold)
}
