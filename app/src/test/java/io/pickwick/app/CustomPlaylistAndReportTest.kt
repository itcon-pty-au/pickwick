package io.pickwick.app

import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.CustomVideo
import io.pickwick.app.data.Screener
import io.pickwick.app.data.ScreeningStore
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Video
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.data.WhitelistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Custom playlists, the per-channel AI switch, autoplay and kid reports. */
class CustomPlaylistAndReportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val v1 = CustomVideo("dQw4w9WgXcQ", "First", "Chan A", "http://t/1.jpg", 212)
    private val v2 = CustomVideo("9bZkp7q19f0", "Second", "Chan B", null, 0)

    private fun custom(vararg videos: CustomVideo) = WhitelistEntry(
        "custom-ab12cd34", "pickwick://custom/custom-ab12cd34", "Dino night",
        SourceKind.CUSTOM, customVideos = videos.toList()
    )

    // --- pasted links ---

    @Test
    fun `video links are recognised in every common form`() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, WhitelistParser.videoIdFromLink("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, WhitelistParser.videoIdFromLink("https://youtu.be/$id?t=5"))
        assertEquals(id, WhitelistParser.videoIdFromLink("https://m.youtube.com/shorts/$id"))
        assertEquals(id, WhitelistParser.videoIdFromLink("https://www.youtube.com/live/$id"))
    }

    @Test
    fun `channel and playlist links are not video links`() {
        assertNull(WhitelistParser.videoIdFromLink("https://www.youtube.com/@somebody"))
        assertNull(WhitelistParser.videoIdFromLink("https://www.youtube.com/playlist?list=PLabcdefghijkl"))
        // A video that names a list is still a playlist link.
        assertNull(
            WhitelistParser.videoIdFromLink(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefghijkl"
            )
        )
        assertNull(WhitelistParser.videoIdFromLink("not a link"))
    }

    // --- config round-trip and hash ---

    @Test
    fun `custom playlist survives a JSON round-trip in order`() {
        val config = Whitelist(listOf(custom(v1, v2)), emptySet())
        val back = ConfigStore.fromJson(ConfigStore.toJson(config)).sources.single()
        assertEquals(SourceKind.CUSTOM, back.kind)
        assertEquals(listOf(v1, v2), back.customVideos)
    }

    @Test
    fun `adding a video moves the fingerprint so it pushes`() {
        val one = Whitelist(listOf(custom(v1)), emptySet())
        val two = Whitelist(listOf(custom(v1, v2)), emptySet())
        assertNotEquals(ConfigStore.fingerprint(one), ConfigStore.fingerprint(two))
    }

    @Test
    fun `ai check and autoplay default on-and-off and sync when changed`() {
        val plain = Whitelist(
            listOf(WhitelistEntry("UCa", "https://www.youtube.com/channel/UCa", "A", SourceKind.CHANNEL)),
            emptySet()
        )
        val json = ConfigStore.toJson(plain)
        assertFalse(json.contains("aiCheck"))
        assertFalse(json.contains("customAutoplay"))

        val changed = plain.copy(
            sources = plain.sources.map { it.copy(aiCheck = false) },
            customAutoplay = true
        )
        val back = ConfigStore.fromJson(ConfigStore.toJson(changed))
        assertFalse(back.sources.single().aiCheck)
        assertTrue(back.customAutoplay)
        assertNotEquals(ConfigStore.fingerprint(plain), ConfigStore.fingerprint(changed))
    }

    @Test
    fun `picked videos count as allowed for kids who can see the list`() {
        val config = Whitelist(
            listOf(custom(v1, v2).copy(profileIds = setOf("kidA"), aiCheck = false)), emptySet()
        )
        assertEquals(setOf(v1.id, v2.id), config.allowedIdsFor("kidA"))
        assertTrue(config.allowedIdsFor("kidB").isEmpty())
    }

    @Test
    fun `a custom list with its AI check on is screened and carries its note`() {
        val list = custom(v1, v2).copy(aiCheck = true, aiNote = "no scary bits")
        val config = Whitelist(listOf(list), emptySet())
        assertTrue(config.allowedIdsFor(null).isEmpty())
        assertEquals(
            mapOf(v1.id to "no scary bits", v2.id to "no scary bits"),
            io.pickwick.app.data.DeepCheck.customNotesByVideoId(config.sources)
        )
        // Switched off, the note stops applying.
        assertTrue(
            io.pickwick.app.data.DeepCheck
                .customNotesByVideoId(listOf(list.copy(aiCheck = false))).isEmpty()
        )
    }

    @Test
    fun `a custom playlist note overrides the uploader's note for its videos`() {
        val s = screener(aiOn = true)
        val v = video(v1.id)
        s.channelNotes = mapOf("Chan" to "channel rule")
        val before = s.needsScreening(v)
        s.videoNotes = mapOf(v1.id to "playlist rule")
        // Nothing stored yet, so it needs screening either way; the point is
        // that the note change is seen as a different note once a verdict exists.
        assertTrue(before)
        s.store.putAll(
            mapOf(
                v1.id to ScreeningStore.Entry(
                    io.pickwick.app.data.AiScreener.Verdict.ALLOW, "ok", "t", "Chan", null,
                    s.config.rulesVersion, 1L,
                    noteHash = io.pickwick.app.data.AiScreener.noteHash("channel rule")
                )
            )
        )
        assertTrue(s.needsScreening(v))
    }

    // --- kid reports ---

    private fun screener(aiOn: Boolean): Screener {
        val s = Screener(ScreeningStore(File(tmp.root, "screening.json")))
        s.config = AiConfig(enabled = aiOn)
        return s
    }

    private fun video(id: String) = Video(
        "https://www.youtube.com/watch?v=$id", "Title $id", "Chan", null, 60
    )

    @Test
    fun `a report hides the video even with AI screening off`() {
        val s = screener(aiOn = false)
        val v = video("dQw4w9WgXcQ")
        assertTrue(s.isVisible(v))
        s.report(v, "Sam")
        assertFalse(s.isVisible(v))
        assertTrue(s.isVisible(video("9bZkp7q19f0")))
    }

    @Test
    fun `a parent allow brings a reported video back`() {
        val s = screener(aiOn = false)
        val v = video("dQw4w9WgXcQ")
        s.report(v, null)
        s.allowedOverrides = setOf("dQw4w9WgXcQ")
        assertTrue(s.isVisible(v))
    }

    @Test
    fun `a report is queued for the parent and never re-screened`() {
        val s = screener(aiOn = true)
        s.config = s.config.copy(model = "m")
        val v = video("dQw4w9WgXcQ")
        s.report(v, "Sam")
        val flagged = s.store.flagged(s.config.rulesVersion)
        assertEquals(1, flagged.size)
        assertTrue(flagged.single().second.reported)
        assertEquals("Reported by Sam", flagged.single().second.reason)
        assertFalse(s.needsScreening(v))
    }

    @Test
    fun `a peer's report overrides a verdict we already hold`() {
        val phone = ScreeningStore(File(tmp.root, "phone.json"))
        val tv = Screener(ScreeningStore(File(tmp.root, "tv.json"))).also {
            it.config = AiConfig(enabled = false)
        }
        phone.putAll(
            mapOf(
                "dQw4w9WgXcQ" to ScreeningStore.Entry(
                    io.pickwick.app.data.AiScreener.Verdict.ALLOW, "ok", "t", "c", null, 0, 1L
                )
            )
        )
        tv.report(video("dQw4w9WgXcQ"), null)
        assertEquals(1, phone.importJson(tv.store.exportJson(0), 0))
        assertTrue(phone.get("dQw4w9WgXcQ")!!.reported)
    }

    @Test
    fun `ai-off channels are visible without a verdict`() {
        val s = screener(aiOn = true)
        val v = video("dQw4w9WgXcQ")
        assertFalse(s.isVisible(v))
        s.aiOffChannels = setOf("Chan")
        assertTrue(s.isVisible(v))
        assertFalse(s.needsScreening(v))
    }
}
