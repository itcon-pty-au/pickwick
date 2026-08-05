package io.pickwick.app

import io.pickwick.app.data.Directory
import io.pickwick.app.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryParserTest {

    @Test
    fun `parses channels and playlists with ages and topics`() {
        val json = """
            {
              "language": "en",
              "updated": "2026-08-06",
              "entries": [
                {
                  "url": "https://www.youtube.com/@TEDEd",
                  "name": "TED-Ed",
                  "kind": "channel",
                  "ages": ["8-10", "11+"],
                  "topics": ["Science", "History"],
                  "note": "Animated answers to big questions.",
                  "added": "2026-08-06"
                },
                {
                  "url": "https://www.youtube.com/playlist?list=PLxyz",
                  "name": "Some Season",
                  "kind": "playlist",
                  "ages": ["2-4"],
                  "topics": ["Stories"],
                  "note": "A full season."
                }
              ]
            }
        """.trimIndent()

        val entries = Directory.parseEntries(json)
        assertEquals(2, entries.size)

        val ted = entries[0]
        assertEquals("TED-Ed", ted.name)
        assertEquals(SourceKind.CHANNEL, ted.kind)
        assertEquals(listOf("8-10", "11+"), ted.ages)
        assertEquals(listOf("Science", "History"), ted.topics)
        assertEquals("Animated answers to big questions.", ted.note)

        assertEquals(SourceKind.PLAYLIST, entries[1].kind)
    }

    @Test
    fun `tolerates missing fields and skips broken entries`() {
        val json = """
            {
              "entries": [
                {"url": "https://www.youtube.com/@ok", "name": "OK"},
                {"name": "no url"},
                {"url": "https://www.youtube.com/@nameless"}
              ]
            }
        """.trimIndent()

        val entries = Directory.parseEntries(json)
        assertEquals(1, entries.size)
        assertEquals("OK", entries[0].name)
        // Unspecified kind defaults to channel; lists default empty.
        assertEquals(SourceKind.CHANNEL, entries[0].kind)
        assertTrue(entries[0].ages.isEmpty())
        assertTrue(entries[0].topics.isEmpty())
    }

    @Test
    fun `empty or entryless documents parse to nothing`() {
        assertTrue(Directory.parseEntries("{}").isEmpty())
        assertTrue(Directory.parseEntries("""{"entries": []}""").isEmpty())
    }
}
