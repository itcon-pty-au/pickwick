package io.pickwick.app.ui

import io.pickwick.app.data.*

sealed interface Screen {
    data object Home : Screen
    data class ChannelVideos(val source: Source) : Screen
    /** Random mix across all whitelisted sources. */
    data object Surprise : Screen
    /** The kid's saved-for-later videos. */
    data object Watchlist : Screen
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
    /** Video URLs the kid has saved for later (drives the hold-menu row label). */
    val watchlisted: Set<String> = emptySet(),
    /** Video URLs lined up to play next (drives the hold-menu row label and the Up next tile). */
    val queued: Set<String> = emptySet(),
    /** Video URLs requested/approved/fetching — not yet playable offline (⏳). */
    val downloadPending: Set<String> = emptySet(),
    /** Video URLs fully on disk, playable without a network (✅). */
    val downloaded: Set<String> = emptySet(),
    /** Live-screening progress on the search screen; null when nothing is in flight. */
    val searchScreening: SearchScreening? = null
)

/**
 * Search hits handed to the AI screener in the current window. [done]/[total]
 * drives the progress bar; results append to the grid as verdicts land.
 * [beyondWindow] = matches past the screened window, screened as the kid scrolls.
 */
data class SearchScreening(val total: Int, val done: Int, val beyondWindow: Int)
