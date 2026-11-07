package io.pickwick.app

import io.pickwick.app.data.*
import org.junit.Assert.*
import org.junit.Test

class HomeRowTest {
    private fun channel(name: String) =
        Source(name, "https://www.youtube.com/@$name", name, null, SourceKind.CHANNEL)

    private val bluey = channel("bluey")
    private val numberblocks = channel("numberblocks")
    private val veritasium = channel("veritasium")
    private val row = HomeRow(id = "cartoons", name = "Cartoons", sourceUrls = setOf(bluey.url))

    @Test fun `rows round trip and change the sync fingerprint`() {
        val limited = row.copy(profileIds = setOf("sam"))
        val config = Whitelist(emptyList(), emptySet(), homeRows = listOf(limited))
        assertEquals(config, ConfigStore.fromJson(ConfigStore.toJson(config)))
        assertNotEquals(ConfigStore.fingerprint(config),
            ConfigStore.fingerprint(config.copy(homeRows = listOf(limited.copy(name = "Shows")))))
    }

    @Test fun `no rows leaves the fingerprint and old configs alone`() {
        val config = Whitelist(emptyList(), emptySet())
        assertFalse(ConfigStore.toJson(config).contains("homeRows"))
        assertTrue(ConfigStore.fromJson("{\"entries\":[],\"blocked\":[]}").homeRows.isEmpty())
    }

    @Test fun `row order in the config decides the fingerprint`() {
        val other = row.copy(id = "learning", name = "Learning")
        val config = Whitelist(emptyList(), emptySet(), homeRows = listOf(row, other))
        assertNotEquals(ConfigStore.fingerprint(config),
            ConfigStore.fingerprint(config.copy(homeRows = listOf(other, row))))
    }

    @Test fun `invalid rows are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { row.copy(name = " ") }
        assertThrows(IllegalArgumentException::class.java) { row.copy(id = "../../x") }
        assertThrows(IllegalArgumentException::class.java) { row.copy(name = "x".repeat(HomeRow.MAX_NAME + 1)) }
        val duplicate = Whitelist(emptyList(), emptySet(), homeRows = listOf(row, row))
        assertThrows(IllegalArgumentException::class.java) { ConfigStore.fromJson(ConfigStore.toJson(duplicate)) }
        val tooMany = Whitelist(emptyList(), emptySet(),
            homeRows = (0..HomeRow.MAX_ROWS).map { row.copy(id = "row-$it") })
        assertThrows(IllegalArgumentException::class.java) { ConfigStore.fromJson(ConfigStore.toJson(tooMany)) }
    }

    @Test fun `empty profile list means everyone`() {
        assertTrue(row.visibleTo("sam"))
        assertTrue(row.visibleTo(null))
        val limited = row.copy(profileIds = setOf("sam"))
        assertTrue(limited.visibleTo("sam"))
        assertFalse(limited.visibleTo("alex"))
        assertTrue(limited.visibleTo(null))
    }

    @Test fun `channels split into rows and the rest keeps the unclaimed`() {
        val layout = arrangeHome(listOf(bluey, numberblocks, veritasium), listOf(row))
        assertEquals(listOf(row to listOf(bluey)), layout.rows)
        assertEquals(listOf(numberblocks, veritasium), layout.rest)
    }

    @Test fun `channel order inside a row follows the channel list, not the row`() {
        val both = row.copy(sourceUrls = setOf(veritasium.url, bluey.url))
        assertEquals(listOf(bluey, veritasium), arrangeHome(listOf(bluey, veritasium), listOf(both)).rows[0].second)
        assertEquals(listOf(veritasium, bluey), arrangeHome(listOf(veritasium, bluey), listOf(both)).rows[0].second)
    }

    @Test fun `a channel in two rows shows only in the first`() {
        val second = row.copy(id = "favourites", name = "Favourites", sourceUrls = setOf(bluey.url, veritasium.url))
        val layout = arrangeHome(listOf(bluey, veritasium), listOf(row, second))
        assertEquals(listOf(bluey), layout.rows[0].second)
        assertEquals(listOf(veritasium), layout.rows[1].second)
        assertTrue(layout.rest.isEmpty())
    }

    @Test fun `a row with nothing visible drops out`() {
        val layout = arrangeHome(listOf(numberblocks), listOf(row))
        assertTrue(layout.rows.isEmpty())
        assertEquals(listOf(numberblocks), layout.rest)
    }
}
