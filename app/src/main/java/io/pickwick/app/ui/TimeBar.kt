package io.pickwick.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp

/**
 * Today's screen time as one bar: the base budget in teal, then any bonus a
 * parent granted in orange, with the watched part solid and what is left
 * hatched. Same drawing on the kid page and the Stats screen so a parent
 * learns it once; every number is a minute so the two never disagree on
 * what a pixel means.
 *
 * Watched fills left to right across the base first and only then eats the
 * bonus — that is the order the guard spends them in, and it is what makes
 * "take back" legible: the orange end shortens, and if the solid part
 * already reaches it the kid is out of time.
 */
@Composable
fun TodayTimeBar(
    watchedMin: Int,
    baseMin: Int,
    bonusMin: Int,
    modifier: Modifier = Modifier
) {
    val total = (baseMin + bonusMin).coerceAtLeast(1)
    val base = MaterialTheme.colorScheme.primary
    Canvas(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
    ) {
        val unit = size.width / total
        val baseW = baseMin * unit
        val bonusW = bonusMin * unit
        val watchedW = (watchedMin.coerceIn(0, total)) * unit
        segment(0f, baseW, base, watchedW)
        segment(baseW, bonusW, BonusTimeOrange, watchedW)
        // A hairline where the bonus starts, so "how much did I add" can be
        // read off the bar even once it is all solid.
        if (bonusMin > 0 && baseMin > 0) drawRect(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(baseW - 1f, 0f), size = Size(2f, size.height)
        )
    }
}

/** One colour stretch [x, x+w): solid up to [watchedTo], hatched past it. */
private fun DrawScope.segment(x: Float, w: Float, color: Color, watchedTo: Float) {
    if (w <= 0f) return
    val solidEnd = watchedTo.coerceIn(x, x + w)
    if (solidEnd > x) drawRect(color, Offset(x, 0f), Size(solidEnd - x, size.height))
    if (solidEnd < x + w) clipRect(solidEnd, 0f, x + w, size.height) {
        drawRect(color.copy(alpha = 0.18f), Offset(solidEnd, 0f), Size(x + w - solidEnd, size.height))
        // 45° stripes: each line runs from the bottom edge up-right by the
        // bar's height, stepping along the bar.
        val step = size.height * 0.75f
        var sx = solidEnd - size.height
        while (sx < x + w) {
            drawLine(
                color.copy(alpha = 0.55f),
                Offset(sx, size.height), Offset(sx + size.height, 0f),
                strokeWidth = 2.5f
            )
            sx += step
        }
    }
}

/** Two swatches under the bar naming its colours; only worth it when both appear. */
@Composable
fun TodayTimeLegend(baseMin: Int, bonusMin: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Swatch(MaterialTheme.colorScheme.primary)
        Text(
            "$baseMin min today",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (bonusMin > 0) {
            Spacer(Modifier.width(14.dp))
            Swatch(BonusTimeOrange)
            Text(
                "+$bonusMin bonus",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Canvas(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp))) { drawRect(color) }
    Spacer(Modifier.width(6.dp))
}

/** Bar + legend, the block both screens share. */
@Composable
fun TodayTimeBlock(watchedMin: Int, baseMin: Int, bonusMin: Int) {
    Column {
        TodayTimeBar(watchedMin, baseMin, bonusMin)
        Spacer(Modifier.height(6.dp))
        TodayTimeLegend(baseMin, bonusMin)
    }
}
