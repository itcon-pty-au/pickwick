package io.pickwick.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.LanServer
import io.pickwick.app.data.Limits
import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.PairingWindow
import io.pickwick.app.data.SettingsStore
import io.pickwick.app.data.SourceCache
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.YouTubeRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the TV's running LAN server so the settings QR can reference it. */
object LanServerHolder {
    @Volatile
    var server: LanServer? = null
}

/**
 * Parent-gated admin: biometrics on phones, PIN pad on TVs, then a form-based
 * editor — channel search, screen-time steppers, grants, pairing, updates.
 */
@Composable
fun SettingsFlow(
    settings: SettingsStore,
    configStore: ConfigStore,
    pairingStore: PairingStore,
    isTv: Boolean,
    onDone: (changed: Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val biometricsAvailable = remember {
        activity != null && androidx.biometric.BiometricManager.from(context).canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }
    var stage by remember {
        mutableStateOf(
            when {
                // TV shows only the pairing QR — nothing editable to gate. (Trade-off:
                // the QR token is a pairing credential; fine while the kids are young.)
                isTv -> Stage.Editor
                biometricsAvailable -> Stage.Biometric
                settings.hasPin() -> Stage.Enter
                else -> Stage.Create
            }
        )
    }
    var firstPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onDone(false) }

    MaterialTheme(colorScheme = AdminDarkColors) {
    Surface(Modifier.fillMaxSize()) {
        when (stage) {
            Stage.Biometric -> {
                LaunchedEffect(Unit) {
                    val prompt = androidx.biometric.BiometricPrompt(
                        activity!!,
                        androidx.core.content.ContextCompat.getMainExecutor(context),
                        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                result: androidx.biometric.BiometricPrompt.AuthenticationResult
                            ) {
                                stage = Stage.Editor
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                if (settings.hasPin()) stage = Stage.Enter else onDone(false)
                            }
                        }
                    )
                    prompt.authenticate(
                        androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Parents only")
                            .setSubtitle("Confirm it's you to open settings")
                            .setAllowedAuthenticators(
                                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            )
                            .build()
                    )
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Confirm it's you…",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Stage.Enter -> key(stage, error) {
                // The stretched hash costs ~100ms of CPU — off the main thread,
                // or every PIN entry drops frames on a TV box.
                val scope = rememberCoroutineScope()
                var checking by remember { mutableStateOf(false) }
                PinPad(title = "Enter parent PIN", error = error) { pin ->
                    if (!checking) {
                        checking = true
                        scope.launch {
                            val ok = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.Default
                            ) { settings.checkPin(pin) }
                            checking = false
                            if (ok) {
                                error = null
                                stage = Stage.Editor
                            } else {
                                error = "Wrong PIN — try again"
                            }
                        }
                    }
                }
            }
            Stage.Create -> key(stage) {
                PinPad(
                    title = "Set a parent PIN",
                    subtitle = "Parents will need this 4-digit PIN to change settings",
                    error = error
                ) { pin ->
                    firstPin = pin
                    error = null
                    stage = Stage.Confirm
                }
            }
            Stage.Confirm -> key(stage) {
                val scope = rememberCoroutineScope()
                var saving by remember { mutableStateOf(false) }
                PinPad(title = "Enter the same PIN again to confirm") { pin ->
                    when {
                        saving -> {}
                        pin == firstPin -> {
                            saving = true
                            scope.launch {
                                kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.Default
                                ) { settings.setPin(pin) }
                                stage = Stage.Editor
                            }
                        }
                        else -> {
                            error = "PINs didn't match — start again"
                            stage = Stage.Create
                        }
                    }
                }
            }
            Stage.Editor ->
                if (isTv) TvSettingsScreen(configStore, pairingStore)
                else AdminScreen(configStore, pairingStore, onDone)
        }
    }
    }
}

private enum class Stage { Biometric, Enter, Create, Confirm, Editor }

// ---------------------------------------------------------------------------
// Admin editor
// ---------------------------------------------------------------------------

/** TV settings: no form — just the pairing QR and a live version line. All
 *  editing happens on the paired phone; pushes update this screen in place. */
