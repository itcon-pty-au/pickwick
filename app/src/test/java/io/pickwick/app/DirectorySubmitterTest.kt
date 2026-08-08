package io.pickwick.app

import io.pickwick.app.data.DirectorySubmitter
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.WhitelistEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorySubmitterTest {

    private val entries = listOf(
        WhitelistEntry(
            "UC4a-Gbdw7vOaccHmFo40b9g",
            "https://www.youtube.com/channel/UC4a-Gbdw7vOaccHmFo40b9g",
            "Khan Academy", SourceKind.CHANNEL
        ),
        WhitelistEntry(
            "PL8dPuuaLjXtNlUrzyH5r6jN9ulIgZBpdo",
            "https://www.youtube.com/playlist?list=PL8dPuuaLjXtNlUrzyH5r6jN9ulIgZBpdo",
            "Awesome Nature", SourceKind.PLAYLIST
        )
    )

    @Test
    fun requestCarriesOnlyUrlsAndLanguage() {
        val o = JSONObject(DirectorySubmitter.buildRequest(entries, "en"))

        assertEquals(setOf("urls", "lang"), o.keys().asSequence().toSet())
        assertEquals("en", o.getString("lang"))
        val urls = o.getJSONArray("urls")
        assertEquals(2, urls.length())
        assertEquals(entries[0].url, urls.getString(0))
        assertEquals(entries[1].url, urls.getString(1))
    }

    @Test
    fun emptyListStillBuildsValidJson() {
        val o = JSONObject(DirectorySubmitter.buildRequest(emptyList(), "es"))
        assertEquals(0, o.getJSONArray("urls").length())
    }

    @Test
    fun languageCodeMustBeTwoLowercaseLetters() {
        assertTrue(DirectorySubmitter.isValidLang("en"))
        assertTrue(DirectorySubmitter.isValidLang("es"))
        assertFalse(DirectorySubmitter.isValidLang("EN"))
        assertFalse(DirectorySubmitter.isValidLang("e"))
        assertFalse(DirectorySubmitter.isValidLang("eng"))
        assertFalse(DirectorySubmitter.isValidLang(""))
        assertFalse(DirectorySubmitter.isValidLang("e1"))
    }

    @Test
    fun parsesTheCountedOkResponse() {
        val r = DirectorySubmitter.parseResponse(
            """{"status":"ok","submitted":3,"duplicates":12,"invalid":1,"queueFull":2}"""
        )
        assertEquals("ok", r.status)
        assertEquals(3, r.submitted)
        assertEquals(12, r.duplicates)
        assertEquals(1, r.invalid)
        assertEquals(2, r.queueFull)
    }

    @Test
    fun missingCountsReadAsZero() {
        val r = DirectorySubmitter.parseResponse("""{"status":"busy"}""")
        assertEquals("busy", r.status)
        assertEquals(0, r.submitted)
    }

    @Test
    fun unreadableBodyIsAnErrorNotASuccess() {
        assertEquals("error", DirectorySubmitter.parseResponse("<html>502</html>").status)
        assertEquals("error", DirectorySubmitter.parseResponse("").status)
        assertEquals("error", DirectorySubmitter.parseResponse("{}").status)
    }

    @Test
    fun summaryMentionsEveryNonZeroOutcome() {
        val text = DirectorySubmitter.summarize(
            DirectorySubmitter.Result("ok", submitted = 3, duplicates = 12, queueFull = 2)
        )
        assertTrue(text.contains("Sent 3 for review"))
        assertTrue(text.contains("12 already in the directory"))
        assertTrue(text.contains("queue filled before 2"))
        assertTrue(text.contains("submit again"))
        // Zero counts are noise, not information.
        assertFalse(text.contains("0 "))
    }

    @Test
    fun workerSideErrorsReadAsRetryAdviceNotBadLinks() {
        val r = DirectorySubmitter.parseResponse(
            """{"status":"ok","submitted":2,"errors":3}"""
        )
        assertEquals(3, r.errors)
        val text = DirectorySubmitter.summarize(r)
        assertTrue(text.contains("Sent 2 for review"))
        assertTrue(text.contains("3 hit a glitch"))
        // GitHub trouble must not send the parent off re-editing good URLs.
        assertFalse(text.contains("couldn't be read"))
    }

    @Test
    fun combineSumsCountsAndKeepsTheStickyStatus() {
        val a = DirectorySubmitter.Result("ok", submitted = 3, duplicates = 1)
        val b = DirectorySubmitter.Result("busy", submitted = 1, queueFull = 4, errors = 2)
        val c = DirectorySubmitter.combine(a, b)
        assertEquals("busy", c.status)
        assertEquals(4, c.submitted)
        assertEquals(1, c.duplicates)
        assertEquals(4, c.queueFull)
        assertEquals(2, c.errors)
        // A later "ok" chunk must not clear an earlier failure.
        assertEquals("busy", DirectorySubmitter.combine(c, DirectorySubmitter.Result("ok")).status)
    }

    @Test
    fun partialProgressSurvivesABusyOrFailedTail() {
        val busy = DirectorySubmitter.summarize(DirectorySubmitter.Result("busy", submitted = 3))
        assertTrue(busy.contains("Sent 3 for review"))
        val err = DirectorySubmitter.summarize(DirectorySubmitter.Result("error", submitted = 2))
        assertTrue(err.contains("Sent 2 for review"))
    }

    @Test
    fun busyAndErrorReadAsPlainAdvice() {
        assertTrue(DirectorySubmitter.summarize(DirectorySubmitter.Result("busy")).contains("full"))
        assertTrue(
            DirectorySubmitter.summarize(DirectorySubmitter.Result("error")).contains("try again")
        )
    }
}
