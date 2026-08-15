package io.pickwick.app.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.pickwick.app.data.AiScreener
import io.pickwick.app.data.NowPlaying
import io.pickwick.app.data.RemotePlayerControl
import io.pickwick.app.data.SessionGuard
import io.pickwick.app.data.WatchHistoryStore
import io.pickwick.app.data.YouTubeRepository
import io.pickwick.app.data.listenDrainPercent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        /** Optional: ordered page-URLs to auto-advance through (playlist sources). */
        const val EXTRA_QUEUE = "queue"
        const val EXTRA_INDEX = "index"
        /**
         * Optional, parallel to EXTRA_QUEUE: channel name and drain percent per
         * item. A hand-built queue crosses channels, so one launch-wide value
         * would credit every minute to the first channel and bill it at the
         * first channel's rate. Absent (playlist/single launches) → the single
         * EXTRA_CHANNEL / EXTRA_TIME_PERCENT applies to every item, as before.
         */
        const val EXTRA_QUEUE_CHANNELS = "queue_channels"
        const val EXTRA_QUEUE_PERCENTS = "queue_percents"
        /** True when EXTRA_QUEUE came from the kid's QueueStore: items that
         *  truly finish are removed there, so the queue self-clears. */
        const val EXTRA_FROM_QUEUE = "from_queue"
        const val EXTRA_CHANNEL = "channel"
        /** Screen-time drain rate for this launch, percent (100 normal, 0 FREE). */
        const val EXTRA_TIME_PERCENT = "time_percent"
        /**
         * Store suffix of the kid this playback belongs to, resolved by the
         * launching screen. The launcher is the only place with the full
         * resolution rules (device assignment beats a remembered pick) — the
         * player re-deriving it from device-local state is how a dedicated TV
         * once enforced a stale kid's rules and wrote history into their stores.
         */
        const val EXTRA_PROFILE_SUFFIX = "profile_suffix"
        /**
         * The kid's profile id, resolved by the launcher alongside the suffix.
         * The pre-play deep check needs the *id* (per-kid verdicts and allow
         * overrides key on it); the suffix is a storage namespace and can't be
         * mapped back. Absent → fail-closed on the strictest per-kid verdict.
         */
        const val EXTRA_PROFILE_ID = "profile_id"
    }

    private var player: ExoPlayer? = null
    private lateinit var history: WatchHistoryStore
    private lateinit var sessionGuard: SessionGuard
    private lateinit var channelUsage: io.pickwick.app.data.ChannelUsage
    private lateinit var repo: YouTubeRepository
    private lateinit var downloads: io.pickwick.app.data.DownloadStore
    private lateinit var localLibrary: io.pickwick.app.data.LocalLibrary
    private var queue: List<String> = emptyList()
    private var queueChannels: List<String> = emptyList()
    private var queuePercents: IntArray? = null
    /** Non-null only for EXTRA_FROM_QUEUE launches. */
    private var queueStore: io.pickwick.app.data.QueueStore? = null
    private var timePercent = 100
    private var currentPageUrl: String? = null
    private var currentTitle: String = ""
    private var currentChannel: String = ""
    private var currentPlayback: YouTubeRepository.Playback? = null
    private var isTv = false
    /** Non-null once a screen-time rule fires mid-playback. */
    private val timeUpMessage = mutableStateOf<String?>(null)
    /** True while ExoPlayer waits for data (initial buffer, seek, stall). */
    private val buffering = mutableStateOf(false)
    /** TV controls overlay stays visible until this timestamp (poked by remote keys). */
    private val controlsVisibleUntil = mutableStateOf(0L)
    /** Transient top-of-screen pill: time-left warnings, subtitles toggled, … */
    private val notice = mutableStateOf<Notice?>(null)
    /** Which time-left warnings (5, 1 min) already fired; cleared when time is granted back. */
    private val warnedMinutes = mutableSetOf<Int>()
    /** Kid's sticky captions choice (survives across videos and app runs). */
    private var captionsOn = false
    private var currentSubtitles: List<YouTubeRepository.Subtitle> = emptyList()
    private val trackPanel = mutableStateOf(TvTrackPanel.Hidden)
    private val trackCursor = mutableIntStateOf(0)
    private val selectedAudioTrack = mutableIntStateOf(0)
    private val selectedSubtitleTrack = mutableIntStateOf(-1)
    /** Community-marked promo stretches for the current video (see SponsorBlock). */
    private val sponsorSegments =
        mutableStateOf<List<io.pickwick.app.data.SponsorBlock.Segment>>(emptyList())
    /** Parent's SponsorBlock switch, read off-main on first use; null = not read yet. */
    private var sponsorSkipOn: Boolean? = null

    // Queue position, resolved streams, and terminal error, hoisted out of the
    // composition. Deliberate: recomposition needs display frames, which stop
    // dead when the screen is off — a LaunchedEffect-driven advance would
    // stall a listen-mode playlist at the end of its first video until the
    // screen came back. Activity-level functions + lifecycleScope (a plain
    // main-looper dispatcher) keep working in the dark; composition just
    // renders whatever these say when frames exist.
    private val indexState = mutableIntStateOf(0)
    private val playbackState = mutableStateOf<YouTubeRepository.Playback?>(null)
    private val errorState = mutableStateOf<String?>(null)
    /** Kid the deep check judges for — see [EXTRA_PROFILE_ID]. */
    private var gateProfileId: String? = null
    /** Family config for the gate (AI settings, overrides, kids), loaded off-main once. */
    private var familyConfig: io.pickwick.app.data.Whitelist? = null
    /** Channel name → parent's channel note, resolved once alongside the config. */
    private var channelNotes: Map<String, String>? = null
    private val screeningStore by lazy { io.pickwick.app.data.ScreeningStore(this) }
    /** True while the pre-play deep check is talking to the AI ("Checking this one…"). */
    private val deepChecking = mutableStateOf(false)
    /** Non-null once the deep check said no: the gentle screen before going back. */
    private val blockedGently = mutableStateOf<String?>(null)
    /** Latches on the first resolved video (see the PlayerView comment below). */
    private val everPlayed = mutableStateOf(false)
    private var resolveJob: kotlinx.coroutines.Job? = null

    /**
     * Family screen-off listening rate; null = feature off (default), which
     * keeps the old behavior: locking the phone pauses. Loaded off-main once,
     * phones only.
     */
    private var listenPercent: Int? = null
    /**
     * True while playback is sound-only: the screen went dark, the kid switched
     * apps, or a "Allow listening" window is on ([listenOnlyWindow]). Drives the
     * audio-only stream swap and the listening drain rate.
     */
    private var listenActive = false
    /**
     * True while a window marked "Allow listening" is blocking watching. Unlike
     * the screen-off kind, coming back to the player must NOT restore the
     * picture — bedtime is still on — so this pins listening on until the
     * window ends.
     */
    private var listenOnlyWindow = false
    /** Kid-facing line while a window has playback down to sound only. */
    private val listenOnlyMessage = mutableStateOf<String?>(null)

    private fun pokeControls() {
        controlsVisibleUntil.value = System.currentTimeMillis() + 3_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isTv = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        // Progress, screen time and channel minutes all belong to the kid the
        // launching screen resolved — delivered in the intent, never re-derived
        // here. Fallback (extra absent should be impossible; the activity isn't
        // exported): the mirrored active pick, which MainActivity keeps current.
        val profileSuffix = intent.getStringExtra(EXTRA_PROFILE_SUFFIX) ?: run {
            val config = io.pickwick.app.data.ConfigStore(this).load()
            // Membership-checked: a remembered pick can name a since-deleted kid,
            // and suffixFor would silently mint an orphan namespace for it.
            val active = io.pickwick.app.data.ActiveProfileStore(this).activeId()
                ?.takeIf { config.profile(it) != null }
            io.pickwick.app.data.ProfileNamespace(this).suffixFor(active)
        }
        gateProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        channelUsage = io.pickwick.app.data.ChannelUsage(this, profileSuffix)
        currentChannel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
        queue = intent.getStringArrayListExtra(EXTRA_QUEUE)
            ?: listOfNotNull(intent.getStringExtra(EXTRA_VIDEO_URL))
        if (queue.isEmpty()) {
            finish(); return
        }
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, queue.lastIndex)
        queueChannels = intent.getStringArrayListExtra(EXTRA_QUEUE_CHANNELS).orEmpty()
        queuePercents = intent.getIntArrayExtra(EXTRA_QUEUE_PERCENTS)
        if (intent.getBooleanExtra(EXTRA_FROM_QUEUE, false)) {
            queueStore = io.pickwick.app.data.QueueStore(this, profileSuffix)
        }

        history = WatchHistoryStore(this, profileSuffix)
        sessionGuard = SessionGuard(this, profileSuffix)
        repo = YouTubeRepository()
        downloads = io.pickwick.app.data.DownloadStore(this)
        localLibrary = io.pickwick.app.data.LocalLibrary(this)
        // Per-item when the parallel array is present; the gate below judges
        // the *starting* item's rate (matching single launches — later items
        // are enforced by the 5-second tick, same as any mid-playback change).
        timePercent = (queuePercents?.getOrNull(startIndex)
            ?: intent.getIntExtra(EXTRA_TIME_PERCENT, 100)).coerceIn(0, 400)

        // Screen-time rules: blocked before we even build the player.
        sessionGuard.checkStart(timePercent)?.let { reason ->
            // A window marked "Allow listening" refuses the picture, not the
            // story — start sound-only instead of showing the block screen.
            // Only when listening clears *every* rule: an exhausted budget or a
            // break lock still stops the story. TVs are out (no playing with
            // the panel off) and so is an unset family rate, which means the
            // feature doesn't exist for this family. The config read is on the
            // main thread here, but only on this path, and its answer decides
            // what to show next — same read the profile fallback above does.
            val listenThrough = !isTv &&
                sessionGuard.checkStart(timePercent, listening = true) == null &&
                io.pickwick.app.data.ConfigStore(this).load().listenPercent
                    ?.also { listenPercent = it } != null
            if (!listenThrough) {
                showBlockedScreen(reason)
                return
            }
            // Flags now, service once the player exists (see below): the whole
            // startup path from here on reads listenActive to stay audio-only.
            listenActive = true
            listenOnlyWindow = true
            listenOnlyMessage.value = reason
        }

        captionsOn = getSharedPreferences("player", MODE_PRIVATE).getBoolean("captions", false)

        if (!isTv && listenPercent == null) {
            // Listen mode is a phone feature; off-main because ConfigStore is a
            // file read + JSON parse. Racing the first power-button press is
            // theoretical (this finishes in ms), and losing the race just means
            // that one lock pauses like the feature was off. Skipped when the
            // gate above already read it.
            lifecycleScope.launch(Dispatchers.IO) {
                listenPercent = io.pickwick.app.data.ConfigStore(this@PlayerActivity)
                    .load().listenPercent
            }
        }

        // Read further ahead than the 50s default: with the chunked data source
        // the network can outrun playback, so minutes of buffer absorb Wi-Fi
        // dips instead of stalling. Back-buffer keeps 30s behind the playhead
        // so the kid's favourite "watch that again" ±10s hops don't refetch.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000, 300_000,
                androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                androidx.media3.exoplayer.DefaultLoadControl
                    .DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setBackBuffer(30_000, false)
            // Hard byte ceiling, and not optional: the time-based window above
            // is only a request, and media3's default video target is 128 MB.
            // A Chromecast gives us a 256 MB heap that the browse screen's
            // thumbnails have already eaten ~120 MB of, so an unbounded
            // 5-minute window on a high-bitrate stream would OOM. 48 MB is
            // several minutes at the bitrates these streams actually run at.
            .setTargetBufferBytes(48 * 1024 * 1024)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl)
            .apply {
                if (!isTv) {
                    // Phone niceties, and listen mode's life support: the wake
                    // mode holds CPU + Wi-Fi while playing with the screen off
                    // (without it, doze starves the stream mid-song). Focus
                    // handling and becoming-noisy make it behave like a music
                    // app around calls and unplugged headphones. TVs keep the
                    // exact pre-listen behavior — nothing here helps a TV.
                    setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                    setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        /* handleAudioFocus = */ true
                    )
                    setHandleAudioBecomingNoisy(true)
                }
            }
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        buffering.value = playbackState == Player.STATE_BUFFERING
                        if (playbackState == Player.STATE_ENDED) {
                            // Mark fully watched, then move on (playlist) or stay (single).
                            // The store commits (fsync, deliberate) — off-main.
                            currentPageUrl?.let { url ->
                                val dur = duration
                                lifecycleScope.launch(Dispatchers.IO) {
                                    history.save(url, dur, dur)
                                    // Queue launches self-clear: only a video that
                                    // truly finished leaves the lineup. Written here
                                    // (not by the home screen) because with the
                                    // screen off in listen mode, this is the only
                                    // code still running.
                                    queueStore?.remove(url)
                                }
                            }
                            advanceQueue()
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // Never leave a frozen screen: skip ahead (playlist) or say why.
                        onPlaybackFailed(error.message ?: error.errorCodeName)
                    }
                })
            }

        // Launched into an "Allow listening" window: the player exists now, so
        // the notification and the screen-off handover can be armed.
        if (listenOnlyWindow) armListenOnly()

        // Parent's phone can pause/resume via the LAN server ("come to dinner").
        RemotePlayerControl.handler = handler@{ cmd ->
            if (player == null || timeUpMessage.value != null) return@handler false
            runOnUiThread {
                val exo = player ?: return@runOnUiThread
                when (cmd) {
                    "pause" -> exo.pause()
                    "play" -> exo.play()
                }
                // Publish right away so the phone's next stats poll sees the change.
                if (exo.duration > 0) {
                    NowPlaying.update(
                        currentTitle, currentChannel,
                        exo.currentPosition, exo.duration, exo.isPlaying
                    )
                }
                if (isTv) pokeControls()
            }
            true
        }

        // Auto-skip the promo stretches SponsorBlock knows about. Deliberately
        // silent — the jump is the whole feature, no toast. Polled at 500ms:
        // at most half a second of a sponsor read plays before the seek, and a
        // position read this light is free next to the 5s stats tick below.
        lifecycleScope.launch {
            while (isActive) {
                delay(500)
                val exo = player ?: continue
                if (!exo.isPlaying) continue
                val pos = exo.currentPosition
                // 300ms tail guard: landing exactly on endMs must not re-match
                // on the next tick while the seek is still settling.
                sponsorSegments.value
                    .firstOrNull { pos >= it.startMs && pos < it.endMs - 300 }
                    ?.let { exo.seekTo(it.endMs) }
            }
        }

        // A parent's grant or rules edit landing mid-video. Worth interrupting
        // for: extra minutes arriving silently look like the countdown warning
        // was wrong, and a rules change here is what stops the film.
        lifecycleScope.launch {
            io.pickwick.app.data.KidNotices.messages.collect {
                notice.value = Notice(it.text)
            }
        }

        // Persist progress and enforce screen-time rules every 5s while playing.
        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                saveProgress()
                val exo = player ?: continue
                // Publish now-playing for the parent's stats screen.
                if (exo.duration > 0) {
                    io.pickwick.app.data.NowPlaying.update(
                        currentTitle, currentChannel,
                        exo.currentPosition, exo.duration, exo.isPlaying
                    )
                }
                // A window can open or close mid-story: bedtime arriving drops
                // the picture instead of stopping the video, and morning gives
                // it back. Checked whether or not playback is running, so a
                // paused story is in the right mode when it resumes.
                syncListenOnlyWindow()
                if (exo.isPlaying && timeUpMessage.value == null) {
                    // Stats record real watch time; the budget drains at the
                    // source's multiplier (exact integer ms — 25% of 5s = 1250ms),
                    // further scaled by the family listening rate while the
                    // screen is off.
                    val drain =
                        if (listenActive) listenDrainPercent(timePercent, listenPercent)
                        else timePercent
                    channelUsage.addSeconds(currentChannel, 5)
                    sessionGuard.tick(5_000L * drain / 100, listenActive)?.let { reason ->
                        exo.pause()
                        timeUpMessage.value = reason
                        delay(6_000)
                        finish()
                    }
                    // Soften the cutoff: warn at 5 and 1 wall-clock minutes before
                    // the budget runs out at the current drain rate (on FREE
                    // sources only an approaching bedtime counts down). A parent
                    // grant that lifts the countdown re-arms the warnings.
                    if (timeUpMessage.value == null)
                        sessionGuard.remainingMs(drain, listenActive)?.let { left ->
                        val threshold = when {
                            left <= 60_000L -> 1
                            left <= 5 * 60_000L -> 5
                            else -> null
                        }
                        if (threshold == null) warnedMinutes.clear()
                        else if (warnedMinutes.add(threshold)) {
                            notice.value = Notice(
                                if (threshold == 1) "1 minute left! ⏳"
                                else "$threshold minutes left! ⏳"
                            )
                        }
                    }
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = PickwickDarkColors) {
                val playback by playbackState
                val error by errorState
                val played by everPlayed
                val timeUp by timeUpMessage
                val blocked by blockedGently
                val checking by deepChecking
                val listenOnly by listenOnlyMessage
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        timeUp != null -> Text(
                            timeUp!!,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        // Deliberately reason-free: the AI's explanation goes to
                        // the parent's phone, not a TV the child is watching.
                        blocked != null -> Text(
                            blocked!!,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        error != null -> Text("Could not play video: $error", color = Color.White)
                        // Sound only, by the parent's window: no video view at
                        // all, and the screen is no longer held awake, so this
                        // is what the kid sees for the few seconds before it
                        // goes dark — and again if they wake the phone.
                        listenOnly != null -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                listenOnly!!,
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                playback?.title.orEmpty(),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        // Before the first resolved video there is no frame to
                        // hold, so a bare spinner is honest. The label appears
                        // only while the deep check runs — stream resolution is
                        // fast enough not to need explaining.
                        !played -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            if (checking) {
                                Spacer(Modifier.height(12.dp))
                                Text("Checking this one…", color = Color.White)
                            }
                        }
                        // Composed from the first video onwards and never swapped
                        // out again — resolving the *next* one used to replace this
                        // view with a spinner, which destroys the SurfaceView and
                        // takes the last frame with it. Keeping it mounted holds
                        // that frame under the spinner instead of cutting to black.
                        else -> AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                PlayerView(context).apply {
                                    this.player = this@PlayerActivity.player
                                    // TV: no on-screen controller — the remote drives
                                    // playback directly (OK, ◀ ▶, play/pause keys).
                                    useController = !isTv
                                    setShowSubtitleButton(true)
                                    // Without this the view drops its shutter (opaque
                                    // black) the moment the player is re-prepared with
                                    // the next video — the other half of the cut to black.
                                    setKeepContentOnPlayerReset(true)
                                }
                            }
                        )
                    }
                    // Spinner over the held frame: resolving the next video's
                    // streams, initial buffer, seek, or a mid-video stall.
                    if (timeUp == null && error == null && blocked == null &&
                        listenOnly == null && played &&
                        (playback == null || buffering.value)
                    ) {
                        CircularProgressIndicator()
                    }
                    if (isTv && timeUp == null && blocked == null) {
                        TvControlsOverlay(
                            controlsVisibleUntil,
                            sponsorSegments.value,
                            trackPanel,
                            trackCursor,
                            playback,
                            selectedAudioTrack.intValue,
                            selectedSubtitleTrack.intValue,
                            captionsOn
                        ) { player }
                    }
                    if (timeUp == null) NoticeOverlay(notice)
                }
            }
        }

        playIndex(startIndex)
    }

    private fun advanceQueue() {
        if (indexState.intValue < queue.lastIndex) playIndex(indexState.intValue + 1)
        else finish()
    }

    private fun onPlaybackFailed(message: String) {
        if (indexState.intValue < queue.lastIndex) playIndex(indexState.intValue + 1)
        else errorState.value = message
    }

    /**
     * Resolves streams for queue position [i] and hands them to the player.
     * A downloaded video plays from disk — instant start, and no network
     * needed at all (car trips). Otherwise target height follows connection +
     * device (1080p TV on fast Wi-Fi, down to muxed).
     */
    private fun playIndex(i: Int) {
        indexState.intValue = i
        selectedAudioTrack.intValue = 0
        selectedSubtitleTrack.intValue = -1
        trackPanel.value = TvTrackPanel.Hidden
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            playbackState.value = null
            errorState.value = null
            currentPageUrl = queue[i]
            // Per-item stats/drain: the 5-second tick reads these fields, so a
            // cross-channel queue charges and credits each video correctly.
            queueChannels.getOrNull(i)?.let { currentChannel = it }
            queuePercents?.getOrNull(i)?.let { timePercent = it.coerceIn(0, 400) }
            sponsorSegments.value = emptyList()
            // Segment lookup rides alongside stream resolution, never on
            // its critical path — a slow or down SponsorBlock server
            // costs nothing but unskipped sponsors. Child of this
            // job, so advancing the queue cancels a stale fetch.
            io.pickwick.app.data.SponsorBlock.videoIdOf(queue[i])?.let { vid ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    val on = sponsorSkipOn
                        ?: io.pickwick.app.data.ConfigStore(this@PlayerActivity)
                            .load().sponsorSkip.also { sponsorSkipOn = it }
                    if (!on) return@launch
                    val segments = io.pickwick.app.data.SponsorBlock.segmentsFor(vid)
                    // The blocking fetch outlives cancellation — isActive
                    // keeps a late answer from tagging the *next* video.
                    if (segments.isNotEmpty() && isActive) sponsorSegments.value = segments
                }
            }
            val pb = runCatching {
                val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // Sideloaded file (content:// via SAF) or a finished
                    // download — both play from disk with no network.
                    localLibrary.playback(queue[i])
                        ?: downloads.localPlayback(queue[i])
                }
                val resolved = local ?: repo.resolvePlayback(
                    queue[i],
                    io.pickwick.app.data.QualityTargets.playbackMaxHeight
                )
                // First play of a streamed video: the once-per-video deep check
                // (description + tags + transcript), riding the StreamInfo we
                // just paid for. Local files skip it — offline playback can't
                // reach the AI, and downloads were screened before they landed.
                if (local == null && deepCheckBlocks(queue[i], resolved)) null else resolved
            }.getOrElse { e ->
                // A superseded resolve (queue advanced again) must not be
                // mistaken for a broken video and trigger its own advance.
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Mid-playlist failure: skip to the next video instead of dying.
                if (i < queue.lastIndex) playIndex(i + 1)
                else errorState.value = e.message
                return@launch
            }
            if (pb == null) {
                onDeepBlocked(i)
                return@launch
            }
            currentTitle = pb.title
            currentPlayback = pb
            ListenService.title = pb.title
            ListenService.channelName = currentChannel
            playbackState.value = pb
            everPlayed.value = true
            attachSources(
                pb,
                audioOnly = listenActive && pb.audioUrl != null,
                resumeMs = null
            )
        }
    }

    /**
     * Whether the pre-play deep check refuses this video for the launching kid.
     * One AI call per video per rules version, cached in [screeningStore] like
     * a batch verdict (with the deep flag, so the cheap title pass never
     * overwrites it) — after that, this answers from disk. Fail-open on
     * purpose: an unreachable or erroring provider plays the video unchecked
     * this once and caches nothing, so the next press tries again — the kid is
     * not punished for an outage.
     */
    private suspend fun deepCheckBlocks(
        pageUrl: String,
        pb: YouTubeRepository.Playback
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val id = io.pickwick.app.data.SponsorBlock.videoIdOf(pageUrl)
            ?: return@withContext false
        val cfg = familyConfig
            ?: io.pickwick.app.data.ConfigStore(this@PlayerActivity).load()
                .also { familyConfig = it }
        val ai = cfg.ai
        if (!ai.enabled || ai.model.isBlank()) return@withContext false
        // A parent's explicit allow beats every AI verdict, deep ones included.
        if (id in cfg.allowedIdsFor(gateProfileId)) return@withContext false

        val note = (channelNotes ?: io.pickwick.app.data.DeepCheck.notesByChannelName(
            cfg.sources, io.pickwick.app.data.SourceCache(this@PlayerActivity).load()
        ).also { channelNotes = it })[currentChannel]

        io.pickwick.app.data.DeepCheck.cached(
            screeningStore, id, ai.rulesVersion, AiScreener.noteHash(note)
        )?.let {
            android.util.Log.i(
                "Pickwick",
                "Deep check $id: cached ${it.verdictFor(gateProfileId)} (\"${it.reason}\")"
            )
            return@withContext it.verdictFor(gateProfileId) != AiScreener.Verdict.ALLOW
        }

        deepChecking.value = true
        val entry = try {
            // Bounded overall: past ~20s the kid is staring at a spinner and an
            // answer that slow is treated like an outage (play this once).
            io.pickwick.app.data.DeepCheck.runAndStore(
                ai, cfg.profiles, screeningStore, id, pb.title, currentChannel,
                pb, timeoutMs = 20_000, channelNote = note
            )
        } finally {
            deepChecking.value = false
        }
        // Null = failure/timeout: play unchecked this once, nothing cached.
        entry != null && entry.verdictFor(gateProfileId) != AiScreener.Verdict.ALLOW
    }

    /**
     * Deep check said no. Mid-queue the lineup just moves on, same as a broken
     * video. For the video the kid actually pressed, a gentle reason-free line
     * — the AI's explanation is for the parent's phone, not a TV with a child
     * in front of it — then back to the shelf they came from, where the
     * verdict now hides this video.
     */
    private fun onDeepBlocked(i: Int) {
        if (i < queue.lastIndex) {
            playIndex(i + 1)
            return
        }
        blockedGently.value = "This one isn't available."
        lifecycleScope.launch {
            delay(4_000)
            finish()
        }
    }

    /**
     * Hands the resolved streams to the (single, reused) player. [audioOnly]
     * is the listen-mode swap: just the audio track — the video stream would
     * only be downloaded to feed a decoder nobody is watching. [resumeMs]
     * null means a fresh video (resume from saved history); a value is an
     * in-place stream swap that must not lose the playhead or override the
     * kid's pause.
     */
    private fun attachSources(
        pb: YouTubeRepository.Playback,
        audioOnly: Boolean,
        resumeMs: Long?
    ) {
        val exo = player ?: return
        currentSubtitles = pb.subtitles
        // Android 13+ builds the lock-screen/QS media controls from the
        // session's metadata and ignores the notification adapter's strings,
        // so the title must ride on the MediaItem itself — a bare-URI item
        // leaves the lock screen showing whatever the system scrapes instead.
        val mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(pb.title)
            .setArtist(currentChannel.ifBlank { null })
            .build()
        val audioUrl = pb.audioTracks.getOrNull(selectedAudioTrack.intValue)?.url ?: pb.audioUrl
        // DefaultDataSource: http for streams, file for offline
        // downloads, content for sideloaded SAF files. Wrapped so
        // googlevideo streams fetch in range-parameter chunks.
        // Measured on a Chromecast, same video: unwrapped, the
        // buffer starved (stalled at 0s ahead, then crept up at
        // ~1.5x playback); chunked, the whole video was resident
        // 19s in. See ChunkedStreamDataSource for why.
        val factory = io.pickwick.app.data.ChunkedStreamDataSource.Factory(
            androidx.media3.datasource.DefaultDataSource.Factory(this)
        )
        fun progressive(url: String) =
            androidx.media3.exoplayer.source.ProgressiveMediaSource
                .Factory(factory).createMediaSource(
                    MediaItem.Builder().setUri(url)
                        .setMediaMetadata(mediaMetadata).build()
                )
        val wasPlaying = if (resumeMs != null) exo.playWhenReady else true
        if (audioOnly && audioUrl != null) {
            exo.setMediaSource(progressive(audioUrl))
        } else {
            // Subtitles ride along as side-loaded tracks on the video item;
            // DefaultMediaSourceFactory parses them during extraction (the
            // modern pipeline — SingleSampleMediaSource is the legacy path
            // that media3 1.4+ refuses at play time). Whether one is shown
            // is the kid's sticky captions choice.
            val subConfigs = pb.subtitles.map { sub ->
                MediaItem.SubtitleConfiguration
                    .Builder(android.net.Uri.parse(sub.url))
                    .setMimeType(sub.mimeType)
                    .setLanguage(sub.languageTag.ifBlank { null })
                    .setLabel(sub.name.ifBlank { null })
                    .build()
            }
            val video = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(factory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(pb.videoUrl)
                        .setMediaMetadata(mediaMetadata)
                        .setSubtitleConfigurations(subConfigs)
                        .build()
                )
            // HD: separate video+audio merged in the player (NewPipe-style).
            exo.setMediaSource(
                if (audioUrl != null) {
                    androidx.media3.exoplayer.source.MergingMediaSource(
                        video, progressive(audioUrl)
                    )
                } else video
            )
        }
        applyCaptionsPreference()
        exo.prepare()
        if (resumeMs != null) {
            exo.seekTo(resumeMs)
        } else {
            currentPageUrl?.let { page ->
                history.progress(page)?.takeIf { !it.isFinished }
                    ?.let { exo.seekTo(it.positionMs) }
            }
        }
        exo.playWhenReady = wasPlaying
        if (isTv) pokeControls() // brief position peek at start
    }

    /**
     * Leaving the player while playing (phones, listening rate set) — power
     * button or switching to another app: keep the sound going instead of
     * pausing. Entered from [onStop] whenever the activity isn't finishing.
     */
    private fun enterListenMode() {
        if (isTv || listenActive) return
        listenPercent ?: return // unset = feature off: onStop pauses as always
        if (timeUpMessage.value != null) return
        val exo = player ?: return
        // Mid-advance (between videos) counts as playing: the resolve finishes
        // in the dark and attachSources starts the next one audio-only.
        if (!exo.isPlaying && resolveJob?.isActive != true) return
        listenActive = true
        // Drop to the bare audio stream where one exists (HD sources): the
        // video track is most of the bandwidth and all of the decode work,
        // and nobody is watching. Muxed and local files just keep playing.
        // NOT mid-advance, though: currentPlayback is still the *previous*
        // video there, and re-attaching it seeked to exo's end-of-old-video
        // position fires an instant ENDED that marks the next queued video
        // watched and drops it. The in-flight resolve honors listenActive on
        // its own when it attaches.
        if (resolveJob?.isActive != true) {
            currentPlayback?.let { pb ->
                if (pb.audioUrl != null) {
                    attachSources(pb, audioOnly = true, resumeMs = exo.currentPosition)
                }
            }
        }
        ListenService.title = currentTitle
        ListenService.channelName = currentChannel
        ListenService.player = exo
        ListenService.start(this)
    }

    /**
     * Follows a "Allow listening" window opening or closing under a story
     * that is already playing. Bedtime arriving must not cut the story off
     * mid-sentence — that is the whole reason the checkbox exists — so the
     * picture goes and the sound stays; when the window ends, the picture is
     * available again.
     */
    private fun syncListenOnlyWindow() {
        if (isTv || listenPercent == null) return
        val blocking = sessionGuard.listenOnlyWindow() != null
        if (blocking == listenOnlyWindow) return
        if (blocking) {
            listenOnlyWindow = true
            // The reason line, phrased by the guard exactly as the kid would
            // otherwise have been stopped with.
            listenOnlyMessage.value = sessionGuard.checkStart(timePercent)
            armListenOnly()
        } else {
            listenOnlyWindow = false
            listenOnlyMessage.value = null
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Only the pin is gone. Backgrounded or screen-off, this is
            // ordinary listen mode and stays exactly as it is; in front of the
            // kid, the picture comes back.
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                exitListenMode()
            }
        }
    }

    /**
     * Sound-only because a window says so: the audio swap and the notification
     * that ordinary listen mode gets from [enterListenMode], plus dropping the
     * keep-awake so the screen goes dark by itself. Nothing here turns the
     * screen off — the system's own timeout does, once we stop holding it on.
     */
    private fun armListenOnly() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listenActive = true
        val exo = player ?: return
        // Same mid-advance guard as enterListenMode: the in-flight resolve
        // attaches audio-only on its own (it reads listenActive).
        if (resolveJob?.isActive != true) {
            currentPlayback?.let { pb ->
                if (pb.audioUrl != null) {
                    attachSources(pb, audioOnly = true, resumeMs = exo.currentPosition)
                }
            }
        }
        ListenService.title = currentTitle
        ListenService.channelName = currentChannel
        ListenService.player = exo
        ListenService.start(this)
    }

    /** Back in front (unlock, or lock screen never engaged): video + normal rate. */
    private fun exitListenMode() {
        if (!listenActive) return
        // A window still has watching blocked — being back in front of the
        // player doesn't lift bedtime, it just means the kid is looking at the
        // "listening only" card.
        if (listenOnlyWindow) return
        listenActive = false
        ListenService.stop(this)
        val exo = player ?: return
        // Same mid-advance guard as enterListenMode: a stale playback must not
        // be re-attached over an in-flight resolve.
        if (resolveJob?.isActive != true) {
            currentPlayback?.let { pb ->
                if (pb.audioUrl != null) {
                    attachSources(pb, audioOnly = false, resumeMs = exo.currentPosition)
                }
            }
        }
    }

    /** Friendly full-screen block (bedtime / session limits) instead of the player. */
    private fun showBlockedScreen(reason: String) {
        setContent {
            MaterialTheme(colorScheme = PickwickDarkColors) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        reason,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
                LaunchedEffect(Unit) {
                    delay(5_000)
                    finish()
                }
            }
        }
    }

    /** TV remote: OK toggles play/pause, ◀ ▶ seek ±10s, with a time overlay. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (!isTv) return super.onKeyDown(keyCode, event)
        val exo = player ?: return super.onKeyDown(keyCode, event)
        if (trackPanel.value != TvTrackPanel.Hidden && handleTrackPanelKey(keyCode)) {
            pokeControls()
            return true
        }
        val handled = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (exo.isPlaying) exo.pause() else exo.play(); true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { exo.play(); true }
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo.pause(); true }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                exo.seekTo(exo.currentPosition + 10_000); true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)); true
            }
            // Up just peeks at the time without changing anything.
            android.view.KeyEvent.KEYCODE_DPAD_UP -> true
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                trackCursor.intValue = 0
                trackPanel.value = TvTrackPanel.Toolbar
                true
            }
            android.view.KeyEvent.KEYCODE_CAPTIONS -> {
                trackCursor.intValue = if (captionsOn) {
                    selectedSubtitleTrack.intValue + 1
                } else 0
                trackPanel.value = TvTrackPanel.Subtitles
                true
            }
            else -> false
        }
        if (handled) {
            pokeControls()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleTrackPanelKey(keyCode: Int): Boolean {
        val pb = currentPlayback ?: return false
        return when (trackPanel.value) {
            TvTrackPanel.Hidden -> false
            TvTrackPanel.Toolbar -> when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    trackCursor.intValue = 0; true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    trackCursor.intValue = 1; true
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    trackPanel.value = if (trackCursor.intValue == 0) {
                        trackCursor.intValue = selectedAudioTrack.intValue
                        TvTrackPanel.Audio
                    } else {
                        trackCursor.intValue = if (captionsOn) {
                            selectedSubtitleTrack.intValue + 1
                        } else 0
                        TvTrackPanel.Subtitles
                    }
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_BACK -> {
                    trackPanel.value = TvTrackPanel.Hidden; true
                }
                else -> false
            }
            TvTrackPanel.Audio -> handleOptionKey(
                keyCode, pb.audioTracks.size.coerceAtLeast(1), toolbarCursor = 0
            ) {
                val chosen = if (pb.audioTracks.isEmpty()) 0
                    else trackCursor.intValue.coerceIn(0, pb.audioTracks.lastIndex)
                if (pb.audioTracks.isNotEmpty() && chosen != selectedAudioTrack.intValue) {
                    selectedAudioTrack.intValue = chosen
                    attachSources(pb, audioOnly = false, resumeMs = player?.currentPosition)
                }
                notice.value = Notice(
                    "Audio: " + (pb.audioTracks.getOrNull(chosen)?.name ?: "Original")
                )
            }
            TvTrackPanel.Subtitles -> handleOptionKey(
                keyCode, pb.subtitles.size + 1, toolbarCursor = 1
            ) {
                val chosen = trackCursor.intValue - 1
                selectedSubtitleTrack.intValue = chosen
                captionsOn = chosen >= 0
                getSharedPreferences("player", MODE_PRIVATE)
                    .edit().putBoolean("captions", captionsOn).apply()
                applyCaptionsPreference()
                notice.value = Notice(
                    if (chosen < 0) "Subtitles off"
                    else "Subtitles: ${pb.subtitles[chosen].name}"
                )
            }
        }
    }

    private fun handleOptionKey(
        keyCode: Int,
        optionCount: Int,
        toolbarCursor: Int,
        select: () -> Unit
    ): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
            trackCursor.intValue = (trackCursor.intValue - 1).coerceAtLeast(0); true
        }
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
            trackCursor.intValue = (trackCursor.intValue + 1).coerceAtMost(optionCount - 1); true
        }
        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
        android.view.KeyEvent.KEYCODE_ENTER -> {
            select()
            trackPanel.value = TvTrackPanel.Toolbar
            trackCursor.intValue = toolbarCursor
            true
        }
        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        android.view.KeyEvent.KEYCODE_BACK -> {
            trackPanel.value = TvTrackPanel.Toolbar
            trackCursor.intValue = toolbarCursor
            true
        }
        else -> false
    }

    /**
     * Applies the sticky captions choice to the player: text track enabled or
     * not, preferring the device language, then whatever this video offers.
     */
    private fun applyCaptionsPreference() {
        val exo = player ?: return
        val builder = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !captionsOn)
        if (captionsOn) {
            val selected = currentSubtitles.getOrNull(selectedSubtitleTrack.intValue)
            val languages =
                (listOfNotNull(selected?.languageTag) +
                    java.util.Locale.getDefault().language +
                    currentSubtitles.map { it.languageTag })
                    .filter { it.isNotBlank() }.distinct()
            builder.setPreferredTextLanguages(*languages.toTypedArray())
        }
        exo.trackSelectionParameters = builder.build()
    }

    private fun toggleCaptions() {
        if (currentSubtitles.isEmpty()) {
            notice.value = Notice("No subtitles for this video")
            return
        }
        captionsOn = !captionsOn
        getSharedPreferences("player", MODE_PRIVATE)
            .edit().putBoolean("captions", captionsOn).apply()
        applyCaptionsPreference()
        notice.value = Notice(if (captionsOn) "Subtitles on 💬" else "Subtitles off")
    }

    /**
     * Position is read here (ExoPlayer is main-thread only) but written on IO:
     * history.save uses commit() on purpose (crash-safety), and its fsync would
     * otherwise stall the UI thread mid-playback on every 5s tick.
     */
    private fun saveProgress() {
        val exo = player ?: return
        val url = currentPageUrl ?: return
        val pos = exo.currentPosition
        val dur = exo.duration
        if (dur <= 0) return
        lifecycleScope.launch(Dispatchers.IO) { history.save(url, pos, dur) }
    }

    override fun onResume() {
        super.onResume()
        // The single exit from listen mode: "resumed" is the one state that
        // means the kid is actually looking at the player again, on every
        // unlock path (keyguard, swipe, no lock at all).
        exitListenMode()
    }

    override fun onStop() {
        super.onStop()
        // Screen off and switching to another app are the same "listening,
        // not watching" state (family listening rate set): keep the sound
        // going either way. Only actually leaving the player — back/close,
        // which finishes the activity — stops playback.
        // The foreground-service start from onStop rides the "leaving a
        // user-visible state" exemption; if an OEM's timing disagrees, losing
        // the race must mean "this leave pauses", not a crash.
        if (!isFinishing) runCatching { enterListenMode() }
        // Inline, not dispatched: lifecycleScope dies with the activity, and the
        // exit position is the one write that must not be dropped. Nothing is
        // animating by now, so the blocking commit is harmless.
        val exo = player
        val url = currentPageUrl
        if (exo != null && url != null && exo.duration > 0) {
            history.save(url, exo.currentPosition, exo.duration)
        }
        // Watching counts as presence — the who's-watching screen must not
        // re-ask right after a long video just because home sat idle.
        io.pickwick.app.data.ActiveProfileStore(this).touch()
        // Listen mode is the whole point of not pausing here; leaving with
        // the feature off, the player paused, or the activity finishing
        // still pauses as always.
        if (!listenActive) player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        resolveJob = null
        RemotePlayerControl.handler = null
        io.pickwick.app.data.NowPlaying.clear()
        ListenService.stop(this)
        if (ListenService.player === player) ListenService.player = null
        player?.release()
        player = null
    }
}

