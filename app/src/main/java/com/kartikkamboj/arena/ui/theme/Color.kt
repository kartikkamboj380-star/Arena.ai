package com.kartikkamboj.arena.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette: obsidian and graphite surfaces, one emerald accent for anything the app is *doing*, and
 * warm amber for anything the user has to notice. No blue, no gradient hero, no glow.
 *
 * The surfaces are lifted in steps of ~4 in local brightness rather than by elevation shadows, which
 * is what keeps a 2 GB device honest: a shadow costs a render pass, a lighter rectangle costs
 * nothing. Text tones hold AA contrast on the darkest surface, and every accent is chosen to remain
 * legible against both the dark and the light scheme.
 */
internal object ArenaPalette {
    val Black = Color(0xFF060708)
    val Charcoal = Color(0xFF0A0C0D)
    val Surface = Color(0xFF101314)
    val SurfaceHigh = Color(0xFF15191A)
    val SurfaceVariant = Color(0xFF1A1F20)
    val SurfaceLow = Color(0xFF0D1011)
    val Outline = Color(0xFF282E2F)
    val OutlineSoft = Color(0xFF1D2223)

    /** Primary accent: the user's own side of the boundary — active slot, live session, focus. */
    val Emerald = Color(0xFF35C48A)
    val EmeraldSoft = Color(0xFF6FDCAE)

    /** Attention, never alarm: rate limits, "verify me", pending sign-in. */
    val Amber = Color(0xFFE3A857)

    val Rose = Color(0xFFF0596E)
    val Mint = Color(0xFF7FE0BB)

    val Text = Color(0xFFECEDF0)
    val TextMuted = Color(0xFF99A1A6)
    val TextFaint = Color(0xFF6A737A)

    val OnAccent = Color(0xFF07130E)

    // Light scheme keeps the same accents on paper-white surfaces.
    val LightBackground = Color(0xFFFAFBFA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFEDF0EF)
    val LightOutline = Color(0xFFD4D9D8)
    val LightText = Color(0xFF101314)
    val LightTextMuted = Color(0xFF565E62)

    /**
     * Card fill. Deliberately two adjacent tones: enough to read as a raised panel, close enough
     * that it never looks like a gradient was applied for its own sake.
     */
    val cardGradient: androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(Color(0xFF141819), Color(0xFF101314)),
    )
}
