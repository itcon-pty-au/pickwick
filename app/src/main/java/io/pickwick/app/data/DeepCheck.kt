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

    /** A regular REVIEW verdict makes the existing parent queue and sync apply. */
    internal fun incompleteResult(ai: AiConfig, videoId: String, reason: String): AiScreener.Result? =
        if (ai.reviewIncompleteChecks) AiScreener.Result(videoId, AiScreener.Verdict.REVIEW, reason)
        else null

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
     * Runs the deep check and persists the verdict. Incomplete checks are held
     * as REVIEW when configured. Otherwise failures return null without caching,
     * so callers allow this attempt and the next attempt tries again.
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
        channelNote: String? = null,
        /** Have this same call write a quiz, saved to [quizStore]. */
        quiz: QuizRequest? = null,
        quizStore: QuizStore? = null
    ): ScreeningStore.Entry? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val fallbackAction = if (ai.reviewIncompleteChecks) "holding for parent review"
            else "allowing unchecked this once"
        var timedOut = false
        var transcriptChars = -1
        var questions: List<QuizQuestion>? = null
        val checked = try {
            withTimeoutOrNull(timeoutMs) {
                if (pb.description.isBlank()) {
                    incompleteResult(ai, videoId, "Description unavailable; parent review required")?.let {
                        return@withTimeoutOrNull it
                    }
                }
                val track = Captions.pickEnglish(pb.subtitles)
                val transcript = track?.let { Captions.fetchText(it) }
                transcriptChars = transcript?.length ?: -1
                if (transcript.isNullOrBlank()) {
                    incompleteResult(ai, videoId, "English subtitles unavailable; parent review required")?.let {
                        return@withTimeoutOrNull it
                    }
                }
                AiScreener.deepScreenWithQuiz(
                    ai, videoId, title, channel, pb.description, pb.tags, transcript,
                    profiles, channelNote, quiz.takeIf { canDecideQuiz(track != null, transcript) }
                ).also { questions = it.quiz }.result
            }.also { if (it == null) timedOut = true }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Pickwick", "Deep check $videoId failed — $fallbackAction", e)
            null
        }
        if (checked == null) {
            if (timedOut) {
                android.util.Log.w(
                    "Pickwick",
                    "Deep check $videoId timed out after ${timeoutMs}ms " +
                        "(transcript ${if (transcriptChars < 0) "not fetched" else "$transcriptChars chars"}) " +
                        "— $fallbackAction"
                )
            }
        }
        val result = checked ?: incompleteResult(
            ai, videoId,
            if (timedOut) "Pre-play check timed out; parent review required"
            else "Pre-play check failed; parent review required"
        ) ?: return@withContext null
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
        // A kid who can't watch this has no use for its quiz.
        if (quiz != null && quizStore != null &&
            entry.verdictFor(quiz.profileId) == AiScreener.Verdict.ALLOW
        ) {
            questions?.let { quizStore.put(videoId, QuizStore.Entry(quiz.age, it, entry.at)) }
        }
        entry
    }

    /**
     * A failed caption download says nothing about the video, so it mustn't be
     * remembered as "no quiz here". No English track at all can be.
     */
    private fun canDecideQuiz(hasTrack: Boolean, transcript: String?): Boolean =
        !hasTrack || transcript != null

    /**
     * For a video the deep check won't run on: already judged before the parent
     * switched quizzes on, or exempt from AI checking. The same prompt, so there is
     * one quiz format, but the verdict is thrown away. Replacing a cached verdict
     * would relitigate it, and an exempt video has no verdict to replace. Runs
     * beside playback, so a slow model only delays the hint. Any failure just means
     * no quiz this time.
     */
    suspend fun writeQuizOnly(
        ai: AiConfig,
        profiles: List<Profile>,
        quizStore: QuizStore,
        videoId: String,
        title: String,
        channel: String,
        pb: YouTubeRepository.Playback,
        channelNote: String?,
        quiz: QuizRequest,
        timeoutMs: Long
    ) = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(timeoutMs) {
                val track = Captions.pickEnglish(pb.subtitles)
                val transcript = track?.let { Captions.fetchText(it) }
                if (!canDecideQuiz(track != null, transcript)) return@withTimeoutOrNull
                // This call exists only for the quiz, so don't pay for one that can't have any.
                if (!Quiz.transcriptUsable(transcript)) {
                    quizStore.put(videoId, QuizStore.Entry(quiz.age, emptyList(), System.currentTimeMillis()))
                    return@withTimeoutOrNull
                }
                AiScreener.deepScreenWithQuiz(
                    ai, videoId, title, channel, pb.description, pb.tags, transcript,
                    profiles, channelNote, quiz
                ).quiz?.let {
                    quizStore.put(videoId, QuizStore.Entry(quiz.age, it, System.currentTimeMillis()))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Pickwick", "Quiz for $videoId failed; none this time", e)
        }
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

    /** Video id → note, for custom playlists whose AI check is on and carry instructions. */
    fun customNotesByVideoId(entries: List<WhitelistEntry>): Map<String, String> =
        entries
            .filter { it.kind == SourceKind.CUSTOM && it.aiCheck && !it.aiNote.isNullOrBlank() }
            .flatMap { e -> e.customVideos.map { it.id to e.aiNote!!.trim() } }
            .toMap()

    /** Uploader names of channels the parent switched AI checking off for —
     *  resolved by URL exactly like [notesByChannelName]. */
    fun aiOffChannelNames(
        entries: List<WhitelistEntry>,
        sources: List<Source>
    ): Set<String> {
        val offUrls = entries.filter { !it.aiCheck }.map { it.url }.toSet()
        if (offUrls.isEmpty()) return emptySet()
        return sources.filter { it.url in offUrls }.map { it.name }.toSet()
    }
}
