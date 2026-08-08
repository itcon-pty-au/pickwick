package io.pickwick.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One line, always the same tile height; scrolls sideways while focused. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MarqueeTitle(
    text: String,
    focused: Boolean,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium
) {
    Text(
        text,
        maxLines = 1,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        style = style,
        modifier = if (focused) Modifier.basicMarquee() else Modifier
    )
}

@Composable
internal fun SpecialTile(
    emoji: String,
    label: String,
    circleColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        shape = androidx.compose.ui.graphics.RectangleShape,
        modifier = modifier.tvFocusHighlight().clickable { onClick() }
    ) {
        Column {
            // Full-bleed color block matching the channel-tile geometry.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(circleColor)
            ) {
                Text(emoji, fontSize = androidx.compose.ui.unit.TextUnit(56f, androidx.compose.ui.unit.TextUnitType.Sp))
            }
            Box(Modifier.padding(8.dp)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