/** A transient kid-facing message; `at` gives repeats a fresh identity. */
private data class Notice(val text: String, val at: Long = System.currentTimeMillis())

private enum class TvTrackPanel { Hidden, Toolbar, Audio, Subtitles }

/** Top-center pill that shows a notice for a few seconds, then fades away. */
@Composable
private fun BoxScope.NoticeOverlay(state: MutableState<Notice?>) {
    val n = state.value ?: return
    Text(
        n.text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 28.dp)
            .background(Color(0xCC000000), shape = RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp)
    )
    LaunchedEffect(n) {
        delay(4_000)
        if (state.value == n) state.value = null
    }
}

/** Bottom overlay: elapsed / remaining / total time and a red progress bar. */
@Composable
private fun BoxScope.TvControlsOverlay(
    visibleUntil: State<Long>,
    sponsorSegments: List<io.pickwick.app.data.SponsorBlock.Segment>,
    panelState: State<TvTrackPanel>,
    cursorState: State<Int>,
    playback: YouTubeRepository.Playback?,
    selectedAudio: Int,
    selectedSubtitle: Int,
    captionsOn: Boolean,
    playerProvider: () -> ExoPlayer?
) {
    val until by visibleUntil
    val panel by panelState
    val cursor by cursorState
    var visible by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(until) {
        while (System.currentTimeMillis() < until) {
            playerProvider()?.let {
                positionMs = it.currentPosition.coerceAtLeast(0)
                durationMs = it.duration.coerceAtLeast(0)
                bufferedMs = it.bufferedPosition.coerceAtLeast(0)
            }
            visible = true
            delay(250)
        }
        visible = false
    }

    if ((visible || panel != TvTrackPanel.Hidden) && durationMs > 0) {
        if (panel == TvTrackPanel.Audio || panel == TvTrackPanel.Subtitles) {
            TvTrackSheet(
                panel = panel,
                cursor = cursor,
                playback = playback,
                selectedAudio = selectedAudio,
                selectedSubtitle = selectedSubtitle,
                captionsOn = captionsOn
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x80000000))
                .padding(horizontal = 32.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatClock(positionMs / 1000) + " / " + formatClock(durationMs / 1000),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (panel != TvTrackPanel.Hidden) {
                        TvTrackIcon(
                            TvTrackGlyph.Audio,
                            "Audio",
                            panel == TvTrackPanel.Toolbar && cursor == 0
                        )
                        Spacer(Modifier.width(16.dp))
                        TvTrackIcon(
                            TvTrackGlyph.Captions,
                            "Subtitles",
                            panel == TvTrackPanel.Toolbar && cursor == 1
                        )
                        Spacer(Modifier.width(24.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // Three layers, YouTube's convention: dim track for the whole
            // video, a lighter band for how far ahead is downloaded, red for
            // what has been played. The buffered band is the honest answer to
            // "why did it stop?" — a band that stays hard up against the red
            // is a starving connection, not a broken app.
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x40FFFFFF))
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth((bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(Color(0x8CFFFFFF))
                )
                // Green skip marks sit under playback state: an already-viewed
                // stretch stays red, while upcoming sponsor stretches stay green.
                sponsorSegments.forEach { s ->
                    val start = (s.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    val end = (s.endMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    if (end > start) Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = maxWidth * start)
                            .width(maxWidth * (end - start))
                            .height(4.dp)
                            .background(SponsorSegmentGreen)
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(WatchedProgressRed)
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(
                            start = (maxWidth - 12.dp) *
                                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        )
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(WatchedProgressRed)
                )
            }
        }
    }
}

private enum class TvTrackGlyph { Audio, Captions }

@Composable
private fun TvTrackIcon(glyph: TvTrackGlyph, label: String, selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
    ) {
        val ink = if (selected) Color(0xFF0F0F0F) else Color.White
        when (glyph) {
            TvTrackGlyph.Audio -> Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .offset(x = (-7).dp)
                        .size(width = 7.dp, height = 12.dp)
                        .background(ink, RoundedCornerShape(1.dp))
                )
                Box(
                    Modifier
                        .offset(x = 1.dp)
                        .size(width = 10.dp, height = 18.dp)
                        .clip(
                            androidx.compose.foundation.shape.GenericShape { size, _ ->
                                moveTo(0f, size.height * 0.28f)
                                lineTo(size.width * 0.55f, 0f)
                                lineTo(size.width, 0f)
                                lineTo(size.width, size.height)
                                lineTo(size.width * 0.55f, size.height)
                                lineTo(0f, size.height * 0.72f)
                                close()
                            }
                        )
                        .background(ink)
                )
                Text(
                    ")))",
                    color = ink,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 22.dp)
                )
            }
            TvTrackGlyph.Captions -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 30.dp, height = 22.dp)
                    .border(2.dp, ink, RoundedCornerShape(3.dp))
            ) {
                Text(
                    "CC",
                    color = ink,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun BoxScope.TvTrackSheet(
    panel: TvTrackPanel,
    cursor: Int,
    playback: YouTubeRepository.Playback?,
    selectedAudio: Int,
    selectedSubtitle: Int,
    captionsOn: Boolean
) {
    val options = if (panel == TvTrackPanel.Audio) {
        playback?.audioTracks.orEmpty().mapIndexed { index, track ->
            Triple(
                track.name + if (track.original) "  ·  Original" else "",
                index == selectedAudio,
                index
            )
        }.ifEmpty { listOf(Triple("Original", true, 0)) }
    } else {
        listOf(Triple("Off", !captionsOn, 0)) +
            playback?.subtitles.orEmpty().mapIndexed { index, track ->
                Triple(track.name, captionsOn && index == selectedSubtitle, index + 1)
            }
    }
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 48.dp, bottom = 118.dp)
            .width(330.dp)
            .background(Color(0xE6000000), RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp)
    ) {
        Text(
            if (panel == TvTrackPanel.Audio) "Audio" else "Subtitles",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
        val windowStart = (cursor - 3).coerceIn(0, (options.size - 7).coerceAtLeast(0))
        options.drop(windowStart).take(7).forEach { (label, checked, index) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (index == cursor) Color(0x33FFFFFF) else Color.Transparent
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (checked) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}
