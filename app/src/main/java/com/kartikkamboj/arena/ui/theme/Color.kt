package com.kartikkamboj.arena.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The whole palette. Two modes, one accent, no gradient, no glow.
 *
 * The rule the app is built on is that colour carries meaning, not decoration: surfaces step in small
 * increments so panels read as physical layers, warm ink is the only accent, and the one red is kept
 * for the one thing it means (a load that failed). Nothing in here animates a colour or paints a blob.
 */
object ArenaPalette {

    // ---- light: warm paper, not clinical white -------------------------------------------------
    val Paper = Color(0xFFF6F3EF)
    val PaperSurface = Color(0xFFFFFFFF)
    val PaperSurfaceHigh = Color(0xFFF1EDE8)
    val PaperOutline = Color(0xFFE3DDD5)
    val PaperOutlineSoft = Color(0xFFEDE8E2)
    val PaperInk = Color(0xFF1C1A17)
    val PaperInkMuted = Color(0xFF6B655D)
    val PaperInkFaint = Color(0xFF746D63)
    val PaperOnAccent = Color(0xFFFFF8F4)

    // ---- dark: charcoal and graphite, warm-tinted so it never reads as blue -------------------
    val Charcoal = Color(0xFF141312)
    val Graphite = Color(0xFF1C1B19)
    val GraphiteHigh = Color(0xFF262421)
    val GraphiteOutline = Color(0xFF37342F)
    val GraphiteOutlineSoft = Color(0xFF2C2A26)
    val Ink = Color(0xFFECE9E4)
    val InkMuted = Color(0xFFA39C92)
    val InkFaint = Color(0xFF8B857B)
    val OnAccent = Color(0xFF1B0E06)

    // ---- accent + the two semantic colours, in both keys --------------------------------------
    /** Clay: the app's own chrome. Deliberately warm, deliberately the only saturated colour. */
    val Clay = Color(0xFFC8794F)
    val ClayLight = Color(0xFFA64E2C)
    val ClaySoft = Color(0xFFE8B392)
    val Error = Color(0xFFD96A5B)
    val ErrorLight = Color(0xFFA8321F)
    // Note: `Error` above is the dark-mode red; `ErrorLight` is the light-mode one. Both are used
    // only for text that says something went wrong — never as a fill, never as a border glow.
    val Warning = Color(0xFFD9A03F)
    val WarningLight = Color(0xFF8A5A00)

    /** Scrim behind a panel. No blur, no frosted anything. */
    val Scrim = Color(0x99000000)

    val light = Palette(
        background = Paper,
        surface = PaperSurface,
        surfaceHigh = PaperSurfaceHigh,
        outline = PaperOutline,
        outlineSoft = PaperOutlineSoft,
        ink = PaperInk,
        inkMuted = PaperInkMuted,
        inkFaint = PaperInkFaint,
        accent = ClayLight,
        accentSoft = ClaySoft,
        onAccent = PaperOnAccent,
        error = ErrorLight,
        warning = WarningLight,
    )

    val dark = Palette(
        background = Charcoal,
        surface = Graphite,
        surfaceHigh = GraphiteHigh,
        outline = GraphiteOutline,
        outlineSoft = GraphiteOutlineSoft,
        ink = Ink,
        inkMuted = InkMuted,
        inkFaint = InkFaint,
        accent = Clay,
        accentSoft = Clay,
        onAccent = OnAccent,
        error = Error,
        warning = Warning,
    )
}

/**
 * A named set of the six roles the shell actually paints. Existing as data rather than as a
 * `MaterialTheme.colorScheme` lookup keeps the "what colour is a failure" decision in one place, and
 * lets the WebView host pick its own backdrop without reaching through the colour scheme.
 */
data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val outlineSoft: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val error: Color,
    val warning: Color,
)
