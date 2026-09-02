package io.pickwick.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.Limits
import io.pickwick.app.data.PROFILE_AVATARS
import io.pickwick.app.data.PROFILE_COLORS
import io.pickwick.app.data.Profile

// ---------------------------------------------------------------------------
// The "Kids" list of the parent settings, plus the small per-kid widgets
// (toggle chips, who-for dialog) reused by channels, folders and the AI queue.
// ---------------------------------------------------------------------------

/**
 * The family's kids. Each row opens that kid's page ([KidPage]); adding one
 * opens the page on a fresh profile. There is always at least one kid — the
 * admin form makes a "Kid" out of a kid-less config on load — so nothing here
 * has to explain a profile-free mode any more.
 */
@Composable
fun KidsSection(
    profiles: List<Profile>,
    onOpen: (profile: Profile, isNew: Boolean) -> Unit
) {
    Text(
        "Tap a kid for their profile, screen-time rules and today's extras.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    profiles.forEach { profile ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusHighlight()
                .clickable { onOpen(profile, false) }
                .padding(vertical = 8.dp)
        ) {
            ProfileAvatar(profile, size = 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        profile.age?.let { "age $it" },
                        if (profile.pin != null) "\uD83D\uDD12 code set" else null,
                        rulesSummary(profile.limits)
                    ).joinToString(" \u00B7 ").ifEmpty { "no age set" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "\u203A", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Button(
        modifier = Modifier.tvFocusHighlight(),
        onClick = {
            onOpen(
                Profile(
                    id = Profile.newId(),
                    name = "",
                    colorArgb = PROFILE_COLORS[profiles.size % PROFILE_COLORS.size],
                    avatar = PROFILE_AVATARS[profiles.size % PROFILE_AVATARS.size]
                ),
                true
            )
        }
    ) { Text("Add a kid") }
}

/** One-line hint of what's set, so the list answers "who has rules?" at a glance. */
private fun rulesSummary(l: Limits): String? {
    val parts = buildList {
        l.sessionMinutes?.let { add("$it min sessions") }
        if (l.windows.isNotEmpty()) {
            add("${l.windows.size} blocked time${if (l.windows.size == 1) "" else "s"}")
        }
        l.minVideoMinutes?.let { add("no videos under $it min") }
        if ((l.pausedUntilMillis ?: 0L) > System.currentTimeMillis()) add("paused today")
    }
    return parts.joinToString(", ").ifEmpty { null }
}

// ---------------------------------------------------------------------------
// Reusable per-kid widgets
// ---------------------------------------------------------------------------

/**
 * One row of name chips showing which kids something applies to — scrolls
 * sideways so a big family never wraps into a broken layout. Tap toggles a
 * kid; the empty set means "everyone" (matching the config convention), so
 * the last remaining kid can't be toggled off — a channel visible to no one
 * just looks broken. Avatars stay in the Kids section; everywhere else in the
 * parent settings, names alone read faster.
 */
@Composable
fun KidToggleChips(
    profiles: List<Profile>,
    selectedIds: Set<String>,
    onChanged: (Set<String>) -> Unit
) {
    val effective = selectedIds.ifEmpty { profiles.map { it.id }.toSet() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        profiles.forEach { profile ->
            val on = profile.id in effective
            FilterChip(
                modifier = Modifier.tvFocusHighlight(),
                selected = on,
                onClick = {
                    val next = if (on) effective - profile.id else effective + profile.id
                    if (next.isEmpty()) return@FilterChip
                    // Collapse back to "everyone" so future kids are included.
                    onChanged(if (next.size == profiles.size) emptySet() else next)
                },
                label = { Text(profile.name) }
            )
        }
    }
}

/** Selector row (exactly one kid active) — screen-time editor, grants. */
@Composable
fun KidSelectorChips(
    profiles: List<Profile>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        profiles.forEach { profile ->
            FilterChip(
                modifier = Modifier.tvFocusHighlight(),
                selected = profile.id == selectedId,
                onClick = { onSelect(profile.id) },
                label = { Text(profile.name) }
            )
        }
    }
}

/**
 * "Who is this for?" — checkbox per kid, empty result meaning everyone.
 * Reused by add-channel, folder linking and the AI queue's long-press.
 */
@Composable
fun WhoForDialog(
    title: String,
    profiles: List<Profile>,
    initialIds: Set<String>,
    confirmLabel: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var checked by remember {
        mutableStateOf(initialIds.ifEmpty { profiles.map { it.id }.toSet() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Reachable from a hold in the screening log, so the tail of that
            // hold must not tick a kid on the way in.
            Column(modifier = Modifier.ignoreSelectUntilRelease()) {
                profiles.forEach { profile ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                checked = if (profile.id in checked) checked - profile.id
                                else checked + profile.id
                            }
                    ) {
                        Checkbox(
                            checked = profile.id in checked,
                            onCheckedChange = { on ->
                                checked = if (on) checked + profile.id else checked - profile.id
                            }
                        )
                        Text(profile.name)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = checked.isNotEmpty(),
                onClick = {
                    onConfirm(if (checked.size == profiles.size) emptySet() else checked)
                },
                // Sits outside the column above, and a ruling confirmed by a
                // stray release is the worst thing this dialog could do.
                modifier = Modifier.ignoreSelectUntilRelease()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
