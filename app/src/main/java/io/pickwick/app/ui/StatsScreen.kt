package io.pickwick.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.Stats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Per-device stats, pulled over the LAN. Refreshes while open so "now playing" is live. */
@Composable
fun StatsScreen(
    device: PairedDevice,
    configStore: ConfigStore,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val statsCache = remember { io.pickwick.app.data.StatsCache(context.applicationContext) }
    var payload by remember { mutableStateOf<Stats.Payload?>(null) }
    var offline by remember { mutableStateOf(false) }
    /** When the payload on screen was actually fetched from the device. */
    var fetchedAt by remember { mutableStateOf(0L) }
    /**
     * Flagged videos already ruled on — from this phone's config, so the held-back
     * count doesn't include items resolved after a stale snapshot was taken.
     * Ruling itself happens in one place: Settings → "Waiting for your OK".
     */
    val resolved = remember {
        configStore.load().let {
            it.aiAllowedVideoIds + it.blockedVideoIds + it.allowedFor.keys + it.blockedFor.keys
        }
    }

    /** Bumped to restart the poll loop for an immediate refetch (e.g. after remote pause). */
    var refreshTick by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    LaunchedEffect(device, refreshTick) {
        // Paint instantly from the last snapshot — the parent can review the day
        // and rule on flagged videos even while the TV is off.
        if (payload == null) statsCache.load(device.token)?.let { (at, json) ->
            Stats.parse(json)?.let { payload = it; fetchedAt = at; offline = true }
        }
        while (true) {
            val json = LanClient.stats(device)
            if (json == null) offline = true
            else {
                Stats.parse(json)?.let {
                    payload = it
                    offline = false
                    fetchedAt = System.currentTimeMillis()
                    statsCache.save(device.token, json)
                }
            }
            delay(5_000) // keeps now-playing current
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    // Whose day these numbers describe — a shared TV reports
                    // whichever kid it is currently showing.
                    payload?.profileName?.let {
                        Text(
                            "showing $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(modifier = Modifier.tvFocusHighlight(), onClick = onBack) { Text("Close") }
            }

            val data = payload
            if (data == null) {
                Text(
                    if (offline) "Can't reach ${device.name} — is it on and on home Wi-Fi?"
                    else "Loading…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            if (offline) {
                val age = relativeTime(fetchedAt).ifEmpty { "earlier" }
                Text(
                    "Device unreachable — showing its last report from $age.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // --- Now playing (live only — a stale "watching now" would mislead) ---
            if (!offline) data.nowPlaying?.let { np ->
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (np.playing) "▶ Watching now" else "⏸ Paused",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(np.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        if (np.channel.isNotBlank()) {
                            Text(np.channel, style = MaterialTheme.typography.bodySmall)
                        }
                        if (np.durationMs > 0) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${fmt(np.positionMs)} / ${fmt(np.durationMs)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // Remote control: pause for "come to dinner", resume to be nice.
                        Spacer(Modifier.height(8.dp))
                        var sending by remember { mutableStateOf(false) }
                        FilledTonalButton(
                            enabled = !sending,
                            modifier = Modifier.tvFocusHighlight(),
                            onClick = {
                                sending = true
                                scope.launch {
                                    LanClient.playerCommand(
                                        device, if (np.playing) "pause" else "play"
                                    )
                                    refreshTick++ // refetch now so the card flips state
                                    sending = false
                                }
                            }
                        ) { Text(if (np.playing) "⏸ Pause on TV" else "▶ Resume on TV") }
                    }
                }
            }

            // --- Today -------------------------------------------------------
            StatsSection("Today")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${data.watchedTodayMin} min " +
                        (if (data.multipliersActive) "counted" else "watched") +
                        (data.budgetTodayMin?.let { " of $it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(onClick = {}, label = { Text(data.state) })
            }
            data.budgetTodayMin?.let { budget ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (data.watchedTodayMin.toFloat() / budget).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // Without this, "45 of 90" looks wrong to a parent who watched the
            // clock: a FREE channel adds nothing, a 1.5x one adds more.
            if (data.multipliersActive) {
                Text(
                    "Counted against the budget — channels with a time chip " +
                        "(FREE, 0.5x, 1.5x…) draw it at their own rate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            data.breakUntil?.let {
                Text("On a break until $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (data.bonusTodayMin > 0) {
                Text("Includes ${data.bonusTodayMin} bonus min granted today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            data.sittingCapMin?.let { cap ->
                Text(
                    "This sitting: ${data.sittingWatchedMin} of $cap min" +
                        if (data.multipliersActive) " counted" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // --- AI screening ------------------------------------------------
            // Numbers only — the Allow/Block cards live in one place, Settings →
            // "Waiting for your OK", which folds in this device's queue.
            data.aiScreened?.let { screened ->
                StatsSection("AI screening")
                val held = data.aiFlagged.count { it.videoId !in resolved }
                Text(
                    "$screened video(s) screened" + when (held) {
                        0 -> " · nothing waiting for you"
                        else -> " · $held held back"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (held > 0) {
                    Text(
                        "Review them under \"Waiting for your OK\" in settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Unscreened videos are hidden on the device (fail-closed) but must
                // never be *silently* hidden — the parent sees the backlog here.
                if (data.aiPending > 0) {
                    Text(
                        "${data.aiPending} video(s) not screened yet — they stay hidden " +
                            "on the device until the AI rules on them. Screening runs and " +
                            "retries whenever the kid's app is open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // --- Last 7 days -------------------------------------------------
            if (data.history.isNotEmpty()) {
                StatsSection("Last days")
                val recentDays = data.history.takeLast(7)
                val max = (recentDays.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                recentDays.forEach { (day, mins) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(prettyDay(day), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(64.dp))
                        Box(
                            Modifier
                                .fillMaxWidth(0.75f * (mins.toFloat() / max))
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("$mins min", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // --- Where the time goes -----------------------------------------
            if (data.topChannels.isNotEmpty()) {
                StatsSection("Most watched channels")
                // These are real minutes — the counterpart to the counted total
                // above, and the honest answer to "where did the time go?".
                if (data.multipliersActive) {
                    Text(
                        "Real time watched, before channel rates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val max = (data.topChannels.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                data.topChannels.forEach { (name, mins) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Box(
                            Modifier
                                .fillMaxWidth(0.4f * (mins.toFloat() / max))
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("$mins min", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // --- Recently watched --------------------------------------------
            StatsSection("Recently watched")
            if (data.recent.isEmpty()) {
                Text("Nothing watched yet.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            data.recent.forEach { v ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    AsyncImage(
                        model = v.thumbnailUrl,
                        contentDescription = v.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 80.dp, height = 45.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                v.channel.takeIf { it.isNotBlank() },
                                "${v.percent}% watched",
                                relativeTime(v.watchedAt)
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                "Saved for later: ${data.watchlistCount} video(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatsSection(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(6.dp))
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

private fun prettyDay(yyyymmdd: String): String = runCatching {
    val parsed = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).parse(yyyymmdd)!!
    java.text.SimpleDateFormat("EEE d", java.util.Locale.US).format(parsed)
}.getOrDefault(yyyymmdd)

private fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val diff = System.currentTimeMillis() - epochMs
    val mins = diff / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 24 * 60 -> "${mins / 60}h ago"
        else -> "${mins / (24 * 60)}d ago"
    }
}
