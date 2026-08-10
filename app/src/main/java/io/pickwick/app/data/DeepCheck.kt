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

    /** The stored deep verdict still valid under the current rules and channel
     *  note, or null. A title-only entry doesn't count — that's exactly what
     *  the deep pass upgrades — and neither does an ALLOW/REVIEW judged under
     *  a different note. A deep BLOCK never goes stale (blocked stays blocked). */
    fun cached(
        store: ScreeningStore,
        videoId: String,
        rulesVersion: Int,
        noteHash: Int
    ): ScreeningStore.Entry? =
        store.get(videoId)?.takeIf {
            it.deep && it.rulesVersion == rulesVersion &&
                (it.verdict == AiScreener.Verdict.BLOCK || it.noteHash == noteHash)
        }

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
        timeoutMs: Long,
        channelNote: String? = null
    ): ScreeningStore.Entry? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        // Fail-open is deliberate, but it must never be *silent* — a run of
        // timeouts reads as "screening stopped working" unless the log says
        // exactly what happened to each check.
        var timedOut = false
        var transcriptChars = -1
        val result = try {
            withTimeoutOrNull(timeoutMs) {
                val transcript = Captions.pickEnglish(pb.subtitles)?.let { Captions.fetchText(it) }
                transcriptChars = transcript?.length ?: -1
                AiScreener.deepScreen(
                    ai, videoId, title, channel, pb.description, pb.tags, transcript,
                    profiles, channelNote
                )
            }.also { if (it == null) timedOut = true }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Pickwick", "Deep check $videoId failed — allowing unchecked this once", e)
            null
        }
        if (result == null) {
            if (timedOut) {
                android.util.Log.w(
                    "Pickwick",
                    "Deep check $videoId timed out after ${timeoutMs}ms " +
                        "(transcript ${if (transcriptChars < 0) "not fetched" else "$transcriptChars chars"}) " +
                        "— allowing unchecked this once"
                )
            }
            return@withContext null
        }
        android.util.Log.i(
            "Pickwick",
            "Deep check $videoId: ${result.verdict} (\"${result.reason}\") " +
                "in ${System.currentTimeMillis() - started}ms, " +
                "transcript ${if (transcriptChars < 0) "none" else "$transcriptChars chars"}"
        )

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
            deep = true,
            noteHash = AiScreener.noteHash(channelNote)
        )
        store.putAll(mapOf(videoId to entry))
        entry
    }

    /**
     * Channel name → note, resolved through the source cache: whitelist
     * entries are keyed by id/URL but verdict-time lookups only have the
     * uploader name a video carries. Matched by URL, never id — resolution
     * canonicalizes user/, c/ and @handle ids, so an id join silently drops
     * those entries. Playlist notes only match when the uploader name happens
     * to equal the playlist name — notes are a channel feature.
     */
    fun notesByChannelName(
        entries: List<WhitelistEntry>,
        sources: List<Source>
    ): Map<String, String> {
        val byUrl = entries
            .filter { !it.aiNote.isNullOrBlank() }
            .associate { it.url to it.aiNote!!.trim() }
        if (byUrl.isEmpty()) return emptyMap()
        return sources.mapNotNull { s -> byUrl[s.url]?.let { s.name to it } }.toMap()
    }
}
