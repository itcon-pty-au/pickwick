package io.pickwick.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.data.CustomVideo
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.TIME_MULTIPLIERS
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.data.WhitelistParser
import io.pickwick.app.data.YouTubeRepository
import kotlinx.coroutines.launch

// --- Channels ---------------------------------------------------------------

/** What the YouTube search box looks for; one chip each. */
private enum class SearchKind(val label: String, val hint: String) {
    CHANNELS("Channels", "Search YouTube channels…"),
    PLAYLISTS("Playlists", "Search YouTube playlists…"),
    VIDEOS("Videos", "Search YouTube videos…")
}

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
    // Every page loaded so far for the current query, per kind, so Back is instant and
    // Next only fetches a page that hasn't been seen.
    var chPages by remember { mutableStateOf<List<YouTubeRepository.SearchPage<YouTubeRepository.ChannelResult>>>(emptyList()) }
    var plPages by remember { mutableStateOf<List<YouTubeRepository.SearchPage<YouTubeRepository.PlaylistResult>>>(emptyList()) }
    var vdPages by remember { mutableStateOf<List<YouTubeRepository.SearchPage<YouTubeRepository.VideoResult>>>(emptyList()) }
    var pageNo by remember { mutableStateOf<Map<SearchKind, Int>>(emptyMap()) }
    val searchTop = remember { BringIntoViewRequester() }
    val results = chPages.getOrNull(pageNo[SearchKind.CHANNELS] ?: 0)?.items.orEmpty()
    val playlistResults = plPages.getOrNull(pageNo[SearchKind.PLAYLISTS] ?: 0)?.items.orEmpty()
    val videoResults = vdPages.getOrNull(pageNo[SearchKind.VIDEOS] ?: 0)?.items.orEmpty()
    var kind by remember { mutableStateOf(SearchKind.CHANNELS) }
    /** The query each chip's list was loaded for, so flipping chips doesn't refetch. */
    var loadedFor by remember { mutableStateOf<Map<SearchKind, String>>(emptyMap()) }
    var searchFailed by remember { mutableStateOf(false) }
    /** The search result a parent tapped Add on: its title shows while the playability check runs. */
    var pendingVideoHint by remember { mutableStateOf<CustomVideo?>(null) }
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<WhitelistEntry?>(null) }
    /** A just-picked channel awaiting the "who is this for?" answer. */
    var pendingAdd by remember { mutableStateOf<WhitelistEntry?>(null) }
    /** The entry whose AI note dialog is open. */
    var noteEntry by remember { mutableStateOf<WhitelistEntry?>(null) }
    /** A pasted video link, resolved and waiting for the "which playlist?" answer. */
    var pendingVideoId by remember { mutableStateOf<String?>(null) }

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

    pendingVideoId?.let { videoId ->
        val customLists = entries.filter { it.kind == SourceKind.CUSTOM }
        var newName by remember(videoId) { mutableStateOf("") }
        // The extractor takes a few seconds; the dialog is up straight away and
        // its buttons wake when the title and thumbnail have arrived.
        var lookup by remember(videoId) { mutableStateOf<CustomVideo?>(null) }
        var lookupError by remember(videoId) { mutableStateOf<String?>(null) }
        LaunchedEffect(videoId) {
            when (val r = yt.lookupVideo(videoId)) {
                is YouTubeRepository.VideoLookup.Found -> lookup = r.video
                YouTubeRepository.VideoLookup.Unplayable ->
                    lookupError = "This video can't be played in the app."
                YouTubeRepository.VideoLookup.Failed ->
                    lookupError = "Couldn't check that video. Try again."
            }
        }
        val video = lookup
        AlertDialog(
            onDismissRequest = { pendingVideoId = null },
            title = { Text("Add to a custom playlist") },
            text = {
                Column {
                    Text(
                        when {
                            video != null -> video.title.ifBlank { video.id }
                            lookupError != null -> lookupError!!
                            else -> pendingVideoHint?.title?.ifBlank { null } ?: "Looking up the video…"
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (lookupError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "A single video can only go into a playlist you make — " +
                            "never into a channel you've added.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    customLists.forEach { list ->
                        val has = list.customVideos.any { it.id == videoId }
                        TextButton(
                            enabled = video != null && !has,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                video ?: return@TextButton
                                onChanged(entries.map {
                                    if (it.id == list.id) it.copy(customVideos = it.customVideos + video)
                                    else it
                                })
                                pendingVideoId = null
                            }
                        ) {
                            Text(
                                (list.label ?: "Custom playlist") + if (has) "  ✓ already in it" else "",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("New playlist name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = video != null && newName.isNotBlank(),
                    onClick = {
                        video ?: return@Button
                        val id = "custom-" + java.util.UUID.randomUUID().toString().take(8)
                        add(
                            WhitelistEntry(
                                id = id, url = "pickwick://custom/$id", label = newName.trim(),
                                // AI check stays on (the default): a picked video is reviewed
                                // when played, like any other, if screening is enabled.
                                kind = SourceKind.CUSTOM, customVideos = listOf(video)
                            )
                        )
                        pendingVideoId = null
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { pendingVideoId = null }) { Text("Cancel") }
            }
        )
    }

    noteEntry?.let { entry ->
        var text by remember(entry.id) { mutableStateOf(entry.aiNote.orEmpty()) }
        var check by remember(entry.id) { mutableStateOf(entry.aiCheck) }
        // Exemption matches by uploader name, so it only works for channels.
        val canToggle = entry.kind != SourceKind.PLAYLIST
        val what = if (entry.kind == SourceKind.CUSTOM) "playlist" else "channel"
        AlertDialog(
            onDismissRequest = { noteEntry = null },
            title = { Text("AI instructions — ${displayName(entry)}") },
            text = {
                Column {
                    if (canToggle) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Check this $what with AI",
                                modifier = Modifier.weight(1f)
                            )
                            Switch(checked = check, onCheckedChange = { check = it })
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (check) {
                        Text(
                            "Rules for this $what only, applied on top of the family " +
                                "rules (and winning where they clash). This $what's " +
                                "videos are re-checked under the new instructions; " +
                                "already-blocked ones stay blocked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            minLines = 3,
                            placeholder = {
                                Text(
                                    "e.g. Mild cartoon slapstick is fine here. " +
                                        "Block anything filmed as a \"prank\"."
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            "Videos from this $what skip AI screening and show " +
                                "up as if you had allowed them. Your blocked list " +
                                "still applies.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    // The note is kept while AI is off, so switching back restores it.
                    val note = text.trim().ifEmpty { null }
                    onChanged(entries.map {
                        if (it.id == entry.id) it.copy(aiNote = note, aiCheck = check) else it
                    })
                    noteEntry = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { noteEntry = null }) { Text("Cancel") }
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
        // A custom list's name tap opens its videos (other names marquee instead).
        var open by remember(entry.id) { mutableStateOf(false) }
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
                    ) { if (entry.kind == SourceKind.CUSTOM) open = !open else marquee = !marquee }
            ) {
                Text(
                    displayName(entry) +
                        when (entry.kind) {
                            SourceKind.PLAYLIST -> "  (playlist)"
                            SourceKind.CUSTOM -> if (open) "  ▴" else "  ▾"
                            else -> ""
                        },
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
            // Quiz after this source's videos: bright = on. Off by default, since
            // only the parent can say a channel teaches anything worth quizzing.
            IconButton(
                onClick = {
                    onChanged(entries.map {
                        if (it.id == entry.id) it.copy(quiz = !it.quiz) else it
                    })
                }
            ) {
                Text(
                    "🎓",
                    modifier = Modifier
                        .alpha(if (entry.quiz) 1f else 0.35f)
                        .semantics {
                            contentDescription = "Quiz after videos from ${displayName(entry)}: " +
                                if (entry.quiz) "on" else "off"
                        }
                )
            }
            // Per-channel AI instructions; dimmed until one exists. Emoji over
            // a vector icon, matching the app's other glyph affordances.
            // Bright = AI is checking this source; dim = it isn't. A YouTube playlist
            // has no switch, so for it bright means it has instructions.
            val aiActive = if (entry.kind == SourceKind.PLAYLIST) !entry.aiNote.isNullOrBlank()
                else entry.aiCheck
            IconButton(
                onClick = { noteEntry = entry }
            ) {
                Text(
                    "📝",
                    // alpha, not text color: emoji glyphs ignore the latter.
                    modifier = Modifier
                        .alpha(if (aiActive) 1f else 0.35f)
                        .semantics {
                            contentDescription = "AI instructions for ${displayName(entry)}"
                        }
                )
            }
            IconButton(
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
        if (entry.kind == SourceKind.CUSTOM && open) {
            entry.customVideos.forEach { v ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        v.title.ifBlank { v.id },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        onChanged(entries.map {
                            if (it.id == entry.id) it.copy(customVideos = it.customVideos - v) else it
                        })
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${v.title} from the playlist")
                    }
                }
            }
        }
        }
        }
    }

    Spacer(Modifier.height(8.dp))
    // Search by name, one chip per kind of result
    fun load(k: SearchKind, q: String) {
        scope.launch {
            searching = true
            searchFailed = false
            runCatching {
                when (k) {
                    SearchKind.CHANNELS -> chPages = listOf(yt.searchChannelsPage(q, null))
                    SearchKind.PLAYLISTS -> plPages = listOf(yt.searchPlaylistsPage(q, null))
                    SearchKind.VIDEOS -> vdPages = listOf(yt.searchVideosPage(q, null))
                }
            }.onSuccess {
                loadedFor = loadedFor + (k to q)
                pageNo = pageNo + (k to 0)
            }.onFailure { searchFailed = true }
            searching = false
        }
    }
    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        loadedFor = emptyMap()
        pageNo = emptyMap()
        chPages = emptyList(); plPages = emptyList(); vdPages = emptyList()
        load(kind, q)
    }
    /** The ✕ in the box: empties it and the results, so the next search starts clean. */
    fun clearSearch() {
        query = ""
        loadedFor = emptyMap()
        pageNo = emptyMap()
        chPages = emptyList(); plPages = emptyList(); vdPages = emptyList()
        searchFailed = false
    }
    fun pick(k: SearchKind) {
        kind = k
        val q = query.trim()
        // The repository keeps results for a few minutes, so this is instant when seen lately.
        if (q.isNotEmpty() && loadedFor[k] != q) load(k, q)
    }
    /** Back (-1) or Next (+1); shows the new page from the top of the search area. */
    fun turn(delta: Int) {
        val q = query.trim()
        val k = kind
        val target = (pageNo[k] ?: 0) + delta
        if (target < 0 || q.isEmpty()) return
        scope.launch {
            searching = true
            searchFailed = false
            runCatching {
                when (k) {
                    SearchKind.CHANNELS -> if (target >= chPages.size)
                        chPages = chPages + yt.searchChannelsPage(q, chPages.last().next)
                    SearchKind.PLAYLISTS -> if (target >= plPages.size)
                        plPages = plPages + yt.searchPlaylistsPage(q, plPages.last().next)
                    SearchKind.VIDEOS -> if (target >= vdPages.size)
                        vdPages = vdPages + yt.searchVideosPage(q, vdPages.last().next)
                }
                pageNo = pageNo + (k to target)
            }.onFailure { searchFailed = true }
            searching = false
            searchTop.bringIntoView()
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.bringIntoViewRequester(searchTop)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(kind.hint) },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { clearSearch() }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                runSearch()
            }),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        IconButton(
            enabled = !searching && query.isNotBlank(),
            onClick = { runSearch() }
        ) {
            Icon(Icons.Filled.Search, contentDescription = "Search YouTube")
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        SearchKind.entries.forEach { k ->
            FilterChip(
                selected = kind == k,
                onClick = { pick(k) },
                label = { Text(k.label) },
            )
        }
    }
    val q = query.trim()
    if (searching) {
        Text(
            "Searching…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    } else if (searchFailed) {
        Text(
            "Couldn't search right now. Check the connection and try again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    } else if (loadedFor[kind] == q && q.isNotEmpty() &&
        when (kind) {
            SearchKind.CHANNELS -> results.isEmpty()
            SearchKind.PLAYLISTS -> playlistResults.isEmpty()
            SearchKind.VIDEOS -> videoResults.isEmpty()
        }
    ) {
        Text(
            "No ${kind.label.lowercase()} found for \"$q\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }

    if (kind == SearchKind.CHANNELS) results.forEach { r ->
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
            CompactButton(
                enabled = !alreadyAdded,
                onClick = {
                    val id = r.url.substringAfterLast('/')
                    add(WhitelistEntry(id, r.url, r.name, SourceKind.CHANNEL))
                }
            ) { Text(if (alreadyAdded) "Added ✓" else "Add") }
        }
    }

    if (kind == SearchKind.PLAYLISTS) playlistResults.forEach { r ->
        val parsed = WhitelistParser.parse(r.url).sources.firstOrNull()
        val alreadyAdded = parsed != null && entries.any { it.id == parsed.id }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            AsyncImage(
                model = r.thumbnailUrl,
                contentDescription = r.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(width = 72.dp, height = 40.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        r.uploaderName,
                        r.videoCount.takeIf { it > 0 }?.let { "$it videos" }
                    ).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CompactButton(
                enabled = parsed != null && !alreadyAdded,
                onClick = { parsed?.let { add(it.copy(label = r.name)) } }
            ) { Text(if (alreadyAdded) "Added ✓" else "Add") }
        }
    }

    if (kind == SearchKind.VIDEOS) videoResults.forEach { r ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Box {
                AsyncImage(
                    model = r.thumbnailUrl,
                    contentDescription = r.title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 96.dp, height = 54.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                )
                if (r.live) {
                    Text(
                        "LIVE",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(3.dp)
                            .background(Color(0xFFD32F2F), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        r.channel.takeIf { it.isNotBlank() },
                        if (r.live) "live now" else r.durationSeconds.takeIf { it > 0 }?.let { formatClock(it) }
                    ).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CompactButton(
                onClick = {
                    pendingVideoHint = r.toCustomVideo()
                    pendingVideoId = r.id
                }
            ) { Text("Add") }
        }
    }

    // Back / Next, shown under the results of whichever chip is selected.
    run {
        val idx = pageNo[kind] ?: 0
        val shown = when (kind) {
            SearchKind.CHANNELS -> chPages
            SearchKind.PLAYLISTS -> plPages
            SearchKind.VIDEOS -> vdPages
        }
        val current = shown.getOrNull(idx)
        if (current != null && current.items.isNotEmpty()) {
            val hasNext = idx + 1 < shown.size || current.next != null
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                IconButton(
                    enabled = idx > 0 && !searching,
                    onClick = { turn(-1) }
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous results") }
                Text(
                    "Page ${idx + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                IconButton(
                    enabled = hasNext && !searching,
                    onClick = { turn(1) }
                ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next results") }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    // Paste fallback (also how playlists are added)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = pasteText,
            onValueChange = { pasteText = it },
            placeholder = { Text("Or paste a channel, playlist or video link") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        CompactButton(
            onClick = {
                val videoId = WhitelistParser.videoIdFromLink(pasteText)
                if (videoId != null) {
                    pasteError = null
                    pasteText = ""
                    pendingVideoHint = null
                    pendingVideoId = videoId
                    return@CompactButton
                }
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
internal fun TimeMultiplierChip(percent: Int, onChange: (Int) -> Unit) {
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
