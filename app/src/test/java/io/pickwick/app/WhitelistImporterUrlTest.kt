package io.pickwick.app

import io.pickwick.app.data.WhitelistImporter
import org.junit.Assert.assertEquals
import org.junit.Test

class WhitelistImporterUrlTest {

    @Test
    fun rewritesGithubBlobPageToRawFile() {
        assertEquals(
            "https://raw.githubusercontent.com/itcon-pty-au/pickwick/main/whitelist.txt",
            WhitelistImporter.normalize(
                "https://github.com/itcon-pty-au/pickwick/blob/main/whitelist.txt"
            )
        )
    }

    @Test
    fun keepsCommitPinnedPathsIntact() {
        val sha = "ea5a6c6ecbbcf1aa92496f7424bd9c84fc86426e"
        assertEquals(
            "https://raw.githubusercontent.com/itcon-pty-au/pickwick/$sha/whitelist.txt",
            WhitelistImporter.normalize(
                "https://github.com/itcon-pty-au/pickwick/blob/$sha/whitelist.txt"
            )
        )
    }

    @Test
    fun passesRawAndForeignUrlsThrough() {
        val raw = "https://raw.githubusercontent.com/itcon-pty-au/pickwick/main/whitelist.txt"
        assertEquals(raw, WhitelistImporter.normalize(raw))
        assertEquals(raw, WhitelistImporter.normalize("  $raw  "))
        val gist = "https://example.com/whitelist.txt"
        assertEquals(gist, WhitelistImporter.normalize(gist))
    }
}
