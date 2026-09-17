package com.evercare.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sizes that are deliberately larger than Material defaults.
 *
 * These are not a style preference. Presbyopia and reduced fine motor control
 * are the norm in this app's audience, so the minimum tap target is well above
 * Material's 48dp and body text starts where Material's headline ends.
 */
object EverCareDimens {
    /** Minimum height for anything tappable. */
    val TouchTarget = 72.dp

    /** The main action buttons on the floating panel. */
    val ActionButton = 88.dp

    /** The collapsed floating bubble. */
    val Bubble = 64.dp

    val ScreenPadding = 20.dp
    val ItemSpacing = 16.dp
    val CornerRadius = 20.dp
}

/**
 * High-contrast palette. Contrast ratios are kept well above 4.5:1 because
 * cataracts and yellowing lenses wash out the low-contrast greys that modern
 * UIs favour.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5FA5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E6F7),
    onPrimaryContainer = Color(0xFF062A4A),
    secondary = Color(0xFF166B4F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFEBDE),
    onSecondaryContainer = Color(0xFF05301F),
    error = Color(0xFFA51C1C),
    onError = Color.White,
    background = Color(0xFFFDFCF8),
    onBackground = Color(0xFF14181C),
    surface = Color.White,
    onSurface = Color(0xFF14181C),
    surfaceVariant = Color(0xFFE6E9EC),
    onSurfaceVariant = Color(0xFF2C3238),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC9F0),
    onPrimary = Color(0xFF00243F),
    primaryContainer = Color(0xFF0B4676),
    onPrimaryContainer = Color(0xFFD4E6F7),
    secondary = Color(0xFF8FD6B7),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFF0E5038),
    onSecondaryContainer = Color(0xFFCFEBDE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF111416),
    onBackground = Color(0xFFF1F3F5),
    surface = Color(0xFF191C1E),
    onSurface = Color(0xFFF1F3F5),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFDDE3E8),
)

/**
 * Every size is bumped one step up the scale, and line height is generous:
 * tightly packed lines are the first thing to become unreadable with age.
 */
private val ElderFriendlyTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 34.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
    labelLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun EverCareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ElderFriendlyTypography,
        content = content,
    )
}
