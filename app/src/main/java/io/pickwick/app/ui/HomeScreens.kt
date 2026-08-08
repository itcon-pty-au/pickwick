package io.pickwick.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.data.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KeepWatchingRow(
    items: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    onDismiss: (VideoItem) -> Unit
) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // Room for the focus glow so it isn't clipped by the row bounds.
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        // Held ◀/▶ paces itself so leftward steps (nothing pre-composed behind
        // the pivot) stop sticking mid-scroll.
        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
    ) {
        items(items.size, key = { items[it].video.url }) { index ->
            val item = items[index]
            var focused by remember { mutableStateOf(false) }
            Card(
                shape = androidx.compose.ui.graphics.RectangleShape,
                modifier = Modifier
                    .tvFocusHighlight { focused = it }
                    // Touch long-press and remote hold-OK both dismiss the tile.
                    .dpadLongPress { onDismiss(item) }
                    .combinedClickable(
                        onClick = { onPlay(item) },
                        onLongClick = { onDismiss(item) }
                    )
                    .width(150.dp)
            ) {
                Column {
                    Box {
                        AsyncImage(
                            model = item.video.thumbnailUrl,
                            contentDescription = item.video.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        item.progress?.let { fraction -> WatchedProgressBar(fraction) }
                    }
                    Box(Modifier.padding(8.dp)) {
                        MarqueeTitle(item.video.title, focused,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChannelGrid(
    channels: List<Source>,
    newBadges: Set<String> = emptySet(),
    keepWatching: List<VideoItem> = emptyList(),
    onPlay: (VideoItem) -> Unit = {},
    onDismissKeepWatching: (VideoItem) -> Unit = {},
    onOpen: (Source) -> Unit,
    onSurprise: () -> Unit,
    onOpenWatchlist: () -> Unit,
    hasQueue: Boolean = false,
    onOpenQueue: () -> Unit = {},
    hasDownloads: Boolean = false,
    onOpenDownloads: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    activeProfile: io.pickwick.app.data.Profile? = null,
    onSwitchProfile: (() -> Unit)? = null,
    onSearch: (String) -> Unit = {}
) {
    if (channels.isEmpty()) {
        EmptyHome(onOpenSettings)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // Room for the focus glow on edge tiles.
        contentPadding = PaddingValues(8.dp)
    ) {
        // Branding + settings scroll away like everything else — content is king.
        item(key = "app-header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                HomeHeader(onOpenSettings, activeProfile, onSwitchProfile)
                SearchBar(isTv = false, onSearch = onSearch)
            }
        }
        // Keep-watching scrolls away with the rest — not sticky.
        if (keepWatching.isNotEmpty()) {
            item(key = "kw-title", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Keep watching",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                )
            }
            item(key = "kw-row", span = { GridItemSpan(maxLineSpan) }) {
                KeepWatchingRow(keepWatching, onPlay = onPlay, onDismiss = onDismissKeepWatching)
            }
        }
        // First tile: Surprise — same size and shape as a channel tile.
        item(key = "surprise-tile") {
            SurpriseTile(onClick = onSurprise)
        }
        // The lined-up videos, only while there are any — an empty queue tile
        // would just be a dead end for the kid.
        if (hasQueue) {
            item(key = "queue-tile") {
                QueueTile(onClick = onOpenQueue)
            }
        }
        // Second tile: the kid's saved-for-later list.
        item(key = "watchlist-tile") {
            WatchlistTile(onClick = onOpenWatchlist)
        }
        // The offline shelf appears once the first download lands.
        if (hasDownloads) {
            item(key = "downloads-tile") {
                SpecialTile(
                    emoji = "⬇️",
                    label = "Downloads",
                    circleColor = DownloadsTileTeal,
                    onClick = onOpenDownloads
                )
            }
        }
        items(channels, key = { it.id }) { channel ->
            ChannelTile(channel, isNew = channel.id in newBadges, onOpen = onOpen)
        }
    }
}

@Composable
private fun ChannelTile(
    channel: Source,
    isNew: Boolean,
    onOpen: (Source) -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        shape = androidx.compose.ui.graphics.RectangleShape,
        modifier = modifier
            .tvFocusHighlight { focused = it }
            .clickable { onOpen(channel) }
    ) {
        Column {
            // Full-bleed cover, video-tile style (1:1 — avatars are square).
            Box {
                AsyncImage(
                    model = channel.avatarUrl,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
                if (isNew) {
                    Text(
                        "NEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color(0xFF4DB6AC))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                // The screen-time "price tag" — shown only when it differs from
                // normal, so kids can pick cheap/free channels knowingly.
                timeMultiplierColor(channel.timeMultiplierPercent)?.let { color ->
                    Text(
                        timeMultiplierLabel(channel.timeMultiplierPercent),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(color)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Box(Modifier.padding(8.dp)) {
                MarqueeTitle(
                    text = channel.name +
                        if (channel.kind == SourceKind.PLAYLIST) "  ·  playlist" else "",
                    focused = focused
                )
            }
        }
    }
}

/**
 * First-run screen: without it a fresh install is a dead end — the settings
 * entry lives in the home header, which only renders once channels exist.
 * The button auto-focuses so a TV remote can open settings with one press.
 */
@Composable
private fun EmptyHome(onOpenSettings: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            "No channels yet",
            style = MaterialTheme.typography.headlineSmall
        )
        Text("Add channels in parent settings to fill this screen.")
        Button(
            onClick = onOpenSettings,
            modifier = Modifier
                .focusRequester(focus)
                .tvFocusHighlight()
        ) {
            Text("Parent settings")
        }
    }
}

@Composable
private fun HomeHeader(
    onOpenSettings: () -> Unit,
    activeProfile: io.pickwick.app.data.Profile? = null,
    onSwitchProfile: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // Mini logo mark: dark teal square, white play triangle — the
            // launcher tile at header scale.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(0xFF00695C))
            ) {
                // Drawn, not the "▶" glyph: font side bearings and line-height
                // padding left that mark visibly off-centre in the tile. These
                // fractions are ic_launcher.xml's triangle scaled to the tile,
                // so the centroid — not the bounding box — lands on the centre,
                // which is what the eye reads as centred.
                Canvas(Modifier.size(30.dp)) {
                    val w = size.width
                    val h = size.height
                    val play = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.38f, h * 0.28f)
                        lineTo(w * 0.76f, h * 0.50f)
                        lineTo(w * 0.38f, h * 0.72f)
                        close()
                    }
                    drawPath(play, Color.White)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "Pickwick",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.5f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
            )
        }
        // Whose home this is — always visible so a wrong pick gets noticed.
        // On shared devices it's also the way back to the who's-watching screen.
        activeProfile?.let { profile ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .let { m ->
                        if (onSwitchProfile != null) {
                            m.tvFocusHighlight()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                                .clickable { onSwitchProfile() }
                        } else m
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                ProfileAvatar(profile, size = 32)
                Spacer(Modifier.width(6.dp))
                Text(profile.name, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(6.dp))
        }
        IconButton(
            modifier = Modifier.size(48.dp),
            onClick = onOpenSettings
        ) {
            val updatePending by UpdateEvents.pending.collectAsState()
            Box {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(32.dp)
                )
                // Quiet "a newer build exists" nudge for the parent; settings
                // itself stays behind the PIN, so kids tapping it learn nothing.
                if (updatePending != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(8.dp)
                            .background(UpdateDot, androidx.compose.foundation.shape.CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * The ten-foot home: a vertical stack of horizontal rows — Keep watching,
 * Channels (most-watched first), Explore — with the focused tile pinned near
 * the left edge of its row. The row structure plus the pivot scroll specs is
 * what gives streaming apps their steady, predictable D-pad motion.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TvHomeRows(
    channels: List<Source>,
    newBadges: Set<String>,
    keepWatching: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    onDismissKeepWatching: (VideoItem) -> Unit,
    onOpen: (Source) -> Unit,
    onSurprise: () -> Unit,
    onOpenWatchlist: () -> Unit,
    hasQueue: Boolean = false,
    onOpenQueue: () -> Unit = {},
    onOpenSettings: () -> Unit,
    activeProfile: io.pickwick.app.data.Profile? = null,
    onSwitchProfile: (() -> Unit)? = null,
    onSearch: (String) -> Unit = {}
) {
    if (channels.isEmpty()) {
        EmptyHome(onOpenSettings)
        return
    }
    val firstTileFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTileFocus.requestFocus() } }

    androidx.compose.foundation.lazy.LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item(key = "header") {
            Column {
                HomeHeader(onOpenSettings, activeProfile, onSwitchProfile)
                SearchBar(isTv = true, onSearch = onSearch)
            }
        }

        if (keepWatching.isNotEmpty()) {
            item(key = "kw") {
                Column {
                    TvRowTitle("Keep watching")
                    CompositionLocalProvider(
                        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
                    ) {
                        KeepWatchingRow(keepWatching, onPlay = onPlay, onDismiss = onDismissKeepWatching)
                    }
                }
            }
        }

        item(key = "channels") {
            Column {
                TvRowTitle("Channels")
                CompositionLocalProvider(
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
                    ) {
                        items(channels.size, key = { channels[it].id }) { i ->
                            ChannelTile(
                                channels[i],
                                isNew = channels[i].id in newBadges,
                                onOpen = onOpen,
                                modifier = (if (i == 0) Modifier.focusRequester(firstTileFocus)
                                    else Modifier).width(150.dp)
                            )
                        }
                    }
                }
            }
        }

        item(key = "explore") {
            Column {
                TvRowTitle("Explore")
                CompositionLocalProvider(
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        // Lined-up videos lead the row while any exist.
                        if (hasQueue) {
                            item(key = "queue") {
                                QueueTile(Modifier.width(150.dp), onOpenQueue)
                            }
                        }
                        item(key = "surprise") {
                            SurpriseTile(Modifier.width(150.dp), onSurprise)
                        }
                        item(key = "watchlist") {
                            WatchlistTile(Modifier.width(150.dp), onOpenWatchlist)
                        }
                    }
                }
            }
        }
    }
}

// One spelling per special tile: the phone grid and the TV row draw the same
// three, and a repaint of one layout must not drift from the other.
@Composable
private fun SurpriseTile(modifier: Modifier = Modifier, onClick: () -> Unit) =
    SpecialTile("🎲", "Surprise me!", SurpriseTileCyan, modifier = modifier, onClick = onClick)

@Composable
private fun QueueTile(modifier: Modifier = Modifier, onClick: () -> Unit) =
    SpecialTile("📚", "Up next", QueueTilePurple, modifier = modifier, onClick = onClick)

@Composable
private fun WatchlistTile(modifier: Modifier = Modifier, onClick: () -> Unit) =
    SpecialTile("❤️", "My list", WatchlistTileTeal, modifier = modifier, onClick = onClick)

@Composable
private fun TvRowTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        ),
        modifier = Modifier.padding(start = 8.dp, top = 12.dp)
    )
}
