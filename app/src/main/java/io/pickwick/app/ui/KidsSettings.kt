package io.pickwick.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.Limits
import io.pickwick.app.data.PROFILE_AVATARS
import io.pickwick.app.data.PROFILE_COLORS
import io.pickwick.app.data.Profile

// ---------------------------------------------------------------------------
// The "Kids" section of the parent settings, plus the small per-kid widgets
// (toggle chips, who-for dialog) reused by channels, folders and the AI queue.
// ---------------------------------------------------------------------------

/**
 * Manage the family's kids. The first kid a family creates inherits today's
 * setup (limits, AI age) — the upgrade story is "your current setup becomes
 * kid #1", not "start over".
 */
@Composable
fun KidsSection(
    profiles: List<Profile>,
    /** Seeds for the very first kid: the pre-profile family-wide settings. */
    legacyLimits: Limits,
    legacyAi: AiConfig,
    onChanged: (List<Profile>) -> Unit
) {
    var editing by remember { mutableStateOf<Profile?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }

    Text(
        if (profiles.isEmpty()) {
            "Add your kids to give each their own channels, watch history and screen time."
        } else {
            "Each kid has their own channels, watch history, saved list and screen-time rules."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${profile.name}?") },
            text = {
                Text(
                    "${profile.name}'s profile disappears from every device. Their " +
                        "watch history stays on each device but is no longer shown."
                )
            },
            confirmButton = {
                Button(onClick = {
                    onChanged(profiles - profile)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    editing?.let { profile ->
        KidEditorDialog(
            profile = profile,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = { saved ->
                onChanged(
                    if (isNew) profiles + saved
                    else profiles.map { if (it.id == saved.id) saved else it }
                )
                editing = null
            }
        )
    }

    profiles.forEach { profile ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            ProfileAvatar(profile, size = 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        profile.age?.let { "age $it" },
                        if (profile.pin != null) "🔒 code set" else null
                    ).joinToString(" · ").ifEmpty { "no age set" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { isNew = false; editing = profile }
            ) { Text("Edit") }
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { pendingDelete = profile }
            ) { Text("Remove") }
        }
    }

    Button(
        modifier = Modifier.tvFocusHighlight(),
        onClick = {
            isNew = true
            editing = if (profiles.isEmpty()) {
                // Kid #1 adopts everything the family already configured.
                Profile(
                    id = Profile.newId(),
                    name = "",
                    colorArgb = PROFILE_COLORS.first(),
                    avatar = PROFILE_AVATARS.first(),
                    age = legacyAi.childAge,
                    limits = legacyLimits.copy(pausedUntilMillis = null)
                )
            } else {
                Profile(
                    id = Profile.newId(),
                    name = "",
                    colorArgb = PROFILE_COLORS[profiles.size % PROFILE_COLORS.size],
                    avatar = PROFILE_AVATARS[profiles.size % PROFILE_AVATARS.size]
                )
            }
        }
    ) { Text(if (profiles.isEmpty()) "Add your first kid" else "Add a kid") }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KidEditorDialog(
    profile: Profile,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var age by remember { mutableStateOf(profile.age) }
    var color by remember { mutableStateOf(profile.colorArgb) }
    var avatar by remember { mutableStateOf(profile.avatar) }
    var pin by remember { mutableStateOf(profile.pin) }
    var settingPin by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New kid" else "Edit ${profile.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Age", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        age = age?.let { (it - 1).coerceAtLeast(2) }
                    }) { Text("−") }
                    Text(
                        age?.toString() ?: "—",
                        modifier = Modifier.width(40.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    TextButton(onClick = {
                        age = ((age ?: 3) + 1).coerceAtMost(16)
                    }) { Text("+") }
                }
                Text(
                    "The AI screener judges videos against each kid's age.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text("Color", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PROFILE_COLORS.forEach { c ->
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .clickable { color = c },
                            contentAlignment = Alignment.Center
                        ) {
                            if (c == color) Text("✓", color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Avatar", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PROFILE_AVATARS.forEach { a ->
                        ProfileAvatar(
                            Profile(id = "preview", name = a, colorArgb = color, avatar = a),
                            size = 44,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { avatar = a }
                                .let { m ->
                                    if (a == avatar) m.background(
                                        MaterialTheme.colorScheme.primary, CircleShape
                                    ).padding(2.dp) else m
                                }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Profile lock", style = MaterialTheme.typography.labelLarge)
                if (settingPin) {
                    var entered by remember { mutableStateOf("") }
                    Text(
                        "Tap four buttons (arrows or OK) — on the TV, " +
                            "${name.ifBlank { "your kid" }} presses them on the remote, " +
                            "and the screen shows only dots.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // Parent context: show the arrows while setting — the
                        // secrecy matters at entry time on the couch, not here.
                        if (entered.isEmpty()) "· · · ·" else directionPinArrows(entered),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(6.dp))
                    DirectionArrowPad(onPress = { dir ->
                        if (entered.length < 4) entered += dir
                        if (entered.length == 4) {
                            pin = entered
                            settingPin = false
                        }
                    })
                    TextButton(onClick = { settingPin = false }) { Text("Cancel") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pin?.let { "Code: ${directionPinArrows(it)}" }
                                ?: "No code — anyone can pick this profile",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { settingPin = true }) {
                            Text(if (pin == null) "Set code" else "Change")
                        }
                        if (pin != null) {
                            TextButton(onClick = { pin = null }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        profile.copy(
                            name = name.trim(),
                            age = age,
                            colorArgb = color,
                            avatar = avatar,
                            pin = pin
                        )
                    )
                }
            ) { Text(if (isNew) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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
