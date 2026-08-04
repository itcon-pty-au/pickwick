package io.pickwick.app

import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.WhitelistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistParserTest {

    @Test
    fun acceptsEveryEntryForm() {
        val list = WhitelistParser.parse(
            """
            # comment line
            UC4a-Gbdw7vOaccHmFo40b9g | Khan Academy
            https://www.youtube.com/user/crashcoursekids
            https://www.youtube.com/c/TEDEd | TED-Ed
            https://www.youtube.com/@veritasium
            @NatGeoKids
            youtube.com/channel/UCsooa4yRKGN_zEE8iknghZA
            https://www.youtube.com/playlist?list=PL8dPuuaLjXtNlUrzyH5r6jN9ulIgZBpdo
            PLbpi6ZahtOH6Blw3RGYYWD1o0dnvHnJ8- | Crash Course Kids
            https://www.youtube.com/watch?v=abcdefghijk&list=PLbpi6ZahtOH4a1eN2xtiGKcWOkoNGbcy2
            ! dQw4w9WgXcQ
            ! https://www.youtube.com/watch?v=oHg5SJYRHA0
            not-a-youtube-line
            https://vimeo.com/12345
            """.trimIndent()
        )

        val byId = list.sources.associateBy { it.id }

        // Bare channel ID
        assertEquals("https://www.youtube.com/channel/UC4a-Gbdw7vOaccHmFo40b9g",
            byId["UC4a-Gbdw7vOaccHmFo40b9g"]!!.url)
        assertEquals("Khan Academy", byId["UC4a-Gbdw7vOaccHmFo40b9g"]!!.label)

        // Legacy /user/ URL — the form the user asked about
        assertEquals("https://www.youtube.com/user/crashcoursekids",
            byId["user/crashcoursekids"]!!.url)
        assertEquals(SourceKind.CHANNEL, byId["user/crashcoursekids"]!!.kind)

        // /c/ vanity URL, with label
        assertEquals("https://www.youtube.com/c/TEDEd", byId["c/TEDEd"]!!.url)
        assertEquals("TED-Ed", byId["c/TEDEd"]!!.label)

        // @handle as URL and bare
        assertEquals("https://www.youtube.com/@veritasium", byId["@veritasium"]!!.url)
        assertEquals("https://www.youtube.com/@NatGeoKids", byId["@NatGeoKids"]!!.url)

        // Scheme-less /channel/ URL
        assertEquals(SourceKind.CHANNEL, byId["UCsooa4yRKGN_zEE8iknghZA"]!!.kind)

        // Playlist URL and bare playlist ID
        assertEquals(SourceKind.PLAYLIST, byId["PL8dPuuaLjXtNlUrzyH5r6jN9ulIgZBpdo"]!!.kind)
        assertEquals(SourceKind.PLAYLIST, byId["PLbpi6ZahtOH6Blw3RGYYWD1o0dnvHnJ8-"]!!.kind)

        // watch?v=..&list=.. is treated as its playlist
        assertEquals(SourceKind.PLAYLIST, byId["PLbpi6ZahtOH4a1eN2xtiGKcWOkoNGbcy2"]!!.kind)

        // Blocked videos, bare and as URL
        assertEquals(setOf("dQw4w9WgXcQ", "oHg5SJYRHA0"), list.blockedVideoIds)

        // Junk lines ignored
        assertTrue(list.sources.none { it.url.contains("vimeo") })
        assertEquals(9, list.sources.size)
    }
}
