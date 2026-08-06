package io.pickwick.app

import io.pickwick.app.data.AiScreener
import io.pickwick.app.data.ScreeningStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verdicts are paid for once; losing them re-bills the family. These tests pin
 * the persistence and merge behavior that protects that money: atomic writes,
 * the never-overwrite import rule, the size cap, and writes from separate
 * store instances landing in one file without clobbering each other.
 */
class ScreeningStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storeFile() = File(tmp.root, "screening.json")

    private fun entry(at: Long = 1L, rv: Int = 1, verdict: AiScreener.Verdict = AiScreener.Verdict.ALLOW) =
        ScreeningStore.Entry(
            verdict = verdict, reason = "r", title = "t", channel = "c",
            thumb = null, rulesVersion = rv, at = at
        )

    @Test
    fun `verdicts survive a reload through a fresh instance`() {
        ScreeningStore(storeFile()).putAll(mapOf("vid1" to entry(), "vid2" to entry(verdict = AiScreener.Verdict.BLOCK)))
        val reread = ScreeningStore(storeFile())
        assertEquals(AiScreener.Verdict.ALLOW, reread.get("vid1")?.verdict)
        assertEquals(AiScreener.Verdict.BLOCK, reread.get("vid2")?.verdict)
    }

    @Test
    fun `persist leaves no temp file behind and survives repeated writes`() {
        val store = ScreeningStore(storeFile())
        store.putAll(mapOf("a" to entry(at = 1)))
        // Second write renames over an existing file — the path that differs per OS.
        store.putAll(mapOf("b" to entry(at = 2)))
        assertNotNull(store.get("a"))
        assertNotNull(store.get("b"))
        assertFalse(File(tmp.root, "screening.json.tmp").exists())
    }

    @Test
    fun `import never overwrites an existing verdict for the same rules`() {
        val store = ScreeningStore(storeFile())
        store.putAll(mapOf("vid" to entry(verdict = AiScreener.Verdict.ALLOW)))
        val peer = ScreeningStore(File(tmp.root, "peer.json"))
        peer.putAll(mapOf("vid" to entry(verdict = AiScreener.Verdict.BLOCK), "new" to entry()))
        val imported = store.importJson(peer.exportJson(1), 1)
        assertEquals(1, imported)
        assertEquals(AiScreener.Verdict.ALLOW, store.get("vid")?.verdict)
        assertNotNull(store.get("new"))
    }

    @Test
    fun `import ignores entries from a different rules version`() {
        val store = ScreeningStore(storeFile())
        val peer = ScreeningStore(File(tmp.root, "peer.json"))
        peer.putAll(mapOf("old" to entry(rv = 1), "cur" to entry(rv = 2)))
        assertEquals(1, store.importJson(peer.exportJson(2), 2))
        assertNull(store.get("old"))
        assertNotNull(store.get("cur"))
    }

    @Test
    fun `cap strips the oldest verdicts once past 5000`() {
        val store = ScreeningStore(storeFile())
        store.putAll((1..5001).associate { "v$it" to entry(at = it.toLong()) })
        assertEquals(4000, store.screenedCount(1))
        assertNull(store.get("v1"))
        assertNotNull(store.get("v5001"))
    }

    @Test
    fun `concurrent writers through separate instances lose nothing`() {
        // The production shape of the race: the feed screener's putAll on one
        // instance while the LAN server imports through another.
        val a = ScreeningStore(storeFile())
        val b = ScreeningStore(storeFile())
        val t1 = Thread { repeat(50) { i -> a.putAll(mapOf("a$i" to entry(at = i.toLong()))) } }
        val t2 = Thread { repeat(50) { i -> b.putAll(mapOf("b$i" to entry(at = i.toLong()))) } }
        t1.start(); t2.start(); t1.join(); t2.join()
        val reread = ScreeningStore(storeFile())
        repeat(50) { i ->
            assertNotNull("a$i lost", reread.get("a$i"))
            assertNotNull("b$i lost", reread.get("b$i"))
        }
    }
}
