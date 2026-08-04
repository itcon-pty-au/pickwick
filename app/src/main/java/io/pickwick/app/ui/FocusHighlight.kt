package io.pickwick.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Focused tiles gently zoom — no ring, no shadow; the size change alone marks
 * focus. [onFocusChange] lets tiles react (e.g. marquee).
 *
 * Built for buttery D-pad scrolling: no elevation shadow (RenderNode shadows
 * are the single most expensive part of a focus sweep on TV GPUs), and the
 * zoom lives entirely in graphicsLayer, so focus moves never touch layout —
 * the tile's measured size (and thus the list's scroll geometry) is constant.
 *
 * The zoom is deliberately mild (4%) and eased symmetrically: during a focus
 * step the outgoing tile shrinks on the same curve the incoming tile grows,
 * so the pair reads as one smooth handoff instead of a jump.
 */
@Composable
internal fun Modifier.tvFocusHighlight(onFocusChange: ((Boolean) -> Unit)? = null): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 160,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "focusScale"
    )
    return this
        .onFocusChanged {
            val now = it.isFocused || it.hasFocus
            focused = now
            onFocusChange?.invoke(now)
        }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
}

/**
 * How often a *held* D-pad key may advance focus in a throttled list, per step.
 * 2.5 steps/sec: fast enough to traverse a long channel, slow enough that titles
 * are readable in flight and each new tile gets a full composition budget.
 * The single knob for held-scroll speed — raise to scroll slower.
 */
internal const val HELD_DPAD_STEP_MS = 400L

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
 * TV remote hold-to-act: holding the OK/select button fires [onLongPress] once
 * (and swallows the release so the normal click doesn't also fire). Short
 * presses pass through to the regular clickable.
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
