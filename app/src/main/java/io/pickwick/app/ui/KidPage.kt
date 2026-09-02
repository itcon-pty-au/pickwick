package io.pickwick.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.PROFILE_AVATARS
import io.pickwick.app.data.PROFILE_COLORS
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.Profile

/**
 * One kid, one page: who they are, their rules, and today's levers. It
 * replaced the edit dialog plus the kid chips over the screen-time steppers,
 * so a parent looking for "Dave's bedtime" finds it under Dave rather than
 * under a section that then asks which kid.
 *
 * Applies as you edit: every change goes to [onChanged] and the settings
 * form's auto-save does the rest. A new kid is only handed over once they
 * have a name — an unnamed profile on the TV's who's-watching screen would
 * be a blank tile.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun KidPage(
    profile: Profile,
    isNew: Boolean,
    siblings: List<Profile>,
    pairingStore: PairingStore,
    onBack: () -> Unit,
    onChanged: (Profile) -> Unit,
    onRemove: () -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var age by remember { mutableStateOf(profile.age) }
    var color by remember { mutableStateOf(profile.colorArgb) }
    var avatar by remember { mutableStateOf(profile.avatar) }
    var pin by remember { mutableStateOf(profile.pin) }
    var settingPin by remember { mutableStateOf(false) }
    var limits by remember { mutableStateOf(profile.limits) }
    var confirmRemove by remember { mutableStateOf(false) }

    val built = profile.copy(
        name = name.trim(), age = age, colorArgb = color, avatar = avatar, pin = pin, limits = limits
    )
    LaunchedEffect(built) {
        if (built != profile && built.name.isNotBlank()) onChanged(built)
    }

    BackHandler(onBack = onBack)

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${built.name}?") },
            text = {
                Text(
                    "${built.name}'s profile disappears from every device. Their " +
                        "watch history stays on each device but is no longer shown."
                )
            },
            confirmButton = {
                Button(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            }
        )
    }

    SubPage(title = if (isNew && name.isBlank()) "New kid" else name, onBack = onBack) {
        if (isNew && name.isBlank()) {
            Text(
                "Give them a name to add them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionTitle("Profile")
        SettingsCard {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Age", modifier = Modifier.weight(1f))
            TextButton(onClick = { age = age?.let { (it - 1).coerceAtLeast(2) } }) { Text("−") }
            Text(
                age?.toString() ?: "—",
                modifier = Modifier.width(40.dp),
                textAlign = TextAlign.Center
            )
            TextButton(onClick = { age = ((age ?: 3) + 1).coerceAtMost(16) }) { Text("+") }
        }
        Text(
            "The AI screener judges videos against each kid's age.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsDivider()
        Text("Color", style = MaterialTheme.typography.labelLarge)
        FlowRow(
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
        Text("Avatar", style = MaterialTheme.typography.labelLarge)
        FlowRow(
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
        SettingsDivider()
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
            Text(
                // Parent context: show the arrows while setting — the secrecy
                // matters at entry time on the couch, not here.
                if (entered.isEmpty()) "· · · ·" else directionPinArrows(entered),
                style = MaterialTheme.typography.titleLarge
            )
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

        RulesSection(limits, onChanged = { limits = it }) {
            if (siblings.isNotEmpty()) {
                SettingsDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Copy rules from:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    siblings.forEach { sibling ->
                        TextButton(
                            modifier = Modifier.tvFocusHighlight(),
                            // Rules only: a sibling's timeout is theirs.
                            onClick = { limits = sibling.limits.copy(pausedUntilMillis = null) }
                        ) { Text(sibling.name) }
                    }
                }
            }
        }
        BlockedTimesSection(limits.windows) { limits = limits.copy(windows = it) }

        // A kid who isn't on any device yet has nothing to grant or pause.
        if (!isNew) {
            SectionTitle("Today")
            SettingsCard {
                GrantTimeSection(pairingStore, listOf(built))
                SettingsDivider()
                PauseTodayRow(
                    pausedUntil = limits.pausedUntilMillis,
                    kidName = built.name,
                    onChanged = { until -> limits = limits.copy(pausedUntilMillis = until) }
                )
            }

            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { confirmRemove = true }
            ) { Text("Remove ${built.name}") }
        }
    }
}
