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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.data.*

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
    androidx.compose.foundation.lazy.LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(videos.size, key = { videos[it].video.url }) { i ->
            val item = videos[i]
            var focused by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = (if (grabFocus && i == 0) Modifier.focusRequester(firstRowFocus)
                    else Modifier)
                    .fillMaxWidth()
                    .tvFocusHighlight { focused = it }
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
                // Ends are no-ops (the store clamps) — buttons stay in place so
                // the D-pad landscape doesn't shift under the kid's focus.
                IconButton(
                    onClick = { onMove(item, -1) },
                    modifier = Modifier.tvFocusHighlight()
                ) { Text("▲") }
                IconButton(
                    onClick = { onMove(item, +1) },
                    modifier = Modifier.tvFocusHighlight()
                ) { Text("▼") }
                IconButton(
                    onClick = { onRemove(item) },
                    modifier = Modifier.tvFocusHighlight()
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
    downloadPending: Set<String> = emptySet(),
    downloaded: Set<String> = emptySet(),
    /** Non-null on phones: adds the download row to the hold menu and the
     *  passive ⏳/✅ status light on posters. */
    onToggleDownload: ((VideoItem) -> Unit)? = null,
    queued: Set<String> = emptySet(),
    onToggleQueue: ((VideoItem) -> Unit)? = null,
    grabFocus: Boolean = false,
    onNearEnd: (() -> Unit)? = null
) {
    if (videos.isEmpty()) {
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
    menuFor?.let { item ->
        val firstAction = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(item) { runCatching { firstAction.requestFocus() } }
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { MarqueeTitle(item.video.title, focused = false) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onToggleQueue?.invoke(item); menuFor = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(firstAction)
                            .tvFocusHighlight()
                    ) {
                        Text(
                            if (item.video.url in queued) "➖  Remove from Up next"
                            else "➕  Add to Up next"
                        )
                    }
                    TextButton(
                        onClick = { onToggleWatchlist?.invoke(item); menuFor = null },
                        modifier = Modifier.fillMaxWidth().tvFocusHighlight()
                    ) {
                        Text(
                            if (item.video.url in watchlisted) "💔  Remove from My list"
                            else "❤️  Add to My list"
                        )
                    }
                    if (onToggleDownload != null) {
                        val url = item.video.url
                        TextButton(
                            onClick = { onToggleDownload(item); menuFor = null },
                            // Deleting a finished download is the parent's job
                            // in settings — the row goes inert, not hidden, so
                            // the state is still readable here.
                            enabled = url !in downloaded,
                            modifier = Modifier.fillMaxWidth().tvFocusHighlight()
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
    if (onNearEnd != null) {
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
        columns = GridCells.Adaptive(minSize = 240.dp),
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
        items(videos, key = { it.video.url }) { item ->
            // Tap/OK plays; hold (touch long-press or held OK on the remote)
            // opens the action menu.
            var focused by remember { mutableStateOf(false) }
            val focusMod = if (grabFocus && item == videos.first()) {
                Modifier.focusRequester(firstTileFocus)
            } else Modifier
            val cardModifier = if (onToggleWatchlist != null) {
                focusMod
                    .tvFocusHighlight { focused = it }
                    .dpadLongPress { menuFor = item }
                    .combinedClickable(
                        onClick = { onPlay(item) },
                        onLongClick = { menuFor = item }
                    )
            } else {
                focusMod.tvFocusHighlight { focused = it }.clickable { onPlay(item) }
            }
            // Watched videos stay browsable (kids rewatch) but recede: dimmed
            // unless focused, plus their full red bar below.
            val finished = (item.progress ?: 0f) >= 0.98f
            Card(
                shape = androidx.compose.ui.graphics.RectangleShape,
                modifier = cardModifier.graphicsLayer {
                    alpha = if (finished && !focused) 0.5f else 1f
                }
            ) {
                Column {
                    Box {
                        AsyncImage(
                            model = item.video.thumbnailUrl,
                            contentDescription = item.video.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        if (onToggleDownload != null) {
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
                        // YouTube-style watched-progress bar.
                        item.progress?.let { fraction -> WatchedProgressBar(fraction) }
                    }
                    Column(Modifier.padding(8.dp)) {
                        MarqueeTitle(item.video.title, focused)
                        Text(item.video.channelName, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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
