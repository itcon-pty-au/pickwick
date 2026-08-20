package io.pickwick.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Pickwick brand: the logo's dark teal (#00695C), lightened to the tones a
 * dark theme needs — Material wants `primary` legible *on* the background, so
 * the logo colour itself only appears as a container/fill, never as text.
 */
val PickwickDarkColors = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF00352F),
    primaryContainer = Color(0xFF00695C),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB0CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFFA5C8E4)
)

/**
 * Watched/played progress. Deliberately not the brand teal: kids read this bar
 * by the same convention YouTube taught them, so it stays red everywhere it
 * appears (thumbnail bars and the player scrubber).
 */
val WatchedProgressRed = Color(0xFFFF0000)

/** The thumbnail-bottom watched bar, one spelling for every grid and row. */
@Composable
fun BoxScope.WatchedProgressBar(fraction: Float) {
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .height(4.dp).background(Color(0x66FFFFFF))
    )
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction)
            .height(4.dp).background(WatchedProgressRed)
    )
}

/** "h:mm:ss" over an hour, "m:ss" under — one spelling of a duration everywhere. */
fun formatClock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

/**
 * SponsorBlock-marked stretches on the player scrubber. Green by the same
 * borrowed-convention logic as [WatchedProgressRed]: SmartTube et al. taught
 * viewers that green-on-the-bar means "this part will be skipped".
 */
val SponsorSegmentGreen = Color(0xFF00C853)

/** The "newer build available" dot on the settings gear. */
val UpdateDot = Color(0xFFFF5252)

/** Status readouts in settings (search-index health, per-channel completeness):
 *  soft Material green/red — full-saturation traffic lights read as alarms. */
val StatusOkGreen = Color(0xFF81C784)
val StatusFailRed = Color(0xFFE57373)

/** The home screen's non-channel tiles — the phone grid and the TV row draw
 *  the same tiles, so their identity colors get one spelling here. */
val SurpriseTileCyan = Color(0xFF00ACC1)
val QueueTilePurple = Color(0xFF6A4FA3)
val WatchlistTileTeal = Color(0xFF00897B)
val WatchLaterTileTeal = Color(0xFF4DB6AC)
val DownloadsTileTeal = Color(0xFF00636E)

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
 * Parent-facing settings/stats: the same teal, desaturated a step. Dense admin
 * forms shouldn't shout, and the muted variant still reads as "same app,
 * grown-up room" rather than switching brand mid-flow.
 */
val AdminDarkColors = darkColorScheme(
    primary = Color(0xFF7FBAB2),
    onPrimary = Color(0xFF00352F),
    primaryContainer = Color(0xFF23514B),
    onPrimaryContainer = Color(0xFFCCE8E3),
    secondary = Color(0xFFAEBFBC),
    onSecondary = Color(0xFF1C2A28),
    secondaryContainer = Color(0xFF3A4B49),
    onSecondaryContainer = Color(0xFFDCE7E5),
    tertiary = Color(0xFF9FC6C0)
)
