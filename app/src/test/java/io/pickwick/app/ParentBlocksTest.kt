package io.pickwick.app

import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.AiScreener
import io.pickwick.app.data.ParentBlocks
import io.pickwick.app.data.Screener
import io.pickwick.app.data.ScreeningStore
import io.pickwick.app.data.Stats
import io.pickwick.app.data.Video
import io.pickwick.app.data.Whitelist
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The list of a parent's own blocks, and what a kid device reports to fill it. */
class ParentBlocksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = ScreeningStore(File(tmp.root, "screening.json"))

    private fun entry(
        verdict: AiScreener.Verdict = AiScreener.Verdict.BLOCK,
        title: String = "Title",
        reported: Boolean = false
    ) = ScreeningStore.Entry(verdict, "why", title, "Chan", null, 0, 1L, reported = reported)

    /** Stats.parse reads the whole payload; only its "ai" section matters here. */
    private fun parse(ai: JSONObject?) = Stats.parse(
        JSONObject().apply { if (ai != null) put("ai", ai) }.toString()
    )!!

    @Test
    fun `family-wide and per-kid blocks are one list with the right scope`() {
        val rows = ParentBlocks.rows(setOf("a", "both"), mapOf("k" to setOf("kid1"), "both" to setOf("kid1"))) { null }
        assertEquals(setOf("a", "both", "k"), rows.map { it.videoId }.toSet())
        assertEquals(setOf("kid1"), rows.single { it.videoId == "k" }.kidIds)
        // Family-wide wins: "blocked for kid1" would understate it.
        assertTrue(rows.single { it.videoId == "both" }.kidIds.isEmpty())
    }

    @Test
    fun `a block with no known details is still listed, last, with a derived thumbnail`() {
        val rows = ParentBlocks.rows(setOf("unknown", "zed", "apple"), emptyMap()) { id ->
            when (id) {
                "zed" -> ParentBlocks.Details("Zed", "C", "http://t/z.jpg")
                "apple" -> ParentBlocks.Details("apple", "C", null)
                else -> null
            }
        }
        assertEquals(listOf("apple", "zed", "unknown"), rows.map { it.videoId })
        assertEquals("", rows.last().title)
        assertEquals("https://i.ytimg.com/vi/unknown/mqdefault.jpg", rows.last().thumb)
        assertEquals("http://t/z.jpg", rows[1].thumb)
    }

    @Test
    fun `search finds a block by title, channel or id, ignoring case`() {
        val rows = ParentBlocks.rows(setOf("abcDEF12345", "other"), emptyMap()) { id ->
            if (id == "other") ParentBlocks.Details("Peppa Paints", "Official Channel", null) else null
        }
        assertEquals(listOf("other"), ParentBlocks.matching(rows, " peppa ").map { it.videoId })
        assertEquals(listOf("other"), ParentBlocks.matching(rows, "official").map { it.videoId })
        // An unknown video has only its id to go on.
        assertEquals(listOf("abcDEF12345"), ParentBlocks.matching(rows, "abcdef").map { it.videoId })
        assertEquals(2, ParentBlocks.matching(rows, "  ").size)
        assertTrue(ParentBlocks.matching(rows, "nope").isEmpty())
    }

    @Test
    fun `a report reaches the phone with AI screening off, but AI verdicts do not`() {
        val tv = Screener(store()).also { it.config = AiConfig(enabled = false) }
        tv.store.putAll(mapOf("aiHeld" to entry()))
        tv.report(
            Video("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "Reported", "Chan", null, 60),
            "Sam"
        )
        val ai = Stats.aiJson(Whitelist(emptyList(), emptySet(), ai = tv.config), tv.store, emptyList())
        val payload = parse(ai)
        assertEquals(listOf("dQw4w9WgXcQ"), payload.aiFlagged.map { it.videoId })
        assertTrue(payload.aiFlagged.single().reported)
        // No "screened" keeps the phone's Stats page reading AI as off.
        assertNull(payload.aiScreened)
    }

    @Test
    fun `with AI off and nothing to say the section is omitted`() {
        assertNull(Stats.aiJson(Whitelist(emptyList(), emptySet(), ai = AiConfig(enabled = false)), store(), emptyList()))
    }

    @Test
    fun `AI on still sends counts and held videos`() {
        val s = store()
        s.putAll(mapOf("held" to entry(AiScreener.Verdict.REVIEW)))
        val config = Whitelist(emptyList(), emptySet(), ai = AiConfig(enabled = true))
        val payload = parse(Stats.aiJson(config, s, listOf("held", "unscreened")))
        assertEquals(1, payload.aiScreened)
        assertEquals(1, payload.aiPending)
        assertEquals(listOf("held"), payload.aiFlagged.map { it.videoId })
    }

    @Test
    fun `a parent-blocked video leaves the queue but keeps its details for the phone`() {
        val s = store()
        s.putAll(mapOf("all" to entry(title = "For all"), "kid" to entry(title = "For one")))
        val config = Whitelist(
            emptyList(), setOf("all", "gone"),
            ai = AiConfig(enabled = false),
            blockedFor = mapOf("kid" to setOf("k1"))
        )
        val payload = parse(Stats.aiJson(config, s, emptyList()))
        assertFalse(payload.aiFlagged.any { it.videoId == "all" })
        // "gone" has no entry on this device: nothing to add, and no crash.
        assertEquals(mapOf("all" to "For all", "kid" to "For one"), payload.aiBlocked.associate { it.videoId to it.title })
    }

    @Test
    fun `a payload from an older build parses with no blocked details`() {
        assertTrue(parse(JSONObject().put("screened", 3)).aiBlocked.isEmpty())
        assertEquals(3, parse(JSONObject().put("screened", 3)).aiScreened)
        assertNull(parse(null).aiScreened)
    }
}
