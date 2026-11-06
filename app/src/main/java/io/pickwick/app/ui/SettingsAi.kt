package io.pickwick.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.ScreeningStore
import io.pickwick.app.data.SourceCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- AI screening -------------------------------------------------------------

/** Cards added per "Show more" in the parent's own blocks; also when its search box appears. */
private const val PARENT_BLOCK_PAGE = 30

private data class AiPreset(val name: String, val baseUrl: String, val modelHint: String)

private val AI_PRESETS = listOf(
    AiPreset("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-haiku-4.5"),
    AiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    AiPreset("Anthropic", "https://api.anthropic.com/v1", "claude-haiku-4-5"),
    AiPreset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    AiPreset("Local", "http://192.168.0.10:11434/v1", "llama3.2")
)

private val DEFAULT_AI_RULES = """
    No scary, violent, or disturbing content.
    No adult themes, romance, or innuendo.
    No unboxing, toy hauls, or heavy consumerism.
    No clickbait or low-effort "brainrot" content.
""".trimIndent()

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
internal fun AiScreeningSection(
    ai: io.pickwick.app.data.AiConfig,
    profiles: List<io.pickwick.app.data.Profile>,
    onChanged: (io.pickwick.app.data.AiConfig) -> Unit
) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsMessage by remember { mutableStateOf<String?>(null) }

    // Fetch the provider's model list as soon as it's reachable (key entered, or a
    // keyless local server). Keyed on url+key so edits re-fetch; the delay debounces typing.
    LaunchedEffect(ai.enabled, ai.baseUrl, ai.apiKey) {
        models = emptyList()
        modelsMessage = null
        val keyless = ai.baseUrl.startsWith("http://") // local LAN server
        if (!ai.enabled || (ai.apiKey.isBlank() && !keyless)) return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        modelsMessage = "Loading models…"
        runCatching { io.pickwick.app.data.AiScreener.listModels(ai) }
            .onSuccess {
                models = it
                modelsMessage = if (it.isEmpty()) "Provider returned no models" else null
            }
            .onFailure {
                // The row shows a trimmed line; the log keeps the provider's
                // whole answer, which is what actually says why.
                android.util.Log.w("Pickwick", "model list from ${ai.baseUrl} failed", it)
                modelsMessage = "Couldn't load models: ${it.message?.take(80)}"
            }
    }

    Text(
        "New videos on allowed channels are checked against your rules by an AI " +
            "before the kid can see them. Before playback, a deeper check also reads " +
            "the description, tags and English subtitles. Watch history is never sent. " +
            "Videos needing a decision appear under Blocked & reported.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Screen new videos with AI", modifier = Modifier.weight(1f))
        Switch(
            modifier = Modifier.tvFocusHighlight(),
            checked = ai.enabled,
            onCheckedChange = { on ->
                onChanged(ai.copy(enabled = on, rules = ai.rules.ifBlank { DEFAULT_AI_RULES }))
            }
        )
    }
    if (!ai.enabled) return

    Text("When the pre-play check is incomplete", style = MaterialTheme.typography.titleSmall)
    Text(
        "If the description or English subtitles are unavailable, or the check fails or times out.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = !ai.reviewIncompleteChecks,
            onClick = { onChanged(ai.copy(reviewIncompleteChecks = false)) },
            modifier = Modifier.tvFocusHighlight()
        )
        Text("Allow playback (default)")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = ai.reviewIncompleteChecks,
            onClick = { onChanged(ai.copy(reviewIncompleteChecks = true)) },
            modifier = Modifier.tvFocusHighlight()
        )
        Text("Block playback and ask for parent review")
    }
    Text(
        "Parent review holds the video in Waiting for your OK. With Allow playback, " +
            "the AI still checks available information and can flag a rule violation.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AI_PRESETS.forEach { preset ->
            FilterChip(
                selected = ai.baseUrl == preset.baseUrl,
                onClick = { onChanged(ai.copy(baseUrl = preset.baseUrl, model = preset.modelHint)) },
                label = { Text(preset.name) }
            )
        }
    }
    val endpointSafe = ai.baseUrl.isBlank() ||
        io.pickwick.app.data.AiScreener.isEndpointSafe(ai.baseUrl)
    OutlinedTextField(
        value = ai.baseUrl,
        onValueChange = { onChanged(ai.copy(baseUrl = it.trim())) },
        label = { Text("API base URL (OpenAI-compatible)") },
        singleLine = true,
        isError = !endpointSafe,
        modifier = Modifier.fillMaxWidth()
    )
    // Screening refuses to run against this rather than leak the key and the
    // kids' details in the clear, so say why here instead of failing silently.
    if (!endpointSafe) {
        Text(
            "Use https:// — a plain http:// address sends your API key and the " +
                "video titles unencrypted. http:// is allowed only for a model " +
                "running on your own network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ai.apiKey,
        onValueChange = { onChanged(ai.copy(apiKey = it.trim())) },
        label = { Text("API key (leave empty for a local server)") },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    if (models.isEmpty()) {
        // No list (no key yet, or fetch failed): plain text entry still works.
        OutlinedTextField(
            value = ai.model,
            onValueChange = { onChanged(ai.copy(model = it.trim())) },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        // Type-to-filter dropdown: OpenRouter alone lists hundreds of models.
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = ai.model,
                onValueChange = {
                    onChanged(ai.copy(model = it.trim()))
                    expanded = true
                },
                label = { Text("Model — type to filter, tap to pick") },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
            )
            val filtered = models.filter { it.contains(ai.model, ignoreCase = true) }.take(25)
            ExposedDropdownMenu(
                expanded = expanded && filtered.isNotEmpty(),
                onDismissRequest = { expanded = false }
            ) {
                filtered.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = {
                            onChanged(ai.copy(model = m))
                            expanded = false
                        }
                    )
                }
            }
        }
    }
    modelsMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ai.rules,
        onValueChange = { onChanged(ai.copy(rules = it)) },
        label = { Text("House rules the AI enforces") },
        supportingText = { Text("Rough notes are fine — the AI understands shorthand.") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth()
    )
    if (profiles.isEmpty()) {
        StepperRow(
            label = "Child age",
            value = ai.childAge, step = 1, min = 2, max = 16,
            format = { "$it" },
            onChanged = { onChanged(ai.copy(childAge = it)) }
        )
    } else {
        Text(
            "Each video is checked once for the whole family — one AI call, a " +
                "verdict per kid, using the ages set under Kids: " +
                profiles.joinToString(", ") { p ->
                    p.name + (p.age?.let { " ($it)" } ?: " (no age)")
                } + ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Changing rules re-screens the whole catalog on every device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        CompactButton(
            enabled = !testing && ai.model.isNotBlank(),
            onClick = {
                scope.launch {
                    testing = true
                    testMessage = "Testing…"
                    runCatching {
                        io.pickwick.app.data.AiScreener.screen(
                            ai,
                            listOf(
                                io.pickwick.app.data.Video(
                                    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                                    title = "Fun science experiment for kids",
                                    channelName = "Test Channel",
                                    thumbnailUrl = null,
                                    durationSeconds = 300
                                )
                            )
                        )
                    }
                        .onSuccess { testMessage = "Connected ✓ — ${ai.model} answered" }
                        .onFailure { testMessage = "Failed: ${it.message?.take(160)}" }
                    testing = false
                }
            }
        ) { Text(if (testing) "…" else "Test connection") }
    }
    testMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- AI review queue ---------------------------------------------------------

/**
 * THE review queue — the one place a held-back video waits for the parent.
 * Served from this device's own verdict store, merged with what each paired
 * device reports as held (live over the LAN, or its last cached stats snapshot
 * when it's off) — the models are not perfectly deterministic, so a kid device
 * can hold a video this phone's own screening let through. Decisions are
 * committed and pushed as they're tapped, not held for Save & close.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AiReviewSection(
    ai: io.pickwick.app.data.AiConfig,
    profiles: List<io.pickwick.app.data.Profile>,
    pairingStore: PairingStore,
    /** Already ruled on (parent-blocked or allowed) — dropped from the queue. */
    resolved: Set<String>,
    /** The parent's own rulings: family-wide, and video → the kids it's blocked for. */
    blockedForAll: Set<String>,
    blockedForKids: Map<String, Set<String>>,
    /** Second arg: null = everyone (tap); a kid set = long-press per-kid ruling. */
    onAllow: (String, Set<String>?) -> Unit,
    onBlock: (String, Set<String>?) -> Unit,
    onUnblock: (String) -> Unit
) {
    /** (videoId, allow?) awaiting the long-press "which kids?" answer. */
    var perKid by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    /** Asked first: nothing on this page can re-block a video that no one flagged. */
    var confirmUnblock by remember { mutableStateOf<io.pickwick.app.data.ParentBlocks.Row?>(null) }

    confirmUnblock?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmUnblock = null },
            title = { Text("Unblock this video?") },
            text = {
                Text(
                    "\"${row.title.ifBlank { row.videoId }}\" can appear on your kids' devices " +
                        "again. If the AI or a kid flagged it, it goes back to Waiting for your OK instead."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onUnblock(row.videoId); confirmUnblock = null }
                ) { Text("Unblock") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmUnblock = null }
                ) { Text("Cancel") }
            }
        )
    }

    perKid?.let { (videoId, isAllow) ->
        WhoForDialog(
            title = if (isAllow) "Allow for which kids?" else "Block for which kids?",
            profiles = profiles,
            initialIds = emptySet(),
            confirmLabel = if (isAllow) "Allow" else "Block",
            onDismiss = { perKid = null },
            onConfirm = { forKids ->
                // "All kids" collapses to the family-wide tap ruling.
                val target = forKids.ifEmpty { null }
                if (isAllow) onAllow(videoId, target) else onBlock(videoId, target)
                perKid = null
            }
        )
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { io.pickwick.app.data.ScreeningStore(context.applicationContext) }
    var all by remember { mutableStateOf<List<Pair<String, ScreeningStore.Entry>>>(emptyList()) }
    /** device token → videos that device says it is holding back. */
    var remoteByDevice by remember {
        mutableStateOf<Map<String, List<io.pickwick.app.data.Stats.AiFlagged>>>(emptyMap())
    }
    /** Details kid devices still hold for videos the parent blocked. */
    var remoteBlocked by remember { mutableStateOf<List<io.pickwick.app.data.Stats.AiFlagged>>(emptyList()) }
    /** Details this phone's own stores hold for videos the parent blocked. */
    var localBlockDetails by remember {
        mutableStateOf<Map<String, io.pickwick.app.data.ParentBlocks.Details>>(emptyMap())
    }
    /** Cached-feed videos with no verdict yet — the queue is still growing by this much. */
    var stillScreening by remember { mutableIntStateOf(0) }
    // The poll loop outlives any single value of `resolved`; read the latest each
    // pass rather than restarting the (disk-reading) loop on every Allow/Block tap.
    val latestResolved by rememberUpdatedState(resolved)
    val latestBlockedIds by rememberUpdatedState(blockedForAll + blockedForKids.keys)

    // Polled, not read once: screening runs in background batches, and a snapshot
    // taken when the screen opened showed the parent one batch and hid the rest
    // until they left and came back. Off the main thread — this reads every
    // source's cached video list plus the verdict file.
    LaunchedEffect(ai.rulesVersion) {
        while (true) {
            val snapshot = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val flaggedNow = store.flagged(ai.rulesVersion)
                val videoCache = io.pickwick.app.data.VideoCache(context.applicationContext)
                val feed = SourceCache(context.applicationContext).load()
                    .flatMap { videoCache.load(it.id) }
                val pending = feed
                    .mapNotNull { it.videoId }
                    .distinct()
                    // Parent-allowed videos are never sent for screening, so they'd
                    // otherwise sit in this count forever.
                    .count {
                        it !in latestResolved && store.get(it)?.rulesVersion != ai.rulesVersion
                    }
                val details = buildMap {
                    feed.forEach { v ->
                        val id = v.videoId ?: return@forEach
                        if (id in latestBlockedIds) {
                            put(id, io.pickwick.app.data.ParentBlocks.Details(v.title, v.channelName, v.thumbnailUrl))
                        }
                    }
                    // Any rules version: the title is still right after a rules change.
                    latestBlockedIds.forEach { id ->
                        store.get(id)?.let {
                            put(id, io.pickwick.app.data.ParentBlocks.Details(it.title, it.channel, it.thumb))
                        }
                    }
                }
                Triple(flaggedNow, pending, details)
            }
            all = snapshot.first
            stillScreening = snapshot.second
            localBlockDetails = snapshot.third
            delay(4_000)
        }
    }

    // Kid devices can hold videos this phone let through, so their queues fold in
    // here. First pass paints from the cached snapshots (instant, works with the
    // TV off); later passes refresh live and update the cache the per-device
    // stats page reads. Slower cadence than the local poll — this hits the LAN.
    LaunchedEffect(Unit) {
        val statsCache = io.pickwick.app.data.StatsCache(context.applicationContext)
        var live = false
        while (true) {
            val payloads = withContext(kotlinx.coroutines.Dispatchers.IO) {
                pairingStore.paired().associate { device ->
                    val json = (if (live) LanClient.stats(device)
                        ?.also { statsCache.save(device.key, it) } else null)
                        ?: statsCache.load(device.key)?.second
                    device.key to json?.let { io.pickwick.app.data.Stats.parse(it) }
                }
            }
            remoteByDevice = payloads.mapValues { it.value?.aiFlagged.orEmpty() }
            remoteBlocked = payloads.values.flatMap { it?.aiBlocked.orEmpty() }
            if (live) delay(15_000)
            live = true
        }
    }

    val localFlagged = all.filterNot { (id, _) -> id in resolved }
    // A remote hold on a video the local store also flags is the same card; a
    // remote hold on one it doesn't is still the parent's to rule on.
    val localIds = localFlagged.map { it.first }.toSet()
    val remoteOnly = remoteByDevice.values.flatten()
        .distinctBy { it.videoId }
        .filter { it.videoId !in localIds && it.videoId !in resolved }
        .map { f ->
            f.videoId to ScreeningStore.Entry(
                verdict = if (f.verdict == io.pickwick.app.data.AiScreener.Verdict.REVIEW.name)
                    io.pickwick.app.data.AiScreener.Verdict.REVIEW
                else io.pickwick.app.data.AiScreener.Verdict.BLOCK,
                reason = f.reason,
                title = f.title,
                channel = f.channel,
                thumb = f.thumbnailUrl,
                rulesVersion = ai.rulesVersion,
                at = f.at,
                reported = f.reported
            )
        }
    val flagged = (localFlagged + remoteOnly).sortedByDescending { it.second.at }

    // Two piles for the parent: "AI unsure — waiting on you" stays up top, and
    // hard blocks (the title pass or the pre-play deep check, this device or a
    // paired one) sit in their own collapsed section below — those are already
    // ruled and hidden, listed only so a wrong call can be overruled.
    val review = flagged.filter { it.second.verdict == io.pickwick.app.data.AiScreener.Verdict.REVIEW }
    val blockedList = flagged.filter { it.second.verdict == io.pickwick.app.data.AiScreener.Verdict.BLOCK }

    // Families reach hundreds of blocks, so this list starts collapsed and pages:
    // this screen is a plain Column, and every card in it is composed up front.
    var parentBlocksExpanded by remember { mutableStateOf(false) }
    var parentBlockQuery by remember { mutableStateOf("") }
    var parentBlocksShown by remember { mutableIntStateOf(PARENT_BLOCK_PAGE) }
    val parentBlockCount = (blockedForAll + blockedForKids.keys).size
    // Built only once opened, and from maps: this body re-runs on each poll.
    val parentBlocks = remember(
        parentBlocksExpanded, blockedForAll, blockedForKids, all, remoteByDevice, remoteBlocked, localBlockDetails
    ) {
        if (!parentBlocksExpanded) return@remember emptyList()
        val local = all.toMap()
        val remote = (remoteByDevice.values.flatten() + remoteBlocked)
            .filter { it.title.isNotBlank() }
            .associateBy { it.videoId }
        io.pickwick.app.data.ParentBlocks.rows(blockedForAll, blockedForKids) { id ->
            // The flagged lists come first: they already hold a video the moment it's
            // blocked, before the slower polls above have picked up its details.
            val l = local[id]
            val r = remote[id]
            when {
                l != null -> io.pickwick.app.data.ParentBlocks.Details(l.title, l.channel, l.thumb)
                r != null -> io.pickwick.app.data.ParentBlocks.Details(r.title, r.channel, r.thumbnailUrl)
                else -> localBlockDetails[id]
            }
        }
    }

    @Composable
    fun youTubeButton(videoId: String) {
        // Watch it yourself before ruling on the AI's call.
        CompactButton( onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId")
                    )
                )
            }
        }) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(io.pickwick.app.R.drawable.ic_source_youtube),
                contentDescription = "View in YouTube",
                modifier = Modifier.size(28.dp)
            )
        }
    }

    @Composable
    fun parentBlockCard(row: io.pickwick.app.data.ParentBlocks.Row) {
        val who = if (row.kidIds.isEmpty()) "Blocked for everyone" else
            "Blocked for " + profiles.filter { it.id in row.kidIds }.joinToString(", ") { it.name }
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    AsyncImage(
                        model = row.thumb,
                        contentDescription = row.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(width = 104.dp, height = 58.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.title.ifBlank { "Video ${row.videoId}" }, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            listOfNotNull(row.channel.takeIf { it.isNotBlank() }, who).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    youTubeButton(row.videoId)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { confirmUnblock = row }
                    ) { Text("Unblock") }
                }
            }
        }
    }

    @Composable
    fun flaggedCard(videoId: String, e: ScreeningStore.Entry) {
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    AsyncImage(
                        model = e.thumb,
                        contentDescription = e.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(width = 104.dp, height = 58.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(2.dp))
                        // With per-kid verdicts, say who it's held for — "held
                        // for Dave · fine for Katy" is the whole point.
                        val verdictLabel = if (profiles.isNotEmpty() && e.perProfile.isNotEmpty()) {
                            val held = profiles.filter {
                                e.perProfile[it.id] != io.pickwick.app.data.AiScreener.Verdict.ALLOW
                            }
                            val fine = profiles.filter {
                                e.perProfile[it.id] == io.pickwick.app.data.AiScreener.Verdict.ALLOW
                            }
                            listOfNotNull(
                                held.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ") { it.name }?.let { "held for $it" },
                                fine.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ") { it.name }?.let { "fine for $it" }
                            ).joinToString(" · ")
                        } else if (e.reported) {
                            "reported by a kid"
                        } else if (e.verdict == io.pickwick.app.data.AiScreener.Verdict.REVIEW) {
                            "AI unsure"
                        } else "AI blocked"
                        Text(
                            listOfNotNull(
                                e.channel.takeIf { it.isNotBlank() },
                                verdictLabel
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (e.reason.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (e.reported) e.reason else "AI: ${e.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    youTubeButton(videoId)
                    Spacer(Modifier.weight(1f))
                    // Tap rules for everyone; with 2+ kids a long-press picks who —
                    // TextButton owns its click, so these are hand-rolled buttons.
                    @Composable
                    fun rulingButton(label: String, isAllow: Boolean) {
                        Text(
                            label,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .tvFocusHighlight()
                                .clipOnPhone(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                .dpadLongPress {
                                    if (profiles.size >= 2) perKid = videoId to isAllow
                                }
                                .combinedClickable(
                                    onClick = {
                                        if (isAllow) onAllow(videoId, null)
                                        else onBlock(videoId, null)
                                    },
                                    onLongClick = {
                                        if (profiles.size >= 2) perKid = videoId to isAllow
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                    rulingButton("Allow", isAllow = true)
                    Spacer(Modifier.width(8.dp))
                    rulingButton("Block", isAllow = false)
                }
            }
        }
    }

    @Composable
    fun perKidHint() {
        if (profiles.size >= 2) {
            Text(
                "Tap Allow/Block for all kids — hold to choose which kids.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    val screeningNote = if (ai.enabled && stillScreening > 0) {
        " $stillScreening more still being screened — they'll appear here as the AI finishes."
    } else ""

    if (review.isEmpty()) {
        Text(
            "Nothing waiting for you. Videos the AI is unsure about, or that " +
                "your kids report, appear here for your decision." + screeningNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(
            "${review.size} video(s) held back — hidden from the kid until you decide. " +
                "Each Allow/Block is saved as you tap it." + screeningNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // No pagination: a parent asked to rule on a queue wants the whole queue, not
        // a batch that refills after each round trip. The cap only exists so a runaway
        // store can't build thousands of cards into one scrolling Column.
        review.take(300).forEach { (videoId, e) -> flaggedCard(videoId, e) }
        perKidHint()
        if (review.size > 300) {
            Text(
                "…and ${review.size - 300} more — they appear as you rule on these.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    SectionTitle("Blocked by you")
    if (parentBlockCount == 0) {
        Text(
            "Nothing blocked. Videos you block collect here so you can change your mind.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(
            if (parentBlocksExpanded) "▾ $parentBlockCount video(s) you blocked — hide list"
            else "▸ $parentBlockCount video(s) you blocked — show",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .tvFocusHighlight()
                .clipOnPhone(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .combinedClickable(onClick = { parentBlocksExpanded = !parentBlocksExpanded })
                .padding(horizontal = 4.dp, vertical = 8.dp)
        )
        if (parentBlocksExpanded) {
            if (parentBlockCount > PARENT_BLOCK_PAGE) {
                OutlinedTextField(
                    value = parentBlockQuery,
                    onValueChange = { parentBlockQuery = it; parentBlocksShown = PARENT_BLOCK_PAGE },
                    label = { Text("Find by title, channel or video id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvFocusHighlight()
                )
            }
            val matches = io.pickwick.app.data.ParentBlocks.matching(parentBlocks, parentBlockQuery)
            if (matches.isEmpty()) {
                Text(
                    "No blocked video matches that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            matches.take(parentBlocksShown).forEach { parentBlockCard(it) }
            if (matches.size > parentBlocksShown) {
                TextButton(
                    onClick = { parentBlocksShown += PARENT_BLOCK_PAGE }
                ) { Text("Show more (${matches.size - parentBlocksShown} left)") }
            }
        }
    }

    // Nothing to explain to a family that doesn't use the AI.
    if (!ai.enabled && blockedList.isEmpty()) return

    SectionTitle("Blocked by AI")
    if (blockedList.isEmpty()) {
        Text(
            "Nothing blocked right now. Videos the AI blocks — at screening, or in " +
                "the final check just before one plays — collect here so you can " +
                "overrule a wrong call.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        // Collapsed by default: these are already ruled and hidden — routine
        // visits are about the queue above, not re-reading old blocks.
        var blockedExpanded by remember { mutableStateOf(false) }
        Text(
            if (blockedExpanded) "▾ ${blockedList.size} video(s) blocked and hidden — hide list"
            else "▸ ${blockedList.size} video(s) blocked and hidden — show",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .tvFocusHighlight()
                .clipOnPhone(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .combinedClickable(onClick = { blockedExpanded = !blockedExpanded })
                .padding(horizontal = 4.dp, vertical = 8.dp)
        )
        if (blockedExpanded) {
            Text(
                "Allow overrules the AI — the video reappears on every device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            blockedList.take(300).forEach { (videoId, e) -> flaggedCard(videoId, e) }
            perKidHint()
            if (blockedList.size > 300) {
                Text(
                    "…and ${blockedList.size - 300} more — they appear as you rule on these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
