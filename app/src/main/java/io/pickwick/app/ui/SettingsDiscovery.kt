package io.pickwick.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.data.WhitelistParser
import io.pickwick.app.data.YouTubeRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// --- AI discovery -------------------------------------------------------------

/** A model suggestion that survived verification against real YouTube. */
private data class DiscoveryCard(
    val entry: WhitelistEntry,
    val name: String,
    val url: String,
    val imageUrl: String?,
    val subtitle: String,
    val why: String
)

/**
 * Natural-language channel discovery: the AI proposes candidates, each is
 * verified via a real YouTube search, and only verified results are shown —
 * with the actual name/avatar/counts, an open-in-YouTube inspection link, and
 * an explicit Add. Hallucinated channels never reach the whitelist.
 */
@Composable
internal fun AiDiscoverySection(
    ai: io.pickwick.app.data.AiConfig,
    entries: List<WhitelistEntry>,
    yt: YouTubeRepository,
    onAdd: (WhitelistEntry) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var cards by remember { mutableStateOf<List<DiscoveryCard>>(emptyList()) }

    val ready = ai.model.isNotBlank() && (ai.apiKey.isNotBlank() || ai.baseUrl.startsWith("http://"))
    if (!ready) {
        Text(
            "Describe what your kid loves and let AI suggest channels. To use this, " +
                "set up the AI (model + API key) under \"AI content screening\" above — " +
                "the screening toggle itself can stay off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Text(
        "Describe what your kid loves — the AI suggests channels and playlists, " +
            "each verified against YouTube. Inspect anything in the YouTube app " +
            "before adding it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("e.g. calm science videos for a dinosaur fan, age 7") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !busy && query.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    cards = emptyList()
                    message = "Asking ${ai.model}…"
                    val suggestions = runCatching {
                        io.pickwick.app.data.AiScreener.suggest(ai, query.trim())
                    }.getOrElse { e ->
                        message = "AI request failed: ${e.message?.take(120)}"
                        busy = false
                        return@launch
                    }
                    if (suggestions.isEmpty()) {
                        message = "No suggestions — try describing it differently"
                        busy = false
                        return@launch
                    }

                    message = "Checking ${suggestions.size} suggestion(s) on YouTube…"
                    val verified = coroutineScope {
                        suggestions.map { s ->
                            async { runCatching { verifySuggestion(s, yt) }.getOrNull() }
                        }.awaitAll()
                    }.filterNotNull().distinctBy { it.entry.id }

                    cards = verified
                    message = when {
                        verified.isEmpty() -> "None of the suggestions could be verified on YouTube — try again"
                        else -> "Verified ${verified.size} of ${suggestions.size} — " +
                            "inspect in YouTube, then add what you like"
                    }
                    busy = false
                }
            }
        ) { Text(if (busy) "…" else "Suggest") }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    cards.forEach { card ->
        val alreadyAdded = entries.any { it.id == card.entry.id || it.url == card.entry.url }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            AsyncImage(
                model = card.imageUrl,
                contentDescription = card.name,
                modifier = Modifier.size(40.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(card.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (card.why.isNotBlank()) {
                    Text(
                        card.why,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(card.url)
                        )
                    )
                }
            }) { Text("YouTube") }
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                enabled = !alreadyAdded,
                onClick = { onAdd(card.entry) }
            ) { Text(if (alreadyAdded) "Added ✓" else "Add") }
        }
    }
}

/** Resolves one AI suggestion against real YouTube; null when nothing matches. */
private suspend fun verifySuggestion(
    s: io.pickwick.app.data.AiScreener.Suggestion,
    yt: YouTubeRepository
): DiscoveryCard? = when (s.kind) {
    SourceKind.CHANNEL -> yt.searchChannels(s.searchQuery).firstOrNull()?.let { r ->
        WhitelistParser.parse(r.url).sources.firstOrNull()?.let { e ->
            DiscoveryCard(
                entry = e.copy(label = r.name),
                name = r.name,
                url = r.url,
                imageUrl = r.avatarUrl,
                subtitle = listOfNotNull(
                    "Channel",
                    r.subscriberCount.takeIf { it > 0 }?.let { "${formatCount(it)} subscribers" }
                ).joinToString(" · "),
                why = s.why
            )
        }
    }
    SourceKind.PLAYLIST -> yt.searchPlaylists(s.searchQuery).firstOrNull()?.let { r ->
        WhitelistParser.parse(r.url).sources.firstOrNull()?.let { e ->
            DiscoveryCard(
                entry = e.copy(label = r.name),
                name = r.name,
                url = r.url,
                imageUrl = r.thumbnailUrl,
                subtitle = listOfNotNull(
                    "Playlist",
                    r.uploaderName?.let { "by $it" },
                    r.videoCount.takeIf { it > 0 }?.let { "$it videos" }
                ).joinToString(" · "),
                why = s.why
            )
        }
    }
}

// --- Community directory ------------------------------------------------------

