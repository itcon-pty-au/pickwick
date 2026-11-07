package io.pickwick.app.ui

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.data.YouTubeRepository
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.offset as layoutOffset

/** What the TV controls show, hoisted so the remote's key handling can drive it. */
internal class TvControlsState {
    /** The overlay stays up until this wall-clock time; 0 hides it. */
    val visibleUntil = mutableLongStateOf(0L)
    val zone = mutableStateOf(TvZone.Timeline)
    val focused = mutableStateOf(TvButton.PlayPause)
    /** Where a scrub will land; null when not scrubbing. Playback carries on meanwhile. */
    val scrubTargetMs = mutableStateOf<Long?>(null)
    /** Center acknowledgement of a remote press; `at` gives repeats a fresh identity. */
    val flash = mutableStateOf<TvFlash?>(null)
    val upNext = mutableStateOf<TvUpNext?>(null)
    /** Wall-clock screen time left; null when no rule is counting down. */
    val timeLeftMs = mutableStateOf<Long?>(null)
    /** When this live stream started playing here (elapsedRealtime). */
    val liveSince = mutableLongStateOf(0L)
}

internal data class TvFlash(val glyph: TvGlyph, val at: Long = System.currentTimeMillis())

internal data class TvUpNext(val seconds: Long, val title: String, val thumbnailUrl: String?)

internal enum class TvGlyph { Previous, Play, Pause, Next, Audio, Captions, Quality, Clock }

private val ScrimColor = Color.Black
private val Quiet = Color.White.copy(alpha = 0.72f)

/** Sum of what sits below the timeline, so the scrub preview can float clear above it. */
private val PreviewBottom = 112.dp
private val PreviewWidth = 196.dp

/**
 * The TV player's controls. Two zones (see [TvZone]): the timeline, where ◀ ▶
 * scrub, and the button row below it. Nothing here takes real focus; the remote
 * is handled in [PlayerActivity.onKeyDown] and this only draws the result, so
 * the video never competes with Compose for key events.
 */
@Composable
internal fun BoxScope.TvControlsOverlay(
    state: TvControlsState,
    playback: YouTubeRepository.Playback,
    channel: String,
    queuePosition: Pair<Int, Int>?,
    buttons: List<TvButton>,
    sponsorSegments: List<io.pickwick.app.data.SponsorBlock.Segment>,
    /** "Science: 4:12 this session", when a content group's cap applies. */
    contentCap: String?,
    /** Set when a quiz follows this video. */
    quizHint: String?,
    panel: TvTrackPanel,
    cursor: Int,
    selectedAudio: Int,
    selectedSubtitle: Int,
    captionsOn: Boolean,
    qualityPref: Int,
    onVisibilityChanged: (Boolean) -> Unit,
    playerProvider: () -> androidx.media3.exoplayer.ExoPlayer?
) {
    val until by state.visibleUntil
    val zone by state.zone
    val focused by state.focused
    val scrubTarget by state.scrubTargetMs
    var polledVisible by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }

    LaunchedEffect(until) {
        while (System.currentTimeMillis() < until) {
            playerProvider()?.let {
                positionMs = it.currentPosition.coerceAtLeast(0)
                durationMs = it.duration.coerceAtLeast(0)
                bufferedMs = it.bufferedPosition.coerceAtLeast(0)
                playing = it.playWhenReady
            }
            polledVisible = true
            delay(250)
        }
        polledVisible = false
    }

    val canShow = durationMs > 0 || playback.live
    val shown = (polledVisible || scrubTarget != null || panel != TvTrackPanel.Hidden) && canShow
    SideEffect { onVisibilityChanged(shown) }
    DisposableEffect(Unit) { onDispose { onVisibilityChanged(false) } }

    AnimatedVisibility(
        visible = shown,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn() + slideInVertically { -it / 4 },
        exit = fadeOut() + slideOutVertically { -it / 4 }
    ) {
        TvTopBar(playback.title, channel, queuePosition, state.timeLeftMs.value, contentCap, quizHint)
    }

    AnimatedVisibility(
        visible = shown,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn() + slideInVertically { it / 4 },
        exit = fadeOut() + slideOutVertically { it / 4 }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to ScrimColor.copy(alpha = 0.55f),
                        1f to ScrimColor.copy(alpha = 0.88f)
                    )
                )
                // Overscan-safe: TVs may crop the outer few percent of the picture.
                .padding(start = 48.dp, end = 48.dp, top = 56.dp, bottom = 20.dp)
        ) {
            // Bottom-anchored: the preview makes this box taller while scrubbing,
            // and the timeline must not move under it when that happens.
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                if (playback.live) {
                    TvLiveRow(state.liveSince.longValue)
                } else {
                    val shownPosition = scrubTarget ?: positionMs
                    Text(
                        chapterAt(playback.chapters, shownPosition).orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    TvTimeline(
                        positionMs, bufferedMs, durationMs, scrubTarget,
                        emphasized = zone == TvZone.Timeline,
                        chapters = playback.chapters,
                        sponsorSegments = sponsorSegments
                    )
                    Spacer(Modifier.height(4.dp))
                    TvTimesRow(shownPosition, durationMs, positionMs)
                }
                Spacer(Modifier.height(8.dp))
                TvButtonRow(buttons, zone, focused, playing)
            }
            if (scrubTarget != null && durationMs > 0) {
                TvScrubPreview(scrubTarget!!, durationMs, playback)
            }
        }
    }

    if (panel != TvTrackPanel.Hidden) {
        TvTrackPanelSheet(
            panel, cursor, playback, selectedAudio, selectedSubtitle, captionsOn, qualityPref
        )
    }
}

