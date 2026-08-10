package io.pickwick.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.pickwick.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// FragmentActivity: required host for androidx BiometricPrompt (parent gate).
class MainActivity : androidx.fragment.app.FragmentActivity() {

    /** Pairing progresses: scanned → confirm → waiting for approval → result. */
    sealed interface PairFlow {
        data class Confirm(val name: String, val host: String, val port: Int) : PairFlow
        data class Waiting(val name: String, val host: String, val port: Int) : PairFlow
        data class Result(val message: String) : PairFlow
    }

    private val pairFlow = mutableStateOf<PairFlow?>(null)

    private fun handlePairIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "pickwick" || data.host != "pair") return
        val host = data.getQueryParameter("host") ?: return
        val port = data.getQueryParameter("port")?.toIntOrNull() ?: return
        val name = data.getQueryParameter("name") ?: "TV"
        pairFlow.value = PairFlow.Confirm(name, host, port)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(applicationContext)
        val appContext = applicationContext
        val configStore = ConfigStore(applicationContext)
        // Cold start needs the config before the first frame (the screener must
        // be seeded fail-closed), but the read + JSON parse doesn't have to
        // serialize with the rest of onCreate — start it now, join below.
        val initialConfigTask = java.util.concurrent.FutureTask { configStore.load() }
        Thread(initialConfigTask, "config-preload").start()
        val pairingStore = PairingStore(applicationContext)
        val deviceIsTv = (getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        // Role is explicit from the first launch: TVs are kid devices, and any
        // device that already pairs others is a parent. Phone/tablet with no
        // history stays unset until its first pairing act decides it.
        if (deviceIsTv) pairingStore.setRole(PairingStore.Role.KID)
        if (pairingStore.paired().isNotEmpty()) pairingStore.setRole(PairingStore.Role.PARENT)
        val downloadStore = DownloadStore(applicationContext)
        val localLibrary = LocalLibrary(applicationContext)
        // A queue interrupted by a reboot or crash resumes on next open.
        DownloadService.startIfPending(this)
        val profileNs = ProfileNamespace(applicationContext)
        val activeProfiles = ActiveProfileStore(applicationContext)
        val initialConfig = initialConfigTask.get()
        // Seeded before the ViewModel exists so the first cached paint is already
        // gated — a held video must never flash on screen while config loads.
        // Until the ViewModel's first refresh names the active kid, a null id
        // gates on the strictest verdict across kids: still fail-closed.
        val screener = io.pickwick.app.data.Screener(io.pickwick.app.data.ScreeningStore(applicationContext)).also {
            it.config = initialConfig.ai
            it.profiles = initialConfig.profiles
            it.allowedOverrides = initialConfig.aiAllowedVideoIds
        }
        // Every device runs the LAN server now, not just TVs: a kid's phone or
        // tablet is pairable too (config pushes, grants, index sync). Same
        // token-gated listener as before, just no longer gated on form factor.
        if (LanServerHolder.server == null) {
            LanServerHolder.server = LanServer(
                configStore,
                grantHandler = { minutes, profileId ->
                    // Route the grant to the named kid's guard; unnamed grants
                    // (older admin phones) land on whoever this device shows.
                    val target = profileId ?: run {
                        val f = ConfigStore(appContext).load()
                        if (f.profiles.isEmpty()) null
                        else f.deviceProfiles[PairingStore(appContext).deviceToken()]
                            // A remembered pick can outlive its kid (deleted on
                            // the phone, picker not used since): suffixFor would
                            // auto-register an orphan namespace and the grant
                            // would vanish into it. Explicit ids stay unvalidated
                            // on purpose — a grant can arrive moments before the
                            // config push that introduces its (new) kid.
                            ?: ActiveProfileStore(appContext).activeId()
                                ?.takeIf { f.profile(it) != null }
                    }
                    SessionGuard(appContext, ProfileNamespace(appContext).suffixFor(target))
                        .grantExtraMinutes(minutes)
                },
                pairingStore,
                statsProvider = { io.pickwick.app.data.Stats.build(appContext) },
                watchStateProvider = { WatchSync.exportJson(appContext) },
                watchStateMerger = { json ->
                    if (WatchSync.mergeJson(appContext, json)) {
                        // Refresh keep-watching / hearts on the TV right away.
                        ConfigEvents.onConfigChanged?.invoke()
                    }
                },
                verdictsProvider = {
                    io.pickwick.app.data.ScreeningStore(appContext)
                        .exportJson(ConfigStore(appContext).load().ai.rulesVersion)
                },
                verdictsMerger = { json ->
                    val imported = io.pickwick.app.data.ScreeningStore(appContext)
                        .importJson(json, ConfigStore(appContext).load().ai.rulesVersion)
                    // Imported ALLOWs reveal videos this device hadn't screened yet.
                    if (imported > 0) ConfigEvents.onConfigChanged?.invoke()
                },
                indexStatusProvider = {
                    io.pickwick.app.data.ChannelIndex(appContext).statusJson()
                },
                indexSourceProvider = { sourceId ->
                    io.pickwick.app.data.ChannelIndex(appContext).exportSourceWithState(sourceId)
                },
                indexMerger = { sourceId, body ->
                    io.pickwick.app.data.ChannelIndex(appContext).importSourceWithState(sourceId, body)
                }
            ).also { it.start() }
        }
        handlePairIntent(intent)
        val prefetchThumbs: suspend (List<String>) -> Unit = { urls ->
            val loader = coil.Coil.imageLoader(appContext)
            for (url in urls) {
                // execute() suspends until done — one image in flight at a time,
                // and a no-op for anything already in the disk cache.
                runCatching {
                    loader.execute(coil.request.ImageRequest.Builder(appContext).data(url).build())
                }
            }
        }
        // Once-a-day silent update check; drives the dot on the settings gear.
        lifecycleScope.launch { io.pickwick.app.data.Updater(applicationContext).autoCheck() }
        // The channel-index crawl runs whether or not the app is open — the
        // worker itself no-ops on any device that isn't the master.
        io.pickwick.app.data.IndexCrawlWorker.schedule(applicationContext)
        setContent {
            MaterialTheme(colorScheme = PickwickDarkColors) {
                // The family config drives who exists and whether this device is
                // dedicated; reloaded whenever a push lands or settings close.
                var family by remember { mutableStateOf(initialConfig) }
                val myToken = remember { pairingStore.deviceToken() }

                fun assignedId(f: io.pickwick.app.data.Whitelist): String? =
                    f.deviceProfiles[myToken]?.takeIf { pid -> f.profile(pid) != null }

                // null with 2+ kids = the who's-watching screen must ask.
                fun resolveActive(f: io.pickwick.app.data.Whitelist): String? = when {
                    f.profiles.isEmpty() -> null
                    else -> assignedId(f)
                        ?: f.profiles.singleOrNull()?.id
                        ?: activeProfiles.activeId()?.takeIf { pid ->
                            f.profile(pid) != null &&
                                !activeProfiles.needsReask(f.limitsFor(pid).breakMinutes)
                        }
                }

                var activeProfileId by remember { mutableStateOf(resolveActive(initialConfig)) }
                // A pushed config can rename, delete or reassign kids out from
                // under the current pick — re-resolve rather than show a ghost.
                LaunchedEffect(family) {
                    val resolved = resolveActive(family)
                    if (family.profiles.isEmpty()) activeProfileId = null
                    else if (activeProfileId == null ||
                        family.profile(activeProfileId) == null ||
                        (assignedId(family) != null && assignedId(family) != activeProfileId)
                    ) activeProfileId = resolved
                }
                // PlayerActivity (screen-time gate, history, channel minutes)
                // reads the active kid from ActiveProfileStore on its own. The
                // picker writes it, but a *dedicated* device resolves its kid
                // right here and never shows a picker — leaving whatever stale
                // pick the store held, so the player enforced (and recorded
                // into) the wrong kid's namespace. Mirror every resolution.
                LaunchedEffect(activeProfileId) {
                    activeProfileId?.let { activeProfiles.setActive(it) }
                }
                // New sitting on a shared device → ask again; leaving marks the
                // moment the room emptied so the gap measures real absence.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> {
                                // Fires on every return from the player too — only a
                                // set break rule defines a gap that means "new
                                // sitting". With the rule off, the who's-watching ask
                                // happens once per launch (resolveActive) instead of
                                // ever re-asking mid-session.
                                val breakMin = family.limitsFor(activeProfileId).breakMinutes
                                if (family.profiles.size >= 2 && assignedId(family) == null &&
                                    breakMin != null && activeProfiles.needsReask(breakMin)
                                ) activeProfileId = null
                            }
                            Lifecycle.Event.ON_STOP ->
                                if (activeProfileId != null) activeProfiles.touch()
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val profileSuffix = profileNs.suffixFor(activeProfileId)
                // Keyed per kid: switching profiles rebuilds the ViewModel over
                // that kid's own history/watchlist/usage/screen-time stores.
                val vm: MainViewModel = viewModel(key = "main-${activeProfileId ?: "solo"}") {
                    MainViewModel(
                        WhitelistRepository(configStore),
                        WatchHistoryStore(applicationContext, profileSuffix),
                        SourceCache(applicationContext),
                        VideoCache(applicationContext),
                        UsageStore(applicationContext, profileSuffix),
                        SessionGuard(applicationContext, profileSuffix),
                        WatchlistStore(applicationContext, profileSuffix),
                        QueueStore(applicationContext, profileSuffix),
                        pairingStore,
                        configStore = configStore,
                        downloadStore = downloadStore,
                        localLibrary = localLibrary,
                        isOffline = {
                            val cm = appContext.getSystemService(
                                android.content.Context.CONNECTIVITY_SERVICE
                            ) as android.net.ConnectivityManager
                            cm.activeNetwork == null
                        },
                        exportWatchState = { WatchSync.exportJson(appContext) },
                        mergeWatchState = { WatchSync.mergeJson(appContext, it) },
                        exportVerdicts = {
                            io.pickwick.app.data.ScreeningStore(appContext)
                                .exportJson(configStore.load().ai.rulesVersion)
                        },
                        mergeVerdicts = {
                            io.pickwick.app.data.ScreeningStore(appContext)
                                .importJson(it, configStore.load().ai.rulesVersion) > 0
                        },
                        cacheStats = { device ->
                            LanClient.stats(device)?.let {
                                // save() re-parses the whole payload and rewrites
                                // digest baselines — off-main like every store write.
                                withContext(Dispatchers.IO) {
                                    io.pickwick.app.data.StatsCache(appContext).save(device.key, it)
                                }
                            }
                        },
                        prefetchThumbs = prefetchThumbs,
                        screener = screener,
                        activeProfileId = activeProfileId,
                        channelIndex = io.pickwick.app.data.ChannelIndex(applicationContext)
                    )
                }
                // TV: a paired phone just pushed new config — apply it live.
                // Disposable so a push landing after this composition is gone
                // can't invoke a lambda whose scope died with the activity.
                // Also gates the keyed per-kid ViewModels: only the one on
                // screen runs its periodic LAN sync — the others would
                // otherwise keep polling for the app's whole life.
                DisposableEffect(vm) {
                    vm.uiActive = true
                    ConfigEvents.onConfigChanged = {
                        // Fired from the LAN server's socket thread; the re-read
                        // (file + JSON) stays off-main, only the apply lands there.
                        lifecycleScope.launch {
                            family = withContext(Dispatchers.IO) { configStore.load() }
                            vm.refresh()
                        }
                    }
                    onDispose {
                        vm.uiActive = false
                        ConfigEvents.onConfigChanged = null
                    }
                }
                // Phone: pairing flow started by scanning a TV's QR code.
                when (val flow = pairFlow.value) {
                    is PairFlow.Confirm -> AlertDialog(
                        onDismissRequest = { pairFlow.value = null },
                        title = { Text("Pair with ${flow.name}?") },
                        text = { Text("This phone will manage that device's channels and screen time.") },
                        confirmButton = {
                            Button(onClick = {
                                pairFlow.value = PairFlow.Waiting(flow.name, flow.host, flow.port)
                            }) { Text("Pair") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pairFlow.value = null }) { Text("Cancel") }
                        }
                    )
                    is PairFlow.Waiting -> {
                        // The dialog narrates which step is running: contacting the TV
                        // and copying its config are both LAN round-trips that can take
                        // seconds, and a static "Waiting for approval…" read as a freeze.
                        var status by remember(flow) {
                            mutableStateOf("Contacting ${flow.name}…")
                        }
                        var awaitingApproval by remember(flow) { mutableStateOf(false) }
                        LaunchedEffect(flow) {
                            val myToken = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                pairingStore.deviceToken()
                            }
                            val myName = android.os.Build.MODEL ?: "Phone"
                            suspend fun paired() {
                                awaitingApproval = false
                                status = "Copying settings from ${flow.name}…"
                                var device = PairedDevice(flow.name, flow.host, flow.port, myToken)
                                // Prefs and config are disk + JSON work; off the main
                                // thread so the spinner keeps animating.
                                val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    // Ask who this device is before storing it: id is the
                                    // stable key for dedupe/remove/rename, and pairing is
                                    // the one moment we know host:port is fresh. Sync
                                    // backfills it later if this round trip fails.
                                    LanClient.fullStatus(device)?.deviceToken?.let {
                                        device = device.copy(id = it)
                                    }
                                    pairingStore.addPaired(device)
                                    // Scanning a device to manage it makes this phone a parent.
                                    pairingStore.setRole(PairingStore.Role.PARENT)
                                    configStore.load()
                                }
                                // Fresh install (or wiped phone): the TV still holds the
                                // family's config — blocks, safe-list, channels, rules.
                                // Adopt it instead of later stomping it with an empty one.
                                val blank = local.sources.isEmpty() &&
                                    local.blockedVideoIds.isEmpty() &&
                                    local.aiAllowedVideoIds.isEmpty()
                                val restored = blank &&
                                    LanClient.fetchConfig(device)?.let { remote ->
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val parsed = runCatching { ConfigStore.fromJson(remote) }.getOrNull()
                                            parsed != null && parsed.sources.isNotEmpty() &&
                                                configStore.saveRaw(remote)
                                        }
                                    } == true
                                if (restored) ConfigEvents.onConfigChanged?.invoke()
                                pairFlow.value = PairFlow.Result(
                                    if (restored) "Paired with ${flow.name} ✓ — its settings were copied to this phone"
                                    else "Paired with ${flow.name} ✓"
                                )
                            }
                            when (LanClient.requestPairing(flow.host, flow.port, myName, myToken)) {
                                "approved" -> paired()
                                "pending" -> {
                                    awaitingApproval = true
                                    status = "Open Pickwick settings on the family's " +
                                        "already-paired phone and approve this request " +
                                        "for \"$myName\"."
                                    // Wait for the family's approved phone to say yes.
                                    repeat(100) {
                                        kotlinx.coroutines.delay(3_000)
                                        when (LanClient.pairStatus(flow.host, flow.port, myToken)) {
                                            "approved" -> { paired(); return@LaunchedEffect }
                                            "unknown" -> {
                                                pairFlow.value =
                                                    PairFlow.Result("The request was declined")
                                                return@LaunchedEffect
                                            }
                                        }
                                    }
                                    pairFlow.value =
                                        PairFlow.Result("No approval received — ask on the paired phone, then scan again")
                                }
                                // The TV has no admin yet and isn't showing its
                                // pairing code, so it won't hand the first slot
                                // to whoever asks. Put the QR back on screen.
                                "closed" -> pairFlow.value = PairFlow.Result(
                                    "Open Settings on ${flow.name} so its pairing code is showing, then scan again"
                                )
                                else -> pairFlow.value =
                                    PairFlow.Result("Couldn't reach ${flow.name} — same Wi-Fi, app open on it?")
                            }
                        }
                        AlertDialog(
                            onDismissRequest = { /* keep waiting; cancel via button */ },
                            title = {
                                Text(if (awaitingApproval) "Waiting for approval…" else "Pairing…")
                            },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text(status)
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { pairFlow.value = null }) { Text("Cancel") }
                            }
                        )
                    }
                    is PairFlow.Result -> AlertDialog(
                        onDismissRequest = { pairFlow.value = null },
                        title = { Text(flow.message) },
                        confirmButton = {
                            Button(onClick = { pairFlow.value = null }) { Text("OK") }
                        }
                    )
                    null -> {}
                }
                // Saveable: a phone rotation must not silently close the whole
                // settings stack mid-edit.
                var showSettings by rememberSaveable { mutableStateOf(false) }
                if (showSettings) {
                    // A device another parent phone manages gets the pairing QR,
                    // not the admin editor — there's nothing to administer here.
                    // Two ways in: an admin already paired to it, or the parent
                    // explicitly dedicated it to a kid (which must hold BEFORE
                    // any admin exists, or the QR that pairing needs never shows).
                    val isKidDevice = !deviceIsTv &&
                        (pairingStore.role() == PairingStore.Role.KID ||
                            (pairingStore.paired().isEmpty() &&
                                pairingStore.approvedPhones().isNotEmpty()))
                    SettingsFlow(settings, configStore, pairingStore, deviceIsTv, isKidDevice) { changed ->
                        showSettings = false
                        // Kids may have been added/edited — re-resolve everything.
                        lifecycleScope.launch {
                            family = withContext(Dispatchers.IO) { configStore.load() }
                            if (changed) vm.refresh()
                        }
                    }
                } else if (family.profiles.size >= 2 && activeProfileId == null) {
                    // Per-kid prefs stores + a possible day rollover rewrite —
                    // disk work, so it lands as state rather than running in
                    // the composable body on Main.
                    val remaining by produceState(emptyMap<String, Int?>(), family) {
                        value = withContext(Dispatchers.IO) {
                            family.profiles.associate { p ->
                                p.id to SessionGuard(appContext, profileNs.suffixFor(p.id))
                                    .remainingTodayMin(family.limitsFor(p.id))
                            }
                        }
                    }
                    WhosWatchingScreen(
                        profiles = family.profiles,
                        remainingMinutes = remaining,
                        onOpenSettings = { showSettings = true },
                        onPicked = { p ->
                            activeProfiles.setActive(p.id)
                            activeProfileId = p.id
                        }
                    )
                } else {
                    PickwickScreen(
                        vm,
                        activeProfile = family.profile(activeProfileId),
                        // Switching lives behind the header avatar, and only on
                        // shared devices — a dedicated device *is* its kid.
                        onSwitchProfile = if (family.profiles.size >= 2 && assignedId(family) == null) {
                            { activeProfileId = null }
                        } else null,
                        onOpenSettings = { showSettings = true },
                        onPlayQueue = { startIndex ->
                            // The *visible* lineup: videos blocked or held after
                            // being queued were filtered out and never reach the
                            // player. Channel + drain rate travel per item — a
                            // hand-built queue crosses channels, so one launch-
                            // wide value would misbill every other video.
                            val items = vm.state.value.videos
                            if (items.isNotEmpty()) {
                                val intent = Intent(this, PlayerActivity::class.java)
                                intent.putStringArrayListExtra(
                                    PlayerActivity.EXTRA_QUEUE,
                                    ArrayList(items.map { it.video.url })
                                )
                                intent.putStringArrayListExtra(
                                    PlayerActivity.EXTRA_QUEUE_CHANNELS,
                                    ArrayList(items.map { it.video.channelName })
                                )
                                intent.putExtra(
                                    PlayerActivity.EXTRA_QUEUE_PERCENTS,
                                    queuePercents(
                                        items.map { it.video },
                                        vm.state.value.channels
                                    ).toIntArray()
                                )
                                intent.putExtra(
                                    PlayerActivity.EXTRA_INDEX,
                                    startIndex.coerceIn(0, items.lastIndex)
                                )
                                intent.putExtra(PlayerActivity.EXTRA_FROM_QUEUE, true)
                                intent.putExtra(PlayerActivity.EXTRA_PROFILE_SUFFIX, profileSuffix)
                                intent.putExtra(PlayerActivity.EXTRA_PROFILE_ID, activeProfileId)
                                startActivity(intent)
                            }
                        }
                    ) { item ->
                        val s = vm.state.value
                        val screen = s.screen
                        val intent = Intent(this, PlayerActivity::class.java)
                        // Playlist sources auto-advance: hand the player the visible queue.
                        if (screen is Screen.ChannelVideos && screen.source.kind == SourceKind.PLAYLIST) {
                            intent.putStringArrayListExtra(
                                PlayerActivity.EXTRA_QUEUE,
                                ArrayList(s.videos.map { it.video.url })
                            )
                            intent.putExtra(
                                PlayerActivity.EXTRA_INDEX,
                                s.videos.indexOfFirst { it.video.url == item.video.url }.coerceAtLeast(0)
                            )
                        } else {
                            intent.putExtra(PlayerActivity.EXTRA_VIDEO_URL, item.video.url)
                        }
                        // Channel name travels with the intent for per-channel stats.
                        intent.putExtra(PlayerActivity.EXTRA_CHANNEL, item.video.channelName)
                        // Whose stores the player must use (screen-time gate, resume
                        // points, channel minutes) — decided here, where the kid was
                        // resolved, so the player never re-derives it and can never
                        // disagree with the home screen that launched it.
                        intent.putExtra(PlayerActivity.EXTRA_PROFILE_SUFFIX, profileSuffix)
                        intent.putExtra(PlayerActivity.EXTRA_PROFILE_ID, activeProfileId)
                        // Screen-time drain rate: exact from the open source; for
                        // mixed rows (Surprise, My list, Keep watching) resolved by
                        // the video's channel name, defaulting to normal speed.
                        val timePercent = when (screen) {
                            is Screen.ChannelVideos -> screen.source.timeMultiplierPercent
                            else -> s.channels
                                .firstOrNull { it.name == item.video.channelName }
                                ?.timeMultiplierPercent ?: 100
                        }
                        intent.putExtra(PlayerActivity.EXTRA_TIME_PERCENT, timePercent)
                        startActivity(intent)
                    }
                }
            }
        }
    }
}
