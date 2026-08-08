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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.AiScreener
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.Digest
import io.pickwick.app.data.DigestStore
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.Stats
import io.pickwick.app.data.StatsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "How did this week go" — one card per paired device (≈ per kid), assembled
 * entirely from data the phone already holds: the cached stats payload (per-day
 * minutes, lifetime channel totals, AI holds) plus DigestStore's daily channel
 * baselines. No cloud; a fresh fetch is attempted once per device on open so
 * the numbers include today, but the screen works fully offline.
 */
@Composable
fun WeeklyDigestScreen(
    pairingStore: PairingStore,
    configStore: ConfigStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    BackHandler { onBack() }

    data class DeviceDigest(
        val device: PairedDevice,
        val kidName: String?,
        val weekly: Digest.Weekly,
        val fresh: Boolean
    )

    var digests by remember { mutableStateOf<List<DeviceDigest>?>(null) }
    var aiEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Disk + JSON + one LAN round trip per device — all off-main; cached
        // snapshots keep the screen honest when a TV is asleep.
        digests = withContext(Dispatchers.IO) {
            aiEnabled = configStore.load().ai.enabled
            val statsCache = StatsCache(appContext)
            val digestStore = DigestStore(File(appContext.filesDir, "digest"))
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            val today = fmt.format(Date())
            pairingStore.paired().mapNotNull { device ->
                val live = LanClient.stats(device)?.also { statsCache.save(device.key, it) }
                val cached = if (live == null) statsCache.load(device.key) else null
                val json = live ?: cached?.second
                val payload = json?.let { Stats.parse(it) } ?: return@mapNotNull null
                DeviceDigest(
                    device = device,
                    kidName = payload.profileName,
                    // Baselines are keyed per kid — a shared TV's payload is
                    // whichever profile is active, and its file must match.
                    weekly = Digest.assemble(
                        payload,
                        digestStore.load(DigestStore.key(device.key, payload.profileName)),
                        today,
                        // A stale snapshot's "today" is the day it was fetched —
                        // plotting it under the phone's today would move, say,
                        // Tuesday's minutes onto Friday's bar.
                        payloadDayKey = cached?.first?.let { fmt.format(Date(it)) } ?: today
                    ),
                    fresh = live != null
                )
            }
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
                Text(
                    "This week",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
                TextButton(modifier = Modifier.tvFocusHighlight(), onClick = onBack) { Text("Close") }
            }

            val list = digests
            when {
                list == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.isEmpty() -> Text(
                    "No watching recorded yet — the digest fills in once a paired " +
                        "device has reported some stats.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> list.forEach { d ->
                    // Identity-keyed so per-card state (the AI summary) can't
                    // attach to the wrong device if the list ever changes.
                    key(d.device.key) {
                        Spacer(Modifier.height(16.dp))
                        DeviceWeekCard(d.device, d.kidName, d.weekly, d.fresh, aiEnabled, configStore)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DeviceWeekCard(
    device: PairedDevice,
    kidName: String?,
    weekly: Digest.Weekly,
    fresh: Boolean,
    aiEnabled: Boolean,
    configStore: ConfigStore
) {
    val scope = rememberCoroutineScope()
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                kidName?.let { "$it · ${device.name}" } ?: device.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            if (!fresh) {
                Text(
                    "Device unreachable — from its last report; today may be missing minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${weekly.totalMin} min this week",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))

            // Day bars — same idiom as the stats screen's "Last days".
            val max = (weekly.days.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
            weekly.days.forEach { (day, mins) ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(prettyWeekDay(day), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(64.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f * (mins.toFloat() / max))
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("$mins min", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (weekly.topChannels.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Most watched" + (weekly.channelsSinceDay
                        ?.takeIf { it != weekly.days.first().first }
                        ?.let { " (since ${prettyWeekDay(it)})" } ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                weekly.topChannels.take(5).forEach { (name, mins) ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text("$mins min", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Per-channel numbers for the week appear once the phone has a " +
                        "day of history to compare against.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (weekly.blocked.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                // The payload only lists holds the parent hasn't ruled on yet —
                // items resolved mid-week drop out, so this is the open queue
                // from this week, not the week's full screening record.
                Text(
                    "Held back this week, awaiting your OK (${weekly.blocked.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                weekly.blocked.take(20).forEach { f ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            listOfNotNull(f.title, f.channel.takeIf { it.isNotBlank() })
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (f.reason.isNotBlank()) {
                            Text(f.reason, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (aiEnabled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Nothing from this week is waiting for your OK.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (aiEnabled) {
                Spacer(Modifier.height(10.dp))
                var summary by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                summary?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                } ?: FilledTonalButton(
                    enabled = !busy,
                    modifier = Modifier.tvFocusHighlight(),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    AiScreener.summarizeDigest(
                                        configStore.load().ai,
                                        Digest.summaryFacts(kidName, weekly)
                                    )
                                }
                            }.onSuccess { summary = it }
                                .onFailure { error = "Couldn't reach the AI — the numbers above still stand." }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "Summarizing…" else "✨ Summarize the week") }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun prettyWeekDay(yyyymmdd: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyyMMdd", Locale.US).parse(yyyymmdd)!!
    SimpleDateFormat("EEE d", Locale.US).format(parsed)
}.getOrDefault(yyyymmdd)
