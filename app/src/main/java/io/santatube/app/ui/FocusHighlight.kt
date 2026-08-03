package io.santatube.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/** Vivid, kid-friendly glow colors — a fresh one on every focus. */
private val GlowPalette = listOf(
    Color(0xFFFF5252), // red
    Color(0xFFFFAB40), // orange
    Color(0xFFFFEB3B), // yellow
    Color(0xFF69F0AE), // green
    Color(0xFF40C4FF), // sky
    Color(0xFF536DFE), // indigo
    Color(0xFFE040FB), // purple
    Color(0xFFFF4081)  // pink
)

/**
 * Focused tiles gently zoom and get a rainbow ring — a different color each
 * time focus lands. [onFocusChange] lets tiles react (e.g. marquee).
 *
 * Built for buttery D-pad scrolling: no elevation shadow (RenderNode shadows
 * are the single most expensive part of a focus sweep on TV GPUs), and the
 * modifier chain never changes shape on focus — the ring is an always-attached
 * drawBehind that reads animated state, so focus moves only invalidate the
 * draw pass, never layout.
 */
@Composable
internal fun Modifier.tvFocusHighlight(onFocusChange: ((Boolean) -> Unit)? = null): Modifier {
    var focused by remember { mutableStateOf(false) }
    var glow by remember { mutableStateOf(GlowPalette.random()) }
    // Animated zoom: an instant 7% snap on every focus step reads as jitter
    // while scrolling; a quick spring makes the same zoom feel fluid.
    val scale = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) 1.07f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "focusScale"
    )
    val ring = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "focusRing"
    )
    return this
        .onFocusChanged {
            val now = it.isFocused || it.hasFocus
            if (now && !focused) glow = GlowPalette.random()
            focused = now
            onFocusChange?.invoke(now)
        }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        // Single crisp ring drawn fully OUTSIDE the bounds — never over the
        // artwork — fading in/out with focus.
        .drawBehind {
            val a = ring.value
            if (a > 0.01f) {
                val gap = 3.dp.toPx()
                drawRect(
                    color = glow.copy(alpha = 0.85f * a),
                    topLeft = Offset(-gap, -gap),
                    size = Size(size.width + 2 * gap, size.height + 2 * gap),
                    style = Stroke(width = 2 * gap)
                )
            }
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
