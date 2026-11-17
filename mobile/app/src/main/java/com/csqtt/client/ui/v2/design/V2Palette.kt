package com.csqtt.client.ui.v2.design

import androidx.compose.ui.graphics.Color

/**
 * Fixed (non-theme) palette of the v2 UI.
 *
 * Semantic theme colors live in [V2Colors] (V2DarkColors / V2LightColors) and are
 * accessed through `V2Theme.colors`. Everything here does not follow the theme:
 * the log terminal is always dark, and brand elements (version badge, GitHub and
 * IP pills) always sit on their dark ink background regardless of the palette.
 *
 * Change colors only here — screens and components must reference V2Palette.*
 * instead of hardcoding hex values.
 */
object V2Palette {

    // --- Ink (dark brand surfaces) ---

    /** Dark pill background under GitHub / IP labels and hero-card chips. */
    val InkDark: Color = Color(0xFF141414)

    /** Slightly elevated ink surface for floating controls over the dark terminal. */
    val InkDarkElevated: Color = Color(0xFF1D1E24)

    /** Black gear-flower badge behind the version text. */
    val InkDarkBadge: Color = Color(0xFF000000)

    /** Text/icons on ink surfaces. */
    val OnInkDark: Color = Color(0xFFF2F2F2)

    /** Subtle border on ink surfaces. */
    val OnInkDarkSubtle: Color = Color(0x1AFFFFFF)

    // --- Terminal log tags (the terminal is always dark) ---

    val TagError = TagColors(
        background = Color(0xFF281213),
        text = Color(0xFFE5484D),
        border = Color(0xFF4E1E20),
    )

    val TagSuccess = TagColors(
        background = Color(0xFF111F19),
        text = Color(0xFF4CC38A),
        border = Color(0xFF1F3B2A),
    )

    val TagInfo = TagColors(
        background = Color(0xFF131B26),
        text = Color(0xFF5B9DF7),
        border = Color(0xFF20324C),
    )

    val TagWarning = TagColors(
        background = Color(0xFF241D10),
        text = Color(0xFFF0B45C),
        border = Color(0xFF4A3A1B),
    )

    val TagCount = TagColors(
        background = Color(0xFF0C243B),
        text = Color(0xFF38BDF8),
        border = Color(0x800284C7),
    )
}

/** Background / text / border triplet of a log tag pill. */
data class TagColors(
    val background: Color,
    val text: Color,
    val border: Color,
)
