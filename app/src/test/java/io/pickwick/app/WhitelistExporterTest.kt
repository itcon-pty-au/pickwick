package io.pickwick.app

import io.pickwick.app.data.Limits
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.data.WhitelistExporter
import io.pickwick.app.data.WhitelistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistExporterTest {

    private val original = Whitelist(
        sources = listOf(
            WhitelistEntry(
                "UC4a-Gbdw7vOaccHmFo40b9g",
                "https://www.youtube.com/channel/UC4a-Gbdw7vOaccHmFo40b9g",
                "Khan Academy", SourceKind.CHANNEL
            ),
            WhitelistEntry(
                "user/crashcoursekids",
                "https://www.youtube.com/user/crashcoursekids",
                null, SourceKind.CHANNEL
            ),
            WhitelistEntry(
                "c/TEDEd", "https://www.youtube.com/c/TEDEd", "TED-Ed", SourceKind.CHANNEL
            ),
            WhitelistEntry(
                "@veritasium", "https://www.youtube.com/@veritasium", null, SourceKind.CHANNEL
            ),
            WhitelistEntry(
                "PL8dPuuaLjXtNlUrzyH5r6jN9ulIgZBpdo",
                "https://www.youtube.com/playlist?list=PL8dPuuaLjXtNlUrzyH5r6jN9ulIgZBpdo",
                "Awesome Nature", SourceKind.PLAYLIST
            )
        ),
        blockedVideoIds = setOf("dQw4w9WgXcQ", "oHg5SJYRHA0"),
        limits = Limits(sessionMinutes = 30, bedtimeStartMin = 19 * 60 + 30, bedtimeEndMin = 7 * 60)
    )

    @Test
    fun exportRoundTripsThroughTheParser() {
        val reparsed = WhitelistParser.parse(WhitelistExporter.toText(original, "3 Aug 2026"))

        assertEquals(original.sources, reparsed.sources)
        assertEquals(original.blockedVideoIds, reparsed.blockedVideoIds)
        // Screen-time rules are comments only — never re-imported from a file.
        assertEquals(Limits(), reparsed.limits)
    }

    @Test
    fun labelsCannotBreakTheLineFormat() {
        val tricky = Whitelist(
            sources = listOf(
                WhitelistEntry(
                    "UC4a-Gbdw7vOaccHmFo40b9g",
                    "https://www.youtube.com/channel/UC4a-Gbdw7vOaccHmFo40b9g",
                    "Kids | #1 Science", SourceKind.CHANNEL
                )
            ),
            blockedVideoIds = emptySet()
        )
        val reparsed = WhitelistParser.parse(WhitelistExporter.toText(tricky))

        assertEquals(1, reparsed.sources.size)
        assertEquals("UC4a-Gbdw7vOaccHmFo40b9g", reparsed.sources[0].id)
        assertEquals("Kids  1 Science", reparsed.sources[0].label)
    }

    @Test
    fun limitsAppearAsCommentsForHumans() {
        val text = WhitelistExporter.toText(original)
        assertTrue(text.contains("#   time per session: 30 min"))
        assertTrue(text.contains("#   bedtime: 19:30–7:00"))
    }
}
