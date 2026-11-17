package com.csqtt.client.ui.v2.design

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.csqtt.client.R

/**
 * Nunito (variable) — display font: screen titles, hero text, version pills, profile card.
 * Cyrillic is fully supported with clean rounded geometric shapes.
 */
val V2DisplayFamily = FontFamily(
    displayFont(FontWeight.Normal),
    displayFont(FontWeight.Medium),
    displayFont(FontWeight.SemiBold),
    displayFont(FontWeight.Bold),
    displayFont(FontWeight.ExtraBold),
)

/**
 * Nunito (variable) — body font: rows, fields, badges, descriptions.
 * Cyrillic is fully supported.
 */
val V2BodyFamily = FontFamily(
    bodyFont(FontWeight.Normal),
    bodyFont(FontWeight.Medium),
    bodyFont(FontWeight.SemiBold),
    bodyFont(FontWeight.Bold),
    bodyFont(FontWeight.ExtraBold),
)

/**
 * Syne (variable) — Latin-only accent font for brand words (CSQTT-Rebirth, GitHub, LOGO).
 * Falls back to the system font for non-Latin glyphs.
 */
val V2AccentFamily = FontFamily(
    accentFont(FontWeight.Normal),
    accentFont(FontWeight.Medium),
    accentFont(FontWeight.SemiBold),
    accentFont(FontWeight.Bold),
    accentFont(FontWeight.ExtraBold),
)

/**
 * TT Travels Next (variable) — signature geometric font for version pills and badges.
 */
val V2VersionFamily = FontFamily(
    versionFont(FontWeight.Normal),
    versionFont(FontWeight.Medium),
    versionFont(FontWeight.SemiBold),
    versionFont(FontWeight.Bold),
    versionFont(FontWeight.ExtraBold),
)

/**
 * Monospace — terminal and log lines. System monospace until a bundled mono
 * font is added here; screens must use this alias, not FontFamily.Monospace.
 */
val V2MonoFamily: FontFamily = FontFamily.Monospace

private fun versionFont(weight: FontWeight) = Font(
    resId = R.font.tt_travels_next_trial_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun displayFont(weight: FontWeight) = Font(
    resId = R.font.nunito_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun bodyFont(weight: FontWeight) = Font(
    resId = R.font.nunito_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun accentFont(weight: FontWeight) = Font(
    resId = R.font.syne_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)
