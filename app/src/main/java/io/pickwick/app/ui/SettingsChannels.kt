package io.pickwick.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.TIME_MULTIPLIERS
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.data.WhitelistParser
import io.pickwick.app.data.YouTubeRepository
import kotlinx.coroutines.launch

// --- Channels ---------------------------------------------------------------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ChannelsSection(
    entries: List<WhitelistEntry>,
    newIds: Set<String>,
    yt: YouTubeRepository,
    /** url → real name, resolved by AdminScreen for entries without a label. */
    resolvedNames: Map<String, String>,
    profiles: List<io.pickwick.app.data.Profile>,
    onChanged: (List<WhitelistEntry>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<YouTubeRepository.ChannelResult>>(emptyList()) }
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<WhitelistEntry?>(null) }
    /** A just-picked channel awaiting the "who is this for?" answer. */
    var pendingAdd by remember { mutableStateOf<WhitelistEntry?>(null) }

    fun displayName(entry: WhitelistEntry) =
        entry.label ?: resolvedNames[entry.url] ?: entry.id

    fun add(entry: WhitelistEntry) {
        // With one kid (or none) there's nothing to ask.
        if (profiles.size < 2) onChanged((entries + entry).distinctBy { it.id })
        else pendingAdd = entry
    }

    pendingAdd?.let { entry ->
        WhoForDialog(
            title = "Who is ${displayName(entry)} for?",
            profiles = profiles,
            initialIds = emptySet(),
            confirmLabel = "Add",
            onDismiss = { pendingAdd = null },
            onConfirm = { forKids ->
                onChanged((entries + entry.copy(profileIds = forKids)).distinctBy { it.id })
                pendingAdd = null
            }
        )
    }

    // Delete confirmation
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${displayName(entry)}?") },
            text = { Text("It will disappear from the kid's app after you save.") },
            confirmButton = {
                Button(onClick = {
                    onChanged(entries - entry)
                    pendingDelete = null
                    // The focused delete button leaves the tree on removal; without
                    // this, focus falls to the next focusable (the search box).
                    focusManager.clearFocus()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Current list
    if (entries.isEmpty()) {
        Text("Nothing allowed yet — search below to add channels.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    entries.forEach { entry ->
        // One tile per source: identity and actions in the first row, per-kid
        // chips in a scrollable second — chips inline with the name wrapped a
        // three-kid family into a broken layout.
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeMultiplierChip(entry.timeMultiplierPercent) { pct ->
                onChanged(entries.map {
                    if (it.id == entry.id) it.copy(timeMultiplierPercent = pct) else it
                })
            }
            Spacer(Modifier.width(8.dp))
            // Tap toggles a marquee so long names (sharing the row with the
            // chip) can still be read in full. Weight and the click target live
            // on a stable Box so only the Text animates — with them in the same
            // chain, the Row redistributes width every frame and the text
            // shimmers instead of gliding. No ripple: a press flash on every
            // row you grab to scroll reads as flicker, and the marquee itself
            // is the feedback. Self-stopping, so forgotten rows don't keep
            // gliding under a vertical drag.
            var marquee by remember(entry.id) { mutableStateOf(false) }
            // Composed only while scrolling — rows in the idle state (all of
            // them, usually) carry no effect node at all.
            if (marquee) LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(6_000)
                marquee = false
            }
            Box(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null
                    ) { marquee = !marquee }
            ) {
                Text(
                    displayName(entry) +
                        if (entry.kind == SourceKind.PLAYLIST) "  (playlist)" else "",
                    maxLines = 1,
                    // Forces one-line intrinsic measurement; otherwise the
                    // paragraph is re-broken against the marquee's unbounded
                    // width on every frame, which jitters the glyphs.
                    softWrap = false,
                    overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (marquee) Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 0,
                        // Twice the default 30.dp/s — the default reads as sluggish
                        // when you tapped specifically to see the rest of a name.
                        velocity = 60.dp
                    ) else Modifier
                )
            }
            if (entry.id in newIds) {
                Text(
                    "NEW",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            IconButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { pendingDelete = entry }
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove ${displayName(entry)}"
                )
            }
        }
        if (profiles.size >= 2) {
            KidToggleChips(
                profiles = profiles,
                selectedIds = entry.profileIds,
                onChanged = { forKids ->
                    onChanged(entries.map {
                        if (it.id == entry.id) it.copy(profileIds = forKids) else it
                    })
                }
            )
        }
        }
        }
    }

    Spacer(Modifier.height(8.dp))
    // Search by name
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search YouTube channels…") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !searching && query.isNotBlank(),
            onClick = {
                scope.launch {
                    searching = true
                    results = runCatching { yt.searchChannels(query.trim()) }.getOrDefault(emptyList())
                    searching = false
                }
            }
        ) { Text(if (searching) "…" else "Search") }
    }

    results.forEach { r ->
        val alreadyAdded = entries.any { it.url == r.url || r.url.endsWith(it.id) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            AsyncImage(
                model = r.avatarUrl,
                contentDescription = r.name,
                modifier = Modifier.size(40.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.subscriberCount > 0) {
                    Text(
                        "${formatCount(r.subscriberCount)} subscribers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                enabled = !alreadyAdded,
                onClick = {
                    val id = r.url.substringAfterLast('/')
                    add(WhitelistEntry(id, r.url, r.name, SourceKind.CHANNEL))
                }
            ) { Text(if (alreadyAdded) "Added ✓" else "Add") }
        }
    }

    Spacer(Modifier.height(8.dp))
    // Paste fallback (also how playlists are added)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = pasteText,
            onValueChange = { pasteText = it },
            placeholder = { Text("Or paste a channel/playlist link") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            onClick = {
                val parsed = WhitelistParser.parse(pasteText.trim()).sources.firstOrNull()
                if (parsed == null) {
                    pasteError = "Couldn't recognise that link"
                } else {
                    pasteError = null
                    pasteText = ""
                    add(parsed)
                }
            }
        ) { Text("Add") }
    }
    pasteError?.let { Text(it, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall) }
}

/**
 * The screen-time "price tag" for one source: tap cycles
 * 1x → 1.25x → 1.5x → 0.75x → 0.5x → 0.25x → FREE → 1x; long-press resets to 1x.
 * The kid's home screen shows the same label on the channel tile.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TimeMultiplierChip(percent: Int, onChange: (Int) -> Unit) {
    val color = timeMultiplierColor(percent)
    // Deliberately no tvFocusHighlight: this screen is phone/touch-only (TVs
    // show the QR screen), and its graphicsLayer + animation states per row —
    // times a 40-channel list in a non-lazy scroll Column — cost enough frame
    // time to judder the whole page during a drag.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = {
                    // Unknown value (hand-edited config) self-heals to the start.
                    val i = TIME_MULTIPLIERS.indexOf(percent)
                    onChange(TIME_MULTIPLIERS[(i + 1) % TIME_MULTIPLIERS.size])
                },
                onLongClick = { onChange(100) }
            )
            .widthIn(min = 54.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            timeMultiplierLabel(percent),
            style = MaterialTheme.typography.labelMedium,
            color = if (color == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
        )
    }
}

internal fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
