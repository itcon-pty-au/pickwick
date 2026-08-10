package io.pickwick.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Works off CHECKING download requests: the AI deep check runs first, and only
 * an allowed (or uncheckable) request goes on to the parent's approval queue —
 * a refused one is removed before the parent ever sees it, with the verdict in
 * the screening store so the "Blocked videos" section explains what happened
 * and can overrule it. Kicked after every request and once at startup, so a
 * request stranded by a mid-check app death is picked back up.
 */
object DownloadChecker {

    /** One pass at a time process-wide — kicks while running are satisfied by
     *  the loop re-reading the store until no CHECKING entries remain. */
    private val running = AtomicBoolean(false)

    /** Background pass, generous next to the player's 20s: nobody is staring
     *  at a spinner, but a stuck request must still resolve eventually. */
    private const val TIMEOUT_MS = 60_000L

    fun kick(
        scope: CoroutineScope,
        configStore: ConfigStore,
        screeningStore: ScreeningStore,
        downloads: DownloadStore,
        yt: YouTubeRepository,
        /** Kid whose request this is — their per-kid verdict decides. */
        profileId: String?,
        /** A request was refused; the caller tells the kid and re-filters. */
        onBlocked: (Video) -> Unit
    ) {
        if (!running.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val entry = downloads.entries()
                        .firstOrNull { it.status == DownloadStatus.CHECKING } ?: break
                    val video = entry.video
                    val cfg = configStore.load()
                    val ai = cfg.ai
                    val id = video.videoId

                    // Screening off (or turned off since the tap), no id to
                    // check, or a parent override: straight to the parent.
                    if (!ai.enabled || ai.model.isBlank() || id == null ||
                        id in cfg.allowedIdsFor(profileId)
                    ) {
                        downloads.setStatus(video.url, DownloadStatus.REQUESTED)
                        continue
                    }

                    val verdict = DeepCheck.cached(screeningStore, id, ai.rulesVersion)
                        ?: run {
                            // The StreamInfo fetch (description/tags/captions);
                            // its failure — private video, no network — is the
                            // same "couldn't check" as an AI outage.
                            val pb = runCatching { yt.resolvePlayback(video.url, null) }
                                .getOrNull()
                            pb?.let {
                                DeepCheck.runAndStore(
                                    ai, cfg.profiles, screeningStore, id,
                                    video.title, video.channelName, it, TIMEOUT_MS
                                )
                            }
                        }

                    if (verdict == null ||
                        verdict.verdictFor(profileId) == AiScreener.Verdict.ALLOW
                    ) {
                        // Couldn't check → fail open to the parent: their
                        // approval is itself a human gate, unlike play-time.
                        downloads.setStatus(video.url, DownloadStatus.REQUESTED)
                    } else {
                        downloads.remove(video.url)
                        onBlocked(video)
                    }
                }
            } finally {
                running.set(false)
            }
        }
    }
}