@Composable
private fun TvTopBar(
    title: String,
    channel: String,
    queuePosition: Pair<Int, Int>?,
    timeLeftMs: Long?,
    contentCap: String?,
    quizHint: String?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to ScrimColor.copy(alpha = 0.78f),
                    1f to Color.Transparent
                )
            )
            .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val detail = listOfNotNull(
                channel.takeIf { it.isNotBlank() },
                queuePosition?.let { (at, of) -> "$at of $of" }
            ).joinToString("  ·  ")
            if (detail.isNotEmpty()) {
                Text(detail, color = Quiet, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Amber from the same point the "5 minutes left" warning fires.
            timeLeftMs?.let { TvChip(timeLeftLabel(it), if (it <= 5 * 60_000L) PlayerTimeLowAmber else Color.White) }
            contentCap?.let { TvChip(it, Color.White) }
            quizHint?.let { QuizHintChip(it) }
        }
    }
}

@Composable
private fun TvChip(text: String, ink: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        TvGlyphIcon(TvGlyph.Clock, ink, Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun TvLiveRow(liveSince: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "LIVE",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier
                .background(WatchedProgressRed, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(12.dp))
        val watched = (android.os.SystemClock.elapsedRealtime() - liveSince).coerceAtLeast(0) / 1000
        Text("Watching for ${formatClock(watched)}", color = Quiet, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * YouTube's layers on one canvas: dim track, a lighter band for what's
 * downloaded, red for what's played. The buffered band is the honest answer to
 * "why did it stop?" — a band hard up against the red is a starving connection,
 * not a broken app. Chapters are gaps in the track rather than ticks on it, so
 * they survive whichever color is under them.
 */
@Composable
private fun TvTimeline(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    scrubTargetMs: Long?,
    emphasized: Boolean,
    chapters: List<YouTubeRepository.Chapter>,
    sponsorSegments: List<io.pickwick.app.data.SponsorBlock.Segment>
) {
    val track by animateDpAsState(if (emphasized) 7.dp else 4.dp, label = "timeline")
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        if (durationMs <= 0) return@Canvas
        val cy = size.height / 2
        val h = track.toPx()
        val top = cy - h / 2
        fun x(ms: Long) = (ms.toFloat() / durationMs).coerceIn(0f, 1f) * size.width
        fun band(from: Float, to: Float, color: Color) {
            if (to > from) drawRoundRect(
                color, Offset(from, top), Size(to - from, h), CornerRadius(h / 2)
            )
        }
        band(0f, size.width, Color(0x40FFFFFF))
        band(0f, x(bufferedMs), Color(0x8CFFFFFF))
        // Under the red: an already-viewed stretch stays red, upcoming ones green.
        sponsorSegments.forEach { band(x(it.startMs), x(it.endMs), SponsorSegmentGreen) }
        band(0f, x(positionMs), WatchedProgressRed)
        chapters.filter { it.startMs > 0 }.forEach {
            drawRect(Color.Black, Offset(x(it.startMs) - 1.5f, top - 1), Size(3f, h + 2))
        }
        val head = if (emphasized) 8.dp.toPx() else 6.dp.toPx()
        drawCircle(WatchedProgressRed, head, Offset(x(positionMs), cy))
        scrubTargetMs?.let {
            drawCircle(Color.White, 8.dp.toPx(), Offset(x(it), cy))
        }
    }
}

@Composable
private fun TvTimesRow(shownPosition: Long, durationMs: Long, positionMs: Long) {
    val context = LocalContext.current
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            formatClock(shownPosition / 1000) + " / " + formatClock(durationMs / 1000),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        val left = (durationMs - shownPosition).coerceAtLeast(0)
        // From where playback really is: scrubbing hasn't moved anything yet.
        val endsAt = System.currentTimeMillis() + (durationMs - positionMs).coerceAtLeast(0)
        Text(
            "−" + formatClock(left / 1000) + "  ·  ends " + timeFormat.format(java.util.Date(endsAt)),
            color = Quiet,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun TvButtonRow(buttons: List<TvButton>, zone: TvZone, focused: TvButton, playing: Boolean) {
    val active = zone == TvZone.Buttons
    val leftGroup = buttons.filter { it == TvButton.Previous || it == TvButton.PlayPause || it == TvButton.Next }
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            leftGroup.forEach { TvRoundButton(it, active && it == focused, playing) }
        }
        Text(
            if (active) tvButtonLabel(focused, playing) else "◀ ▶ to seek  ·  ▼ for buttons",
            color = if (active) Color.White else Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            (buttons - leftGroup.toSet()).forEach { TvRoundButton(it, active && it == focused, playing) }
        }
    }
}

private fun tvButtonLabel(button: TvButton, playing: Boolean) = when (button) {
    TvButton.Previous -> "Previous"
    TvButton.PlayPause -> if (playing) "Pause" else "Play"
    TvButton.Next -> "Next"
    TvButton.Audio -> "Audio"
    TvButton.Subtitles -> "Subtitles"
    TvButton.Quality -> "Quality"
}

@Composable
private fun TvRoundButton(button: TvButton, selected: Boolean, playing: Boolean) {
    val grow by animateFloatAsState(if (selected) 1.14f else 1f, label = "button")
    val ink = if (selected) Color(0xFF0F0F0F) else Color.White
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .scale(grow)
            .background(if (selected) Color.White else Color.Transparent)
    ) {
        val glyph = when (button) {
            TvButton.Previous -> TvGlyph.Previous
            TvButton.PlayPause -> if (playing) TvGlyph.Pause else TvGlyph.Play
            TvButton.Next -> TvGlyph.Next
            TvButton.Audio -> TvGlyph.Audio
            TvButton.Subtitles -> TvGlyph.Captions
            TvButton.Quality -> TvGlyph.Quality
        }
        TvGlyphIcon(glyph, ink, Modifier.size(24.dp))
    }
}

/**
 * Floats above the timeline at the scrub target: storyboard frame when YouTube
 * offers one, always the time and chapter. Coil keeps recent sheets cached, and
 * a sheet that hasn't arrived just leaves the frame out; it never holds up the
 * scrub, which is the part that has to feel instant.
 */
@Composable
private fun BoxScope.TvScrubPreview(
    targetMs: Long,
    durationMs: Long,
    playback: YouTubeRepository.Playback
) {
    val context = LocalContext.current
    val frame = playback.storyboard?.frameAt(targetMs)
    var sheet by remember(playback) { mutableStateOf<Pair<String, ImageBitmap>?>(null) }
    LaunchedEffect(frame?.url) {
        val url = frame?.url ?: return@LaunchedEffect
        // Software + 565: a sheet is drawn from often and has no use for alpha, and
        // a Chromecast's heap is already busy holding the browse screen's thumbnails.
        val result = coil.Coil.imageLoader(context).execute(
            coil.request.ImageRequest.Builder(context).data(url)
                .allowHardware(false).allowRgb565(true)
                .size(coil.size.Size.ORIGINAL)
                .build()
        )
        (result.drawable as? BitmapDrawable)?.bitmap?.let { sheet = url to it.asImageBitmap() }
    }
    BoxWithConstraints(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = PreviewBottom)) {
        val fraction = (targetMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val x = (maxWidth * fraction - PreviewWidth / 2).coerceIn(0.dp, maxWidth - PreviewWidth)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .layoutOffset(x = x)
                .width(PreviewWidth)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xE6101416))
        ) {
            val board = playback.storyboard
            val loaded = sheet?.takeIf { it.first == frame?.url }?.second
            if (board != null && frame != null && loaded != null) {
                val aspect = board.frameWidth.toFloat() / board.frameHeight
                Canvas(Modifier.fillMaxWidth().height(PreviewWidth / aspect)) {
                    drawImage(
                        loaded,
                        srcOffset = IntOffset(frame.x, frame.y),
                        srcSize = IntSize(board.frameWidth, board.frameHeight),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                    )
                }
            }
            Text(
                formatClock(targetMs / 1000),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp)
            )
            Text(
                chapterAt(playback.chapters, targetMs).orEmpty(),
                color = Quiet,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
            )
        }
    }
}

