package io.pickwick.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.pickwick.app.data.*

/**
 * TV D-pad scrolling: the default bring-into-view spec scrolls only when the
 * focused item reaches the container's edge, so the grid sits still and then
 * lurches a whole row at once. This pivot spec instead keeps the focused item
 * anchored ~30% from the leading edge and animates every step — steady, smooth
 * motion (the same approach as Compose's TV libraries).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal class TvPivotBringIntoView(
    /** Where the focused item's leading edge settles, as a fraction of the container. */
    private val pivot: Float
) : androidx.compose.foundation.gestures.BringIntoViewSpec {

    // Spring, not tween: holding the D-pad interrupts the animation on every
    // step, and a tween restarts from zero velocity each time (a pulsing,
    // stop-start feel). A no-bounce spring carries its velocity into the new
    // target, so held-key scrolling glides at a steady rate — the Netflix feel.
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        )

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val target = pivot * containerSize
        val leadingEdge = if (size <= containerSize && containerSize - target < size) {
            containerSize - size // near the end: don't overscroll past the content
        } else target
        return offset - leadingEdge
    }
}

/** Vertical browsing: the focused row settles a quarter down the screen. */
private val TvColumnPivot = TvPivotBringIntoView(0.25f)

/**
 * Inside a row: the focused tile stays pinned left-of-center. Not tighter than
 * this: with the pivot hugging the left edge there are no composed tiles to the
 * left of focus, so a held LEFT press must compose each tile inside the ~50ms
 * key-repeat window — which the TV can't do, making left-hold stutter while
 * right-hold (90% of the row pre-composed) glides. 25% keeps a runway on both sides.
 */
internal val TvRowPivot = TvPivotBringIntoView(0.25f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickwickScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    /** Who this home screen belongs to; null before profiles exist. */
    activeProfile: io.pickwick.app.data.Profile? = null,
    /** Non-null on shared devices with 2+ kids: header avatar re-opens the picker. */
    onSwitchProfile: (() -> Unit)? = null,
    /** Starts the Up next queue from this position in the visible lineup. */
    onPlayQueue: (Int) -> Unit = {},
    onPlay: (VideoItem) -> Unit
) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val isTv = remember {
        (context.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    BackHandler(enabled = state.screen != Screen.Home) { vm.goHome() }

    // Coming back from the player: re-read progress so bars/filters update.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    vm.refreshProgress()
                    // Fresh progress after watching — share it with paired devices.
                    vm.syncWatchState()
                }
                // App returned to foreground: pick up any whitelist edits.
                Lifecycle.Event.ON_START -> vm.refreshIfIdle()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides
                if (isTv) TvColumnPivot
                else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current,
            // No touch on TV → the overscroll stretch shader is pure render cost
            // at every list edge. Off.
            androidx.compose.foundation.LocalOverscrollConfiguration provides
                if (isTv) null
                else androidx.compose.foundation.LocalOverscrollConfiguration.current
        ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Something went wrong — tell a parent!\n\n${state.error}")
                }
                state.screen is Screen.Home -> {
                    // TV gets the ten-foot layout: horizontal rows under a pinned
                    // focus, like every streaming app's browse screen. No touch, so
                    // no pull-to-refresh either (auto + poll refresh cover it).
                    if (isTv) {
                        TvHomeRows(
                            channels = state.channels,
                            newBadges = state.newBadges,
                            keepWatching = state.keepWatching,
                            onPlay = onPlay,
                            onDismissKeepWatching = vm::dismissKeepWatching,
                            onOpen = vm::openChannel,
                            onSurprise = vm::surpriseMe,
                            onOpenWatchlist = vm::openWatchlist,
                            hasQueue = state.queued.isNotEmpty(),
                            onOpenQueue = vm::openQueue,
                            onOpenSettings = onOpenSettings,
                            activeProfile = activeProfile,
                            onSwitchProfile = onSwitchProfile
                        )
                    } else {
                        PullToRefreshBox(
                            isRefreshing = state.refreshing,
                            onRefresh = { vm.refresh(userInitiated = true) },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            ChannelGrid(
                                state.channels,
                                newBadges = state.newBadges,
                                keepWatching = state.keepWatching,
                                onPlay = onPlay,
                                onDismissKeepWatching = vm::dismissKeepWatching,
                                onOpen = vm::openChannel,
                                onSurprise = vm::surpriseMe,
                                onOpenWatchlist = vm::openWatchlist,
                                hasQueue = state.queued.isNotEmpty(),
                                onOpenQueue = vm::openQueue,
                                hasDownloads = state.downloaded.isNotEmpty(),
                                onOpenDownloads = vm::openDownloads,
                                onOpenSettings = onOpenSettings,
                                activeProfile = activeProfile,
                                onSwitchProfile = onSwitchProfile
                            )
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    val title = when (val s = state.screen) {
                        is Screen.ChannelVideos -> s.source.name
                        is Screen.Surprise -> "🎲 Surprise!"
                        is Screen.Watchlist -> "❤️ My list"
                        is Screen.Downloads -> "⬇️ Downloads"
                        is Screen.Queue -> "📚 Up next"
                        else -> ""
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        val screen = state.screen
                        if (screen is Screen.ChannelVideos &&
                            screen.source.kind == SourceKind.PLAYLIST &&
                            state.videos.isNotEmpty()
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    // Resume mid-video if one is in progress, else the next
                                    // unwatched — never a finished one (they're visible now).
                                    val next = state.videos.firstOrNull {
                                        it.progress != null && it.progress < 0.98f
                                    }
                                        ?: state.videos.firstOrNull { it.progress == null }
                                        ?: state.videos.first()
                                    onPlay(next)
                                },
                                label = { Text("▶ Continue") },
                                modifier = Modifier.tvFocusHighlight()
                            )
                        }
                        if (screen is Screen.Queue && state.videos.isNotEmpty()) {
                            FilterChip(
                                selected = false,
                                onClick = { onPlayQueue(0) },
                                label = { Text("▶ Play") },
                                modifier = Modifier.tvFocusHighlight()
                            )
                        }
                    }
                    if (state.screen is Screen.Queue) {
                        QueueList(
                            state.videos,
                            onPlayFrom = onPlayQueue,
                            onMove = vm::moveQueue,
                            onRemove = vm::removeFromQueue,
                            grabFocus = isTv
                        )
                    } else {
                    VideoGrid(
                        state.videos,
                        onPlay = onPlay,
                        // An all-held source must say so — a silently empty grid
                        // reads as broken to the kid and the parent alike.
                        emptyText = if (state.held > 0) {
                            "${state.held} video(s) here are waiting for a parent's OK.\n" +
                                "A parent can allow them from the app on their phone\n" +
                                "(the kid device's page, under \"AI screening\")."
                        } else "Nothing here yet.",
                        loadingMore = state.loadingMore,
                        watchlisted = state.watchlisted,
                        onToggleWatchlist = vm::toggleWatchlist,
                        downloadPending = state.downloadPending,
                        downloaded = state.downloaded,
                        // Downloads are phone-only: the TV stays on home Wi-Fi.
                        onToggleDownload = if (isTv) null else vm::toggleDownload,
                        queued = state.queued,
                        onToggleQueue = vm::toggleQueue,
                        grabFocus = isTv,
                        onNearEnd = if (state.screen is Screen.ChannelVideos) vm::loadMoreUploads else null
                    )
                    }
                }
            }
        }
        }
    }
}
