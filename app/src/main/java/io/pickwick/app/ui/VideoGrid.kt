package io.pickwick.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.data.*

internal val CatalogGridMinWidth = 240.dp

/**
 * A small emoji status light riding a poster corner — never tappable (every
 * action lives in the hold menu; fingertip-sized corner targets were a
 * mis-tap trap for kids). Dark circular scrim for legibility over any
 * thumbnail, and a springy pop whenever the state flips (⏳→✅) — the subtle
 * "got it" feedback that the async download moved along.
 */
@Composable
private fun PosterBadge(
    label: String,
    modifier: Modifier = Modifier
) {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    var previous by remember { mutableStateOf(label) }
    LaunchedEffect(label) {
        if (previous != label) {
            previous = label
            scale.snapTo(1.4f)
            scale.animateTo(
                1f,
                androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy
                )
            )
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(0x66000000))
            .padding(6.dp)
    )
}

/**
 * The Up next screen: play order top to bottom, one row per video. Reordering
 * is explicit ▲▼ buttons — one mechanism that works for touch and D-pad alike,
 * and rows reposition instantly (no drag, no animation).
 */
@Composable
internal fun QueueList(
    videos: List<VideoItem>,
    /** Tap a row (or ▶ Play) → start the queue from that position. */
    onPlayFrom: (Int) -> Unit,
    onMove: (VideoItem, Int) -> Unit,
    onRemove: (VideoItem) -> Unit,
    grabFocus: Boolean = false
) {
    if (videos.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing lined up right now.\nHold any video and pick “Add to Up next”.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val firstRowFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (grabFocus) {
        LaunchedEffect(videos.isNotEmpty()) { runCatching { firstRowFocus.requestFocus() } }
    }
    // Removing a row destroys the button that had focus, which would leave the
    // remote pointing at nothing. The ✕ that takes its place gets focus instead,
    // once the shorter list has actually arrived, so clearing several is one key.
    var refocusRemove by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    androidx.compose.foundation.lazy.LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(videos.size, key = { videos[it].video.url }) { i ->
            val item = videos[i]
            var focused by remember { mutableStateOf(false) }
            val removeFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            if (refocusRemove == (i to videos.size)) {
                LaunchedEffect(Unit) {
                    runCatching { removeFocus.requestFocus() }
                    refocusRemove = null
                }
            }
            // The row itself must not be clickable: a focused node's D-pad moves
            // look outside it, so buttons nested inside could never be reached.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusHighlight { focused = it }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = (if (grabFocus && i == 0) Modifier.focusRequester(firstRowFocus)
                        else Modifier)
                        .weight(1f)
                        .clickable { onPlayFrom(i) }
                ) {
                    // The Box itself must carry the size: WatchedProgressBar fills
                    // the parent's max width, and an unconstrained Box in a Row
                    // balloons to the whole row once a bar appears — crushing the
                    // title and the ▲▼✕ buttons to zero width.
                    Box(Modifier.width(120.dp).aspectRatio(16f / 9f)) {
                        AsyncImage(
                            model = item.video.thumbnailUrl,
                            contentDescription = item.video.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        item.progress?.let { fraction -> WatchedProgressBar(fraction) }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        MarqueeTitle(item.video.title, focused)
                        Text(
                            item.video.channelName, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Ends are no-ops (the store clamps) — buttons stay in place so
                // the D-pad landscape doesn't shift under the kid's focus.
                IconButton(
                    onClick = { onMove(item, -1) },
                ) { Text("▲") }
                IconButton(
                    onClick = { onMove(item, +1) },
                ) { Text("▼") }
                IconButton(
                    onClick = {
                        val left = videos.size - 1
                        if (left > 0) refocusRemove = i.coerceAtMost(left - 1) to left
                        onRemove(item)
                    },
                    modifier = Modifier.focusRequester(removeFocus)
                ) { Text("✕") }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun VideoGrid(
    videos: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    emptyText: String = "Nothing here yet.",
    loadingMore: Boolean = false,
    watchlisted: Set<String> = emptySet(),
    onToggleWatchlist: ((VideoItem) -> Unit)? = null,
    watchLater: Set<String> = emptySet(),
    onToggleWatchLater: ((VideoItem) -> Unit)? = null,
    downloadPending: Set<String> = emptySet(),
    downloaded: Set<String> = emptySet(),
    /** Non-null on phones: adds the download row to the hold menu and the
     *  passive ⏳/✅ status light on posters. */
    onToggleDownload: ((VideoItem) -> Unit)? = null,
    queued: Set<String> = emptySet(),
    onToggleQueue: ((VideoItem) -> Unit)? = null,
    /** Hold-menu row that marks a video watched, or puts it back. */
    onToggleWatched: ((VideoItem) -> Unit)? = null,
    /** Hold-menu row where a kid asks for a video to be held for the parent. */
    onReport: ((VideoItem) -> Unit)? = null,
    grabFocus: Boolean = false,
    onNearEnd: (() -> Unit)? = null,
    /**
     * A non-video tile dropped in at this index — the channel's "Watched"
     * shelf. Pinned mid-grid on purpose (see [UiState.watchedTileAt]), so it
     * is not simply appended at the end.
     */
    extraTileAt: Int? = null,
    /** Handed a focus requester only when it leads the grid, so a channel with
     *  nothing unwatched still has somewhere for the remote to land. */
    extraTile: (@Composable (androidx.compose.ui.focus.FocusRequester?) -> Unit)? = null,
    /** One-shot jump, see [UiState.scrollTo]. Report back via [onScrolled]. */
    scrollTo: Int? = null,
    onScrolled: (() -> Unit)? = null,
    /** Full-width content above the tiles that scrolls away with them. */
    header: (@Composable () -> Unit)? = null
) {
    // A channel whose every video has been watched still needs its shelf: the
    // grid is empty, but the way to the watched ones must not vanish with it.
    // A header keeps the grid too, and says [emptyText] from inside it: switching
    // layouts when the first video lands would rebuild the header under the
    // kid's focus.
    if (videos.isEmpty() && extraTile == null && header == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                emptyText,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    // TV: land focus on the first tile when the grid gets content — never the cog.
    val firstTileFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (grabFocus) {
        LaunchedEffect(videos.isNotEmpty()) {
            runCatching { firstTileFocus.requestFocus() }
        }
    }
    // Which video's action menu is open (null = none). One gesture on every
    // form factor — touch long-press or held OK on the remote — and every
    // action is a big labeled row, not a fingertip-sized poster corner. A
    // menu is also more forgiving than the old silent long-press toggle: an
    // accidental hold now shows choices instead of quietly unsaving a video.
    var menuFor by remember { mutableStateOf<VideoItem?>(null) }
    // Asked twice on purpose: a report hides the video until a parent rules on it.
    var reportFor by remember { mutableStateOf<VideoItem?>(null) }
    reportFor?.let { item ->
        AlertDialog(
            onDismissRequest = { reportFor = null },
            shape = androidx.compose.ui.graphics.RectangleShape,
            title = { Text("Report this video?") },
            text = {
                Text(
                    "It will be hidden until a grown-up decides whether it's okay.",
                    modifier = Modifier.ignoreSelectUntilRelease()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onReport?.invoke(item); reportFor = null },
                ) { Text("Report") }
            },
            dismissButton = {
                TextButton(
                    onClick = { reportFor = null },
                ) { Text("Cancel") }
            }
        )
    }
    menuFor?.let { item ->
        val firstAction = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(item) { runCatching { firstAction.requestFocus() } }
        AlertDialog(
            onDismissRequest = { menuFor = null },
            // Square, like every tile and card in the app: the focus ring is a
            // hard rectangle, and a rounded container leaves it cutting the
            // corner of the top row.
            shape = androidx.compose.ui.graphics.RectangleShape,
            title = { MarqueeTitle(item.video.title, focused = false) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    // The hold that opened this menu is still in flight; its
                    // release belongs to nothing here.
                    modifier = Modifier.ignoreSelectUntilRelease()
                ) {
                    // Podcast episodes get every row but downloads (below).
                    val podcast = PodcastPaths.isPodcast(item.video.url)
                    TextButton(
                        onClick = { onToggleQueue?.invoke(item); menuFor = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(firstAction)
                    ) {
                        Text(
                            if (item.video.url in queued) "➖  Remove from Up next"
                            else "➕  Add to Up next"
                        )
                    }
                    TextButton(
                        onClick = { onToggleWatchlist?.invoke(item); menuFor = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (item.video.url in watchlisted) "💔  Remove from Favorites"
                            else "❤️  Add to Favorites"
                        )
                    }
                    TextButton(
                        onClick = { onToggleWatchLater?.invoke(item); menuFor = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (item.video.url in watchLater) "➖  Remove from Watch later"
                            else "🕒  Add to Watch later"
                        )
                    }
                    if (onToggleWatched != null) {
                        // Two jobs, one row: retire a video the kid is done
                        // with (it leaves the grid for the Watched shelf), and
                        // undo a stray mark or a video the app called finished
                        // because it was left running.
                        val seen = (item.progress ?: 0f) >= 0.98f
                        TextButton(
                            onClick = { onToggleWatched(item); menuFor = null },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val verb = if (podcast) "listened" else "watched"
                            Text(if (seen) "↩️  Move back to not $verb" else "✔️  Mark as $verb")
                        }
                    }
                    // Only YouTube videos: local, network and podcast items have
                    // no id the parent's review queue could act on.
                    if (onReport != null && item.video.videoId?.length == 11 &&
                        !io.pickwick.app.data.SmbPaths.isNetwork(item.video.url) && !podcast
                    ) {
                        TextButton(
                            onClick = { reportFor = item; menuFor = null },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("🚩  Report this video") }
                    }
                    if (onToggleDownload != null && !io.pickwick.app.data.SmbPaths.isNetwork(item.video.url) && !podcast) {
                        val url = item.video.url
                        TextButton(
                            onClick = { onToggleDownload(item); menuFor = null },
                            // Deleting a finished download is the parent's job
                            // in settings — the row goes inert, not hidden, so
                            // the state is still readable here.
                            enabled = url !in downloaded,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when {
                                    url in downloaded -> "✅  Downloaded"
                                    url in downloadPending -> "⏳  Cancel download request"
                                    else -> "⬇️  Ask to download"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
    val gridState = rememberLazyGridState()
    // Instant, never animated: the kid didn't ask for the trip, they asked to
    // be somewhere. Runs after the new list is in place, so the index means
    // what the caller intended.
    LaunchedEffect(scrollTo) {
        if (scrollTo != null) {
            runCatching { gridState.scrollToItem(scrollTo) }
            onScrolled?.invoke()
        }
    }
    // A lone header is always "near the end", and search pays the AI per near-end
    // call, so nothing fires until there are videos to be near the end of.
    if (onNearEnd != null && (videos.isNotEmpty() || header == null)) {
        // Fire on every scroll-position change, not on a boolean edge: if one page
        // fetch fails, the next nudge of the scroll retries instead of going dead.
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { lastVisible ->
                    if (lastVisible >= 0 && lastVisible >= gridState.layoutInfo.totalItemsCount - 6) {
                        onNearEnd()
                    }
                }
        }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = CatalogGridMinWidth),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // Room for the focus glow on edge tiles.
        contentPadding = PaddingValues(8.dp),
        // Held D-pad browsing (any direction) advances at a readable, steady
        // pace instead of one step per ~50ms key repeat, which outruns both the
        // reader and tile composition (worst going up/left — nothing behind the
        // pivot is pre-composed, so unpaced steps stick mid-scroll).
        modifier = Modifier.dpadHeldScrollThrottle()
    ) {
        if (header != null) item(key = "grid-header", span = { GridItemSpan(maxLineSpan) }) {
            header()
        }
        if (videos.isEmpty() && extraTile == null) item(key = "grid-empty", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                emptyText,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp)
            )
        }
        // Split around the injected tile rather than appending it: its index is
        // pinned to the first page, and pages loaded later join the tail below.
        val cut = extraTileAt?.coerceIn(0, videos.size)?.takeIf { extraTile != null }
        val head = if (cut == null) videos else videos.take(cut)
        val tail = if (cut == null) emptyList() else videos.drop(cut)
        items(head, key = { it.video.url }) { item ->
            VideoTile(
                item = item,
                focusRequester = firstTileFocus.takeIf { grabFocus && item == videos.first() },
                onPlay = onPlay,
                onOpenMenu = if (onToggleWatchlist != null) ({ menuFor = it }) else null,
                downloadPending = downloadPending,
                downloaded = downloaded,
                showDownloadStatus = onToggleDownload != null
            )
        }
        if (cut != null) item(key = "extra-tile", span = { GridItemSpan(1) }) {
            extraTile!!(firstTileFocus.takeIf { grabFocus && head.isEmpty() })
        }
        items(tail, key = { it.video.url }) { item ->
            VideoTile(
                item = item,
                focusRequester = null,
                onPlay = onPlay,
                onOpenMenu = if (onToggleWatchlist != null) ({ menuFor = it }) else null,
                downloadPending = downloadPending,
                downloaded = downloaded,
                showDownloadStatus = onToggleDownload != null
            )
        }
        if (loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
        }
    }
}

/**
 * The way into a channel's watched videos, sized and shaped like a poster so
 * it flows with the grid instead of breaking the row it sits in.
 */
@Composable
internal fun WatchedShelfTile(
    count: Int,
    /** Set only when the tile leads the grid — see [VideoGrid]'s TV focus. */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    /** Podcasts say "Listened": nothing on that shelf was watched. */
    label: String = "Watched",
    subtitle: String = "Ones you've finished",
    onOpen: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        shape = androidx.compose.ui.graphics.RectangleShape,
        colors = tvTileColors(focused),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onTileFocus { focused = it }
            .clickable { onOpen() }
            .tvFocusLift(focused)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("✔", style = MaterialTheme.typography.headlineLarge)
                // Same red as every watched bar in the app, so the tile reads
                // as "the watched ones" without needing to be read.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(WatchedProgressRed)
                )
            }
            Column(Modifier.padding(8.dp)) {
                MarqueeTitle("$label ($count)", focused)
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = tvTileSubtitleColor(focused)
                )
            }
        }
    }
}

/** One poster in the grid. Extracted so a non-video tile can sit among them. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun VideoTile(
    item: VideoItem,
    focusRequester: androidx.compose.ui.focus.FocusRequester?,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: ((VideoItem) -> Unit)?,
    downloadPending: Set<String>,
    downloaded: Set<String>,
    showDownloadStatus: Boolean
) {
    // Tap/OK plays; hold (touch long-press or held OK on the remote)
    // opens the action menu.
    var focused by remember { mutableStateOf(false) }
    val focusMod = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
    // An offline tile stays focusable (the D-pad must be able to pass over it)
    // but does nothing: no play, no menu.
    val offline = item.video.offline
    val cardModifier = if (onOpenMenu != null) {
        focusMod
            .onTileFocus { focused = it }
            .dpadLongPress { if (!offline) onOpenMenu(item) }
            .combinedClickable(
                onClick = { if (!offline) onPlay(item) },
                onLongClick = { if (!offline) onOpenMenu(item) }
            )
    } else {
        focusMod.onTileFocus { focused = it }.clickable { if (!offline) onPlay(item) }
    }
    // Watched videos stay browsable (kids rewatch) but recede: dimmed
    // unless focused, plus their full red bar below.
    val finished = (item.progress ?: 0f) >= 0.98f
    Card(
        shape = androidx.compose.ui.graphics.RectangleShape,
        colors = tvTileColors(focused),
        modifier = cardModifier.tvFocusLift(focused).graphicsLayer {
            alpha = if (offline) 0.4f else if (finished && !focused) 0.5f else 1f
        }
    ) {
        Column {
            Box {
                if (PodcastPaths.isPodcast(item.video.url)) {
                    // Podcast art is square and usually carries the show's name
                    // as text; a 16:9 crop would cut it off. Same tile geometry,
                    // art shown whole over a dimmed, blurred fill of itself.
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(androidx.compose.ui.graphics.RectangleShape)) {
                        // One painter for both layers: two AsyncImages would each
                        // download an uncached image, as Coil doesn't merge requests.
                        val art = coil.compose.rememberAsyncImagePainter(
                            model = item.video.thumbnailUrl,
                            contentScale = ContentScale.Fit
                        )
                        androidx.compose.foundation.Image(
                            painter = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().blur(24.dp).graphicsLayer { alpha = 0.45f }
                        )
                        androidx.compose.foundation.Image(
                            painter = art,
                            contentDescription = item.video.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else AsyncImage(
                    model = item.video.thumbnailUrl,
                    contentDescription = item.video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                if (showDownloadStatus) {
                    // Status light, never a control (all actions live
                    // in the hold menu). Downloads are the one async
                    // flow — ask, parent approves, fetch — and with no
                    // visible ⏳/✅ a kid who sees nothing happen just
                    // asks again and again.
                    val active by DownloadEvents.progress.collectAsState()
                    val fraction = active?.takeIf { it.first == item.video.url }?.second
                    when {
                        item.video.url in downloaded -> PosterBadge(
                            label = "✅",
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        )
                        fraction != null -> Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color(0x66000000))
                                .padding(6.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                                trackColor = Color(0x40FFFFFF)
                            )
                        }
                        item.video.url in downloadPending -> PosterBadge(
                            label = "⏳",
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        )
                    }
                }
                if (item.video.live || offline) {
                    Text(
                        if (offline) "Offline" else "LIVE",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(
                                if (offline) Color(0xCC000000) else Color(0xFFD32F2F),
                                androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                // YouTube-style watched-progress bar.
                item.progress?.let { fraction -> WatchedProgressBar(fraction) }
            }
            Column(Modifier.padding(8.dp)) {
                MarqueeTitle(item.video.title, focused)
                Text(item.video.channelName, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = tvTileSubtitleColor(focused))
            }
        }
    }
}
