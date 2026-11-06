package io.pickwick.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Focus on TV: nothing here outlines anything, because rings looked cheap on
 * artwork. Tiles lift ([onTileFocus], [tvFocusLift]), buttons fill white (TvSquareButtons.kt),
 * and rows and other plain controls get a soft band ([tvFocusHighlight]).
 */

/** The band behind a focused row. Translucent so its own text colors still work. */
@Composable
internal fun Modifier.tvFocusHighlight(onFocusChange: ((Boolean) -> Unit)? = null): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged {
            val now = it.isFocused || it.hasFocus
            focused = now
            onFocusChange?.invoke(now)
        }
        .drawBehind { if (focused) drawRect(TvFocusBand) }
}

private val TvFocusBand = Color.White.copy(alpha = 0.2f)

/**
 * Reports a tile's focus for [tvFocusLift] and its caption, and draws it above
 * its neighbors so the shadow isn't painted over. Goes before the clickable.
 */
@Composable
internal fun Modifier.onTileFocus(onFocusChange: (Boolean) -> Unit): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged {
            val now = it.isFocused || it.hasFocus
            focused = now
            onFocusChange(now)
        }
        .zIndex(if (focused) 1f else 0f)
}

/**
 * A focused tile rises toward the viewer. Scale and shadow live in the graphics
 * layer: they never touch layout, so neighbors stay put, and the animation costs
 * a layer update per frame rather than a recomposition, which a Chromecast feels.
 * The size is what fits the 8dp list padding, so the widest tile isn't clipped.
 *
 * Goes after the clickable, always. Scroll-into-view measures the focus target
 * through any layers around it, so a lift outside it reads as a taller tile, and
 * the page scrolls a few pixels to fit it and back again on every D-pad press.
 */
@Composable
internal fun Modifier.tvFocusLift(focused: Boolean): Modifier {
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(120), label = "tvFocusLift")
    return graphicsLayer {
        val scale = 1f + 0.05f * lift
        scaleX = scale
        scaleY = scale
        shadowElevation = 16.dp.toPx() * lift
    }
}

/** A tile's caption bar flips with focus: the lift alone is faint on dark artwork. */
@Composable
internal fun tvTileColors(focused: Boolean): CardColors =
    if (focused) CardDefaults.cardColors(containerColor = TvFocusFill, contentColor = TvFocusInk)
    else CardDefaults.cardColors()

@Composable
internal fun tvTileSubtitleColor(focused: Boolean): Color =
    if (focused) TvFocusInk.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant

/** A rounded ripple inside the square focus band would bring the odd corners back. */
@Composable
internal fun Modifier.clipOnPhone(shape: Shape): Modifier =
    if (isTvDevice()) this else clip(shape)

/**
 * How often a *held* D-pad key may advance focus in a throttled list, per step.
 * 2.5 steps/sec: fast enough to traverse a long channel, slow enough that titles
 * are readable in flight and each new tile gets a full composition budget.
 * The single knob for held-scroll speed — raise to scroll slower.
 */
internal const val HELD_DPAD_STEP_MS = 400L

/**
 * A low-emphasis action in the settings form. The default TextButton's min
 * height + horizontal padding read as a bare hyperlink and crowd dense rows;
 * this keeps it a real focusable button (TV D-pad, ripple, content desc) at a
 * tighter footprint. Dialog confirm/cancel keeps the standard TextButton.
 */
@Composable
internal fun CompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 10.dp, vertical = 2.dp
        ),
        content = content
    )
}

/** All four directions — for grids, where any held direction moves focus. */
internal val DPAD_ALL_DIRECTIONS =
    setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/** Horizontal only — for rows, whose vertical moves belong to the outer column. */
internal val DPAD_HORIZONTAL = setOf(Key.DirectionLeft, Key.DirectionRight)

