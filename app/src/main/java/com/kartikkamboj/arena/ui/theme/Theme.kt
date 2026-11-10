package com.kartikkamboj.arena.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kartikkamboj.arena.data.prefs.ThemeMode

/** True while the dark charcoal palette is active; screens use it to pick overlay alphas. */
val LocalDarkTheme = staticCompositionLocalOf { true }

private val ArenaTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    // Micro-labels are monospaced and wide-tracked: the one typographic signature of this app. It
    // costs no font loading (a platform family), and it makes status text read as instrumentation
    // rather than as prose the user is meant to parse.
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    ),
)

// Radii are deliberately smaller than a material-default UI: at 12-16 dp a panel still reads as a
// tool, while 28+ reads as a consumer card. This screen is a workspace, not a feed.
private val ArenaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

private val DarkScheme = darkColorScheme(
    // One accent does the work a gradient would otherwise be used for: emerald is "the app agrees",
    // amber is "the app needs you", rose is "this failed". Nothing else is colourful on purpose.
    primary = ArenaPalette.Emerald,
    onPrimary = ArenaPalette.OnAccent,
    primaryContainer = ArenaPalette.SurfaceHigh,
    onPrimaryContainer = ArenaPalette.Text,
    secondary = ArenaPalette.Amber,
    onSecondary = ArenaPalette.OnAccent,
    tertiary = ArenaPalette.Mint,
    onTertiary = ArenaPalette.OnAccent,
    background = ArenaPalette.Charcoal,
    onBackground = ArenaPalette.Text,
    surface = ArenaPalette.Surface,
    onSurface = ArenaPalette.Text,
    surfaceVariant = ArenaPalette.SurfaceVariant,
    onSurfaceVariant = ArenaPalette.TextMuted,
    outline = ArenaPalette.Outline,
    outlineVariant = ArenaPalette.OutlineSoft,
    error = ArenaPalette.Rose,
    onError = ArenaPalette.OnAccent,
    scrim = ArenaPalette.Black,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E7A55),
    onPrimary = Color.White,
    primaryContainer = ArenaPalette.LightSurfaceVariant,
    onPrimaryContainer = ArenaPalette.LightText,
    secondary = Color(0xFF8A5A12),
    onSecondary = Color.White,
    tertiary = Color(0xFF0F766E),
    onTertiary = Color.White,
    background = ArenaPalette.LightBackground,
    onBackground = ArenaPalette.LightText,
    surface = ArenaPalette.LightSurface,
    onSurface = ArenaPalette.LightText,
    surfaceVariant = ArenaPalette.LightSurfaceVariant,
    onSurfaceVariant = ArenaPalette.LightTextMuted,
    outline = ArenaPalette.LightOutline,
    outlineVariant = Color(0xFFE4E6EE),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/**
 * One entry point for colour, type and shape. The app defaults to dark because that is the palette
 * the whole surface treatment assumes, but the light scheme is real (Settings → Theme), not a stub.
 */
@Composable
fun ArenaTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = ArenaTypography,
            shapes = ArenaShapes,
            content = content,
        )
    }
}
