package io.pickwick.app

import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.AiScreener
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.CustomVideo
import io.pickwick.app.data.Profile
import io.pickwick.app.data.Quiz
import io.pickwick.app.data.QuizBonus
import io.pickwick.app.data.QuizQuestion
import io.pickwick.app.data.QuizRequest
import io.pickwick.app.data.QuizStore
import io.pickwick.app.data.Source
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class QuizTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val transcript = """
        Plants make their food using sunlight.
        This process is called photosynthesis,
        and it happens inside the leaves.
    """.trimIndent()

    private fun question(
        q: String = "What is the process called?",
        options: String = """["Photosynthesis","Digestion","Evaporation"]""",
        answer: Int = 0,
        evidence: String = "This process is called photosynthesis"
    ) = """{"q":"$q","options":$options,"answer":$answer,"why":"Plants do it.","evidence":"$evidence"}"""

    private fun reply(vararg questions: String) =
        """{"verdicts":[{"id":"v","v":"allow","why":"ok"}],"quiz":{"questions":[${questions.joinToString(",")}]}}"""

    // --- parsing ------------------------------------------------------------

    @Test
    fun `a quoted question parses, however the quote is punctuated`() {
        val parsed = Quiz.parse(
            reply(question(evidence = "this process is called, PHOTOSYNTHESIS and it happens")),
            transcript, 3
        )!!
        assertEquals("What is the process called?", parsed.single().text)
        assertEquals(0, parsed.single().answerIndex)
    }

    @Test
    fun `a question its transcript does not support is dropped`() {
        val made = question(q = "Which gas?", evidence = "plants breathe in carbon dioxide gas")
        assertEquals(listOf("What is the process called?"),
            Quiz.parse(reply(made, question()), transcript, 3)!!.map { it.text })
    }

    @Test
    fun `malformed questions are dropped one by one`() {
        val badAnswer = question(q = "A?", answer = 7)
        val oneOption = question(q = "B?", options = """["Only"]""")
        val duplicates = question(q = "C?", options = """["Same","same!"]""")
        val blankEvidence = question(q = "D?", evidence = "")
        assertEquals(1, Quiz.parse(
            reply(badAnswer, oneOption, duplicates, blankEvidence, question()), transcript, 3
        )!!.size)
    }

    @Test
    fun `the model declining is an answer, and silence is a failure`() {
        assertEquals(emptyList<QuizQuestion>(), Quiz.parse(reply(), transcript, 3))
        assertNull(Quiz.parse("""{"verdicts":[{"id":"v","v":"allow","why":"ok"}]}""", transcript, 3))
        assertNull(Quiz.parse("not json", transcript, 3))
    }

    @Test
    fun `the question limit holds even if the model overshoots`() {
        val many = (1..4).map { question(q = "Q$it?") }.toTypedArray()
        assertEquals(2, Quiz.parse(reply(*many), transcript, 2)!!.size)
    }

    @Test
    fun `quiz and verdict share one reply`() {
        val raw = reply(question())
        assertEquals(AiScreener.Verdict.ALLOW, AiScreener.parseVerdicts(raw, setOf("v")).single().verdict)
        assertEquals(1, Quiz.parse(raw, transcript, 3)!!.size)
    }

    @Test
    fun `the prompt only asks for a quiz when requested`() {
        val cfg = AiConfig()
        assertFalse("QUIZ" in AiScreener.deepSystemPrompt(cfg))
        val prompt = AiScreener.deepSystemPrompt(
            cfg, quiz = QuizRequest(maxQuestions = 2, age = 8, profileId = null)
        )
        assertTrue("aged 8" in prompt)
        assertTrue("at most 2" in prompt)
        assertTrue("Never invent" in prompt)
    }

    @Test
    fun `thin transcripts never reach the model as quiz material`() {
        assertFalse(Quiz.transcriptUsable(null))
        assertFalse(Quiz.transcriptUsable("too short"))
        assertTrue(Quiz.transcriptUsable("word ".repeat(100)))
    }

    // --- rules --------------------------------------------------------------

    @Test
    fun `short videos, skipped videos and recent quizzes get no quiz`() {
        val tenMin = 600_000L
        val now = 100_000_000L
        assertTrue(Quiz.shouldShow(tenMin, 420_000, now, 0))
        assertFalse("too short", Quiz.shouldShow(240_000, 240_000, now, 0))
        assertFalse("skipped ahead", Quiz.shouldShow(tenMin, 60_000, now, 0))
        assertFalse("quiz too recent", Quiz.shouldShow(tenMin, tenMin, now, now - 60_000))
    }

    @Test
    fun `a promise looks ahead to when the video ends`() {
        val now = 100_000_000L
        // Last quiz 10 minutes ago, but this video ends 10 minutes from now: 20 > 15.
        assertTrue(Quiz.canPromise(600, now, now - 600_000))
        assertFalse(Quiz.canPromise(300, now, now - 60_000))
        assertFalse(Quiz.canPromise(120, now, 0))
    }

    @Test
    fun `the daily cap trims the last award and then stops paying`() {
        val bonus = QuizBonus(secondsPerQuestion = 30, maxMinutesPerDay = 5)
        assertEquals(30_000, Quiz.awardMs(0, bonus))
        assertEquals(10_000, Quiz.awardMs(290_000, bonus))
        assertEquals(0, Quiz.awardMs(300_000, bonus))
    }

    @Test
    fun `shuffling keeps the answer on the right option`() {
        val q = QuizQuestion("Q", listOf("A", "B", "C", "D"), 2, "why")
        repeat(50) { seed ->
            val shuffled = Quiz.shuffled(listOf(q), Random(seed)).single()
            assertEquals("C", shuffled.options[shuffled.answerIndex])
        }
    }

    // --- which videos -------------------------------------------------------

    private val channel = WhitelistEntry("c", "https://youtube.com/@teach", null, SourceKind.CHANNEL, quiz = true)

    private fun enabled(
        entry: WhitelistEntry,
        videoId: String = "vid",
        name: String = "Other",
        uploader: String? = null,
        from: String? = null,
        sources: List<Source> = emptyList()
    ) = Quiz.enabledFor(listOf(entry), sources, videoId, name, uploader, from)

    @Test
    fun `a channel matches by uploader, resolved name or where the kid started`() {
        assertTrue(enabled(channel, uploader = "https://youtube.com/@teach/"))
        assertTrue(enabled(channel, from = channel.url))
        assertTrue(enabled(channel, name = "Teach", sources = listOf(
            Source("c", channel.url, "Teach", null, SourceKind.CHANNEL, 100, emptyList())
        )))
        assertFalse(enabled(channel))
        assertFalse(enabled(channel.copy(quiz = false), from = channel.url))
    }

    @Test
    fun `a custom list matches its own videos and a playlist where it was opened`() {
        val custom = WhitelistEntry("l", "pickwick://custom/l", "List", SourceKind.CUSTOM, quiz = true,
            customVideos = listOf(CustomVideo("vid", "t", "c", null, 600)))
        assertTrue(enabled(custom))
        assertFalse(enabled(custom, videoId = "other"))
        val playlist = WhitelistEntry("p", "https://youtube.com/playlist?list=PL", "P", SourceKind.PLAYLIST, quiz = true)
        assertTrue(enabled(playlist, from = playlist.url))
        assertFalse(enabled(playlist, name = "P"))
    }

    @Test
    fun `a kid's opt-out beats a channel's switch`() {
        val kid = Profile("a", "A", quizOff = true)
        val other = Profile("b", "B")
        val config = Whitelist(listOf(channel), emptySet(), profiles = listOf(kid, other))
        assertFalse(config.quizAllowedFor("a"))
        assertTrue(config.quizAllowedFor("b"))
        assertTrue(config.quizAllowedFor(null))
    }

    // --- persistence --------------------------------------------------------

    @Test
    fun `settings survive the config round trip and move the fingerprint`() {
        val plain = Whitelist(listOf(channel.copy(quiz = false)), emptySet(), profiles = listOf(Profile("a", "A")))
        val json = ConfigStore.toJson(plain)
        assertFalse("quizBonus" in json || "quizOff" in json || "\"quiz\"" in json)

        val rich = plain.copy(
            sources = listOf(channel),
            profiles = listOf(Profile("a", "A", quizOff = true)),
            quizBonus = QuizBonus(45, 2, 20)
        )
        val back = ConfigStore.fromJson(ConfigStore.toJson(rich))
        assertTrue(back.sources.single().quiz)
        assertTrue(back.profiles.single().quizOff)
        assertEquals(QuizBonus(45, 2, 20), back.quizBonus)

        val hash = ConfigStore.fingerprint(plain)
        assertNotEquals(hash, ConfigStore.fingerprint(plain.copy(sources = listOf(channel))))
        assertNotEquals(hash, ConfigStore.fingerprint(plain.copy(quizBonus = QuizBonus(45, 2, 20))))
        assertNotEquals(hash, ConfigStore.fingerprint(plain.copy(profiles = listOf(Profile("a", "A", quizOff = true)))))
    }

    @Test
    fun `out-of-range bonus settings from the LAN are clamped`() {
        val json = ConfigStore.toJson(Whitelist(emptyList(), emptySet()))
            .replaceFirst("{", """{"quizBonus":{"secondsPerQuestion":999999,"maxQuestions":0,"maxMinutesPerDay":-5},""")
        assertEquals(QuizBonus(300, 1, 5), ConfigStore.fromJson(json).quizBonus)
    }

    @Test
    fun `stored quizzes reload, and only for the age they were written for`() {
        val file = File(tmp.root, "quizzes.json")
        val q = QuizQuestion("Q", listOf("A", "B"), 1, "why")
        QuizStore(file).put("vid", QuizStore.Entry(8, listOf(q), 1L))
        assertEquals(listOf(q), QuizStore(file).get("vid", 8)?.questions)
        assertNull(QuizStore(file).get("vid", 4))
        assertNull(QuizStore(file).get("other", 8))
    }

    @Test
    fun `a declined video is remembered so it is not billed again`() {
        val file = File(tmp.root, "quizzes.json")
        QuizStore(file).put("vid", QuizStore.Entry(null, emptyList(), 1L))
        assertEquals(emptyList<QuizQuestion>(), QuizStore(file).get("vid", null)?.questions)
    }
}
