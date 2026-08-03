package io.santatube.app

import io.santatube.app.data.AiConfig
import io.santatube.app.data.AiScreener
import io.santatube.app.data.ConfigStore
import io.santatube.app.data.SourceKind
import io.santatube.app.data.Video
import io.santatube.app.data.Whitelist
import io.santatube.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiScreenerTest {

    private val ids = setOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc")

    @Test
    fun parsesVerdictsFromPlainJson() {
        val reply = """
            {"verdicts":[
              {"id":"aaaaaaaaaaa","v":"allow","why":"educational"},
              {"id":"bbbbbbbbbbb","v":"block","why":"scary themes"},
              {"id":"ccccccccccc","v":"review","why":"ambiguous title"}
            ]}
        """.trimIndent()
        val byId = AiScreener.parseVerdicts(reply, ids).associateBy { it.videoId }

        assertEquals(AiScreener.Verdict.ALLOW, byId["aaaaaaaaaaa"]!!.verdict)
        assertEquals(AiScreener.Verdict.BLOCK, byId["bbbbbbbbbbb"]!!.verdict)
        assertEquals("scary themes", byId["bbbbbbbbbbb"]!!.reason)
        assertEquals(AiScreener.Verdict.REVIEW, byId["ccccccccccc"]!!.verdict)
    }

    @Test
    fun toleratesCodeFencesAndProseAroundTheJson() {
        val reply = """
            Sure! Here are the verdicts:
            ```json
            {"verdicts":[{"id":"aaaaaaaaaaa","v":"allow","why":""}]}
            ```
        """.trimIndent()
        val results = AiScreener.parseVerdicts(reply, setOf("aaaaaaaaaaa"))
        assertEquals(AiScreener.Verdict.ALLOW, results.single().verdict)
    }

    @Test
    fun skippedIdsFailSafeToReviewAndUnknownIdsAreDropped() {
        val reply = """
            {"verdicts":[
              {"id":"aaaaaaaaaaa","v":"allow","why":"fine"},
              {"id":"zzzzzzzzzzz","v":"allow","why":"never asked about this one"}
            ]}
        """.trimIndent()
        val byId = AiScreener.parseVerdicts(reply, ids).associateBy { it.videoId }

        assertEquals(3, byId.size) // the intruder is gone, the skipped ones exist
        assertEquals(AiScreener.Verdict.ALLOW, byId["aaaaaaaaaaa"]!!.verdict)
        assertEquals(AiScreener.Verdict.REVIEW, byId["bbbbbbbbbbb"]!!.verdict)
        assertEquals(AiScreener.Verdict.REVIEW, byId["ccccccccccc"]!!.verdict)
    }

    @Test
    fun promptsCarryRulesAgeAndVideoMetadata() {
        val cfg = AiConfig(enabled = true, rules = "No unboxing videos.", childAge = 7)
        val system = AiScreener.systemPrompt(cfg)
        assertTrue(system.contains("No unboxing videos."))
        assertTrue(system.contains("aged 7"))

        val user = AiScreener.userPrompt(
            listOf(
                Video(
                    url = "https://www.youtube.com/watch?v=aaaaaaaaaaa",
                    title = "Volcano experiment",
                    channelName = "Science Kids",
                    thumbnailUrl = null,
                    durationSeconds = 240
                )
            )
        )
        assertTrue(user.contains("aaaaaaaaaaa"))
        assertTrue(user.contains("Volcano experiment"))
        assertTrue(user.contains("Science Kids"))
    }

    @Test
    fun parsesDiscoverySuggestionsAndFillsGaps() {
        val reply = """
            Here you go:
            ```json
            {"suggestions":[
              {"name":"SciShow Kids","type":"channel","query":"SciShow Kids","why":"short science answers"},
              {"name":"Dino playlist","type":"PLAYLIST","query":"dinosaur documentaries for kids","why":"curated dinos"},
              {"name":"No Query Channel","type":"channel","why":"query missing"},
              {"name":"","type":"channel","query":"nameless"}
            ]}
            ```
        """.trimIndent()
        val suggestions = AiScreener.parseSuggestions(reply)

        assertEquals(3, suggestions.size) // the nameless one is dropped
        assertEquals(SourceKind.CHANNEL, suggestions[0].kind)
        assertEquals(SourceKind.PLAYLIST, suggestions[1].kind)
        // A missing query falls back to the name, so verification can still run.
        assertEquals("No Query Channel", suggestions[2].searchQuery)
    }

    @Test
    fun discoveryPromptCarriesAgeAndRules() {
        val prompt = AiScreener.discoveryPrompt(
            AiConfig(rules = "No unboxing.", childAge = 7)
        )
        assertTrue(prompt.contains("aged 7"))
        assertTrue(prompt.contains("No unboxing."))
    }

    @Test
    fun aiConfigAndOverridesSurviveTheConfigJsonRoundTrip() {
        val original = Whitelist(
            sources = listOf(
                WhitelistEntry(
                    "UC4a-Gbdw7vOaccHmFo40b9g",
                    "https://www.youtube.com/channel/UC4a-Gbdw7vOaccHmFo40b9g",
                    "Khan Academy", SourceKind.CHANNEL
                )
            ),
            blockedVideoIds = setOf("dQw4w9WgXcQ"),
            ai = AiConfig(
                enabled = true,
                baseUrl = "https://openrouter.ai/api/v1",
                model = "anthropic/claude-haiku-4.5",
                apiKey = "sk-test",
                rules = "No scary content.",
                childAge = 6,
                rulesVersion = 3
            ),
            aiAllowedVideoIds = setOf("aaaaaaaaaaa", "bbbbbbbbbbb")
        )
        val reparsed = ConfigStore.fromJson(ConfigStore.toJson(original))

        assertEquals(original.ai, reparsed.ai)
        assertEquals(original.aiAllowedVideoIds, reparsed.aiAllowedVideoIds)
        assertEquals(original.sources, reparsed.sources)
        assertEquals(original.blockedVideoIds, reparsed.blockedVideoIds)
    }
}