/** A side panel, not a popup over the controls: the picture stays in view while choosing. */
@Composable
private fun BoxScope.TvTrackPanelSheet(
    panel: TvTrackPanel,
    cursor: Int,
    playback: YouTubeRepository.Playback,
    selectedAudio: Int,
    selectedSubtitle: Int,
    captionsOn: Boolean,
    qualityPref: Int
) {
    val options: List<Triple<String, Boolean, Int>> = when (panel) {
        TvTrackPanel.Audio -> playback.audioTracks.mapIndexed { index, track ->
            Triple(
                track.name + if (track.original) "  ·  Original" else "",
                index == selectedAudio,
                index
            )
        }.ifEmpty { listOf(Triple("Original", true, 0)) }
        TvTrackPanel.Quality -> {
            val offered = playback.videoOptions
            if (offered.isEmpty()) listOf(Triple("${playback.videoHeight}p", true, 0))
            else listOf(
                Triple(
                    "Auto" + if (qualityPref == 0) "  ·  ${playback.videoHeight}p" else "",
                    qualityPref == 0,
                    0
                )
            ) + offered.mapIndexed { index, option ->
                Triple("${option.height}p", qualityPref == option.height, index + 1)
            }
        }
        else -> listOf(Triple("Off", !captionsOn, 0)) +
            playback.subtitles.mapIndexed { index, track ->
                Triple(track.label, captionsOn && index == selectedSubtitle, index + 1)
            }
    }
    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            // As wide as its longest row, capped so one long audio-track name
            // can't take over the picture.
            .widthIn(max = 340.dp)
            .width(IntrinsicSize.Max)
            // Opaque: a long video title runs underneath, and at any translucency
            // its tail shows through right beside the panel's own heading.
            .background(Color(0xFF101416))
            .padding(horizontal = 16.dp, vertical = 32.dp)
    ) {
        Text(
            when (panel) {
                TvTrackPanel.Audio -> "Audio"
                TvTrackPanel.Quality -> "Quality"
                else -> "Subtitles"
            },
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 12.dp, bottom = 16.dp)
        )
        val windowStart = (cursor - 3).coerceIn(0, (options.size - 7).coerceAtLeast(0))
        // Rows scrolled out of the window still count toward the width, in
        // zero height, or the panel's edge would jump around while scrolling.
        options.forEach { (label) ->
            Row(Modifier.height(0.dp).padding(horizontal = 12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Spacer(Modifier.width(TRACK_CHECK_SLOT))
            }
        }
        options.drop(windowStart).take(7).forEach { (label, checked, index) ->
            val selected = index == cursor
            val ink = if (selected) Color(0xFF0F0F0F) else Color.White
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) Color.White else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    label,
                    color = ink,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(TRACK_CHECK_SLOT - 20.dp))
                if (checked) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                } else Spacer(Modifier.width(20.dp))
            }
        }
    }
}

