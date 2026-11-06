package io.pickwick.app

import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.CustomVideo
import io.pickwick.app.data.LiveRowVideos
import io.pickwick.app.data.Source
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Video
import io.pickwick.app.data.VideoStatus
import io.pickwick.app.data.VideoStatusCache
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored live flag, and what the tile shows for each known status. */
class VideoStatusTest {

    private val live = Video("https://www.youtube.com/watch?v=aaaaaaaaaaa", "T", "C", null, 0, live = true)
    private val clip = Video("https://www.youtube.com/watch?v=bbbbbbbbbbb", "T", "C", null, 60)

    private fun entry(
        playable: Boolean,
        live: Boolean,
        failed: Boolean = false,
        checkedAt: Long = 1_000L
    ) = VideoStatusCache.Entry(playable, live, checkedAt, failed)

    @Test
    fun `a live item stays LIVE while it is live and playable`() {
        val v = VideoStatus.apply(live, entry(playable = true, live = true))
        assertTrue(v.live)
        assertFalse(v.offline)
    }

    @Test
    fun `a live item that went off air or became unplayable goes offline`() {
        val ended = VideoStatus.apply(live, entry(playable = true, live = false))
        assertFalse(ended.live)
        assertTrue(ended.offline)
        assertTrue(VideoStatus.apply(live, entry(playable = false, live = true)).offline)
    }

    @Test
    fun `a failed playback reads offline for a while, then may be tried again`() {
        val failed = entry(playable = true, live = true, failed = true, checkedAt = 1_000L)
        assertTrue(VideoStatus.apply(live, failed, now = 2_000L).offline)
        val later = 1_000L + VideoStatusCache.FAILED_MS + 1
        assertFalse(VideoStatus.apply(live, failed, now = later).offline)
    }

    @Test
    fun `a plain clip is only offline when it can no longer be played`() {
        assertFalse(VideoStatus.apply(clip, entry(playable = true, live = false)).offline)
        assertTrue(VideoStatus.apply(clip, entry(playable = false, live = false)).offline)
    }

    @Test
    fun `no status yet leaves the tile as stored`() {
        assertEquals(live, VideoStatus.apply(live, null))
    }

    @Test
    fun `the stored live flag survives a config round-trip`() {
        val list = WhitelistEntry(
            "custom-x", "pickwick://custom/custom-x", "Shows", SourceKind.CUSTOM,
            customVideos = listOf(
                CustomVideo("aaaaaaaaaaa", "Live", "C", null, 0, live = true),
                CustomVideo("bbbbbbbbbbb", "Clip", "C", null, 60)
            )
        )
        val back = ConfigStore.fromJson(ConfigStore.toJson(Whitelist(listOf(list), emptySet())))
            .sources.single().customVideos
        assertEquals(listOf(true, false), back.map { it.live })
        assertTrue(back[0].toVideo().live)
    }

    private fun picked(vararg videos: CustomVideo, profileIds: Set<String> = emptySet()) =
        WhitelistEntry(
            id = "list", url = "custom:list", label = "List", kind = SourceKind.CUSTOM,
            profileIds = profileIds, customVideos = videos.toList()
        )

    private fun custom(id: String, live: Boolean) = CustomVideo(id, "T", "C", null, 60, live)

    @Test fun `only the live items of a list belong to the Live row`() {
        val ids = LiveRowVideos.ids(listOf(picked(custom("aaaaaaaaaaa", true), custom("bbbbbbbbbbb", false))), "kid")
        assertEquals(setOf("aaaaaaaaaaa"), ids)
    }

    @Test fun `another kid's list doesn't claim a stream`() {
        val list = picked(custom("aaaaaaaaaaa", true), profileIds = setOf("other"))
        assertTrue(LiveRowVideos.ids(listOf(list), "kid").isEmpty())
    }

    @Test fun `a stream in the row leaves the channel view and a clip stays`() {
        assertEquals(listOf(clip), LiveRowVideos.withoutLiveRow(listOf(live, clip), setOf(live.videoId!!)))
    }

    @Test fun `a stream only in a channel's own feed stays`() {
        assertEquals(listOf(live, clip), LiveRowVideos.withoutLiveRow(listOf(live, clip), setOf("zzzzzzzzzzz")))
    }

    @Test fun `a replay of the same video that isn't live stays`() {
        val replay = live.copy(live = false)
        assertEquals(listOf(replay), LiveRowVideos.withoutLiveRow(listOf(replay), setOf(live.videoId!!)))
    }

    @Test fun `a list of only streams has nothing to open onto`() {
        val only = Source("list", "custom:list", "List", null, SourceKind.CUSTOM, customVideos = listOf(live))
        assertTrue(LiveRowVideos.isOnlyStreams(only))
        assertFalse(LiveRowVideos.isOnlyStreams(only.copy(customVideos = listOf(live, clip))))
        assertFalse(LiveRowVideos.isOnlyStreams(only.copy(customVideos = emptyList())))
        assertFalse(LiveRowVideos.isOnlyStreams(only.copy(kind = SourceKind.CHANNEL)))
    }
}
