package com.csqtt.client.ui.v2.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class V2Colors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val field: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val flower: Color,
    val contrast: Color,
    val onContrast: Color,
    val hero: Color,
    val onHero: Color,
    val heroRow: Color,
    val navSelected: Color,
    val bottomSurface: Color,
    val terminal: Color,
    val terminalText: Color,
    val terminalMuted: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val version: Color,
)

internal val V2DarkColors = V2Colors(
    background = Color(0xFF0D0D10),
    surface = Color(0xFF1E1E24),
    surfaceElevated = Color(0xFF2B2B33),
    field = Color(0xFF1E1E24),
    outline = Color(0xFF3A3A46),
    textPrimary = Color(0xFFF3F3F7),
    textSecondary = Color(0xFF9B9BA6),
    textMuted = Color(0xFF6F6F7A),
    accent = Color(0xFF7C7BB0),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF2A2A3D),
    flower = Color(0xFF454F6E),
    contrast = Color(0xFFC9C9D2),
    onContrast = Color(0xFF17171B),
    hero = Color(0xFF6E6E80),
    onHero = Color(0xFFFFFFFF),
    heroRow = Color(0xFF7C7C8F),
    navSelected = Color(0xFF0D0D10),
    bottomSurface = Color(0xFF222228),
    terminal = Color(0xFF0A0A0C),
    terminalText = Color(0xFFD9D9E0),
    terminalMuted = Color(0xFF6F6F7A),
    success = Color(0xFF5BC97A),
    warning = Color(0xFFF0B45C),
    error = Color(0xFFE56B6B),
    version = Color(0xFF9C9ACB),
)

internal val V2NordicSlateColors = V2Colors(
    background = Color(0xFF0D0E12),
    surface = Color(0xFF181B22),
    surfaceElevated = Color(0xFF242934),
    field = Color(0xFF181B22),
    outline = Color(0xFF333B4C),
    textPrimary = Color(0xFFF1F3F7),
    textSecondary = Color(0xFF8D95A5),
    textMuted = Color(0xFF5F6575),
    accent = Color(0xFF5E82A6),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF1C2738),
    flower = Color(0xFF3B5878),
    contrast = Color(0xFFB8D3EE),
    onContrast = Color(0xFF141A24),
    hero = Color(0xFF2C394B),
    onHero = Color(0xFFFFFFFF),
    heroRow = Color(0xFF37465D),
    navSelected = Color(0xFF090A0D),
    bottomSurface = Color(0xFF1E222A),
    terminal = Color(0xFF0A0C0F),
    terminalText = Color(0xFFD6DBE4),
    terminalMuted = Color(0xFF757D8E),
    success = Color(0xFF5BC97A),
    warning = Color(0xFFF0B45C),
    error = Color(0xFFE56B6B),
    version = Color(0xFF8692A6),
)

internal val V2MutedDenimColors = V2Colors(
    background = Color(0xFF090A0D),
    surface = Color(0xFF151820),
    surfaceElevated = Color(0xFF222733),
    field = Color(0xFF151820),
    outline = Color(0xFF2F384A),
    textPrimary = Color(0xFFF2F4F8),
    textSecondary = Color(0xFF8A93A3),
    textMuted = Color(0xFF5C6473),
    accent = Color(0xFF4F7FA8),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF182638),
    flower = Color(0xFF355677),
    contrast = Color(0xFFA8CAEA),
    onContrast = Color(0xFF101620),
    hero = Color(0xFF26374A),
    onHero = Color(0xFFFFFFFF),
    heroRow = Color(0xFF31455C),
    navSelected = Color(0xFF060709),
    bottomSurface = Color(0xFF1A1E26),
    terminal = Color(0xFF07090C),
    terminalText = Color(0xFFD4DAE4),
    terminalMuted = Color(0xFF727A8A),
    success = Color(0xFF5BC97A),
    warning = Color(0xFFF0B45C),
    error = Color(0xFFE56B6B),
    version = Color(0xFF828F9E),
)

internal val V2SteelBlueColors = V2Colors(
    background = Color(0xFF0B0D10),
    surface = Color(0xFF161B22),
    surfaceElevated = Color(0xFF252D3C),
    field = Color(0xFF161B22),
    outline = Color(0xFF344256),
    textPrimary = Color(0xFFF0F3F6),
    textSecondary = Color(0xFF8C96A4),
    textMuted = Color(0xFF5E6875),
    accent = Color(0xFF688EA8),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF1A2834),
    flower = Color(0xFF3F586C),
    contrast = Color(0xFFB4CBDC),
    onContrast = Color(0xFF12181E),
    hero = Color(0xFF283A48),
    onHero = Color(0xFFFFFFFF),
    heroRow = Color(0xFF334A5C),
    navSelected = Color(0xFF07090B),
    bottomSurface = Color(0xFF1C222C),
    terminal = Color(0xFF090B0E),
    terminalText = Color(0xFFD5DBE2),
    terminalMuted = Color(0xFF737D89),
    success = Color(0xFF5BC97A),
    warning = Color(0xFFF0B45C),
    error = Color(0xFFE56B6B),
    version = Color(0xFF85929F),
)

