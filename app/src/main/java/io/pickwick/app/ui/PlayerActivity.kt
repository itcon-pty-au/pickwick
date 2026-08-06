package io.pickwick.app.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.pickwick.app.data.NowPlaying
import io.pickwick.app.data.RemotePlayerControl
import io.pickwick.app.data.SessionGuard
import io.pickwick.app.data.WatchHistoryStore
import io.pickwick.app.data.YouTubeRepository
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
        const val EXTRA_CHANNEL = "channel"
        /** Screen-time drain rate for this launch, percent (100 normal, 0 FREE). */
        const val EXTRA_TIME_PERCENT = "time_percent"
    }

    private var player: ExoPlayer? = null
    private lateinit var history: WatchHistoryStore
    private lateinit var sessionGuard: SessionGuard
    private lateinit var channelUsage: io.pickwick.app.data.ChannelUsage
    private var currentPageUrl: String? = null
    private var currentTitle: String = ""
    private var currentChannel: String = ""
    /** Set from composition; invoked by the player listener when a video ends. */
    private var advance: (() -> Unit)? = null
    /** Set from composition; invoked on a playback error (skip ahead or show it). */
    private var playbackFailed: ((String) -> Unit)? = null
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

    private fun pokeControls() {
        controlsVisibleUntil.value = System.currentTimeMillis() + 3_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isTv = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        // Progress, screen time and channel minutes all belong to whoever the
        // who's-watching screen picked (dedicated devices set it at startup).
        // The active id is only trusted while profiles exist — a stale pick
        // from a removed setup must not strand data in an orphan namespace.
        val profileSuffix = run {
            val hasProfiles = io.pickwick.app.data.ConfigStore(this).load().profiles.isNotEmpty()
            val active = if (hasProfiles) {
                io.pickwick.app.data.ActiveProfileStore(this).activeId()
            } else null
            io.pickwick.app.data.ProfileNamespace(this).suffixFor(active)
        }
        channelUsage = io.pickwick.app.data.ChannelUsage(this, profileSuffix)
        currentChannel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
        val queue: List<String> =
            intent.getStringArrayListExtra(EXTRA_QUEUE)
                ?: listOfNotNull(intent.getStringExtra(EXTRA_VIDEO_URL))
        if (queue.isEmpty()) {
            finish(); return
        }
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, queue.lastIndex)

        history = WatchHistoryStore(this, profileSuffix)
        sessionGuard = SessionGuard(this, profileSuffix)
        val repo = YouTubeRepository()
        val downloads = io.pickwick.app.data.DownloadStore(this)
        val localLibrary = io.pickwick.app.data.LocalLibrary(this)
        val timePercent = intent.getIntExtra(EXTRA_TIME_PERCENT, 100).coerceIn(0, 400)

        // Screen-time rules: blocked before we even build the player.
        sessionGuard.checkStart(timePercent)?.let { reason ->
            showBlockedScreen(reason)
            return
        }

        captionsOn = getSharedPreferences("player", MODE_PRIVATE).getBoolean("captions", false)

        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    buffering.value = playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_ENDED) {
                        // Mark fully watched, then move on (playlist) or stay (single).
                        currentPageUrl?.let { history.save(it, duration, duration) }
                        advance?.invoke()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Never leave a frozen screen: skip ahead (playlist) or say why.
                    playbackFailed?.invoke(error.message ?: error.errorCodeName)
                }
            })
        }

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
                if (exo.isPlaying && timeUpMessage.value == null) {
                    // Stats record real watch time; the budget drains at the
                    // source's multiplier (exact integer ms — 25% of 5s = 1250ms).
                    channelUsage.addSeconds(currentChannel, 5)
                    sessionGuard.tick(5_000L * timePercent / 100)?.let { reason ->
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
                        sessionGuard.remainingMs(timePercent)?.let { left ->
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
                var index by remember { mutableIntStateOf(startIndex) }
                var playback by remember {
                    mutableStateOf<YouTubeRepository.Playback?>(null)
                }
                var error by remember { mutableStateOf<String?>(null) }
                // Latches on the first resolved video. Before it there is no frame
                // to hold, so a bare spinner is honest; after it the PlayerView
                // stays composed for the rest of the session (see below).
                var everPlayed by remember { mutableStateOf(false) }

                advance = {
                    if (index < queue.lastIndex) index += 1 else finish()
                }
                playbackFailed = { message ->
                    if (index < queue.lastIndex) index += 1 else error = message
                }

                // Resolve streams whenever the queue position changes. A downloaded
                // video plays from disk — instant start, and no network needed at
                // all (car trips). Otherwise target height follows connection +
                // device (1080p TV on fast Wi-Fi, down to muxed).
                LaunchedEffect(index) {
                    playback = null
                    error = null
                    currentPageUrl = queue[index]
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            // Sideloaded file (content:// via SAF) or a finished
                            // download — both play from disk with no network.
                            localLibrary.playback(queue[index])
                                ?: downloads.localPlayback(queue[index])
                        } ?: repo.resolvePlayback(
                            queue[index],
                            io.pickwick.app.data.QualityTargets.playbackMaxHeight
                        )
                    }
                        .onSuccess { pb ->
                            currentTitle = pb.title
                            playback = pb
                            everPlayed = true
                        }
                        .onFailure { e ->
                            // Mid-playlist failure: skip to the next video instead of dying.
                            if (index < queue.lastIndex) index += 1
                            else error = e.message
                        }
                }

                // Hand the resolved streams to the (single, reused) player.
                LaunchedEffect(playback) {
                    val pb = playback ?: return@LaunchedEffect
                    val exo = player ?: return@LaunchedEffect
                    currentSubtitles = pb.subtitles
                    // DefaultDataSource: http for streams, file for offline
                    // downloads, content for sideloaded SAF files.
                    val factory = androidx.media3.datasource.DefaultDataSource
                        .Factory(this@PlayerActivity)
                    fun progressive(url: String) =
                        androidx.media3.exoplayer.source.ProgressiveMediaSource
                            .Factory(factory).createMediaSource(MediaItem.fromUri(url))
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
                                .setSubtitleConfigurations(subConfigs)
                                .build()
                        )
                    // HD: separate video+audio merged in the player (NewPipe-style).
                    exo.setMediaSource(
                        if (pb.audioUrl != null) {
                            androidx.media3.exoplayer.source.MergingMediaSource(
                                video, progressive(pb.audioUrl)
                            )
                        } else video
                    )
                    applyCaptionsPreference()
                    exo.prepare()
                    currentPageUrl?.let { page ->
                        history.progress(page)?.takeIf { !it.isFinished }
                            ?.let { exo.seekTo(it.positionMs) }
                    }
                    exo.playWhenReady = true
                    if (isTv) pokeControls() // brief position peek at start
                }

                val timeUp by timeUpMessage
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        timeUp != null -> Text(
                            timeUp!!,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        error != null -> Text("Could not play video: $error", color = Color.White)
                        !everPlayed -> CircularProgressIndicator()
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
                    if (timeUp == null && error == null && everPlayed &&
                        (playback == null || buffering.value)
                    ) {
                        CircularProgressIndicator()
                    }
                    if (isTv && timeUp == null) {
                        TvControlsOverlay(controlsVisibleUntil) { player }
                    }
                    if (timeUp == null) NoticeOverlay(notice)
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
            // Down (or a dedicated captions key) toggles subtitles.
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_CAPTIONS -> {
                toggleCaptions(); true
            }
            else -> false
        }
        if (handled) {
            pokeControls()
            return true
        }
        return super.onKeyDown(keyCode, event)
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
            val languages =
                (listOf(java.util.Locale.getDefault().language) +
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

    private fun saveProgress() {
        val exo = player ?: return
        val url = currentPageUrl ?: return
        if (exo.duration > 0) {
            history.save(url, exo.currentPosition, exo.duration)
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        // Watching counts as presence — the who's-watching screen must not
        // re-ask right after a long video just because home sat idle.
        io.pickwick.app.data.ActiveProfileStore(this).touch()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        advance = null
        playbackFailed = null
        RemotePlayerControl.handler = null
        io.pickwick.app.data.NowPlaying.clear()
        player?.release()
        player = null
    }
}

/** A transient kid-facing message; `at` gives repeats a fresh identity. */
private data class Notice(val text: String, val at: Long = System.currentTimeMillis())

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
    playerProvider: () -> ExoPlayer?
) {
    val until by visibleUntil
    var visible by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }

    LaunchedEffect(until) {
        while (System.currentTimeMillis() < until) {
            playerProvider()?.let {
                positionMs = it.currentPosition.coerceAtLeast(0)
                durationMs = it.duration.coerceAtLeast(0)
                playing = it.isPlaying
            }
            visible = true
            delay(250)
        }
        visible = false
    }

    if (visible && durationMs > 0) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xB3000000))
                .padding(horizontal = 32.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    (if (playing) "▶  " else "⏸  ") + formatTime(positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${formatTime(durationMs - positionMs)} remaining",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color(0x59FFFFFF))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(WatchedProgressRed)
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
