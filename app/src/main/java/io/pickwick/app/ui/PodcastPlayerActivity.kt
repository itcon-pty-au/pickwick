package io.pickwick.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import io.pickwick.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Podcast playback. Deliberately separate from [PlayerActivity]: podcasts are
 * audio-only and sit outside screen-time rules, and that player enforces them
 * at every turn (start gate, 5-second budget tick, windows, content groups).
 * Here there is no gate and no drain — only progress, which still feeds the
 * episode list's bars and resume point.
 *
 * Phones keep playing with the screen off or another app on top, through the
 * same [ListenService] listen mode uses; a TV pauses when it leaves the screen.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PodcastPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FEED_ID = "feed_id"
        const val EXTRA_EPISODES = "episodes"
        const val EXTRA_INDEX = "index"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_SUFFIX = "profile_suffix"
        private const val SKIP_BACK_MS = 15_000L
        private const val SKIP_FORWARD_MS = 30_000L
    }

    private var player: ExoPlayer? = null
    private var isTv = false
    private lateinit var history: WatchHistoryStore
    private lateinit var feedId: String
    private var keys: List<String> = emptyList()
    private val index = mutableIntStateOf(0)
    private val episode = mutableStateOf<PodcastEpisode?>(null)
    private val showName = mutableStateOf("")
    private val message = mutableStateOf<String?>(null)
    private val playing = mutableStateOf(false)
    private val buffering = mutableStateOf(false)
    private val position = mutableLongStateOf(0L)
    private val duration = mutableLongStateOf(0L)

    private val currentUrl get() = PodcastPaths.url(feedId, keys[index.intValue])

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        feedId = intent.getStringExtra(EXTRA_FEED_ID).orEmpty()
        keys = intent.getStringArrayListExtra(EXTRA_EPISODES).orEmpty()
        if (feedId.isEmpty() || keys.isEmpty()) { finish(); return }
        index.intValue = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, keys.lastIndex)
        history = WatchHistoryStore(this, intent.getStringExtra(EXTRA_PROFILE_SUFFIX).orEmpty())

        // Podcast hosts route audio through tracking redirects that hop between
        // https and http; the default data source refuses that hop.
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Pickwick")
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .apply { if (!isTv) setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK) }
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing.value = isPlaying
                        if (!isPlaying) saveProgress()
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        buffering.value = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_ENDED) {
                            val url = currentUrl
                            val dur = this@apply.duration
                            lifecycleScope.launch(Dispatchers.IO) { history.save(url, dur, dur) }
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        message.value = "This episode can't be played right now."
                    }
                })
            }

        lifecycleScope.launch { load(index.intValue) }
        // UI clock, plus the progress save and parent-stats feed every 5 s.
        lifecycleScope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                val exo = player ?: continue
                position.longValue = exo.currentPosition
                duration.longValue = exo.duration.coerceAtLeast(0L)
                if (++ticks % 10 == 0 && exo.isPlaying) {
                    saveProgress()
                    episode.value?.let { NowPlaying.update(it.title, showName.value, exo.currentPosition, exo.duration, true) }
                }
            }
        }

        setContent { MaterialTheme(colorScheme = PickwickDarkColors) { Screen() } }
    }

    private suspend fun load(i: Int) {
        val exo = player ?: return
        saveProgress()
        val pid = intent.getStringExtra(EXTRA_PROFILE_ID)
        val (feed, ep, show) = withContext(Dispatchers.IO) {
            val feed = ConfigStore(this@PodcastPlayerActivity).load().podcastFor(feedId, pid)
            val library = PodcastLibrary(this@PodcastPlayerActivity)
            Triple(feed, library.episode(feedId, keys[i]), library.cached(feedId)?.title)
        }
        if (feed == null) { message.value = "This podcast is no longer available."; exo.stop(); return }
        if (ep == null) { message.value = "This episode is no longer in the podcast."; exo.stop(); return }
        index.intValue = i
        episode.value = ep
        showName.value = feed.name.ifBlank { show.orEmpty() }
        message.value = null
        ListenService.title = ep.title
        ListenService.channelName = showName.value
        val resumeAt = withContext(Dispatchers.IO) { history.progress(currentUrl) }
            ?.takeIf { !it.isFinished }?.positionMs ?: 0L
        exo.setMediaItem(
            MediaItem.Builder().setUri(ep.audioUrl).setMediaId(currentUrl)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(ep.title).setArtist(showName.value).build())
                .build(),
            resumeAt
        )
        exo.prepare()
        exo.play()
    }

    private fun saveProgress() {
        val exo = player ?: return
        if (keys.isEmpty() || exo.duration <= 0 || exo.playbackState == Player.STATE_ENDED) return
        val url = currentUrl
        val pos = exo.currentPosition
        val dur = exo.duration
        lifecycleScope.launch(Dispatchers.IO) { history.save(url, pos, dur) }
    }

    override fun onStart() {
        super.onStart()
        ListenService.stop(this)
    }

    override fun onStop() {
        super.onStop()
        val exo = player ?: return
        saveProgress()
        if (isChangingConfigurations) return
        if (!isTv && exo.isPlaying) {
            ListenService.player = exo
            ListenService.start(this)
        } else exo.pause()
    }

    override fun onDestroy() {
        saveProgress()
        NowPlaying.clear()
        ListenService.stop(this)
        if (ListenService.player === player) ListenService.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        val ep = episode.value
        val playFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.weight(1f, fill = false).aspectRatio(1f).sizeIn(maxWidth = 360.dp, maxHeight = 360.dp)) {
                    AsyncImage(model = ep?.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(DownloadsTileTeal))
                    SourceBadgeIcon(SourceBadge.PODCAST, Modifier.align(Alignment.TopStart).padding(8.dp))
                    if (buffering.value) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.height(20.dp))
                Text(ep?.title.orEmpty(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(showName.value, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                message.value?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(16.dp))
                val dur = duration.longValue
                val fraction = if (dur > 0) (position.longValue.toFloat() / dur).coerceIn(0f, 1f) else 0f
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                    if (isTv) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            color = WatchedProgressRed)
                    } else {
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = dragging ?: fraction,
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                dragging?.let { f -> player?.seekTo((f * dur).toLong()) }
                                dragging = null
                            },
                            enabled = dur > 0,
                            colors = SliderDefaults.colors(thumbColor = WatchedProgressRed, activeTrackColor = WatchedProgressRed)
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(clock(position.longValue), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(if (dur > 0) clock(dur) else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlButton(icon = io.pickwick.app.R.drawable.ic_control_previous, description = "Previous episode", enabled = index.intValue > 0) {
                        lifecycleScope.launch { load(index.intValue - 1) }
                    }
                    ControlButton(label = "−15", description = "Back 15 seconds") {
                        player?.let { it.seekTo((it.currentPosition - SKIP_BACK_MS).coerceAtLeast(0)) }
                    }
                    ControlButton(icon = if (playing.value) io.pickwick.app.R.drawable.ic_control_pause else io.pickwick.app.R.drawable.ic_control_play,
                        description = if (playing.value) "Pause" else "Play",
                        modifier = Modifier.focusRequester(playFocus), large = true) {
                        player?.let { if (it.isPlaying) it.pause() else {
                            if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                            it.play()
                        } }
                    }
                    ControlButton(label = "+30", description = "Forward 30 seconds") {
                        player?.let { it.seekTo(it.currentPosition + SKIP_FORWARD_MS) }
                    }
                    ControlButton(icon = io.pickwick.app.R.drawable.ic_control_next, description = "Next episode", enabled = index.intValue < keys.lastIndex) {
                        lifecycleScope.launch { load(index.intValue + 1) }
                    }
                }
            }
        }
    }

    @Composable
    private fun ControlButton(label: String? = null, icon: Int? = null, description: String, modifier: Modifier = Modifier,
        enabled: Boolean = true, large: Boolean = false, onClick: () -> Unit) {
        FilledTonalButton(
            onClick = onClick, enabled = enabled,
            modifier = modifier.tvFocusHighlight().semanticsLabel(description).size(if (large) 72.dp else 56.dp),
            contentPadding = PaddingValues(0.dp),
            shape = androidx.compose.foundation.shape.CircleShape
        ) {
            // Vector glyphs, not ⏸/▶ characters: Android draws those as color emoji.
            if (icon != null) Icon(androidx.compose.ui.res.painterResource(icon), contentDescription = null,
                modifier = Modifier.size(if (large) 36.dp else 28.dp))
            else Text(label.orEmpty(), fontSize = 16.sp)
        }
    }

    private fun Modifier.semanticsLabel(text: String) =
        semantics { contentDescription = text }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