@Composable
private fun TvSettingsScreen(configStore: ConfigStore, pairingStore: PairingStore) {
    var hash by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(0L) }
    val server = LanServerHolder.server
    val ip = remember { LanServer.localIp() }
    // Live: a push from the phone changes the fingerprint on screen within 2s,
    // so the parent can watch the two devices match.
    LaunchedEffect(Unit) {
        while (true) {
            val (h, e) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                ConfigStore.fingerprint(configStore.load()) to configStore.updatedAt()
            }
            hash = h
            edited = e
            // A QR on screen is the parent's consent to hand out the first
            // admin slot; this tick is what holds that window open, and it
            // lapses seconds after the screen goes away.
            if (server != null && ip != null) PairingWindow.keepOpen()
            kotlinx.coroutines.delay(2_000)
        }
    }
    DisposableEffect(Unit) { onDispose { PairingWindow.close() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Manage this TV from your phone",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Scan with the parent's phone camera — Pickwick on the phone controls " +
                "channels and screen time here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        if (server == null || ip == null) {
            Text("Pairing needs Wi-Fi — check the network connection.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // No secret in the QR: it only says where to *ask*. New phones need
            // approval on the already-paired phone before they can administer.
            val deviceName = java.net.URLEncoder.encode(android.os.Build.MODEL ?: "TV", "UTF-8")
            QrImage("pickwick://pair?name=$deviceName&host=$ip&port=${server.port}")
        }

        Spacer(Modifier.height(20.dp))
        val editedText = edited.takeIf { it > 0 }?.let {
            "  ·  edited " + java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.US)
                .format(java.util.Date(it))
        } ?: ""
        Text(
            "Settings #$hash$editedText",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "This code updates live — after a push from the phone, the two devices show the same number.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Same section the phone settings has: checks on open and offers the
        // Install button right here, so the TV updates without a computer.
        Spacer(Modifier.height(20.dp))
        UpdateSection(tv = true)
    }
}