/**
 * Palette of the focsq desktop client (C:\Progect\focsq, frontend/lib/theme/app_colors.dart):
 * neutral dark surfaces, light-grey primary buttons and the info blue as the accent.
 *
 * focsq -> v2: background 0A0A0A, card 1C1C1C, cardHover 222222, field 2B2C31,
 * border 262626, textPrimary F2F2F2, textSecondary 9A9DA3, textTertiary 666666,
 * buttonPrimary D9D9D9, buttonSecondary 252525, success 4CC38A, warning F2B84B,
 * info 5B9DF7, error E5484D.
 */
internal val V2FocsqColors = V2Colors(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF1C1C1C),
    surfaceElevated = Color(0xFF222222),
    field = Color(0xFF2B2C31),
    outline = Color(0xFF262626),
    textPrimary = Color(0xFFF2F2F2),
    textSecondary = Color(0xFF9A9DA3),
    textMuted = Color(0xFF666666),
    accent = Color(0xFF5B9DF7),
    onAccent = Color(0xFF0A0A0A),
    accentSoft = Color(0xFF252525),
    flower = Color(0xFF555555),
    contrast = Color(0xFFD9D9D9),
    onContrast = Color(0xFF0A0A0A),
    hero = Color(0xFF1C1C1C),
    onHero = Color(0xFFF2F2F2),
    heroRow = Color(0xFF2B2C31),
    navSelected = Color(0xFF2B2C31),
    bottomSurface = Color(0xFF1C1C1C),
    terminal = Color(0xFF09090D),
    terminalText = Color(0xFFF2F2F2),
    terminalMuted = Color(0xFF666666),
    success = Color(0xFF4CC38A),
    warning = Color(0xFFF2B84B),
    error = Color(0xFFE5484D),
    version = Color(0xFF9A9DA3),
)

/**
 * Light counterpart of [V2FocsqColors]. focsq itself is dark-only, so the surfaces and
 * text are derived from the same neutral scale while the semantic colors stay recognisable.
 */
internal val V2FocsqLightColors = V2Colors(
    background = Color(0xFFF2F2F2),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFE6E6E6),
    field = Color(0xFFEDEDED),
    outline = Color(0xFFD9D9D9),
    textPrimary = Color(0xFF0A0A0A),
    textSecondary = Color(0xFF555555),
    textMuted = Color(0xFF9A9DA3),
    accent = Color(0xFF5B9DF7),
    onAccent = Color(0xFF0A0A0A),
    accentSoft = Color(0xFFE3EEFC),
    flower = Color(0xFF555555),
    contrast = Color(0xFF1C1C1C),
    onContrast = Color(0xFFF2F2F2),
    hero = Color(0xFFE6E6E6),
    onHero = Color(0xFF0A0A0A),
    heroRow = Color(0xFFD9D9D9),
    navSelected = Color(0xFFE0E0E0),
    bottomSurface = Color(0xFFFFFFFF),
    terminal = Color(0xFF09090D),
    terminalText = Color(0xFFF2F2F2),
    terminalMuted = Color(0xFF9A9DA3),
    success = Color(0xFF1F8A5B),
    warning = Color(0xFFB7791F),
    error = Color(0xFFC4323A),
    version = Color(0xFF555555),
)

fun v2DarkColorsForPalette(palette: String?): V2Colors = when (palette) {
    "muted_denim" -> V2MutedDenimColors
    "steel_blue" -> V2SteelBlueColors
    "default", "pure_black" -> V2DarkColors
    "nordic_slate" -> V2NordicSlateColors
    "focsq" -> V2FocsqColors
    else -> V2NordicSlateColors
}

fun v2LightColorsForPalette(palette: String?): V2Colors = when (palette) {
    "focsq" -> V2FocsqLightColors
    else -> V2LightColors
}

internal val V2LightColors = V2Colors(
    background = Color(0xFFF5F5F8),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFDFDFE7),
    field = Color(0xFFEDEDF2),
    outline = Color(0xFFC0C0CB),
    textPrimary = Color(0xFF151519),
    textSecondary = Color(0xFF5F5F6B),
    textMuted = Color(0xFF8A8A96),
    accent = Color(0xFF5F5E93),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE4E3F4),
    flower = Color(0xFF454F6E),
    contrast = Color(0xFF1B1B20),
    onContrast = Color(0xFFF5F5F8),
    hero = Color(0xFFD9D9E4),
    onHero = Color(0xFF17171B),
    heroRow = Color(0xFFC9C9D6),
    navSelected = Color(0xFFDCDCE5),
    bottomSurface = Color(0xFFE8E8EE),
    terminal = Color(0xFF101014),
    terminalText = Color(0xFFD9D9E0),
    terminalMuted = Color(0xFF7A7A86),
    success = Color(0xFF2E9E57),
    warning = Color(0xFFB7791F),
    error = Color(0xFFC54545),
    version = Color(0xFF5F5E93),
)

internal val LocalV2Colors = staticCompositionLocalOf { V2DarkColors }

object V2Theme {
    val colors: V2Colors
        @Composable
        @ReadOnlyComposable
        get() = LocalV2Colors.current
}