private val DIRECTORY_AGE_ORDER = listOf("2-4", "5-7", "8-10", "11+")

/**
 * Browse the reviewed community directory (pickwick.tv/directory) and add
 * entries to the whitelist. Directory review means "other parents vouch for
 * this", not "right for this family" — added entries land tagged NEW and go
 * through the same per-kid switches and screening as any hand-added channel.
 */
@Composable
internal fun DirectorySection(
    entries: List<WhitelistEntry>,
    onAdd: (WhitelistEntry) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var all by remember { mutableStateOf<List<io.pickwick.app.data.DirectoryEntry>>(emptyList()) }
    var selectedAges by remember { mutableStateOf(setOf<String>()) }
    var selectedTopics by remember { mutableStateOf(setOf<String>()) }
    var selectedLangs by remember { mutableStateOf(setOf<String>()) }

    fun load() = scope.launch {
        busy = true
        message = "Loading the directory…"
        runCatching { io.pickwick.app.data.Directory.fetch() }
            .onSuccess { list ->
                all = list
                // Pre-select the device language once there's more than one to
                // choose from — one tap on the chip widens back to everything.
                val device = java.util.Locale.getDefault().language
                selectedLangs = if (
                    list.mapTo(mutableSetOf()) { it.langCode }.size > 1 &&
                    list.any { it.langCode == device }
                ) setOf(device) else emptySet()
                message = if (list.isEmpty()) "The directory is empty right now" else null
            }
            .onFailure { message = "Couldn't load the directory: ${it.message?.take(120)}" }
        busy = false
    }

    Text(
        "Channels other Pickwick families vouch for — every entry is reviewed " +
            "before it appears. Adding one works like adding by hand: it lands " +
            "tagged NEW below, with your per-kid switches and screening applying " +
            "as usual.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (all.isEmpty()) {
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !busy,
            onClick = { load() }
        ) { Text(if (busy) "…" else "Browse the directory") }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val ages = all.flatMap { it.ages }.distinct()
        .sortedBy { DIRECTORY_AGE_ORDER.indexOf(it).let { i -> if (i == -1) 99 else i } }
    val topics = all.flatMap { it.topics }.distinct().sorted()
    // Language filter only exists once a second language is published — a lone
    // "English" chip would just be noise.
    val langs = all.map { it.langCode to it.langName }.distinct().sortedBy { it.second }
    val multiLang = langs.size > 1

    if (multiLang) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            langs.forEach { (code, name) ->
                FilterChip(
                    selected = code in selectedLangs,
                    onClick = {
                        selectedLangs = if (code in selectedLangs) selectedLangs - code else selectedLangs + code
                    },
                    label = { Text(name.ifBlank { code }) },
                    modifier = Modifier.padding(end = 6.dp).tvFocusHighlight()
                )
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ages.forEach { a ->
            FilterChip(
                selected = a in selectedAges,
                onClick = {
                    selectedAges = if (a in selectedAges) selectedAges - a else selectedAges + a
                },
                label = { Text("ages " + a.replace("-", "–")) },
                modifier = Modifier.padding(end = 6.dp).tvFocusHighlight()
            )
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        topics.forEach { t ->
            FilterChip(
                selected = t in selectedTopics,
                onClick = {
                    selectedTopics = if (t in selectedTopics) selectedTopics - t else selectedTopics + t
                },
                label = { Text(t) },
                modifier = Modifier.padding(end = 6.dp).tvFocusHighlight()
            )
        }
    }

    val shown = all.filter { e ->
        (selectedLangs.isEmpty() || e.langCode in selectedLangs) &&
            (selectedAges.isEmpty() || e.ages.any { it in selectedAges }) &&
            (selectedTopics.isEmpty() || e.topics.any { it in selectedTopics })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (shown.size == all.size) "${all.size} channels & playlists"
            else "${shown.size} of ${all.size} shown",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !busy,
            onClick = { load() }
        ) { Text("Refresh") }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    shown.forEach { d ->
        // Same accept-path as AI discovery: the canonical WhitelistEntry comes
        // from the parser, so a directory add is byte-identical to a hand add.
        val parsed = remember(d.url) { WhitelistParser.parse(d.url).sources.firstOrNull() }
        val alreadyAdded = parsed != null &&
            entries.any { it.id == parsed.id || it.url == parsed.url }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (d.kind == SourceKind.PLAYLIST) listOf("Playlist") else emptyList())
                        .plus(if (multiLang && d.langName.isNotBlank()) listOf(d.langName) else emptyList())
                        .plus(d.ages.map { "ages " + it.replace("-", "–") })
                        .plus(d.topics)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (d.note.isNotBlank()) {
                    Text(
                        d.note,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(d.url)
                        )
                    )
                }
            }) { Text("YouTube") }
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                enabled = parsed != null && !alreadyAdded,
                onClick = { parsed?.let { onAdd(it.copy(label = d.name)) } }
            ) { Text(if (alreadyAdded) "Added ✓" else "Add") }
        }
    }
}