@Composable
private fun AdminScreen(
    configStore: ConfigStore,
    pairingStore: PairingStore,
    onDone: (changed: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val yt = remember { YouTubeRepository() }

    // Bumped when the config file is replaced underneath the form (a Pull from a
    // kid device) — the whole form reloads from disk, dropping unsaved edits.
    var configEpoch by remember { mutableIntStateOf(0) }
    // Off-main read (file + JSON): opening settings shows a beat of spinner
    // instead of freezing the tap that opened them.
    val loadedConfig by produceState<Whitelist?>(initialValue = null, configEpoch) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) { configStore.load() }
    }
    val initial = loadedConfig ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    var entries by remember(initial) { mutableStateOf(initial.sources) }
    var limits by remember(initial) { mutableStateOf(initial.limits) }
    var blocked by remember(initial) { mutableStateOf(initial.blockedVideoIds) }
    var ai by remember(initial) { mutableStateOf(initial.ai) }
    var aiAllowed by remember(initial) { mutableStateOf(initial.aiAllowedVideoIds) }
    var profiles by remember(initial) { mutableStateOf(initial.profiles) }
    var blockedFor by remember(initial) { mutableStateOf(initial.blockedFor) }
    var allowedFor by remember(initial) { mutableStateOf(initial.allowedFor) }
    var deviceProfiles by remember(initial) { mutableStateOf(initial.deviceProfiles) }
    var sponsorSkip by remember(initial) { mutableStateOf(initial.sponsorSkip) }
    var listenPercent by remember(initial) { mutableStateOf(initial.listenPercent) }
    /** Entries added by this session's URL import — shown with a NEW tag for review. */
    var newIds by remember { mutableStateOf(setOf<String>()) }

    // Real names (keyed by url) for entries that carry no label — pasted links
    // and file imports. A raw "UU…" id tells a parent nothing when they're
    // setting time multipliers, so resolve names the way the kid's home screen
    // does: seed from its tile cache (instant, offline), then fetch the rest —
    // memoized and rate-limited upstream, so this never hammers YouTube. Shared
    // by the channel list rows and the export, which writes them as "| Name".
    val context = androidx.compose.ui.platform.LocalContext.current
    val resolvedNames = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(entries) {
        if (resolvedNames.isEmpty()) {
            SourceCache(context).load().forEach { s ->
                if (s.url !in resolvedNames) resolvedNames[s.url] = s.name
            }
        }
        entries.filter { it.label == null && it.url !in resolvedNames }.forEach { e ->
            launch {
                runCatching { yt.source(e, background = true) }
                    .onSuccess { s -> resolvedNames[e.url] = s.name }
            }
        }
    }

    var statsDevice by remember { mutableStateOf<PairedDevice?>(null) }
    var digestOpen by remember { mutableStateOf(false) }

    /**
     * Review-queue decisions commit the moment they're tapped — a parent who ruled
     * on a batch and walked back without Save & close was asked the same questions
     * again next time. Only the two id sets are written to disk; other unsaved form
     * edits stay unsaved (same rule as PauseTodayRow). The LAN push is debounced,
     * because ruling on a queue is a burst of taps and every push to a sleeping TV
     * costs a connect timeout. Runs on LanPushScope, not the composition scope, so
     * closing settings right after the last tap doesn't cancel the sync.
     */
    val pushJob = remember {
        java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>()
    }
    fun resolveFlagged(mutate: (Whitelist) -> Whitelist) {
        io.pickwick.app.data.LanPushScope.scope.launch {
            val updated = mutate(configStore.load())
            configStore.save(updated)
            val json = ConfigStore.toJson(updated)
            pushJob.getAndSet(launch {
                delay(2_000)
                pairingStore.paired().forEach { LanClient.pushConfig(it, json) }
            })?.cancel()
        }
    }

    // Stats takes over the screen while open.
    statsDevice?.let { device ->
        StatsScreen(device, configStore) { statsDevice = null }
        return
    }
    if (digestOpen) {
        WeeklyDigestScreen(pairingStore, configStore) { digestOpen = false }
        return
    }

    /** The form as a config: what Save & close and Push both deliver. */
    fun buildCurrentConfig(): Whitelist {
        // Changed rules/age/model mean old verdicts no longer apply — bumping the
        // version makes every device re-screen its catalog against the new rules.
        // The bump is computed against `initial` (disk state when the form opened),
        // so building twice in one session yields the same version, not two bumps.
        val judgingChanged = ai.rules != initial.ai.rules ||
            ai.childAge != initial.ai.childAge ||
            ai.model != initial.ai.model ||
            ai.baseUrl != initial.ai.baseUrl ||
            io.pickwick.app.data.screeningJudgmentChanged(initial.profiles, profiles)
        val finalAi = if (judgingChanged) ai.copy(rulesVersion = initial.ai.rulesVersion + 1) else ai
        // A removed kid must not linger: entries owned only by them fall back
        // to everyone, their per-video rulings and device assignment are dropped.
        val validIds = profiles.map { it.id }.toSet()
        fun scrub(overlay: Map<String, Set<String>>) = overlay
            .mapValues { (_, pids) -> pids.intersect(validIds) }
            .filterValues { it.isNotEmpty() }
        return Whitelist(
            entries.map { e ->
                if (e.profileIds.isEmpty()) e
                else e.copy(profileIds = e.profileIds.intersect(validIds))
            },
            blocked, limits, finalAi, aiAllowed,
            profiles, scrub(blockedFor), scrub(allowedFor),
            deviceProfiles.filterValues { it in validIds },
            sponsorSkip = sponsorSkip,
            listenPercent = listenPercent
        )
    }

    fun saveAndSync() {
        val config = buildCurrentConfig()
        scope.launch {
            // The save must land before onDone: closing settings makes
            // MainActivity re-read the file, and it has to see this write.
            val (devices, json) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                configStore.save(config)
                pairingStore.paired() to ConfigStore.toJson(config)
            }
            // Close as soon as the save is on disk. Blocking the button on the
            // LAN round-trip read as "Save & close does nothing" whenever the TV
            // was asleep (a standby Chromecast drops off Wi-Fi, so the push sits
            // in connect-timeout for seconds with no feedback).
            onDone(true)
            if (devices.isEmpty()) return@launch
            val appContext = context.applicationContext
            io.pickwick.app.data.LanPushScope.scope.launch {
                var ok = 0
                devices.forEach { if (LanClient.pushConfig(it, json)) ok++ }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        appContext,
                        if (ok == devices.size) "Synced to $ok device(s) ✓"
                        else "Saved — ${devices.size - ok} device(s) unreachable; " +
                            "they'll sync when back online",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // The update offer lives at the very bottom of a long form, so a parent who
    // came here because of the gear dot would have to scroll past every section
    // to find it. Only scroll when there is actually something to install —
    // otherwise opening settings would dump everyone at the bottom.
    val formScroll = rememberScrollState()
    var scrolledToUpdate by remember { mutableStateOf(false) }
    fun revealUpdate() {
        if (scrolledToUpdate) return
        scrolledToUpdate = true
        scope.launch {
            // Wait for the form's height to stop changing before moving. Channel
            // names resolve in async, which regrows rows underneath us; animating
            // to a maxValue that is still climbing lands short and then jumps.
            var previous = -1
            while (true) {
                val extent = formScroll.maxValue
                if (extent > 0 && extent != Int.MAX_VALUE && extent == previous) break
                previous = extent
                delay(150)
            }
            // Positioned, not animated: a scroll the parent didn't ask for reads
            // as the screen moving on its own. Landing there is calmer.
            formScroll.scrollTo(formScroll.maxValue)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(formScroll)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Parent settings",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
            Button(modifier = Modifier.tvFocusHighlight(), onClick = ::saveAndSync) {
                Text("Save & close")
            }
        }
        SectionTitle("Kids")
        KidsSection(
            profiles = profiles,
            legacyLimits = limits,
            legacyAi = ai,
            onChanged = { profiles = it }
        )

        SectionTitle("Screen time")
        // Skip-pass write-through, PauseTodayRow-style: only the tapped
        // window's pass changes on disk; other unsaved form edits stay unsaved
        // until the parent commits them.
        fun commitPass(kidId: String?, windowId: String, passUntil: Long?) {
            io.pickwick.app.data.LanPushScope.scope.launch {
                fun apply(l: Limits) = l.copy(windows = l.windows.map {
                    if (it.id == windowId) it.copy(passUntilMillis = passUntil) else it
                })
                val updated = configStore.load().let { c ->
                    if (kidId == null) c.copy(limits = apply(c.limits))
                    else c.copy(profiles = c.profiles.map {
                        if (it.id == kidId) it.copy(limits = apply(it.limits)) else it
                    })
                }
                configStore.save(updated)
                val json = ConfigStore.toJson(updated)
                pairingStore.paired().forEach { LanClient.pushConfig(it, json) }
            }
        }
        if (profiles.isEmpty()) {
            ScreenTimeSection(
                limits,
                onChanged = { limits = it },
                onPassCommitted = { windowId, until -> commitPass(null, windowId, until) }
            )
        } else {
            // Per-kid rules: pick a kid, edit their rules, or copy a sibling's.
            var editingKidId by remember(profiles.map { it.id }) {
                mutableStateOf(profiles.first().id)
            }
            val editingKid = profiles.firstOrNull { it.id == editingKidId } ?: profiles.first()
            KidSelectorChips(profiles, editingKid.id, onSelect = { editingKidId = it })
            Spacer(Modifier.height(8.dp))
            ScreenTimeSection(
                editingKid.limits,
                onChanged = { newLimits ->
                    profiles = profiles.map {
                        if (it.id == editingKid.id) it.copy(limits = newLimits) else it
                    }
                },
                onPassCommitted = { windowId, until -> commitPass(editingKid.id, windowId, until) }
            )
            val others = profiles.filter { it.id != editingKid.id }
            if (others.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Copy rules from:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    others.forEach { sibling ->
                        TextButton(
                            modifier = Modifier.tvFocusHighlight(),
                            onClick = {
                                profiles = profiles.map {
                                    if (it.id == editingKid.id) {
                                        it.copy(limits = sibling.limits.copy(pausedUntilMillis = null))
                                    } else it
                                }
                            }
                        ) { Text(sibling.name) }
                    }
                }
            }
        }

        SectionTitle("Listening")
        ListenRateRow(listenPercent) { listenPercent = it }

        SectionTitle("Screen time today")
        GrantTimeSection(pairingStore, profiles)
        PauseTodayRow(
            pausedUntil = limits.pausedUntilMillis,
            onChanged = { until ->
                // Applied immediately — a timeout shouldn't wait for Save & close.
                // Only the pause field changes on disk; other unsaved form edits
                // stay unsaved until the parent commits them. Disk + push ride
                // LanPushScope (IO), like the review-queue rulings above.
                limits = limits.copy(pausedUntilMillis = until)
                io.pickwick.app.data.LanPushScope.scope.launch {
                    val updated = configStore.load().let { c ->
                        c.copy(limits = c.limits.copy(pausedUntilMillis = until))
                    }
                    configStore.save(updated)
                    val json = ConfigStore.toJson(updated)
                    pairingStore.paired().forEach { LanClient.pushConfig(it, json) }
                }
            }
        )

        SectionTitle("Playback")
        Text(
            "Skips the parts of a video the SponsorBlock community has marked: " +
                "sponsor messages, merch plugs, intros/outros and \"like and " +
                "subscribe\" reminders. Marked parts show in green on the TV's " +
                "playback bar. Lookups send only an anonymous fingerprint of the " +
                "video, never what is being watched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Skip sponsors & intros", modifier = Modifier.weight(1f))
            Switch(
                modifier = Modifier.tvFocusHighlight(),
                checked = sponsorSkip,
                onCheckedChange = { sponsorSkip = it }
            )
        }

        SectionTitle("Offline downloads")
        DownloadsSection()

        SectionTitle("Videos from this phone")
        LocalVideosSection(profiles)

        SectionTitle("Kid devices")
        PhoneDevicesSection(
            pairingStore, configStore,
            profiles = profiles,
            deviceProfiles = deviceProfiles,
            // The form's fingerprint, not the file's: "in sync ✓" measured against
            // disk stays green while the parent edits, so a changed bedtime looks
            // already delivered and the Push button (shown only when out of sync)
            // never appears. Recomputed on every form edit — hashing a few KB is
            // nothing next to the recomposition that triggered it.
            localHash = ConfigStore.fingerprint(buildCurrentConfig()),
            // Push must deliver what the parent is LOOKING AT, unsaved edits
            // included — pushing the stale disk config while the form showed
            // freshly-added kids read as "the button does nothing".
            saveCurrent = {
                val config = buildCurrentConfig()
                configStore.save(config)
                ConfigStore.toJson(config)
            },
            onAssign = { token, profileId ->
                deviceProfiles =
                    if (profileId == null) deviceProfiles - token
                    else deviceProfiles + (token to profileId)
                // Assignment applies immediately, like queue rulings — a parent
                // dedicating the TV shouldn't have to remember Save & close.
                resolveFlagged { c ->
                    c.copy(
                        deviceProfiles =
                            if (profileId == null) c.deviceProfiles - token
                            else c.deviceProfiles + (token to profileId)
                    )
                }
            },
            onOpenStats = { statsDevice = it },
            onConfigReplaced = { configEpoch++ }
        )
        // Per-device Stats answers "what's happening today"; this answers
        // "how did the week go" across every device from the phone's own cache.
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            onClick = { digestOpen = true }
        ) { Text("📅 Weekly digest") }

        SectionTitle("AI content screening")
        AiScreeningSection(ai, profiles, onChanged = { ai = it })

        if (ai.enabled) {
            SectionTitle("Waiting for your OK")
            AiReviewSection(
                ai = ai,
                profiles = profiles,
                pairingStore = pairingStore,
                resolved = blocked + aiAllowed + blockedFor.keys + allowedFor.keys,
                onAllow = { id, forKids ->
                    if (forKids == null) {
                        aiAllowed = aiAllowed + id
                        resolveFlagged { c -> c.copy(aiAllowedVideoIds = c.aiAllowedVideoIds + id) }
                    } else {
                        allowedFor = allowedFor + (id to forKids)
                        resolveFlagged { c -> c.copy(allowedFor = c.allowedFor + (id to forKids)) }
                    }
                },
                onBlock = { id, forKids ->
                    if (forKids == null) {
                        blocked = blocked + id
                        resolveFlagged { c -> c.copy(blockedVideoIds = c.blockedVideoIds + id) }
                    } else {
                        blockedFor = blockedFor + (id to forKids)
                        resolveFlagged { c -> c.copy(blockedFor = c.blockedFor + (id to forKids)) }
                    }
                }
            )
        }

        SectionTitle("Discover with AI")
        AiDiscoverySection(ai, entries, yt) { e ->
            entries = (entries + e).distinctBy { it.id }
            newIds = newIds + e.id
        }

        SectionTitle("Suggested channels")
        DirectorySection(entries) { e ->
            entries = (entries + e).distinctBy { it.id }
            newIds = newIds + e.id
        }

        // The list can grow long; only the rarely-used sections sit below it.
        SectionTitle("Channels & playlists")
        ChannelsSection(entries, newIds, yt, resolvedNames, profiles, onChanged = { entries = it })

        SectionTitle("Import, export & backup")
        ExportSection(
            current = {
                // Fill resolved names in as labels so the receiving parent sees
                // "url | Name" lines, not bare urls.
                Whitelist(
                    entries.map { e ->
                        if (e.label == null) e.copy(label = resolvedNames[e.url]) else e
                    },
                    blocked, limits
                )
            },
            onImport = { parsed ->
                // Links only — screen-time rules are UI-managed, never file-driven.
                val fresh = parsed.sources.filter { p -> entries.none { it.id == p.id } }
                entries = entries + fresh
                newIds = newIds + fresh.map { it.id }
                fresh.size
            }
        )

        SectionTitle("App")
        UpdateSection(onUpdateFound = ::revealUpdate)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(16.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
}

