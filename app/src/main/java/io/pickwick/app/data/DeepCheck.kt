package io.pickwick.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The deep screening pass shared by its two gates: the player (first press of
 * a video) and the download checker (a save-offline request, before it may
 * reach the parent). Both judge the same evidence — description, tags,
 * English transcript — and cache through the same store entry, so whichever
 * gate reaches a video first pays the one AI call for both.
 */
object DeepCheck {

    /** The stored deep verdict under the current rules, or null. A title-only
     *  entry doesn't count — that's exactly what the deep pass upgrades. */
    fun cached(store: ScreeningStore, videoId: String, rulesVersion: Int): ScreeningStore.Entry? =
        store.get(videoId)?.takeIf { it.deep && it.rulesVersion == rulesVersion }

    /**
     * Runs the deep check and persists the verdict. Null on failure or
     * [timeoutMs] — callers fail open (play this once / pass to the parent)
     * and nothing is cached, so the next attempt tries again.
     */
    suspend fun runAndStore(
        ai: AiConfig,
        profiles: List<Profile>,
        store: ScreeningStore,
        videoId: String,
        title: String,
        channel: String,
        pb: YouTubeRepository.Playback,
        timeoutMs: Long
    ): ScreeningStore.Entry? = withContext(Dispatchers.IO) {
        val result = try {
            withTimeoutOrNull(timeoutMs) {
                val transcript = Captions.pickEnglish(pb.subtitles)?.let { Captions.fetchText(it) }
                AiScreener.deepScreen(
                    ai, videoId, title, channel, pb.description, pb.tags, transcript, profiles
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Pickwick", "Deep check failed", e)
            null
        } ?: return@withContext null

        val entry = ScreeningStore.Entry(
            verdict = result.verdict,
            reason = result.reason,
            title = title,
            channel = channel,
            // The caller rarely holds the grid thumbnail; YouTube's canonical
            // thumb URL keeps the parent's blocked-list card recognizable.
            thumb = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            rulesVersion = ai.rulesVersion,
            at = System.currentTimeMillis(),
            perProfile = result.perProfile,
            deep = true
        )
        store.putAll(mapOf(videoId to entry))
        entry
    }
}
