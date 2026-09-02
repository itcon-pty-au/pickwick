package io.pickwick.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.ALL_DAYS
import io.pickwick.app.data.DAY_LABELS
import io.pickwick.app.data.LISTEN_MULTIPLIERS
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.Limits
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.SessionGuard
import io.pickwick.app.data.TimeWindow
import io.pickwick.app.data.TimeWindows
import io.pickwick.app.data.WEEKDAYS
import kotlinx.coroutines.launch

// --- Screen time ------------------------------------------------------------

/**
 * The kid's recurring rules — minute limits and the video-length floor — in
 * one card under its own title, so the kid page reads Profile → Rules →
 * Blocked times → Today. [trailing] is the page's own tail for the card
 * (copy-from-sibling), kept inside the border because it acts on these rules.
 */
@Composable
internal fun RulesSection(
    limits: Limits,
    onChanged: (Limits) -> Unit,
    /** Break-skip write-through for callers that need one; null = form state only. */
    onBreakPassCommitted: ((passUntil: Long?) -> Unit)? = null,
    trailing: @Composable ColumnScope.() -> Unit = {}
) {
    SectionTitle("Rules")
    SettingsCard {
        StepperRow(
            label = "Time per session",
            value = limits.sessionMinutes, step = 5, min = 5, max = 240,
            format = { "$it min" }, picker = StepperPicker.Minutes,
            onChanged = { onChanged(limits.copy(sessionMinutes = it)) }
        )
        SettingsDivider()
        StepperRow(
            label = "Sessions on weekdays",
            value = limits.weekdaySessions, step = 1, min = 1, max = 12,
            format = { "$it" },
            onChanged = { onChanged(limits.copy(weekdaySessions = it)) }
        )
        SettingsDivider()
        StepperRow(
            label = "Sessions on weekends",
            value = limits.weekendSessions, step = 1, min = 1, max = 12,
            format = { "$it" },
            onChanged = { onChanged(limits.copy(weekendSessions = it)) }
        )
        SettingsDivider()
        StepperRow(
            label = "Break between sessions",
            value = limits.breakMinutes, step = 15, min = 15, max = 240,
            format = { "$it min" }, picker = StepperPicker.Minutes,
            onChanged = { onChanged(limits.copy(breakMinutes = it)) }
        )
        // Only while there's a break rule to skip — no rule, no row.
        if (limits.breakMinutes != null) {
            SkipBreakRow(limits.breakPassUntilMillis) { until ->
                onChanged(limits.copy(breakPassUntilMillis = until))
                onBreakPassCommitted?.invoke(until)
            }
        }
        val summary = buildString {
            val s = limits.sessionMinutes
            val wd = limits.weekdaySessions
            val we = limits.weekendSessions
            if (s != null && wd != null) append("Weekdays: up to ${s * wd} min total. ")
            if (s != null && we != null) append("Weekends: up to ${s * we} min total.")
            if (isEmpty()) {
                // A bedtime with all steppers Off is a real configuration — don't
                // contradict the blocked-times card below this one.
                append(
                    if (limits.windows.isEmpty()) "No limits set — unlimited watching."
                    else "No minute limits — the blocked times below still apply."
                )
            }
        }
        Text(summary, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        SettingsDivider()
        StepperRow(
            label = "Hide videos shorter than",
            value = limits.minVideoMinutes, step = 1, min = 1, max = 60,
            format = { "$it min" }, picker = StepperPicker.Minutes,
            onChanged = { onChanged(limits.copy(minVideoMinutes = it)) }
        )
        Text(
            "YouTube Shorts are never shown regardless. This also hides the " +
                "short clips a channel uploads as regular videos.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        trailing()
    }
}

// Not named formatClock: Theme.kt's formatClock(Long) formats a *duration*,
// and an Int seconds value passed here would silently print garbage.
internal fun formatMinuteOfDay(minOfDay: Int): String = "%d:%02d".format(minOfDay / 60, minOfDay % 60)

/**
 * The blocked-clock-window list: bedtime, school hours, homework. A list
 * rather than a single bedtime switch because the ordinary cases need more
 * than one window and need different days each. Empty is a real state — no
 * window at all — so there is nothing to switch "off" and no default schedule
 * for a parent who never added one.
 *
 * Rows are collapsed to one line each (alarm-list style) and only the tapped
 * one opens: three windows fully expanded was three name fields, six clocks
 * and three day rows on one screen.
 */
@Composable
internal fun BlockedTimesSection(
    windows: List<TimeWindow>,
    /**
     * Skip-pass write-through for callers that need one; null = form state
     * only (the kid page, where the form's auto-save carries it).
     */
    onPassCommitted: ((windowId: String, passUntil: Long?) -> Unit)? = null,
    onChanged: (List<TimeWindow>) -> Unit
) {
    SectionTitle("Blocked times")
    SettingsCard {
        var expandedId by remember { mutableStateOf<String?>(null) }
        var adding by remember { mutableStateOf(false) }

        if (windows.isEmpty()) {
            Text(
                "No blocked times — screen time is limited by minutes only, not by clock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        windows.forEachIndexed { i, w ->
            if (i > 0) SettingsDivider()
            if (w.id == expandedId) {
                TimeWindowEditor(
                    window = w,
                    onPassCommitted = onPassCommitted,
                    onChanged = { updated -> onChanged(windows.toMutableList().also { it[i] = updated }) },
                    onRemove = {
                        expandedId = null
                        onChanged(windows.filterIndexed { j, _ -> j != i })
                    },
                    onCollapse = { expandedId = null }
                )
            } else {
                TimeWindowRow(w) { expandedId = w.id }
            }
        }
        if (windows.isNotEmpty()) SettingsDivider()

        // One Add with three starting points behind it rather than three
        // buttons: a window has to start with *some* times, and naming the
        // presets is more honest than inventing a schedule behind a blank
        // "Add" — but they don't need to sit on the screen until asked for.
        Box {
            CompactButton(onClick = { adding = true }) { Text("+ Add blocked time") }
            androidx.compose.material3.DropdownMenu(
                expanded = adding,
                onDismissRequest = { adding = false }
            ) {
                fun add(label: String, startMin: Int, endMin: Int, days: Set<Int>) {
                    adding = false
                    val id = newWindowId(windows)
                    onChanged(windows + TimeWindow(id = id, label = label, startMin = startMin, endMin = endMin, days = days))
                    expandedId = id
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Bedtime") },
                    onClick = { add("Bedtime", 19 * 60 + 30, 7 * 60, ALL_DAYS) }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("School hours") },
                    onClick = { add("School hours", 8 * 60 + 30, 15 * 60, WEEKDAYS) }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Custom") },
                    // A deliberate placeholder — blank name, neutral midday
                    // span — that reads as "yours to fill in", not a schedule
                    // we invented.
                    onClick = { add("", 12 * 60, 14 * 60, ALL_DAYS) }
                )
            }
        }
    }
}

/** Unique within the config; the id keys a window's pass, so it must not be reused. */
private fun newWindowId(existing: List<TimeWindow>): String {
    val taken = existing.map { it.id }.toSet()
    return generateSequence(1) { it + 1 }.map { "w$it" }.first { it !in taken }
}

/** Collapsed: name, hours, days — enough to answer "what's set" without opening it. */
@Composable
private fun TimeWindowRow(window: TimeWindow, onOpen: () -> Unit) {
    val skipped = (window.passUntilMillis ?: 0L) > System.currentTimeMillis()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight()
            .clickable(onClick = onOpen)
            .heightIn(min = 44.dp)
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(window.label.ifBlank { "Unnamed" })
            Text(
                listOfNotNull(
                    "${formatMinuteOfDay(window.startMin)}–${formatMinuteOfDay(window.endMin)}",
                    daySummary(window.days),
                    if (window.allowListening) "listening allowed" else null,
                    if (skipped) "skipped once" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("⌄", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun daySummary(days: Set<Int>): String = when (days) {
    ALL_DAYS -> "every day"
    WEEKDAYS -> "Mon–Fri"
    io.pickwick.app.data.WEEKEND_DAYS -> "weekends"
    else -> days.sorted().joinToString(", ") { DAY_LABELS[it - 1] }
}

@Composable
private fun TimeWindowEditor(
    window: TimeWindow,
    onPassCommitted: ((String, Long?) -> Unit)?,
    onChanged: (TimeWindow) -> Unit,
    onRemove: () -> Unit,
    onCollapse: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = window.label,
                onValueChange = { onChanged(window.copy(label = it)) },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            CompactButton(onClick = onCollapse) { Text("Done") }
        }
        // Max is 23:59, not 23:30: the clock picker can land on any minute, so
        // the half-hour grid is only what − and + snap to, not the set of
        // reachable times.
        StepperRow(
            label = "Starts", value = window.startMin, step = 30, min = 0, max = 24 * 60 - 1,
            allowOff = false, format = ::formatMinuteOfDay, picker = StepperPicker.Clock,
            onChanged = { onChanged(window.copy(startMin = it ?: window.startMin)) }
        )
        StepperRow(
            label = "Ends", value = window.endMin, step = 30, min = 0, max = 24 * 60 - 1,
            allowOff = false, format = ::formatMinuteOfDay, picker = StepperPicker.Clock,
            onChanged = { onChanged(window.copy(endMin = it ?: window.endMin)) }
        )
        DayChips(window.days) { onChanged(window.copy(days = it)) }
        AllowListeningRow(window, onChanged)
        SkipOnceRow(window, onPassCommitted, onChanged)
        TextButton(onClick = onRemove) { Text("Remove this blocked time") }
    }
}

/**
 * The film that runs past the sitting cap: waives the next break only — the
 * first break it covers spends it, and an unused skip expires at midnight.
 * Committed and pushed the moment it's tapped, same as a window's Skip.
 */
@Composable
private fun SkipBreakRow(passUntil: Long?, onChanged: (Long?) -> Unit) {
    val skipped = (passUntil ?: 0L) > System.currentTimeMillis()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (skipped) "  Next break skipped" else "  Skip the next break",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        CompactButton(
            onClick = { onChanged(if (skipped) null else endOfToday()) }
        ) { Text(if (skipped) "Undo" else "Skip") }
    }
}

/**
 * Bedtime's exception: the window still stops watching, but a story the kid is
 * listening to plays on. Shown on every window because the parent's own window
 * names are the only thing that says which one is bedtime; off by default, so
 * a window that already exists keeps blocking exactly as it did.
 */
@Composable
private fun AllowListeningRow(window: TimeWindow, onChanged: (TimeWindow) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChanged(window.copy(allowListening = !window.allowListening)) }
    ) {
        Checkbox(
            modifier = Modifier.tvFocusHighlight(),
            checked = window.allowListening,
            onCheckedChange = { onChanged(window.copy(allowListening = it)) }
        )
        Column(Modifier.weight(1f)) {
            Text("Allow listening", style = MaterialTheme.typography.bodyMedium)
            Text(
                // Says what the kid gets, not what the setting does: the screen
                // is the thing a parent is trying to keep off at bedtime.
                "Audio keeps playing — the screen stays off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The sick day, the school holiday, the film that runs long. Scoped to this
 * window and to one occurrence — it's set to the moment that occurrence would
 * have ended, so it lapses on its own and skipping bedtime tonight never
 * unlocks tomorrow morning's school hours.
 */
@Composable
private fun SkipOnceRow(
    window: TimeWindow,
    onPassCommitted: ((String, Long?) -> Unit)?,
    onChanged: (TimeWindow) -> Unit
) {
    val now = System.currentTimeMillis()
    val skipped = (window.passUntilMillis ?: 0L) > now
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (skipped) "Skipped this once" else "Skip the next one",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        CompactButton(
            onClick = {
                val passUntil = if (skipped) null else {
                    val cal = java.util.Calendar.getInstance()
                    TimeWindows.minutesUntilEndOfNext(
                        window,
                        cal.get(java.util.Calendar.DAY_OF_WEEK),
                        cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                            cal.get(java.util.Calendar.MINUTE)
                    )?.let { now + it * 60_000L }
                }
                onChanged(window.copy(passUntilMillis = passUntil))
                // "Skipped this once" must be true the moment it's shown —
                // committed and pushed now, not parked until Save & close.
                onPassCommitted?.invoke(window.id, passUntil)
            }
        ) { Text(if (skipped) "Undo" else "Skip") }
    }
}

@Composable
private fun DayChips(days: Set<Int>, onChanged: (Set<Int>) -> Unit) {
    // Scrolls: seven chips overflow a phone in portrait, and clipping at the
    // screen edge mangled the row.
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        DAY_LABELS.forEachIndexed { index, name ->
            val day = index + 1
            val on = day in days
            FilterChip(
                modifier = Modifier.tvFocusHighlight(),
                selected = on,
                // Clearing the last day would leave a window that blocks
                // nothing but still looks configured; Remove is how you mean
                // that, so the tap is simply ignored.
                onClick = { if (!on || days.size > 1) onChanged(if (on) days - day else days + day) },
                label = { Text(name, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

// --- Listening ----------------------------------------------------------------

/**
 * Family-wide screen-off listening rate — one knob on purpose. Off means the
 * feature doesn't exist: locking the phone pauses playback, exactly the
 * pre-listen behavior, with no hidden default rate. Phones only; a TV can't
 * play with its panel off, so TVs simply ignore the setting.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ListenRateRow(percent: Int?, onChange: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Keep playing when the phone locks", modifier = Modifier.weight(1f))
        val color = percent?.let { timeMultiplierColor(it) }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(
                    onClick = {
                        // Unknown value (hand-edited config) self-heals: indexOf
                        // gives -1, so the next tap lands on Off.
                        val i = LISTEN_MULTIPLIERS.indexOf(percent)
                        onChange(LISTEN_MULTIPLIERS[(i + 1) % LISTEN_MULTIPLIERS.size])
                    },
                    onLongClick = { onChange(null) }
                )
                .widthIn(min = 54.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                percent?.let(::timeMultiplierLabel) ?: "Off",
                style = MaterialTheme.typography.labelMedium,
                color = if (color == null) MaterialTheme.colorScheme.onSurfaceVariant
                else Color.White
            )
        }
    }
    Text(
        when {
            percent == null -> "Off: locking the phone stops playback."
            percent == 0 ->
                "Audio keeps playing with the screen off, and listening is free — " +
                    "it doesn't use up screen time."
            else ->
                "Audio keeps playing with the screen off. Those minutes count at " +
                    "${timeMultiplierLabel(percent)} of each channel's usual rate."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * What tapping a stepper's value opens, and so which rows can be set to an
 * exact figure at all. [None] leaves the value as plain text — the counting
 * rows (sessions per day, child age) step by 1 already, so there is nothing
 * finer for a picker to offer and a tappable number would only add a dialog
 * between a parent and a two-tap edit.
 */
internal enum class StepperPicker { None, Minutes, Clock }

/**
 * Touch stepper. Stepping below [min] turns the rule Off (null).
 *
 * − and + move by whole [step]s *on the grid* — from an off-grid 19:45 the next
 * step down is 19:30, not 19:15 — because the exact minutes come from tapping
 * the value ([picker]), and the buttons stay the coarse "roughly there" control
 * they have always been. Phone-only in practice: TV settings is the pairing QR
 * and nothing else.
 */
@Composable
internal fun StepperRow(
    label: String,
    value: Int?,
    step: Int,
    min: Int,
    max: Int,
    allowOff: Boolean = true,
    format: (Int) -> String,
    picker: StepperPicker = StepperPicker.None,
    onChanged: (Int?) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 44.dp).padding(vertical = 2.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f))
        CompactButton(
            onClick = {
                val next = stepDown(value ?: return@CompactButton, step)
                when {
                    next >= min -> onChanged(next)
                    allowOff -> onChanged(null)
                    else -> onChanged(min)
                }
            }
        ) { Text("−") }
        StepperValue(
            title = label.trim(),
            value = value,
            text = value?.let(format) ?: "Off",
            picker = picker, min = min, max = max, allowOff = allowOff,
            onChanged = onChanged
        )
        CompactButton(
            onClick = {
                onChanged(if (value == null) min else stepUp(value, step).coerceIn(min, max))
            }
        ) { Text("+") }
    }
}

/** Next grid point strictly below [value]; may fall under the row's minimum. */
private fun stepDown(value: Int, step: Int): Int =
    if (value % step == 0) value - step else value / step * step

/** Next grid point strictly above [value]. */
private fun stepUp(value: Int, step: Int): Int = (value / step + 1) * step

/**
 * The number between − and +. With a [picker] it becomes the way to land on an
 * exact figure — 19:45 bedtime, a 20-minute session — which the coarse buttons
 * can't express; it gets a filled chip so it reads as tappable rather than as
 * the label it used to be.
 */
@Composable
private fun StepperValue(
    title: String,
    value: Int?,
    text: String,
    picker: StepperPicker,
    min: Int,
    max: Int,
    allowOff: Boolean,
    onChanged: (Int?) -> Unit
) {
    if (picker == StepperPicker.None) {
        Text(text, modifier = Modifier.widthIn(min = 64.dp), textAlign = TextAlign.Center)
        return
    }
    var editing by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { editing = true }
            .widthIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, textAlign = TextAlign.Center)
    }
    if (editing) {
        // An Off row opens at its minimum: the parent tapped it to set a value,
        // and a picker seeded at nothing has nothing to confirm.
        val seed = value ?: min
        val close = { editing = false }
        val pick: (Int?) -> Unit = { editing = false; onChanged(it) }
        when (picker) {
            StepperPicker.Clock -> ExactClockDialog(title, seed, close, pick)
            StepperPicker.Minutes -> ExactMinutesDialog(title, seed, min, max, allowOff, close, pick)
            StepperPicker.None -> {}
        }
    }
}

/**
 * Always 24-hour, matching how [formatMinuteOfDay] prints the row behind it —
 * a dialog that says 7:45 PM over a row that says 19:45 reads like two
 * different times. Keypad rather than the dial: four digits beats spinning a
 * ring to one particular minute, which is the whole point of the picker.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExactClockDialog(
    title: String,
    initialMin: Int,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialMin / 60,
        initialMinute = initialMin % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Exact minutes for the duration rows, clamped to the row's own range. */
@Composable
private fun ExactMinutesDialog(
    title: String,
    initial: Int,
    min: Int,
    max: Int,
    allowOff: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit
) {
    // Opens selected, so the first digit typed replaces the old value instead
    // of appending to it — 45 min becomes 20, not 4520.
    var field by remember {
        mutableStateOf(
            TextFieldValue(initial.toString(), selection = TextRange(0, initial.toString().length))
        )
    }
    val focus = remember { FocusRequester() }
    val entered = field.text.toIntOrNull()
    val valid = entered != null && entered in min..max

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                // Digits only: the number keyboard is a hint to the IME, not a
                // guarantee — a paste or a hardware keyboard gets past it. The
                // cap keeps a long paste from ever reaching toIntOrNull.
                onValueChange = { new ->
                    field = new.copy(text = new.text.filter(Char::isDigit).take(4))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text("min") },
                supportingText = { Text("$min–$max minutes") },
                isError = field.text.isNotEmpty() && !valid,
                modifier = Modifier.focusRequester(focus)
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onPick(entered) }) { Text("Set") }
        },
        dismissButton = {
            Row {
                if (allowOff) TextButton(onClick = { onPick(null) }) { Text("Turn off") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// --- Grants -----------------------------------------------------------------

@Composable
internal fun GrantTimeSection(
    pairingStore: PairingStore,
    profiles: List<io.pickwick.app.data.Profile> = emptyList()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableIntStateOf(15) }
    var granted by remember { mutableStateOf<GrantReceipt?>(null) }
    var targetKidId by remember(profiles.map { it.id }) {
        mutableStateOf(profiles.firstOrNull()?.id)
    }

    // Granting to a child, not to a device: the minutes land on that kid's
    // guard here and on every paired device.
    if (profiles.size >= 2) {
        KidSelectorChips(profiles, targetKidId ?: profiles.first().id) { targetKidId = it }
        Spacer(Modifier.height(4.dp))
    }

    // Same stepper styling as the screen-time rows; Grant applies the amount.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text("Bonus watch time", modifier = Modifier.weight(1f))
        CompactButton(
            onClick = { minutes = stepDown(minutes, 5).coerceAtLeast(5) }
        ) { Text("−") }
        StepperValue(
            title = "Bonus watch time",
            value = minutes, text = "$minutes min",
            picker = StepperPicker.Minutes, min = 5, max = 180,
            // No Off: a grant of nothing isn't a grant, and Grant is what
            // applies it — there is no rule here to switch off.
            allowOff = false,
            onChanged = { minutes = it ?: minutes }
        )
        CompactButton(
            onClick = { minutes = stepUp(minutes, 5).coerceAtMost(180) }
        ) { Text("+") }
        Spacer(Modifier.width(8.dp))
        Button(
            modifier = Modifier.tvFocusHighlight(),
            onClick = {
                val amount = minutes
                val kidId = if (profiles.isEmpty()) null else targetKidId
                val kidName = profiles.firstOrNull { it.id == kidId }?.name
                val suffix = io.pickwick.app.data.ProfileNamespace(context.applicationContext)
                    .suffixFor(kidId)
                SessionGuard(context.applicationContext, suffix).grantExtraMinutes(amount)
                val devices = pairingStore.paired()
                val who = kidName?.let { " for $it" } ?: ""
                if (devices.isEmpty()) {
                    granted = GrantReceipt("Granted $amount extra minutes$who 🎉")
                } else {
                    scope.launch {
                        var ok = 0
                        devices.forEach { if (LanClient.grant(it, amount, kidId)) ok++ }
                        granted = GrantReceipt("Granted $amount min$who here + $ok device(s) 🎉")
                    }
                }
            }
        ) { Text("Grant") }
    }
    granted?.let { receipt ->
        Text(receipt.text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // A receipt, not a status line: it was true when it appeared and says
        // nothing about now, so it goes rather than sitting under the row until
        // the app is next restarted. Keyed on the receipt (`at` gives two
        // identical grants separate identities) so a second Grant gets its own
        // full five seconds.
        LaunchedEffect(receipt) {
            kotlinx.coroutines.delay(5_000)
            if (granted == receipt) granted = null
        }
    }
}

/** One Grant tap's confirmation; `at` keeps repeats distinguishable. */
private data class GrantReceipt(val text: String, val at: Long = System.currentTimeMillis())

/**
 * Parent timeout: one tap turns watching off until midnight, on every device,
 * effective immediately (the guard's next tick stops a playing video).
 * Deliberately minimal — no durations, no multi-day bans; Resume is the undo.
 * With a [kidName] it is that kid's own pause; without, the family-wide one
 * that stops everyone.
 */
@Composable
internal fun PauseTodayRow(pausedUntil: Long?, kidName: String? = null, onChanged: (Long?) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val active = pausedUntil != null && pausedUntil > System.currentTimeMillis()
    val whose = kidName?.let { "$it's" } ?: "all"

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = {
                Text(
                    if (kidName == null) "Pause screen time for everyone for the rest of today?"
                    else "Pause $kidName's screen time for the rest of today?"
                )
            },
            text = {
                Text(
                    "${if (kidName == null) "All" else "$kidName's"} watching stops right " +
                        "away on every device and stays off until midnight. Normal " +
                        "limits return tomorrow. You can resume any time."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirming = false
                    onChanged(endOfToday())
                }) { Text("Pause") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        if (active) {
            Text(
                if (kidName == null) "⏸ Everyone's screen time is paused until tomorrow"
                else "⏸ $kidName's screen time is paused until tomorrow",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { onChanged(null) }
            ) { Text("Resume") }
        } else {
            Text("Turn off $whose watching until midnight", modifier = Modifier.weight(1f))
            CompactButton(
                onClick = { confirming = true }
            ) { Text("Pause for today") }
        }
    }
}

private fun endOfToday(): Long = java.util.Calendar.getInstance().apply {
    add(java.util.Calendar.DAY_OF_YEAR, 1)
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis
