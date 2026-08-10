package io.pickwick.app

import io.pickwick.app.data.AiScreener
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.Screener
import io.pickwick.app.data.ScreeningStore
import io.pickwick.app.data.Video
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.data.SourceKind
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Per-channel AI notes: the note rides both prompts, verdicts remember the
 * note they were judged under, and the staleness rules do exactly what the
 * parent was promised — editing a note re-screens that channel's videos,
 * except already-blocked ones, which stay blocked.
 */
class ChannelNoteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val video = Video(
        url = "https://www.youtube.com/watch?v=abcdefghijk",
        title = "Nerf battle 3000",
        channelName = "Nerf Central",
        thumbnailUrl = null,
        durationSeconds = 300
    )

    @Test
    fun `note hash treats null blank and whitespace alike`() {
        assertEquals(0, AiScreener.noteHash(null))
        assertEquals(0, AiScreener.noteHash(""))
        assertEquals(0, AiScreener.noteHash("   "))
        assertEquals(AiScreener.noteHash("toy guns ok"), AiScreener.noteHash("  toy guns ok  "))
        assertTrue(AiScreener.noteHash("toy guns ok") != 0)
    }

    @Test
    fun `batch prompt carries the note per video and announces it`() {
        val arr = JSONArray(AiScreener.userPrompt(listOf(video)) { "toy guns are fine here" })
        assertEquals("toy guns are fine here", arr.getJSONObject(0).getString("note"))
        assertTrue("carry a \"note\"" in AiScreener.systemPrompt(io.pickwick.app.data.AiConfig(), hasNotes = true))
        assertFalse("carry a \"note\"" in AiScreener.systemPrompt(io.pickwick.app.data.AiConfig(), hasNotes = false))
        // No note → no field, so noteless configs keep byte-identical prompts.
        val bare = JSONArray(AiScreener.userPrompt(listOf(video)))
        assertFalse(bare.getJSONObject(0).has("note"))
    }

    @Test
    fun `deep prompt folds the note into the system message`() {
        val prompt = AiScreener.deepSystemPrompt(
            io.pickwick.app.data.AiConfig(), channelNote = "block anything filmed as a prank"
        )
        assertTrue("block anything filmed as a prank" in prompt)
    }

    private fun entry(
        verdict: AiScreener.Verdict,
        noteHash: Int,
        deep: Boolean = false
    ) = ScreeningStore.Entry(
        verdict = verdict, reason = "r", title = "t", channel = "Nerf Central",
        thumb = null, rulesVersion = 1, at = 1L, deep = deep, noteHash = noteHash
    )

    private fun screener(e: ScreeningStore.Entry, note: String?): Screener {
        val store = ScreeningStore(File(tmp.root, "screening.json"))
        store.putAll(mapOf("abcdefghijk" to e))
        return Screener(store).apply {
            config = io.pickwick.app.data.AiConfig(enabled = true, model = "m", rulesVersion = 1)
            channelNotes = note?.let { mapOf("Nerf Central" to it) } ?: emptyMap()
        }
    }

    @Test
    fun `editing a note re-screens allows and reviews but never blocks`() {
        val newNote = "toy guns are fine"
        // ALLOW under the old (absent) note: hidden and re-screened.
        screener(entry(AiScreener.Verdict.ALLOW, noteHash = 0), newNote).let {
            assertFalse(it.isVisible(video))
            assertTrue(it.needsScreening(video))
        }
        // ALLOW under the current note: visible, nothing to do.
        screener(entry(AiScreener.Verdict.ALLOW, AiScreener.noteHash(newNote)), newNote).let {
            assertTrue(it.isVisible(video))
            assertFalse(it.needsScreening(video))
        }
        // BLOCK under the old note: stays blocked, never re-screened.
        screener(entry(AiScreener.Verdict.BLOCK, noteHash = 0), newNote).let {
            assertFalse(it.isVisible(video))
            assertFalse(it.needsScreening(video))
        }
        // REVIEW under the old note: still hidden, but the new note may resolve it.
        screener(entry(AiScreener.Verdict.REVIEW, noteHash = 0), newNote).let {
            assertFalse(it.isVisible(video))
            assertTrue(it.needsScreening(video))
        }
        // Clearing the note works the same way: ALLOW under it goes stale.
        screener(entry(AiScreener.Verdict.ALLOW, AiScreener.noteHash(newNote)), null).let {
            assertTrue(it.needsScreening(video))
        }
    }

    @Test
    fun `note hash survives the store round trip`() {
        val file = File(tmp.root, "screening.json")
        ScreeningStore(file).putAll(
            mapOf("vid" to entry(AiScreener.Verdict.ALLOW, noteHash = 12345, deep = true))
        )
        val reread = ScreeningStore(file).get("vid")
        assertEquals(12345, reread?.noteHash)
        assertEquals(true, reread?.deep)
    }

    @Test
    fun `aiNote survives the config json round trip`() {
        val w = Whitelist(
            sources = listOf(
                WhitelistEntry(
                    id = "UC123", url = "https://www.youtube.com/channel/UC123",
                    label = "Nerf Central", kind = SourceKind.CHANNEL,
                    aiNote = "toy guns are fine here"
                ),
                WhitelistEntry(
                    id = "UC456", url = "https://www.youtube.com/channel/UC456",
                    label = null, kind = SourceKind.CHANNEL
                )
            ),
            blockedVideoIds = emptySet()
        )
        val parsed = ConfigStore.fromJson(ConfigStore.toJson(w))
        assertEquals("toy guns are fine here", parsed.sources[0].aiNote)
        assertEquals(null, parsed.sources[1].aiNote)
        // The fingerprint must move on a note edit, or the push button never appears.
        val edited = w.copy(sources = listOf(w.sources[0].copy(aiNote = "changed"), w.sources[1]))
        assertTrue(ConfigStore.fingerprint(w) != ConfigStore.fingerprint(edited))
    }
}
