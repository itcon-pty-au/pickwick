package io.pickwick.app

import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.AiScreener
import io.pickwick.app.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-kid screening: prompt shape, verdict parsing, fail-safe behavior. */
class AiPerProfileTest {

    private val dave = Profile(id = "aaaa1111", name = "Dave", age = 4)
    private val katy = Profile(id = "bbbb2222", name = "Katy", age = 12)
    private val profiles = listOf(dave, katy)
    private val keys = AiScreener.profileKeys(profiles)

    @Test
    fun `prompt lists every kid with age and demands per-kid verdict keys`() {
        val system = AiScreener.systemPrompt(AiConfig(rules = "No scary stuff."), profiles)
        assertTrue(system.contains("p1 = Dave (age 4)"))
        assertTrue(system.contains("p2 = Katy (age 12)"))
        assertTrue(system.contains("\"p1\":\"allow|block|review\""))
        assertTrue(system.contains("No scary stuff."))
    }

    @Test
    fun `per-kid verdict object parses into per-profile map with strictest overall`() {
        val reply = """
            {"verdicts":[
              {"id":"aaaaaaaaaaa","v":{"p1":"block","p2":"allow"},"why":"too intense for a 4yo"}
            ]}
        """.trimIndent()
        val r = AiScreener.parseVerdicts(reply, setOf("aaaaaaaaaaa"), keys).single()

        assertEquals(AiScreener.Verdict.BLOCK, r.perProfile[dave.id])
        assertEquals(AiScreener.Verdict.ALLOW, r.perProfile[katy.id])
        // Overall = strictest, so profile-unaware readers stay fail-closed.
        assertEquals(AiScreener.Verdict.BLOCK, r.verdict)
    }

    @Test
    fun `a kid the model skipped fails safe to review`() {
        val reply = """{"verdicts":[{"id":"aaaaaaaaaaa","v":{"p1":"allow"},"why":""}]}"""
        val r = AiScreener.parseVerdicts(reply, setOf("aaaaaaaaaaa"), keys).single()

        assertEquals(AiScreener.Verdict.ALLOW, r.perProfile[dave.id])
        assertEquals(AiScreener.Verdict.REVIEW, r.perProfile[katy.id])
        assertEquals(AiScreener.Verdict.REVIEW, r.verdict)
    }

    @Test
    fun `a model that answers with a plain string applies it to every kid`() {
        val reply = """{"verdicts":[{"id":"aaaaaaaaaaa","v":"block","why":"gore"}]}"""
        val r = AiScreener.parseVerdicts(reply, setOf("aaaaaaaaaaa"), keys).single()

        assertEquals(AiScreener.Verdict.BLOCK, r.perProfile[dave.id])
        assertEquals(AiScreener.Verdict.BLOCK, r.perProfile[katy.id])
    }

    @Test
    fun `a video the model dropped entirely is review for every kid`() {
        val reply = """{"verdicts":[]}"""
        val r = AiScreener.parseVerdicts(reply, setOf("aaaaaaaaaaa"), keys).single()

        assertEquals(AiScreener.Verdict.REVIEW, r.verdict)
        assertEquals(AiScreener.Verdict.REVIEW, r.perProfile[dave.id])
        assertEquals(AiScreener.Verdict.REVIEW, r.perProfile[katy.id])
    }

    @Test
    fun `legacy no-profile parsing is unchanged`() {
        val reply = """{"verdicts":[{"id":"aaaaaaaaaaa","v":"allow","why":"fine"}]}"""
        val r = AiScreener.parseVerdicts(reply, setOf("aaaaaaaaaaa")).single()
        assertEquals(AiScreener.Verdict.ALLOW, r.verdict)
        assertTrue(r.perProfile.isEmpty())
    }

    @Test
    fun `strictest ordering is block over review over allow`() {
        assertEquals(
            AiScreener.Verdict.BLOCK,
            AiScreener.strictest(listOf(AiScreener.Verdict.ALLOW, AiScreener.Verdict.BLOCK))
        )
        assertEquals(
            AiScreener.Verdict.REVIEW,
            AiScreener.strictest(listOf(AiScreener.Verdict.ALLOW, AiScreener.Verdict.REVIEW))
        )
        assertEquals(
            AiScreener.Verdict.ALLOW,
            AiScreener.strictest(listOf(AiScreener.Verdict.ALLOW))
        )
    }
}