/**
 * Held D-pad speed governor for long lists. The OS auto-repeats a held key every
 * ~50 ms; each repeat moves focus a whole row, which (a) outruns what a TV GPU
 * can compose per frame — the source of held-scroll jank — and (b) flies past
 * content faster than anyone can read. This lets the first press through
 * instantly, then passes at most one repeat per [intervalMs], swallowing the
 * rest; releases and taps are never touched, so deliberate single steps stay
 * instant. Applies only to [keys]; harmless on touch devices (no key events).
 *
 * The pacing matters most for *backward* motion (left/up): the pivot keeps the
 * focused tile near the leading edge, so almost nothing is pre-composed behind
 * it and every backward step must compose its target synchronously — at the raw
 * repeat rate that composition can't keep up and focus visibly sticks.
 */
@Composable
internal fun Modifier.dpadHeldScrollThrottle(
    intervalMs: Long = HELD_DPAD_STEP_MS,
    keys: Set<Key> = DPAD_ALL_DIRECTIONS
): Modifier {
    var lastStepAt by remember { mutableLongStateOf(0L) }
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key !in keys) {
            return@onPreviewKeyEvent false
        }
        val at = event.nativeKeyEvent.eventTime
        when {
            // A fresh press (or a repeat after the gap) passes and stamps the clock.
            event.nativeKeyEvent.repeatCount == 0 -> { lastStepAt = at; false }
            at - lastStepAt >= intervalMs -> { lastStepAt = at; false }
            else -> true // swallow: focus holds still, the animation keeps its pace
        }
    }
}

/**
 * Guards a menu that a *hold* just opened against the tail of that same hold.
 *
 * Firing the long-press while OK is still down means the rest of the key
 * stream — the remaining auto-repeats and the release — lands on whatever the
 * new window focuses, and Compose's `clickable` reads that repeat-plus-release
 * as a deliberate press: the menu appears and instantly picks its first row.
 * Swallowing the release at the tile can't help, because by then the tile is no
 * longer the focus owner and never sees it.
 *
 * So the menu ignores select entirely until the remote has been let go. A
 * fresh press — [android.view.KeyEvent.getRepeatCount] back at 0, which the
 * tail of an in-flight hold can never produce — opens the gate and passes
 * through, so the first *intentional* press still works with no dead period.
 * Put this on an ancestor of whatever the menu focuses: previews run from the
 * root down, so it sees the event before the button does.
 */
@Composable
internal fun Modifier.ignoreSelectUntilRelease(): Modifier {
    var open by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { event ->
        if (open) return@onPreviewKeyEvent false
        val isSelect = event.key == Key.DirectionCenter ||
            event.key == Key.Enter || event.key == Key.NumPadEnter
        if (!isSelect) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
            open = true
            return@onPreviewKeyEvent false
        }
        if (event.type == KeyEventType.KeyUp) open = true
        true
    }
}

/**
 * TV remote hold-to-act: holding the OK/select button fires [onLongPress] once
 * (and swallows the release so the normal click doesn't also fire). Short
 * presses pass through to the regular clickable.
 *
 * It fires on the *first auto-repeat* — the OS's own hold threshold — while the
 * button is still down, matching touch long-press, where the menu appears under
 * a finger that hasn't lifted yet. Waiting for the release instead reads as a
 * delayed click and leaves the hold with no feedback. The release is then
 * swallowed; even if focus has already moved into the menu by then, a KeyUp
 * with no matching KeyDown can't activate anything.
 */
@Composable
internal fun Modifier.dpadLongPress(onLongPress: () -> Unit): Modifier {
    var fired by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { event ->
        val isSelect = event.key == Key.DirectionCenter ||
            event.key == Key.Enter || event.key == Key.NumPadEnter
        if (!isSelect) return@onPreviewKeyEvent false
        when {
            event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 1 -> {
                fired = true
                onLongPress()
                true
            }
            event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 1 -> true
            event.type == KeyEventType.KeyUp && fired -> {
                fired = false
                true
            }
            else -> false
        }
    }
}
