package com.kartikkamboj.arena.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The palette the shell paints with, exposed directly because the WebView host and the status strips
 * need the same ink-and-paper decisions as the Material components around them. Reading them through
 * `MaterialTheme.colorScheme` would mean inventing roles Material already has.
 */
val LocalPalette = staticCompositionLocalOf { ArenaPalette.dark }

val AppPalette: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

/**
 * Light follows the system and nothing else: no in-app theme switch, because the site has its own
 * appearance control and two settings for one thing is the clutter this app is built to avoid.
 */
@Composable
fun ArenaTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) ArenaPalette.dark else ArenaPalette.light
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.surfaceHigh,
            onPrimaryContainer = palette.ink,
            secondary = palette.inkMuted,
            onSecondary = palette.background,
            background = palette.background,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceHigh,
            onSurfaceVariant = palette.inkMuted,
            outline = palette.outline,
            outlineVariant = palette.outlineSoft,
            error = palette.error,
            onError = palette.onAccent,
            scrim = Color(0xB3000000),
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.surfaceHigh,
            onPrimaryContainer = palette.ink,
            secondary = palette.inkMuted,
            onSecondary = palette.background,
            background = palette.background,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceHigh,
            onSurfaceVariant = palette.inkMuted,
            outline = palette.outline,
            outlineVariant = palette.outlineSoft,
            error = palette.error,
            onError = Color(0xFFFFFBF8),
            scrim = Color(0x33000000),
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        // Platform typography on purpose: no font asset is shipped, which keeps the APK small and the
        // metrics identical to the rest of the device.
        typography = Typography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(18.dp),
            extraLarge = RoundedCornerShape(22.dp),
        ),
        content = content,
    )
}