// ---------------------------------------------------------------------------
// PIN pad (unchanged Google-TV-style entry)
// ---------------------------------------------------------------------------

@Composable
private fun PinPad(
    title: String,
    subtitle: String? = null,
    error: String? = null,
    onComplete: (String) -> Unit
) {
    var entered by remember { mutableStateOf("") }
    var selectedRow by remember { mutableIntStateOf(0) }
    // Google TV layout: three digit rows, then a lone centered 0.
    val rows: List<List<String?>> = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(null, "0", null)
    )
    val padFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { padFocus.requestFocus() }

    fun press(label: String?) {
        label ?: return
        if (entered.length < 4) entered += label
        if (entered.length == 4) {
            val pin = entered
            entered = ""
            onComplete(pin)
        }
    }

    fun digitFor(key: Key): String? = when (key) {
        Key.Zero -> "0"; Key.One -> "1"; Key.Two -> "2"; Key.Three -> "3"; Key.Four -> "4"
        Key.Five -> "5"; Key.Six -> "6"; Key.Seven -> "7"; Key.Eight -> "8"; Key.Nine -> "9"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .focusRequester(padFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { selectedRow = (selectedRow + rows.size - 1) % rows.size; true }
                    Key.DirectionDown -> { selectedRow = (selectedRow + 1) % rows.size; true }
                    Key.DirectionLeft -> { press(rows[selectedRow][0]); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { press(rows[selectedRow][1]); true }
                    Key.DirectionRight -> { press(rows[selectedRow][2]); true }
                    else -> digitFor(event.key)?.let { press(it); true } ?: false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < entered.length) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        rows.forEachIndexed { rowIndex, cols ->
            val isSelected = rowIndex == selectedRow
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                cols.forEach { label ->
                    if (label == null) {
                        Spacer(Modifier.size(width = 72.dp, height = 60.dp))
                    } else {
                        PinCell(
                            label = label,
                            highlighted = isSelected,
                            onClick = { selectedRow = rowIndex; press(label) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "▲▼ choose a row  ·  ◀ / OK / ▶ pick the number",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PinCell(label: String, highlighted: Boolean, onClick: () -> Unit) {
    Card(
        shape = androidx.compose.ui.graphics.RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (highlighted) {
            androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
        } else null,
        modifier = Modifier
            .clickable { onClick() }
            .size(width = 72.dp, height = 60.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleLarge,
                color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
