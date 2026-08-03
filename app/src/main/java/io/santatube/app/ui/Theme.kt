package io.santatube.app.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** SantaTube brand: the same red as the logo/focus ring, everywhere. */
val SantaTubeDarkColors = darkColorScheme(
    primary = Color(0xFFE53935),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF7F1D1D),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFEF9A9A),
    onSecondary = Color(0xFF3E0A08),
    secondaryContainer = Color(0xFF5C1512),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFFFB4AB)
)

/** "1.5x" / "0.5x" / "FREE" — one shared spelling of a screen-time multiplier. */
fun timeMultiplierLabel(percent: Int): String = when (percent) {
    0 -> "FREE"
    else -> {
        val whole = percent / 100
        val frac = percent % 100
        if (frac == 0) "${whole}x" else "$whole.${"%02d".format(frac).trimEnd('0')}x"
    }
}

/** Chip color: green = cheaper than normal, amber = costs extra. Null at 1x. */
fun timeMultiplierColor(percent: Int): Color? = when {
    percent == 100 -> null
    percent > 100 -> Color(0xFFB26A00)
    else -> Color(0xFF2E7D32)
}

/**
 * Parent-facing settings/stats: the brand red reads as alarming on a dense
 * admin form, so these screens use a calm blue-grey scheme instead.
 */
val AdminDarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF1F3A5F),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFB0BEC5),
    onSecondary = Color(0xFF1C2A33),
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = Color(0xFFDCE7EC),
    tertiary = Color(0xFF80CBC4)
)
