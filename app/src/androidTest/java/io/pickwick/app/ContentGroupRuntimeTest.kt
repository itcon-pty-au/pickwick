package io.pickwick.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.pickwick.app.data.*
import io.pickwick.app.ui.PlayerActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@RunWith(AndroidJUnit4::class)
class ContentGroupRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pokemon = ContentGroup(id = "pokemon-test", name = "Pokemon", sessionMinutes = mapOf("child" to 1))

    @Test fun parentCanEditPerChildCapsForAMixedContentGroup() {
        val source = WhitelistEntry("PLpokemon", "https://www.youtube.com/playlist?list=PLpokemon", "Pokemon videos", SourceKind.PLAYLIST)
        val catalog = SmbCatalog(name = "Pokemon collection", host = "router", share = "Videos")
        val group = pokemon.copy(youtubeSources = setOf(source.url), catalogIds = setOf(catalog.id), sessionMinutes = mapOf("alex" to 15, "sam" to 20))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(io.pickwick.app.ui.MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val groups = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(listOf(group)) }
                androidx.compose.material3.MaterialTheme(colorScheme = io.pickwick.app.ui.PickwickDarkColors) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                            io.pickwick.app.ui.ContentGroupSettings(groups.value, listOf(source), listOf(catalog),
                                listOf(Profile("alex", "Alex"), Profile("sam", "Sam"))) { groups.value = it }
                        }
                    }
                }
            } }
            assertTrue(device.wait(Until.hasObject(By.text("Alex: 15 min per session")), 10000))
            device.findObject(By.desc("Edit Pokemon")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Alex minutes")), 5000))
            device.findObject(By.clazz("android.widget.EditText").text("15")).text = "10"
            device.executeShellCommand("screencap -p /sdcard/Download/pickwick-content-group-editor.png")
            device.findObject(By.text("Save")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Alex: 10 min per session")), 5000))
            assertTrue(device.hasObject(By.text("Sam: 20 min per session")))
            assertTrue(device.hasObject(By.text("2 sources")))
        }
    }

    private fun withGuard(test: (SessionGuard, String) -> Unit) {
        val suffix = "_content_test_${UUID.randomUUID()}"
        try { test(SessionGuard(context, suffix), suffix) }
        finally { context.getSharedPreferences("limits$suffix", Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun overlappingCapsCountActualTimeAndSurviveRecreation() = withGuard { guard, suffix ->
        guard.saveLimits(Limits(sessionMinutes = 45, weekdaySessions = 3, weekendSessions = 3, breakMinutes = 15))
        val wider = pokemon.copy(id = "cartoons-test", name = "Cartoons", sessionMinutes = mapOf("child" to 2))
        val groups = listOf(pokemon, wider)
        guard.recordContent(groups, 45_000)
        assertEquals(15_000L, SessionGuard(context, suffix).contentRemaining(groups, "child")!!.second)
        assertNull(guard.contentRemaining(groups, "other"))
        guard.recordContent(groups, 15_000)
        assertEquals(pokemon to 0L, guard.contentRemaining(groups, "child"))
        assertEquals(60_000L, guard.contentRemaining(listOf(wider), "child")!!.second)
        assertNull(guard.checkStart(0))
        assertEquals(0L, guard.contentRemaining(groups, "child")!!.second)
        guard.recordContent(groups, 0)
        assertEquals(0L, guard.contentRemaining(groups, "child")!!.second)
    }

    @Test fun realSessionBoundariesResetButAppAndSourceSwitchesDoNot() = withGuard { guard, suffix ->
        val groups = listOf(pokemon)
        guard.saveLimits(Limits(sessionMinutes = 45, breakMinutes = 15))
        guard.recordContent(groups, 60_000)
        repeat(3) { guard.checkStart(); assertEquals(0L, guard.contentRemaining(groups, "child")!!.second) }
        val prefs = context.getSharedPreferences("limits$suffix", Context.MODE_PRIVATE)
        prefs.edit().putLong("lastWatchAt", System.currentTimeMillis() - 16 * 60_000L).commit()
        guard.checkStart()
        assertEquals(60_000L, guard.contentRemaining(groups, "child")!!.second)
        guard.recordContent(groups, 60_000)
        guard.grantExtraMinutes(5)
        assertEquals(60_000L, guard.contentRemaining(groups, "child")!!.second)
        guard.recordContent(groups, 60_000)
        prefs.edit().putString("day", "20000101").commit()
        assertEquals(60_000L, guard.contentRemaining(groups, "child")!!.second)
    }

    @Test fun noBreakRuleDoesNotResetContentOnEveryPlay() = withGuard { guard, _ ->
        guard.saveLimits(Limits(sessionMinutes = 45))
        guard.recordContent(listOf(pokemon), 60_000)
        repeat(3) { guard.checkStart(0) }
        assertEquals(0L, guard.contentRemaining(listOf(pokemon), "child")!!.second)
    }

    @Test fun parentSkipBreakResetsGroupCountersWithTheSitting() = withGuard { guard, _ ->
        guard.saveLimits(Limits(sessionMinutes = 1, breakMinutes = 15, breakPassUntilMillis = System.currentTimeMillis() + 600_000))
        guard.recordContent(listOf(pokemon), 60_000)
        assertNull(guard.tick(60_000))
        assertEquals(60_000L, guard.contentRemaining(listOf(pokemon), "child")!!.second)
    }

    @Test fun playlistMembershipSurvivesFeedReplacementAndCombinesWithNetwork() {
        val id = "PLtest${UUID.randomUUID()}"
        val url = "https://www.youtube.com/watch?v=$id"
        val source = WhitelistEntry(id, "https://www.youtube.com/playlist?list=$id", "Pokemon", SourceKind.PLAYLIST)
        val group = pokemon.copy(youtubeSources = setOf(source.url), catalogIds = setOf("catalog-test"))
        val config = Whitelist(listOf(source), emptySet(), contentGroups = listOf(group))
        val cache = VideoCache(context)
        cache.save(id, listOf(Video(url, "Episode", "Uploader", null, 1200)))
        cache.save(id, emptyList())
        assertEquals(listOf(group), matchingContentGroups(context, config, url))
        assertEquals(listOf(group), matchingContentGroups(context, config, "pickwick://smb/catalog-test/episode.mp4"))
        assertTrue(matchingContentGroups(context, config, "pickwick://smb/other/episode.mp4").isEmpty())
    }

    @Test fun zeroCapStopsNetworkQueueBeforePlaybackAndOffersOtherContent() = withGuard { _, suffix ->
        val store = ConfigStore(context)
        val original = store.load()
        val catalog = SmbCatalog(name = "Pokemon", host = "127.0.0.1", port = 1, share = "Videos")
        try {
            store.save(original.copy(sources = emptyList(), profiles = emptyList(), limits = Limits(),
                networkShares = emptyList(), networkCatalogs = listOf(catalog),
                contentGroups = listOf(pokemon.copy(catalogIds = setOf(catalog.id), sessionMinutes = mapOf("" to 0)))))
            val intent = Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE, arrayListOf(catalog.url("episode.mp4")))
                .putExtra(PlayerActivity.EXTRA_FROM_QUEUE, true).putExtra(PlayerActivity.EXTRA_PROFILE_SUFFIX, suffix)
                .putExtra(PlayerActivity.EXTRA_TIME_PERCENT, 0)
            ActivityScenario.launch<PlayerActivity>(intent).use {
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue(device.wait(Until.hasObject(By.text("Choose something else")), 10000))
                assertTrue(device.hasObject(By.textContains("Pokemon time for this session")))
                device.executeShellCommand("screencap -p /sdcard/Download/pickwick-content-cap.png")
                device.findObject(By.text("Choose something else")).click()
            }
        } finally { store.save(original) }
    }
}
