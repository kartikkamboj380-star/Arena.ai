package com.kartikkamboj.arena.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Palette: black-charcoal surfaces, a violet-to-cyan accent pair, and text tones that keep AA
 * contrast on the darkest surface. Nothing here is bright enough to glow through a dark room, and
 * nothing is dim enough to need a second look.
 */
internal object ArenaPalette {
    val Black = Color(0xFF08090B)
    val Charcoal = Color(0xFF0B0B0D)
    val Surface = Color(0xFF121317)
    val SurfaceHigh = Color(0xFF171922)
    val SurfaceVariant = Color(0xFF1B1D23)
    val SurfaceLow = Color(0xFF0F1013)
    val Outline = Color(0xFF2A2E37)
    val OutlineSoft = Color(0xFF1F222A)

    val Violet = Color(0xFF7C5CFF)
    val VioletSoft = Color(0xFF9E86FF)
    val Cyan = Color(0xFF22D3EE)
    val Amber = Color(0xFFF0A742)
    val Rose = Color(0xFFFF5D73)
    val Mint = Color(0xFF3FD69A)

    val Text = Color(0xFFEDEEF2)
    val TextMuted = Color(0xFF9BA1AE)
    val TextFaint = Color(0xFF6B7280)

    val OnAccent = Color(0xFF0B0B0D)

    // Light scheme keeps the same accents on paper-white surfaces.
    val LightBackground = Color(0xFFFAFAFC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFEFEFF4)
    val LightOutline = Color(0xFFD6D8E0)
    val LightText = Color(0xFF14151A)
    val LightTextMuted = Color(0xFF5A5F6B)

    val heroGradient: Brush = Brush.linearGradient(
        listOf(Color(0x2E7C5CFF), Color(0x1422D3EE), Color(0x00000000)),
    )

    val cardGradient: Brush = Brush.verticalGradient(
        listOf(Color(0xFF191B22), Color(0xFF121317)),
    )

    val accentGradient: Brush = Brush.horizontalGradient(listOf(Violet, Cyan))
}
