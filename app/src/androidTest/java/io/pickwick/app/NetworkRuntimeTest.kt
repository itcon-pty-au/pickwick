package io.pickwick.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pickwick.app.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@RunWith(AndroidJUnit4::class)
class NetworkRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun connectionSettingsSeparateCatalogFoldersFromLoginDetails() {
        val share = SmbShare(name = "Router USB", host = "127.0.0.1", share = "Videos", port = 1)
        val first = share.catalog("ui-one", "Pokemon", "Kids/Pokemon")
        val second = share.catalog("ui-two", "Movies", "Kids/Movies")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(io.pickwick.app.ui.MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val state = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(Whitelist(emptyList(), emptySet(),
                        networkShares = listOf(share), networkCatalogs = listOf(first, second)))
                }
                androidx.compose.material3.MaterialTheme(colorScheme = io.pickwick.app.ui.PickwickDarkColors) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                            io.pickwick.app.ui.NetworkCatalogSettings(state.value.networkShares, state.value.networkCatalogs,
                                listOf(Profile("one", "Alex"), Profile("two", "Sam"))) { shares, catalogs ->
                                state.value = state.value.copy(networkShares = shares, networkCatalogs = catalogs)
                            }
                        }
                    }
                }
            } }
            assertTrue(device.wait(Until.hasObject(By.text("Router USB")), 10000))
            assertTrue(device.hasObject(By.text("Kids/Pokemon")))
            assertEquals(1, device.findObjects(By.text("Add catalog folder")).size)
            device.executeShellCommand("screencap -p /sdcard/Download/pickwick-share-settings.png")
            device.findObject(By.text("Add catalog folder")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Catalog name")), 5000))
            assertFalse(device.hasObject(By.text("Server hostname or IP")))
            assertTrue(device.wait(Until.hasObject(By.text("Cancel").enabled(true)), 10000))
            device.findObject(By.text("Cancel")).click()
            device.findObject(By.desc("Edit connection Router USB")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Server hostname or IP")), 5000))
            assertFalse(device.hasObject(By.text("Catalog name")))
            device.findObject(By.text("Cancel")).click()
            for (name in listOf("Pokemon", "Movies")) {
                device.findObject(By.desc("Remove $name")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Remove $name?")), 5000))
                device.findObject(By.text("Remove")).click()
                assertTrue(device.wait(Until.gone(By.text(name)), 5000))
            }
            assertTrue(device.hasObject(By.text("Router USB")))
            assertTrue(device.hasObject(By.text("Add catalog folder")))
        }
    }

    @Test fun reusableConnectionCredentialsSurviveSaveAndSecretlessSync() {
        val store = ConfigStore(context)
        val original = store.load()
        val share = SmbShare(name = "USB", host = "127.0.0.1", share = "Videos", guest = false,
            username = "parent", password = "connection-test-password")
        try {
            store.save(original.copy(networkShares = listOf(share), networkCatalogs = emptyList()))
            assertEquals(share, store.load().networkShares.single())
            assertFalse(java.io.File(context.filesDir, "config.json").readText().contains(share.password))
            assertTrue(store.saveRaw(ConfigStore.toJson(store.load(), includeSecrets = false)))
            assertEquals(share, store.load().networkShares.single())
            val catalogs = listOf(share.catalog("one", "Pokemon", "Kids/Pokemon"), share.catalog("two", "Movies", "Kids/Movies"))
            store.save(store.load().copy(networkCatalogs = catalogs))
            assertEquals(catalogs, store.load().networkCatalogs)
            val updated = share.copy(password = "updated-connection-password")
            store.save(store.load().copy(networkShares = listOf(updated)))
            assertTrue(store.load().networkCatalogs.all { it.password == updated.password })
            assertTrue(store.saveRaw(ConfigStore.toJson(store.load(), includeSecrets = false)))
            assertTrue(store.load().networkCatalogs.all { it.password == updated.password })
        } finally {
            store.save(original)
            listOf("share-${share.id}", "one", "two").forEach { SecretStore(context).setNetworkPassword(it, "") }
        }
    }

    @Test fun savedNetworkVideosFollowCurrentChildAccess() = kotlinx.coroutines.runBlocking {
        val store = ConfigStore(context)
        val original = store.load()
        val suffix = "-network-access-${UUID.randomUUID()}"
        val c = SmbCatalog(name = "Test", host = "127.0.0.1", share = "Videos", profileIds = setOf("child"), timePercent = 0)
        val video = Video(c.url("episode.mp4"), "Episode", c.name, null, 1200)
        val favorites = SavedListStore(context, suffix)
        val later = SavedListStore(context, suffix, SavedListStore.WATCH_LATER)
        val queue = QueueStore(context, suffix)
        val owner = androidx.lifecycle.ViewModelStore()
        try {
            val config = original.copy(sources = emptyList(), networkCatalogs = listOf(c),
                profiles = listOf(Profile("child", "Child"), Profile("other", "Other")), limits = Limits())
            store.save(config)
            favorites.add(video); later.add(video); queue.add(video)
            val vm = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                io.pickwick.app.ui.MainViewModel(WhitelistRepository(store), WatchHistoryStore(context, suffix),
                    SourceCache(context), VideoCache(context), UsageStore(context, suffix), SessionGuard(context, suffix),
                    favorites, later, queue, activeProfileId = "child").also { owner.put("test", it) }
            }
            kotlinx.coroutines.withTimeout(10000) {
                while (vm.state.value.networkTimePercents[c.id] != 0) kotlinx.coroutines.delay(50)
            }
            for (screen in listOf(io.pickwick.app.ui.Screen.Watchlist, io.pickwick.app.ui.Screen.WatchLater, io.pickwick.app.ui.Screen.Queue)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    when (screen) {
                        io.pickwick.app.ui.Screen.Watchlist -> vm.openWatchlist()
                        io.pickwick.app.ui.Screen.WatchLater -> vm.openWatchLater()
                        else -> vm.openQueue()
                    }
                }
                kotlinx.coroutines.withTimeout(5000) {
                    while (vm.state.value.screen != screen || vm.state.value.videos.size != 1) kotlinx.coroutines.delay(50)
                }
            }
            store.save(config.copy(networkCatalogs = listOf(c.copy(profileIds = setOf("other")))))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { vm.refresh() }.join()
            assertTrue(vm.state.value.videos.isEmpty())
            assertTrue(vm.state.value.networkTimePercents.isEmpty())
            // Revoking access hides the item without destroying the child's saved picks.
            assertEquals(listOf(video), favorites.load())
            assertEquals(listOf(video), queue.load())
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { owner.clear() }
            store.save(original)
            context.filesDir.listFiles()?.filter { it.name.contains(suffix) }?.forEach { it.delete() }
        }
    }

    @Test fun embeddedTrackChoicesKeepExactSelectionAndExcludeUnsupportedTracks() {
        val formats = arrayOf(
            androidx.media3.common.Format.Builder().setId("a").setSampleMimeType("audio/mp4a-latm").setLanguage("en").build(),
            androidx.media3.common.Format.Builder().setId("b").setSampleMimeType("audio/mp4a-latm").setLanguage("en").build(),
            androidx.media3.common.Format.Builder().setId("c").setSampleMimeType("audio/mp4a-latm").setLanguage("ja").build()
        )
        val group = androidx.media3.common.TrackGroup("audio", *formats)
        val tracks = androidx.media3.common.Tracks(com.google.common.collect.ImmutableList.of(
            androidx.media3.common.Tracks.Group(group, false,
                intArrayOf(androidx.media3.common.C.FORMAT_HANDLED, androidx.media3.common.C.FORMAT_HANDLED,
                    androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE), booleanArrayOf(false, true, false))
        ))
        val choices = io.pickwick.app.ui.embeddedTracks(tracks, androidx.media3.common.C.TRACK_TYPE_AUDIO)
        assertEquals(2, choices.size)
        assertNotEquals(choices[0].name, choices[1].name)
        assertFalse(choices[0].selected)
        assertTrue(choices[1].selected)
        assertEquals(listOf(1), choices[1].override.trackIndices)
        assertEquals(group, choices[1].override.mediaTrackGroup)
        assertTrue(io.pickwick.app.ui.embeddedTracks(tracks, androidx.media3.common.C.TRACK_TYPE_TEXT).isEmpty())
    }

    @Test fun networkSavedListsArePersistentAndProfileScoped() {
        val suffix = "-network-test-${UUID.randomUUID()}"
        val video = Video("pickwick://smb/test/Season%201/episode.mp4", "Episode", "Collection", null, 1200)
        try {
            for (name in listOf(SavedListStore.FAVORITES, SavedListStore.WATCH_LATER)) {
                SavedListStore(context, suffix, name).add(video)
                assertEquals(listOf(video), SavedListStore(context, suffix, name).load())
                assertTrue(SavedListStore(context, "$suffix-other", name).load().isEmpty())
                SavedListStore(context, suffix, name).remove(video.url)
                assertTrue(SavedListStore(context, suffix, name).load().isEmpty())
            }
        } finally {
            context.filesDir.listFiles()?.filter { it.name.contains(suffix) }?.forEach { it.delete() }
        }
    }

    @Test fun cachedSeasonsRemainBrowsableWhenTheDriveIsOffline() {
        val store = ConfigStore(context)
        val original = store.load()
        val c = SmbCatalog(name = "Network test collection", host = "127.0.0.1", port = 1, share = "Videos")
        val library = SmbLibrary(context)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            store.save(original.copy(networkCatalogs = listOf(c), profiles = emptyList(), deviceProfiles = emptyMap(), limits = Limits()))
            library.save(c, "", listOf(NetworkFile("Season 01", "Season 01", true), NetworkFile("Season 02", "Season 02", true)))
            library.save(c, "Season 01", listOf(NetworkFile("Episode 2.mp4", "Season 01/Episode 2.mp4", false, duration = 1200),
                NetworkFile("Episode 10.mp4", "Season 01/Episode 10.mp4", false, duration = 1200)))
            ActivityScenario.launch(io.pickwick.app.ui.MainActivity::class.java).use {
                assertTrue(device.wait(Until.hasObject(By.text("Network shares")), 15000))
                device.findObject(By.text("Network shares")).click()
                assertTrue(device.wait(Until.hasObject(By.text(c.name)), 10000))
                device.findObject(By.text(c.name)).click()
                assertTrue(device.wait(Until.hasObject(By.text("Season 01")), 10000))
                device.executeShellCommand("screencap -p /sdcard/Download/pickwick-network-seasons.png")
                device.findObject(By.text("Season 01")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Episode 2")), 10000))
                val first = device.findObject(By.text("Episode 2")).visibleBounds
                val second = device.findObject(By.text("Episode 10")).visibleBounds
                assertTrue(first.top < second.top || (first.top == second.top && first.left < second.left))
                assertFalse(device.hasObject(By.textContains("The network drive is unavailable")))
                for (action in listOf("Add to Favorites", "Add to Watch later", "Add to Up next")) {
                    device.findObject(By.text("Episode 2")).longClick()
                    assertTrue(device.wait(Until.hasObject(By.text(action)), 5000))
                    device.findObject(By.text(action)).click()
                    assertTrue(device.wait(Until.gone(By.text(action)), 5000))
                }
                device.findObject(By.desc("Refresh")).click()
                assertTrue(device.wait(Until.hasObject(By.textContains("The network drive is unavailable")), 20000))
                device.executeShellCommand("screencap -p /sdcard/Download/pickwick-network-episodes-offline.png")
                repeat(3) { device.pressBack(); device.waitForIdle() }
                assertTrue(device.wait(Until.hasObject(By.text("Favorites")), 5000))
                device.findObject(By.text("Favorites")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Episode 2")), 5000))
            }
        } finally { store.save(original) }
    }

    @Test fun networkListingsAndArtworkRemainLocalAcrossLibraryInstances() {
        val c = SmbCatalog(name = "Cache test", host = "127.0.0.1", port = 1, share = "Videos")
        val image = File(context.cacheDir, "network-library-test-${c.id}-scene-v2.jpg")
        image.writeBytes(byteArrayOf(1, 2, 3))
        val library = SmbLibrary(context)
        library.save(c, "", listOf(NetworkFile("Season 01", "Season 01", true)))
        library.save(c, "Season 01", listOf(NetworkFile("Episode 1.mp4", "Season 01/Episode 1.mp4", false,
            duration = 1200, thumbnail = image.toURI().toString())))
        val restored = SmbLibrary(context)
        assertTrue(restored.isFresh(c, ""))
        val cover = restored.cover(c, "")
        assertNotNull(cover)
        assertTrue(File(java.net.URI(cover!!)).absolutePath.startsWith(context.filesDir.absolutePath))
        image.delete()
        assertEquals(cover, SmbLibrary(context).cover(c, ""))
        library.save(c, "empty", emptyList())
        assertTrue(restored.isFresh(c, "empty"))
        assertTrue(restored.cached(c, "empty").isEmpty())
    }

    @Test fun networkCredentialsSurviveStorageWithoutEnteringConfigFile() {
        val store = ConfigStore(context)
        val original = store.load()
        val c = SmbCatalog(name = "Test", host = "127.0.0.1", share = "Videos", guest = false,
            username = "test", password = "runtime-only-password")
        try {
            store.save(original.copy(networkCatalogs = original.networkCatalogs + c))
            assertEquals(c, store.load().networkCatalogs.first { it.id == c.id })
            assertFalse(File(context.filesDir, "config.json").readText().contains(c.password))
            // An older/backup payload without secrets must not erase a stored password.
            assertTrue(store.saveRaw(ConfigStore.toJson(store.load(), includeSecrets = false)))
            assertEquals(c.password, store.load().networkCatalogs.first { it.id == c.id }.password)
            val sealed = NetworkConfigCrypto.seal(store.rawJson(), NetworkConfigCrypto.publicKey())
            assertFalse(sealed.contains(c.password))
            assertEquals(c, ConfigStore.fromJson(NetworkConfigCrypto.open(sealed)).networkCatalogs.first { it.id == c.id })
        } finally {
            store.save(original)
            SecretStore(context).setNetworkPassword(c.id, "")
        }
    }

    @Test fun freeCatalogContinuesAfterBudgetButParentPauseStillStopsIt() = withGuard { guard ->
        val limits = Limits(sessionMinutes = 1, weekdaySessions = 1, weekendSessions = 1)
        guard.saveLimits(limits)
        assertNull(guard.checkStart(100))
        assertNotNull(guard.tick(60000, multiplierPercent = 100))
        assertNotNull(guard.checkStart(100))
        assertNull(guard.checkStart(0))
        assertNull(guard.tick(0, multiplierPercent = 0))
        guard.saveLimits(limits.copy(pausedUntilMillis = System.currentTimeMillis() + 60000))
        assertNotNull(guard.checkStart(0))
        assertNotNull(guard.tick(0, multiplierPercent = 0))
    }

    @Test fun aBreakStillBlocksFreeCatalogs() = withGuard { guard ->
        guard.saveLimits(Limits(sessionMinutes = 1, weekdaySessions = 10, weekendSessions = 10, breakMinutes = 15))
        assertNotNull(guard.tick(60000, multiplierPercent = 100))
        assertNotNull(guard.checkStart(0))
    }

    @Test fun blockedTimeStillStopsFreeCatalogs() = withGuard { guard ->
        val now = java.util.Calendar.getInstance()
        val minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        guard.saveLimits(Limits(windows = listOf(TimeWindow("test", "Quiet time",
            (minute + 1439) % 1440, (minute + 2) % 1440, ALL_DAYS))))
        assertNotNull(guard.checkStart(0))
        assertNotNull(guard.tick(0, multiplierPercent = 0))
    }

    private fun withGuard(test: (SessionGuard) -> Unit) {
        val suffix = "_network_test_" + UUID.randomUUID()
        try { test(SessionGuard(context, suffix)) }
        finally { context.getSharedPreferences("limits$suffix", Context.MODE_PRIVATE).edit().clear().commit() }
    }
}