/** Gap plus check mark, kept on every row so choosing one never crowds its label. */
private val TRACK_CHECK_SLOT = 36.dp

/** Confirms a remote press when the controls may be out of sight. */
@Composable
internal fun BoxScope.TvFlashOverlay(state: androidx.compose.runtime.MutableState<TvFlash?>) {
    val flash = state.value
    AnimatedVisibility(
        visible = flash != null,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(84.dp).background(Color(0x99000000), CircleShape)
        ) {
            // Held through the fade-out, which outlives the state going null.
            var last by remember { mutableStateOf(TvGlyph.Play) }
            flash?.let { last = it.glyph }
            TvGlyphIcon(last, Color.White, Modifier.size(40.dp))
        }
    }
    LaunchedEffect(flash) {
        if (flash == null) return@LaunchedEffect
        delay(450)
        if (state.value == flash) state.value = null
    }
}

/** Sits clear of the bottom edge, and steps aside for the controls when they're up. */
@Composable
internal fun BoxScope.TvUpNextCard(upNext: TvUpNext?) {
    AnimatedVisibility(
        visible = upNext != null,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 32.dp),
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut()
    ) {
        // Held through the fade-out, which outlives the state going null.
        var last by remember { mutableStateOf(upNext) }
        if (upNext != null) last = upNext
        val card = last ?: return@AnimatedVisibility
        Column(
            Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF2101416))
                .padding(10.dp)
        ) {
            card.thumbnailUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.height(8.dp))
            }
            Text("Up next in ${card.seconds}s", color = Quiet, style = MaterialTheme.typography.labelLarge)
            Text(
                card.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text("OK to play now", color = Quiet, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Drawn rather than taken from material-icons-extended: this handful of shapes
 * doesn't justify the dependency's size on a sideloaded APK.
 */
@Composable
private fun TvGlyphIcon(glyph: TvGlyph, ink: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawGlyph(glyph, ink) }
}

private fun DrawScope.drawGlyph(glyph: TvGlyph, ink: Color) {
    val w = size.width
    val h = size.height
    fun triangle(left: Float, right: Float, pointsRight: Boolean) {
        val tipX = if (pointsRight) right else left
        val baseX = if (pointsRight) left else right
        drawPath(
            Path().apply {
                moveTo(baseX, h * 0.18f)
                lineTo(tipX, h * 0.5f)
                lineTo(baseX, h * 0.82f)
                close()
            },
            ink
        )
    }
    fun bar(x: Float, width: Float) =
        drawRoundRect(ink, Offset(x, h * 0.18f), Size(width, h * 0.64f), CornerRadius(w * 0.03f))
    val line = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    when (glyph) {
        TvGlyph.Play -> triangle(w * 0.24f, w * 0.82f, pointsRight = true)
        TvGlyph.Pause -> {
            bar(w * 0.24f, w * 0.18f)
            bar(w * 0.58f, w * 0.18f)
        }
        TvGlyph.Next -> {
            triangle(w * 0.18f, w * 0.68f, pointsRight = true)
            bar(w * 0.70f, w * 0.11f)
        }
        TvGlyph.Previous -> {
            triangle(w * 0.32f, w * 0.82f, pointsRight = false)
            bar(w * 0.19f, w * 0.11f)
        }
        TvGlyph.Audio -> {
            // A speaker and two arcs of sound.
            drawPath(
                Path().apply {
                    moveTo(w * 0.12f, h * 0.38f)
                    lineTo(w * 0.28f, h * 0.38f)
                    lineTo(w * 0.48f, h * 0.2f)
                    lineTo(w * 0.48f, h * 0.8f)
                    lineTo(w * 0.28f, h * 0.62f)
                    lineTo(w * 0.12f, h * 0.62f)
                    close()
                },
                ink
            )
            drawArc(ink, -45f, 90f, false, Offset(w * 0.40f, h * 0.32f), Size(w * 0.24f, h * 0.36f), style = line)
            drawArc(ink, -50f, 100f, false, Offset(w * 0.42f, h * 0.18f), Size(w * 0.42f, h * 0.64f), style = line)
        }
        TvGlyph.Captions -> {
            drawRoundRect(
                ink, Offset(w * 0.08f, h * 0.2f), Size(w * 0.84f, h * 0.6f),
                CornerRadius(w * 0.1f), style = Stroke(width = w * 0.08f)
            )
            drawLine(ink, Offset(w * 0.24f, h * 0.42f), Offset(w * 0.56f, h * 0.42f), w * 0.08f, StrokeCap.Round)
            drawLine(ink, Offset(w * 0.66f, h * 0.42f), Offset(w * 0.76f, h * 0.42f), w * 0.08f, StrokeCap.Round)
            drawLine(ink, Offset(w * 0.24f, h * 0.6f), Offset(w * 0.34f, h * 0.6f), w * 0.08f, StrokeCap.Round)
            drawLine(ink, Offset(w * 0.44f, h * 0.6f), Offset(w * 0.76f, h * 0.6f), w * 0.08f, StrokeCap.Round)
        }
        TvGlyph.Quality -> {
            // Sliders: the universal "tune this" shape.
            listOf(0.26f to 0.66f, 0.5f to 0.32f, 0.74f to 0.58f).forEach { (y, knob) ->
                drawLine(ink, Offset(w * 0.1f, h * y), Offset(w * 0.9f, h * y), w * 0.08f, StrokeCap.Round)
                drawCircle(ink, w * 0.1f, Offset(w * knob, h * y))
            }
        }
        TvGlyph.Clock -> {
            drawCircle(ink, w * 0.42f, Offset(w * 0.5f, h * 0.5f), style = Stroke(width = w * 0.11f))
            drawLine(ink, Offset(w * 0.5f, h * 0.26f), Offset(w * 0.5f, h * 0.52f), w * 0.11f, StrokeCap.Round)
            drawLine(ink, Offset(w * 0.5f, h * 0.52f), Offset(w * 0.68f, h * 0.62f), w * 0.11f, StrokeCap.Round)
        }
    }
}

/** A picker is worth showing only when we know what's playing (not for local files or shares). */
internal fun YouTubeRepository.Playback.hasQualityPicker() = videoOptions.isNotEmpty() || videoHeight > 0
