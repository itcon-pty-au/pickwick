package io.pickwick.app.ui

import io.pickwick.app.data.*

sealed interface Screen {
    data object Home : Screen
    data class ChannelVideos(val source: Source) : Screen
    /**
     * One channel's finished videos, off the main grid. The only two-level
     * screen in the app — back returns to the channel, not home.
     */
    data class WatchedVideos(val source: Source) : Screen
    /** Random mix across all whitelisted sources. */
    data object Surprise : Screen
    /** The kid's hearted videos ("Favorites" on screen). */
    data object Watchlist : Screen
    /** Videos the kid lined up for another day. */
    data object WatchLater : Screen
    /** Parent-approved videos stored on the device — the offline shelf. */
    data object Downloads : Screen
    /** The kid's lined-up videos for one sitting, in play order. */
    data object Queue : Screen
    /** Results of a whitelist-scoped search. */
    data class SearchResults(val query: String) : Screen
}

/** A video plus its local watch progress (0..1), null if never watched. */
data class VideoItem(val video: Video, val progress: Float?)

data class UiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    /** A whitelist re-fetch is in flight (drives the pull-to-refresh spinner). */
    val refreshing: Boolean = false,
    val error: String? = null,
    val channels: List<Source> = emptyList(),
    val screen: Screen = Screen.Home,
    val videos: List<VideoItem> = emptyList(),
    /**
     * Videos on this screen hidden by AI screening (awaiting a verdict or held
     * for parent review). Lets an all-held source explain itself instead of
     * rendering as inexplicably empty.
     */
    val held: Int = 0,
    /** Partially-watched videos, most recent first (home-screen resume row). */
    val keepWatching: List<VideoItem> = emptyList(),
    /** Source ids with uploads the kid hasn't seen yet. */
    val newBadges: Set<String> = emptySet(),
    /** Video URLs the kid has hearted (drives the hold-menu row label). */
    val watchlisted: Set<String> = emptySet(),
    /** Video URLs saved for another day (drives the hold-menu row label and the Watch later tile). */
    val watchLater: Set<String> = emptySet(),
    /** Video URLs lined up to play next (drives the hold-menu row label and the Up next tile). */
    val queued: Set<String> = emptySet(),
    /** Video URLs requested/approved/fetching — not yet playable offline (⏳). */
    val downloadPending: Set<String> = emptySet(),
    /** Video URLs fully on disk, playable without a network (✅). */
    val downloaded: Set<String> = emptySet(),
    /** Live-screening progress on the search screen; null when nothing is in flight. */
    val searchScreening: SearchScreening? = null,
    /** Transient kid-facing pill (e.g. a save request the deep check refused);
     *  cleared by the ViewModel after a few seconds. */
    val notice: String? = null,
    /**
     * This channel's finished videos, held out of [videos] so the unwatched
     * ones are the whole grid. Feeds the "Watched" tile and the screen behind
     * it; empty on every other screen.
     */
    val channelWatched: List<VideoItem> = emptyList(),
    /**
     * Where the "Watched" tile sits in [videos] — pinned to the end of the
     * first page rather than the end of the list, so it stays a screenful from
     * the top instead of drifting behind hundreds of older videos as more
     * pages load. Null until the first page has landed.
     */
    val watchedTileAt: Int? = null,
    /**
     * A one-shot instruction to put the grid at this item. The channel and its
     * Watched shelf share one grid, so without it the shelf would open at
     * whatever depth the channel was scrolled to. Cleared once obeyed.
     */
    val scrollTo: Int? = null
)

/**
 * Unwatched first, finished last — the split behind the channel grid.
 *
 * [wasFinished] is deliberately a snapshot taken when the channel opened, not
 * a live lookup: a video the kid finishes and comes straight back from must
 * stay under their thumb, dimmed where they left it, and move on the next
 * visit. Half-watched videos count as unwatched — they're something to carry
 * on with, which is what the home screen's "Keep watching" row assumes too.
 */
internal fun splitWatched(
    items: List<VideoItem>,
    wasFinished: (VideoItem) -> Boolean
): Pair<List<VideoItem>, List<VideoItem>> =
    items.partition { !wasFinished(it) }

/**
 * Search hits handed to the AI screener in the current window. [done]/[total]
 * drives the progress bar; results append to the grid as verdicts land.
 * [beyondWindow] = matches past the screened window, screened as the kid scrolls.
 */
data class SearchScreening(val total: Int, val done: Int, val beyondWindow: Int)
