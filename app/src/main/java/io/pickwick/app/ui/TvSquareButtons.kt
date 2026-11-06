package io.pickwick.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AssistChip as M3AssistChip
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.FilledTonalButton as M3FilledTonalButton
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.TextButton as M3TextButton

/*
 * Material's buttons and chips, TV-styled: square, and solid white with dark text
 * while focused, like the player's own buttons. Phones keep Material's look.
 *
 * Same names on purpose: a package member outranks the material3.* star import,
 * so every screen gets these without a call site changing. An explicit import
 * of one of Material's names would bypass them, so don't add one. Buttons don't
 * take tvFocusHighlight either: Material keeps a 48dp touch target around the
 * 40dp fill, and the band would poke out around it.
 */

@Composable
internal fun isTvDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }
}

@Composable
private fun squareOnTv(phone: Shape): Shape = if (isTvDevice()) RectangleShape else phone

@Composable
private fun MutableInteractionSource.litOnTv(): Boolean {
    val focused by collectIsFocusedAsState()
    return focused && isTvDevice()
}

private fun ButtonColors.lit(lit: Boolean): ButtonColors =
    if (lit) copy(containerColor = TvFocusFill, contentColor = TvFocusInk) else this

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = squareOnTv(ButtonDefaults.shape),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    M3Button(onClick, modifier, enabled, shape, colors.lit(source.litOnTv()), elevation, border, contentPadding, source, content)
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = squareOnTv(ButtonDefaults.filledTonalShape),
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    M3FilledTonalButton(onClick, modifier, enabled, shape, colors.lit(source.litOnTv()), elevation, border, contentPadding, source, content)
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = squareOnTv(ButtonDefaults.outlinedShape),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val lit = source.litOnTv()
    M3OutlinedButton(onClick, modifier, enabled, shape, colors.lit(lit), elevation, if (lit) null else border, contentPadding, source, content)
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = squareOnTv(ButtonDefaults.textShape),
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    M3TextButton(onClick, modifier, enabled, shape, colors.lit(source.litOnTv()), elevation, border, contentPadding, source, content)
}

/**
 * This Material version's IconButton takes no shape and clips its focus layer
 * to a circle, so TV draws the same 40dp target itself.
 */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!isTvDevice()) {
        M3IconButton(onClick, modifier, enabled, content = content)
        return
    }
    val source = remember { MutableInteractionSource() }
    val lit = source.litOnTv()
    val ink = if (lit) TvFocusInk else LocalContentColor.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .background(if (lit) TvFocusFill else Color.Transparent)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) ink else ink.copy(alpha = 0.38f),
            content = content
        )
    }
}

/**
 * A focused chip can't use plain white for both states, or a toggle would hide
 * whether it's on at the moment someone goes to change it. On reads in the accent.
 */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val source = remember { MutableInteractionSource() }
    val lit = source.litOnTv()
    val fill = if (selected) MaterialTheme.colorScheme.primary else TvFocusFill
    val ink = if (selected) MaterialTheme.colorScheme.onPrimary else TvFocusInk
    M3FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = squareOnTv(FilterChipDefaults.shape),
        colors = if (lit) FilterChipDefaults.filterChipColors(
            containerColor = fill, labelColor = ink, iconColor = ink,
            selectedContainerColor = fill, selectedLabelColor = ink, selectedLeadingIconColor = ink
        ) else FilterChipDefaults.filterChipColors(),
        border = if (lit) null else FilterChipDefaults.filterChipBorder(enabled, selected),
        interactionSource = source
    )
}

@Composable
fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val source = remember { MutableInteractionSource() }
    val lit = source.litOnTv()
    M3AssistChip(
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = squareOnTv(AssistChipDefaults.shape),
        colors = if (lit) AssistChipDefaults.assistChipColors(
            containerColor = TvFocusFill, labelColor = TvFocusInk,
            leadingIconContentColor = TvFocusInk, trailingIconContentColor = TvFocusInk
        ) else AssistChipDefaults.assistChipColors(),
        border = if (lit) null else AssistChipDefaults.assistChipBorder(enabled),
        interactionSource = source
    )
}

/**
 * MaterialTheme, minus Material's focus layer on TV. Lift and fill already say
 * what has focus, and that translucent wash over the top turned the white fills
 * grey and dimmed a focused tile's artwork. Pressing still ripples.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickwickTheme(colors: ColorScheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        if (isTvDevice()) {
            CompositionLocalProvider(
                LocalRippleConfiguration provides RippleConfiguration(
                    rippleAlpha = RippleAlpha(
                        draggedAlpha = 0.16f,
                        focusedAlpha = 0f,
                        hoveredAlpha = 0.08f,
                        pressedAlpha = 0.12f
                    )
                ),
                content = content
            )
        } else content()
    }
}
