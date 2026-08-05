package io.pickwick.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pickwick.app.BuildConfig
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.DownloadEvents
import io.pickwick.app.data.DownloadService
import io.pickwick.app.data.DownloadStatus
import io.pickwick.app.data.DownloadStore
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.LanServer
import io.pickwick.app.data.Limits
import io.pickwick.app.data.LocalLibrary
import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.ScreeningStore
import io.pickwick.app.data.SessionGuard
import io.pickwick.app.data.SettingsStore
import io.pickwick.app.data.SourceCache
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.TIME_MULTIPLIERS
import io.pickwick.app.data.Updater
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.data.WhitelistParser
import io.pickwick.app.data.YouTubeRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
                PinPad(title = "Enter parent PIN", error = error) { pin ->
                    if (settings.checkPin(pin)) {
                        error = null
                        stage = Stage.Editor
                    } else {
                        error = "Wrong PIN — try again"
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
                PinPad(title = "Enter the same PIN again to confirm") { pin ->
                    if (pin == firstPin) {
                        settings.setPin(pin)
                        stage = Stage.Editor
                    } else {
                        error = "PINs didn't match — start again"
                        stage = Stage.Create
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
    // Live: a push from the phone changes the fingerprint on screen within 2s,
    // so the parent can watch the two devices match.
    LaunchedEffect(Unit) {
        while (true) {
            hash = ConfigStore.fingerprint(configStore.load())
            edited = configStore.updatedAt()
            kotlinx.coroutines.delay(2_000)
        }
    }

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

        val server = LanServerHolder.server
        val ip = remember { LanServer.localIp() }
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
    val initial = remember(configEpoch) { configStore.load() }
    var entries by remember(initial) { mutableStateOf(initial.sources) }
    var limits by remember(initial) { mutableStateOf(initial.limits) }
    var blocked by remember(initial) { mutableStateOf(initial.blockedVideoIds) }
    var ai by remember(initial) { mutableStateOf(initial.ai) }
    var aiAllowed by remember(initial) { mutableStateOf(initial.aiAllowedVideoIds) }
    var profiles by remember(initial) { mutableStateOf(initial.profiles) }
    var blockedFor by remember(initial) { mutableStateOf(initial.blockedFor) }
    var allowedFor by remember(initial) { mutableStateOf(initial.allowedFor) }
    var deviceProfiles by remember(initial) { mutableStateOf(initial.deviceProfiles) }
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
        StatsScreen(device, configStore, pairingStore) { statsDevice = null }
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
            deviceProfiles.filterValues { it in validIds }
        )
    }

    fun saveAndSync() {
        val config = buildCurrentConfig()
        configStore.save(config)
        val devices = pairingStore.paired()
        // Close immediately — the save is already on disk. Blocking the button on
        // the LAN round-trip read as "Save & close does nothing" whenever the TV
        // was asleep (a standby Chromecast drops off Wi-Fi, so the push sits in
        // connect-timeout for seconds with no feedback).
        onDone(true)
        if (devices.isEmpty()) return
        val json = ConfigStore.toJson(config)
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
        if (profiles.isEmpty()) {
            ScreenTimeSection(limits, onChanged = { limits = it })
        } else {
            // Per-kid rules: pick a kid, edit their rules, or copy a sibling's.
            var editingKidId by remember(profiles.map { it.id }) {
                mutableStateOf(profiles.first().id)
            }
            val editingKid = profiles.firstOrNull { it.id == editingKidId } ?: profiles.first()
            KidSelectorChips(profiles, editingKid.id, onSelect = { editingKidId = it })
            Spacer(Modifier.height(8.dp))
            ScreenTimeSection(editingKid.limits, onChanged = { newLimits ->
                profiles = profiles.map {
                    if (it.id == editingKid.id) it.copy(limits = newLimits) else it
                }
            })
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

        SectionTitle("Screen time today")
        GrantTimeSection(pairingStore, profiles)
        PauseTodayRow(
            pausedUntil = limits.pausedUntilMillis,
            onChanged = { until ->
                // Applied immediately — a timeout shouldn't wait for Save & close.
                // Only the pause field changes on disk; other unsaved form edits
                // stay unsaved until the parent commits them.
                limits = limits.copy(pausedUntilMillis = until)
                val updated = configStore.load().let { c ->
                    c.copy(limits = c.limits.copy(pausedUntilMillis = until))
                }
                configStore.save(updated)
                val json = ConfigStore.toJson(updated)
                io.pickwick.app.data.LanPushScope.scope.launch {
                    pairingStore.paired().forEach { LanClient.pushConfig(it, json) }
                }
            }
        )

        SectionTitle("Offline downloads")
        DownloadsSection()

        SectionTitle("Videos from this phone")
        LocalVideosSection(profiles)

        SectionTitle("Kid devices")
        PhoneDevicesSection(
            pairingStore, configStore,
            profiles = profiles,
            deviceProfiles = deviceProfiles,
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

        SectionTitle("AI content screening")
        AiScreeningSection(ai, profiles, onChanged = { ai = it })

        if (ai.enabled) {
            SectionTitle("Waiting for your OK")
            AiReviewSection(
                ai = ai,
                profiles = profiles,
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

        SectionTitle("Import from a whitelist link")
        UrlImportSection { parsed ->
            // Links only — screen-time rules are UI-managed, never file-driven.
            val fresh = parsed.sources.filter { p -> entries.none { it.id == p.id } }
            entries = entries + fresh
            newIds = newIds + fresh.map { it.id }
            fresh.size
        }

        SectionTitle("Export & backup")
        ExportSection {
            // Fill resolved names in as labels so the receiving parent sees
            // "url | Name" lines, not bare urls.
            Whitelist(
                entries.map { e ->
                    if (e.label == null) e.copy(label = resolvedNames[e.url]) else e
                },
                blocked, limits
            )
        }

        SectionTitle("App")
        UpdateSection()

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

// --- Channels ---------------------------------------------------------------

/** One-time import: fetch a hosted whitelist file, add its links, forget the URL. */
@Composable
private fun UrlImportSection(onImport: (Whitelist) -> Int) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Text(
        "Have a whitelist file online? Paste its raw link — the channels and playlists " +
            "in it are added below (tagged NEW), then the link is forgotten.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = { Text("https://…/whitelist.txt") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !busy && url.isNotBlank(),
            onClick = {
                val candidate = url.trim()
                when {
                    !candidate.startsWith("https://") -> message = "Link must start with https://"
                    "/blob/" in candidate -> message = "Use the raw link (no /blob/)"
                    else -> scope.launch {
                        busy = true
                        message = "Fetching…"
                        runCatching { io.pickwick.app.data.WhitelistImporter.fetch(candidate) }
                            .onSuccess { parsed ->
                                val added = onImport(parsed)
                                message = if (added > 0) {
                                    "Added $added new channel(s)/playlist(s) — review them below (tagged NEW)"
                                } else "Nothing new in that file"
                                url = ""
                            }
                            .onFailure { message = "Import failed: ${it.message}" }
                        busy = false
                    }
                }
            }
        ) { Text(if (busy) "…" else "Import") }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Shares or saves the current list (including unsaved edits) as a whitelist.txt
 * in the import-compatible format — a backup, or a list another parent can use.
 */
@Composable
private fun ExportSection(current: () -> Whitelist) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    fun exportText(): String {
        val today = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
            .format(java.util.Date())
        return io.pickwick.app.data.WhitelistExporter.toText(current(), today)
    }

    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(exportText().toByteArray())
            } ?: error("couldn't open the file")
            "Saved ✓"
        }.getOrElse { "Save failed: ${it.message}" }
    }

    Text(
        "Save the channel list as a file — keep it as a backup, or send it to " +
            "another parent so they can import it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row {
        TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Pickwick whitelist")
                putExtra(android.content.Intent.EXTRA_TEXT, exportText())
            }
            runCatching {
                context.startActivity(
                    android.content.Intent.createChooser(send, "Share whitelist")
                )
            }.onFailure { message = "No app available to share with" }
        }) { Text("Share…") }
        Spacer(Modifier.width(8.dp))
        TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
            runCatching { saveLauncher.launch("pickwick-whitelist.txt") }
                .onFailure { message = "No file picker on this device" }
        }) { Text("Save to file…") }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelsSection(
    entries: List<WhitelistEntry>,
    newIds: Set<String>,
    yt: YouTubeRepository,
    /** url → real name, resolved by AdminScreen for entries without a label. */
    resolvedNames: Map<String, String>,
    profiles: List<io.pickwick.app.data.Profile>,
    onChanged: (List<WhitelistEntry>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<YouTubeRepository.ChannelResult>>(emptyList()) }
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<WhitelistEntry?>(null) }
    /** A just-picked channel awaiting the "who is this for?" answer. */
    var pendingAdd by remember { mutableStateOf<WhitelistEntry?>(null) }

    fun displayName(entry: WhitelistEntry) =
        entry.label ?: resolvedNames[entry.url] ?: entry.id

    fun add(entry: WhitelistEntry) {
        // With one kid (or none) there's nothing to ask.
        if (profiles.size < 2) onChanged((entries + entry).distinctBy { it.id })
        else pendingAdd = entry
    }

    pendingAdd?.let { entry ->
        WhoForDialog(
            title = "Who is ${displayName(entry)} for?",
            profiles = profiles,
            initialIds = emptySet(),
            confirmLabel = "Add",
            onDismiss = { pendingAdd = null },
            onConfirm = { forKids ->
                onChanged((entries + entry.copy(profileIds = forKids)).distinctBy { it.id })
                pendingAdd = null
            }
        )
    }

    // Delete confirmation
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${displayName(entry)}?") },
            text = { Text("It will disappear from the kid's app after you save.") },
            confirmButton = {
                Button(onClick = {
                    onChanged(entries - entry)
                    pendingDelete = null
                    // The focused delete button leaves the tree on removal; without
                    // this, focus falls to the next focusable (the search box).
                    focusManager.clearFocus()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Current list
    if (entries.isEmpty()) {
        Text("Nothing allowed yet — search below to add channels.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    entries.forEach { entry ->
        // One tile per source: identity and actions in the first row, per-kid
        // chips in a scrollable second — chips inline with the name wrapped a
        // three-kid family into a broken layout.
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeMultiplierChip(entry.timeMultiplierPercent) { pct ->
                onChanged(entries.map {
                    if (it.id == entry.id) it.copy(timeMultiplierPercent = pct) else it
                })
            }
            Spacer(Modifier.width(8.dp))
            // Tap toggles a marquee so long names (sharing the row with the
            // chip) can still be read in full. Weight and the click target live
            // on a stable Box so only the Text animates — with them in the same
            // chain, the Row redistributes width every frame and the text
            // shimmers instead of gliding. No ripple: a press flash on every
            // row you grab to scroll reads as flicker, and the marquee itself
            // is the feedback. Self-stopping, so forgotten rows don't keep
            // gliding under a vertical drag.
            var marquee by remember(entry.id) { mutableStateOf(false) }
            // Composed only while scrolling — rows in the idle state (all of
            // them, usually) carry no effect node at all.
            if (marquee) LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(6_000)
                marquee = false
            }
            Box(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null
                    ) { marquee = !marquee }
            ) {
                Text(
                    displayName(entry) +
                        if (entry.kind == SourceKind.PLAYLIST) "  (playlist)" else "",
                    maxLines = 1,
                    // Forces one-line intrinsic measurement; otherwise the
                    // paragraph is re-broken against the marquee's unbounded
                    // width on every frame, which jitters the glyphs.
                    softWrap = false,
                    overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (marquee) Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 0,
                        // Twice the default 30.dp/s — the default reads as sluggish
                        // when you tapped specifically to see the rest of a name.
                        velocity = 60.dp
                    ) else Modifier
                )
            }
            if (entry.id in newIds) {
                Text(
                    "NEW",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            IconButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { pendingDelete = entry }
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove ${displayName(entry)}"
                )
            }
        }
        if (profiles.size >= 2) {
            KidToggleChips(
                profiles = profiles,
                selectedIds = entry.profileIds,
                onChanged = { forKids ->
                    onChanged(entries.map {
                        if (it.id == entry.id) it.copy(profileIds = forKids) else it
                    })
                }
            )
        }
        }
        }
    }

    Spacer(Modifier.height(8.dp))
    // Search by name
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search YouTube channels…") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !searching && query.isNotBlank(),
            onClick = {
                scope.launch {
                    searching = true
                    results = runCatching { yt.searchChannels(query.trim()) }.getOrDefault(emptyList())
                    searching = false
                }
            }
        ) { Text(if (searching) "…" else "Search") }
    }

    results.forEach { r ->
        val alreadyAdded = entries.any { it.url == r.url || r.url.endsWith(it.id) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            AsyncImage(
                model = r.avatarUrl,
                contentDescription = r.name,
                modifier = Modifier.size(40.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.subscriberCount > 0) {
                    Text(
                        "${formatCount(r.subscriberCount)} subscribers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                enabled = !alreadyAdded,
                onClick = {
                    val id = r.url.substringAfterLast('/')
                    add(WhitelistEntry(id, r.url, r.name, SourceKind.CHANNEL))
                }
            ) { Text(if (alreadyAdded) "Added ✓" else "Add") }
        }
    }

    Spacer(Modifier.height(8.dp))
    // Paste fallback (also how playlists are added)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = pasteText,
            onValueChange = { pasteText = it },
            placeholder = { Text("Or paste a channel/playlist link") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            onClick = {
                val parsed = WhitelistParser.parse(pasteText.trim()).sources.firstOrNull()
                if (parsed == null) {
                    pasteError = "Couldn't recognise that link"
                } else {
                    pasteError = null
                    pasteText = ""
                    add(parsed)
                }
            }
        ) { Text("Add") }
    }
    pasteError?.let { Text(it, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall) }
}

/**
 * The screen-time "price tag" for one source: tap cycles
 * 1x → 1.25x → 1.5x → 0.75x → 0.5x → 0.25x → FREE → 1x; long-press resets to 1x.
 * The kid's home screen shows the same label on the channel tile.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TimeMultiplierChip(percent: Int, onChange: (Int) -> Unit) {
    val color = timeMultiplierColor(percent)
    // Deliberately no tvFocusHighlight: this screen is phone/touch-only (TVs
    // show the QR screen), and its graphicsLayer + animation states per row —
    // times a 40-channel list in a non-lazy scroll Column — cost enough frame
    // time to judder the whole page during a drag.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = {
                    // Unknown value (hand-edited config) self-heals to the start.
                    val i = TIME_MULTIPLIERS.indexOf(percent)
                    onChange(TIME_MULTIPLIERS[(i + 1) % TIME_MULTIPLIERS.size])
                },
                onLongClick = { onChange(100) }
            )
            .widthIn(min = 54.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            timeMultiplierLabel(percent),
            style = MaterialTheme.typography.labelMedium,
            color = if (color == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
        )
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

// --- Screen time ------------------------------------------------------------

@Composable
private fun ScreenTimeSection(limits: Limits, onChanged: (Limits) -> Unit) {
    StepperRow(
        label = "Time per session",
        value = limits.sessionMinutes, step = 5, min = 5, max = 240,
        format = { "$it min" },
        onChanged = { onChanged(limits.copy(sessionMinutes = it)) }
    )
    StepperRow(
        label = "Sessions on weekdays",
        value = limits.weekdaySessions, step = 1, min = 1, max = 12,
        format = { "$it" },
        onChanged = { onChanged(limits.copy(weekdaySessions = it)) }
    )
    StepperRow(
        label = "Sessions on weekends",
        value = limits.weekendSessions, step = 1, min = 1, max = 12,
        format = { "$it" },
        onChanged = { onChanged(limits.copy(weekendSessions = it)) }
    )
    StepperRow(
        label = "Break between sessions",
        value = limits.breakMinutes, step = 15, min = 15, max = 240,
        format = { "$it min" },
        onChanged = { onChanged(limits.copy(breakMinutes = it)) }
    )

    val bedtimeOn = limits.bedtimeStartMin != null && limits.bedtimeEndMin != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Bedtime", modifier = Modifier.weight(1f))
        Switch(
            modifier = Modifier.tvFocusHighlight(),
            checked = bedtimeOn,
            onCheckedChange = { on ->
                onChanged(
                    if (on) limits.copy(bedtimeStartMin = 19 * 60 + 30, bedtimeEndMin = 7 * 60)
                    else limits.copy(bedtimeStartMin = null, bedtimeEndMin = null)
                )
            }
        )
    }
    if (bedtimeOn) {
        StepperRow(
            label = "  Starts", value = limits.bedtimeStartMin, step = 30, min = 0, max = 24 * 60 - 30,
            allowOff = false, format = ::formatClock,
            onChanged = { onChanged(limits.copy(bedtimeStartMin = it)) }
        )
        StepperRow(
            label = "  Ends", value = limits.bedtimeEndMin, step = 30, min = 0, max = 24 * 60 - 30,
            allowOff = false, format = ::formatClock,
            onChanged = { onChanged(limits.copy(bedtimeEndMin = it)) }
        )
    }
    val summary = buildString {
        val s = limits.sessionMinutes
        val wd = limits.weekdaySessions
        val we = limits.weekendSessions
        if (s != null && wd != null) append("Weekdays: up to ${s * wd} min total. ")
        if (s != null && we != null) append("Weekends: up to ${s * we} min total.")
        if (isEmpty()) append("No limits set — unlimited watching.")
    }
    Text(summary, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun formatClock(minOfDay: Int): String = "%d:%02d".format(minOfDay / 60, minOfDay % 60)

/** D-pad/touch-friendly stepper. Stepping below [min] turns the rule Off (null). */
@Composable
private fun StepperRow(
    label: String,
    value: Int?,
    step: Int,
    min: Int,
    max: Int,
    allowOff: Boolean = true,
    format: (Int) -> String,
    onChanged: (Int?) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            onClick = {
                when {
                    value == null -> {}
                    value - step < min -> if (allowOff) onChanged(null)
                    else -> onChanged(value - step)
                }
            }
        ) { Text("−") }
        Text(
            value?.let(format) ?: "Off",
            modifier = Modifier.widthIn(min = 64.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            onClick = {
                onChanged(if (value == null) min else (value + step).coerceAtMost(max))
            }
        ) { Text("+") }
    }
}

// --- AI screening -------------------------------------------------------------

private data class AiPreset(val name: String, val baseUrl: String, val modelHint: String)

private val AI_PRESETS = listOf(
    AiPreset("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-haiku-4.5"),
    AiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    AiPreset("Anthropic", "https://api.anthropic.com/v1", "claude-haiku-4-5"),
    AiPreset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    AiPreset("Local", "http://192.168.0.10:11434/v1", "llama3.2")
)

private val DEFAULT_AI_RULES = """
    No scary, violent, or disturbing content.
    No adult themes, romance, or innuendo.
    No unboxing, toy hauls, or heavy consumerism.
    No clickbait or low-effort "brainrot" content.
""".trimIndent()

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
private fun AiScreeningSection(
    ai: io.pickwick.app.data.AiConfig,
    profiles: List<io.pickwick.app.data.Profile>,
    onChanged: (io.pickwick.app.data.AiConfig) -> Unit
) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsMessage by remember { mutableStateOf<String?>(null) }

    // Fetch the provider's model list as soon as it's reachable (key entered, or a
    // keyless local server). Keyed on url+key so edits re-fetch; the delay debounces typing.
    LaunchedEffect(ai.enabled, ai.baseUrl, ai.apiKey) {
        models = emptyList()
        modelsMessage = null
        val keyless = ai.baseUrl.startsWith("http://") // local LAN server
        if (!ai.enabled || (ai.apiKey.isBlank() && !keyless)) return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        modelsMessage = "Loading models…"
        runCatching { io.pickwick.app.data.AiScreener.listModels(ai) }
            .onSuccess {
                models = it
                modelsMessage = if (it.isEmpty()) "Provider returned no models" else null
            }
            .onFailure { modelsMessage = "Couldn't load models: ${it.message?.take(80)}" }
    }

    Text(
        "New videos on allowed channels are checked against your rules by an AI " +
            "before the kid can see them. Only video titles and channel names are " +
            "sent — never watch history. Anything blocked appears under each " +
            "device's Stats for your review.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Screen new videos with AI", modifier = Modifier.weight(1f))
        Switch(
            modifier = Modifier.tvFocusHighlight(),
            checked = ai.enabled,
            onCheckedChange = { on ->
                onChanged(ai.copy(enabled = on, rules = ai.rules.ifBlank { DEFAULT_AI_RULES }))
            }
        )
    }
    if (!ai.enabled) return

    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AI_PRESETS.forEach { preset ->
            FilterChip(
                modifier = Modifier.tvFocusHighlight(),
                selected = ai.baseUrl == preset.baseUrl,
                onClick = { onChanged(ai.copy(baseUrl = preset.baseUrl, model = preset.modelHint)) },
                label = { Text(preset.name) }
            )
        }
    }
    OutlinedTextField(
        value = ai.baseUrl,
        onValueChange = { onChanged(ai.copy(baseUrl = it.trim())) },
        label = { Text("API base URL (OpenAI-compatible)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ai.apiKey,
        onValueChange = { onChanged(ai.copy(apiKey = it.trim())) },
        label = { Text("API key (leave empty for a local server)") },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    if (models.isEmpty()) {
        // No list (no key yet, or fetch failed): plain text entry still works.
        OutlinedTextField(
            value = ai.model,
            onValueChange = { onChanged(ai.copy(model = it.trim())) },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        // Type-to-filter dropdown: OpenRouter alone lists hundreds of models.
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = ai.model,
                onValueChange = {
                    onChanged(ai.copy(model = it.trim()))
                    expanded = true
                },
                label = { Text("Model — type to filter, tap to pick") },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
            )
            val filtered = models.filter { it.contains(ai.model, ignoreCase = true) }.take(25)
            ExposedDropdownMenu(
                expanded = expanded && filtered.isNotEmpty(),
                onDismissRequest = { expanded = false }
            ) {
                filtered.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = {
                            onChanged(ai.copy(model = m))
                            expanded = false
                        }
                    )
                }
            }
        }
    }
    modelsMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ai.rules,
        onValueChange = { onChanged(ai.copy(rules = it)) },
        label = { Text("House rules the AI enforces") },
        supportingText = { Text("Rough notes are fine — the AI understands shorthand.") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth()
    )
    if (profiles.isEmpty()) {
        StepperRow(
            label = "Child age",
            value = ai.childAge, step = 1, min = 2, max = 16,
            format = { "$it" },
            onChanged = { onChanged(ai.copy(childAge = it)) }
        )
    } else {
        Text(
            "Each video is checked once for the whole family — one AI call, a " +
                "verdict per kid, using the ages set under Kids: " +
                profiles.joinToString(", ") { p ->
                    p.name + (p.age?.let { " ($it)" } ?: " (no age)")
                } + ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Changing rules re-screens the whole catalog on every device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !testing && ai.model.isNotBlank(),
            onClick = {
                scope.launch {
                    testing = true
                    testMessage = "Testing…"
                    runCatching {
                        io.pickwick.app.data.AiScreener.screen(
                            ai,
                            listOf(
                                io.pickwick.app.data.Video(
                                    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                                    title = "Fun science experiment for kids",
                                    channelName = "Test Channel",
                                    thumbnailUrl = null,
                                    durationSeconds = 300
                                )
                            )
                        )
                    }
                        .onSuccess { testMessage = "Connected ✓ — ${ai.model} answered" }
                        .onFailure { testMessage = "Failed: ${it.message?.take(160)}" }
                    testing = false
                }
            }
        ) { Text(if (testing) "…" else "Test connection") }
    }
    testMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- AI review queue ---------------------------------------------------------

/**
 * The review queue, served from THIS device's own verdict store — so the parent
 * can rule on held videos even while the kid device is off or unreachable (each
 * device screens its own catalog and reaches the same verdicts). Decisions are
 * committed and pushed as they're tapped, not held for Save & close. The per-device
 * pages under "Kid devices" show the same queue live from that device.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AiReviewSection(
    ai: io.pickwick.app.data.AiConfig,
    profiles: List<io.pickwick.app.data.Profile>,
    /** Already ruled on (parent-blocked or allowed) — dropped from the queue. */
    resolved: Set<String>,
    /** Second arg: null = everyone (tap); a kid set = long-press per-kid ruling. */
    onAllow: (String, Set<String>?) -> Unit,
    onBlock: (String, Set<String>?) -> Unit
) {
    /** (videoId, allow?) awaiting the long-press "which kids?" answer. */
    var perKid by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    perKid?.let { (videoId, isAllow) ->
        WhoForDialog(
            title = if (isAllow) "Allow for which kids?" else "Block for which kids?",
            profiles = profiles,
            initialIds = emptySet(),
            confirmLabel = if (isAllow) "Allow" else "Block",
            onDismiss = { perKid = null },
            onConfirm = { forKids ->
                // "All kids" collapses to the family-wide tap ruling.
                val target = forKids.ifEmpty { null }
                if (isAllow) onAllow(videoId, target) else onBlock(videoId, target)
                perKid = null
            }
        )
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { io.pickwick.app.data.ScreeningStore(context.applicationContext) }
    var all by remember { mutableStateOf<List<Pair<String, ScreeningStore.Entry>>>(emptyList()) }
    /** Cached-feed videos with no verdict yet — the queue is still growing by this much. */
    var stillScreening by remember { mutableIntStateOf(0) }
    // The poll loop outlives any single value of `resolved`; read the latest each
    // pass rather than restarting the (disk-reading) loop on every Allow/Block tap.
    val latestResolved by rememberUpdatedState(resolved)

    // Polled, not read once: screening runs in background batches, and a snapshot
    // taken when the screen opened showed the parent one batch and hid the rest
    // until they left and came back. Off the main thread — this reads every
    // source's cached video list plus the verdict file.
    LaunchedEffect(ai.rulesVersion) {
        while (true) {
            val snapshot = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val flaggedNow = store.flagged(ai.rulesVersion)
                val videoCache = io.pickwick.app.data.VideoCache(context.applicationContext)
                val pending = SourceCache(context.applicationContext).load()
                    .flatMap { videoCache.load(it.id) }
                    .mapNotNull { it.videoId }
                    .distinct()
                    // Parent-allowed videos are never sent for screening, so they'd
                    // otherwise sit in this count forever.
                    .count {
                        it !in latestResolved && store.get(it)?.rulesVersion != ai.rulesVersion
                    }
                flaggedNow to pending
            }
            all = snapshot.first
            stillScreening = snapshot.second
            delay(4_000)
        }
    }

    val flagged = all.filterNot { (id, _) -> id in resolved }

    val screeningNote = if (stillScreening > 0) {
        " $stillScreening more still being screened — they'll appear here as the AI finishes."
    } else ""

    if (flagged.isEmpty()) {
        Text(
            "Nothing waiting for you. Videos the AI blocks or is unsure about " +
                "appear here for your decision." + screeningNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Text(
        "${flagged.size} video(s) held back — hidden from the kid until you decide. " +
            "Each Allow/Block is saved as you tap it." + screeningNote,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // No pagination: a parent asked to rule on a queue wants the whole queue, not
    // a batch that refills after each round trip. The cap only exists so a runaway
    // store can't build thousands of cards into one scrolling Column.
    flagged.take(300).forEach { (videoId, e) ->
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    AsyncImage(
                        model = e.thumb,
                        contentDescription = e.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(width = 104.dp, height = 58.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(2.dp))
                        // With per-kid verdicts, say who it's held for — "held
                        // for Dave · fine for Katy" is the whole point.
                        val verdictLabel = if (profiles.isNotEmpty() && e.perProfile.isNotEmpty()) {
                            val held = profiles.filter {
                                e.perProfile[it.id] != io.pickwick.app.data.AiScreener.Verdict.ALLOW
                            }
                            val fine = profiles.filter {
                                e.perProfile[it.id] == io.pickwick.app.data.AiScreener.Verdict.ALLOW
                            }
                            listOfNotNull(
                                held.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ") { it.name }?.let { "held for $it" },
                                fine.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ") { it.name }?.let { "fine for $it" }
                            ).joinToString(" · ")
                        } else if (e.verdict == io.pickwick.app.data.AiScreener.Verdict.REVIEW) {
                            "AI unsure"
                        } else "AI blocked"
                        Text(
                            listOfNotNull(
                                e.channel.takeIf { it.isNotBlank() },
                                verdictLabel
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (e.reason.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "AI: ${e.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    // Watch it yourself before ruling on the AI's call.
                    TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId")
                                )
                            )
                        }
                    }) { Text("View in YouTube") }
                    Spacer(Modifier.weight(1f))
                    // Tap rules for everyone; with 2+ kids a long-press picks who —
                    // TextButton owns its click, so these are hand-rolled buttons.
                    @Composable
                    fun rulingButton(label: String, isAllow: Boolean) {
                        Text(
                            label,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .tvFocusHighlight()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                .dpadLongPress {
                                    if (profiles.size >= 2) perKid = videoId to isAllow
                                }
                                .combinedClickable(
                                    onClick = {
                                        if (isAllow) onAllow(videoId, null)
                                        else onBlock(videoId, null)
                                    },
                                    onLongClick = {
                                        if (profiles.size >= 2) perKid = videoId to isAllow
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                    rulingButton("Allow", isAllow = true)
                    Spacer(Modifier.width(8.dp))
                    rulingButton("Block", isAllow = false)
                }
            }
        }
    }
    if (profiles.size >= 2) {
        Text(
            "Tap Allow/Block for all kids — hold to choose which kids.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (flagged.size > 300) {
        Text(
            "…and ${flagged.size - 300} more — they appear as you rule on these.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- AI discovery -------------------------------------------------------------

/** A model suggestion that survived verification against real YouTube. */
private data class DiscoveryCard(
    val entry: WhitelistEntry,
    val name: String,
    val url: String,
    val imageUrl: String?,
    val subtitle: String,
    val why: String
)

/**
 * Natural-language channel discovery: the AI proposes candidates, each is
 * verified via a real YouTube search, and only verified results are shown —
 * with the actual name/avatar/counts, an open-in-YouTube inspection link, and
 * an explicit Add. Hallucinated channels never reach the whitelist.
 */
@Composable
private fun AiDiscoverySection(
    ai: io.pickwick.app.data.AiConfig,
    entries: List<WhitelistEntry>,
    yt: YouTubeRepository,
    onAdd: (WhitelistEntry) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var cards by remember { mutableStateOf<List<DiscoveryCard>>(emptyList()) }

    val ready = ai.model.isNotBlank() && (ai.apiKey.isNotBlank() || ai.baseUrl.startsWith("http://"))
    if (!ready) {
        Text(
            "Describe what your kid loves and let AI suggest channels. To use this, " +
                "set up the AI (model + API key) under \"AI content screening\" above — " +
                "the screening toggle itself can stay off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Text(
        "Describe what your kid loves — the AI suggests channels and playlists, " +
            "each verified against YouTube. Inspect anything in the YouTube app " +
            "before adding it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("e.g. calm science videos for a dinosaur fan, age 7") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !busy && query.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    cards = emptyList()
                    message = "Asking ${ai.model}…"
                    val suggestions = runCatching {
                        io.pickwick.app.data.AiScreener.suggest(ai, query.trim())
                    }.getOrElse { e ->
                        message = "AI request failed: ${e.message?.take(120)}"
                        busy = false
                        return@launch
                    }
                    if (suggestions.isEmpty()) {
                        message = "No suggestions — try describing it differently"
                        busy = false
                        return@launch
                    }

                    message = "Checking ${suggestions.size} suggestion(s) on YouTube…"
                    val verified = coroutineScope {
                        suggestions.map { s ->
                            async { runCatching { verifySuggestion(s, yt) }.getOrNull() }
                        }.awaitAll()
                    }.filterNotNull().distinctBy { it.entry.id }

                    cards = verified
                    message = when {
                        verified.isEmpty() -> "None of the suggestions could be verified on YouTube — try again"
                        else -> "Verified ${verified.size} of ${suggestions.size} — " +
                            "inspect in YouTube, then add what you like"
                    }
                    busy = false
                }
            }
        ) { Text(if (busy) "…" else "Suggest") }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    cards.forEach { card ->
        val alreadyAdded = entries.any { it.id == card.entry.id || it.url == card.entry.url }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            AsyncImage(
                model = card.imageUrl,
                contentDescription = card.name,
                modifier = Modifier.size(40.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(card.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (card.why.isNotBlank()) {
                    Text(
                        card.why,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(card.url)
                        )
                    )
                }
            }) { Text("YouTube") }
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                enabled = !alreadyAdded,
                onClick = { onAdd(card.entry) }
            ) { Text(if (alreadyAdded) "Added ✓" else "Add") }
        }
    }
}

/** Resolves one AI suggestion against real YouTube; null when nothing matches. */
private suspend fun verifySuggestion(
    s: io.pickwick.app.data.AiScreener.Suggestion,
    yt: YouTubeRepository
): DiscoveryCard? = when (s.kind) {
    SourceKind.CHANNEL -> yt.searchChannels(s.searchQuery).firstOrNull()?.let { r ->
        WhitelistParser.parse(r.url).sources.firstOrNull()?.let { e ->
            DiscoveryCard(
                entry = e.copy(label = r.name),
                name = r.name,
                url = r.url,
                imageUrl = r.avatarUrl,
                subtitle = listOfNotNull(
                    "Channel",
                    r.subscriberCount.takeIf { it > 0 }?.let { "${formatCount(it)} subscribers" }
                ).joinToString(" · "),
                why = s.why
            )
        }
    }
    SourceKind.PLAYLIST -> yt.searchPlaylists(s.searchQuery).firstOrNull()?.let { r ->
        WhitelistParser.parse(r.url).sources.firstOrNull()?.let { e ->
            DiscoveryCard(
                entry = e.copy(label = r.name),
                name = r.name,
                url = r.url,
                imageUrl = r.thumbnailUrl,
                subtitle = listOfNotNull(
                    "Playlist",
                    r.uploaderName?.let { "by $it" },
                    r.videoCount.takeIf { it > 0 }?.let { "$it videos" }
                ).joinToString(" · "),
                why = s.why
            )
        }
    }
}

// --- Community directory ------------------------------------------------------

private val DIRECTORY_AGE_ORDER = listOf("2-4", "5-7", "8-10", "11+")

/**
 * Browse the reviewed community directory (pickwick.tv/directory) and add
 * entries to the whitelist. Directory review means "other parents vouch for
 * this", not "right for this family" — added entries land tagged NEW and go
 * through the same per-kid switches and screening as any hand-added channel.
 */
@Composable
private fun DirectorySection(
    entries: List<WhitelistEntry>,
    onAdd: (WhitelistEntry) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var all by remember { mutableStateOf<List<io.pickwick.app.data.DirectoryEntry>>(emptyList()) }
    var selectedAges by remember { mutableStateOf(setOf<String>()) }
    var selectedTopics by remember { mutableStateOf(setOf<String>()) }

    fun load() = scope.launch {
        busy = true
        message = "Loading the directory…"
        runCatching { io.pickwick.app.data.Directory.fetch() }
            .onSuccess { list ->
                all = list
                message = if (list.isEmpty()) "The directory is empty right now" else null
            }
            .onFailure { message = "Couldn't load the directory: ${it.message?.take(120)}" }
        busy = false
    }

    Text(
        "Channels other Pickwick families vouch for — every entry is reviewed " +
            "before it appears. Adding one works like adding by hand: it lands " +
            "tagged NEW below, with your per-kid switches and screening applying " +
            "as usual.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (all.isEmpty()) {
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !busy,
            onClick = { load() }
        ) { Text(if (busy) "…" else "Browse the directory") }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val ages = all.flatMap { it.ages }.distinct()
        .sortedBy { DIRECTORY_AGE_ORDER.indexOf(it).let { i -> if (i == -1) 99 else i } }
    val topics = all.flatMap { it.topics }.distinct().sorted()

    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ages.forEach { a ->
            FilterChip(
                selected = a in selectedAges,
                onClick = {
                    selectedAges = if (a in selectedAges) selectedAges - a else selectedAges + a
                },
                label = { Text("ages " + a.replace("-", "–")) },
                modifier = Modifier.padding(end = 6.dp).tvFocusHighlight()
            )
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        topics.forEach { t ->
            FilterChip(
                selected = t in selectedTopics,
                onClick = {
                    selectedTopics = if (t in selectedTopics) selectedTopics - t else selectedTopics + t
                },
                label = { Text(t) },
                modifier = Modifier.padding(end = 6.dp).tvFocusHighlight()
            )
        }
    }

    val shown = all.filter { e ->
        (selectedAges.isEmpty() || e.ages.any { it in selectedAges }) &&
            (selectedTopics.isEmpty() || e.topics.any { it in selectedTopics })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (shown.size == all.size) "${all.size} channels & playlists"
            else "${shown.size} of ${all.size} shown",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            enabled = !busy,
            onClick = { load() }
        ) { Text("Refresh") }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    shown.forEach { d ->
        // Same accept-path as AI discovery: the canonical WhitelistEntry comes
        // from the parser, so a directory add is byte-identical to a hand add.
        val parsed = remember(d.url) { WhitelistParser.parse(d.url).sources.firstOrNull() }
        val alreadyAdded = parsed != null &&
            entries.any { it.id == parsed.id || it.url == parsed.url }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (d.kind == SourceKind.PLAYLIST) listOf("Playlist") else emptyList())
                        .plus(d.ages.map { "ages " + it.replace("-", "–") })
                        .plus(d.topics)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (d.note.isNotBlank()) {
                    Text(
                        d.note,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(d.url)
                        )
                    )
                }
            }) { Text("YouTube") }
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                enabled = parsed != null && !alreadyAdded,
                onClick = { parsed?.let { onAdd(it.copy(label = d.name)) } }
            ) { Text(if (alreadyAdded) "Added ✓" else "Add") }
        }
    }
}

// --- Offline downloads ------------------------------------------------------

/**
 * The parent's side of offline downloads: approve or decline the kid's
 * requests, watch active downloads, pick the quality, and free up space.
 * No storage cap — usage is shown and cleanup is a deliberate delete here.
 */
@Composable
private fun DownloadsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { DownloadStore(context.applicationContext) }
    // Any change from the kid's taps or the service re-reads the queue live.
    val changes by DownloadEvents.changes.collectAsState()
    val entries = remember(changes) { store.entries() }
    val activeProgress by DownloadEvents.progress.collectAsState()
    var quality by remember { mutableIntStateOf(store.maxHeight) }

    // Android 13+: the first approval asks to show the progress notification.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    fun approve(url: String) {
        store.approve(url)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        DownloadService.start(context)
    }

    Text(
        "The kid taps ⬇️ on any video to ask for it offline. Approved videos are " +
            "saved to this device and play without internet — perfect for car trips. " +
            "Watching them still uses screen time as usual.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Download quality", modifier = Modifier.weight(1f))
        listOf(480, 720, 1080).forEach { h ->
            FilterChip(
                selected = quality == h,
                onClick = { quality = h; store.maxHeight = h },
                label = { Text("${h}p") },
                modifier = Modifier.padding(start = 6.dp).tvFocusHighlight()
            )
        }
    }

    val requested = entries.filter { it.status == DownloadStatus.REQUESTED }
    val active = entries.filter {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
    }
    val failed = entries.filter { it.status == DownloadStatus.FAILED }
    val done = entries.filter { it.status == DownloadStatus.DONE }

    if (requested.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Waiting for your OK", style = MaterialTheme.typography.titleSmall)
        requested.forEach { e ->
            DownloadRow(e, subtitle = e.video.channelName) {
                TextButton(
                    modifier = Modifier.tvFocusHighlight(),
                    onClick = { approve(e.video.url) }
                ) { Text("Approve") }
                TextButton(
                    modifier = Modifier.tvFocusHighlight(),
                    onClick = { store.remove(e.video.url) }
                ) { Text("No") }
            }
        }
    }

    if (active.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Downloading", style = MaterialTheme.typography.titleSmall)
        active.forEach { e ->
            val fraction = activeProgress?.takeIf { it.first == e.video.url }?.second
            val label = when {
                fraction != null -> "${(fraction * 100).toInt()}%"
                else -> "queued"
            }
            DownloadRow(e, subtitle = label) {
                IconButton(onClick = { store.remove(e.video.url) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Cancel download")
                }
            }
        }
    }

    if (failed.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Didn't finish", style = MaterialTheme.typography.titleSmall)
        failed.forEach { e ->
            DownloadRow(e, subtitle = e.error ?: "Download failed") {
                TextButton(
                    modifier = Modifier.tvFocusHighlight(),
                    onClick = { approve(e.video.url) }
                ) { Text("Retry") }
                IconButton(onClick = { store.remove(e.video.url) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                }
            }
        }
    }

    if (done.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("On this device", style = MaterialTheme.typography.titleSmall)
        done.forEach { e ->
            val size = remember(changes, e.video.url) {
                e.video.videoId?.let { store.sizeOf(it) } ?: 0L
            }
            DownloadRow(e, subtitle = formatBytes(size)) {
                IconButton(onClick = { store.remove(e.video.url) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete download")
                }
            }
        }
        Text(
            "Total: ${formatBytes(remember(changes) { store.totalSize() })}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (entries.isEmpty()) {
        Text(
            "No requests yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One video in the downloads lists: thumb, title/subtitle, trailing actions. */
@Composable
private fun DownloadRow(
    entry: DownloadStore.Entry,
    subtitle: String,
    actions: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        AsyncImage(
            model = entry.video.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.width(72.dp).height(40.dp).clip(MaterialTheme.shapes.small)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.video.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.0f kB".format(bytes / 1_000.0)
}

// --- Videos from this phone --------------------------------------------------

/**
 * Parent links videos already on this phone into the kid's Downloads shelf.
 * SAF only: the files stay where they are, Pickwick stores links. Folders are
 * the recommended path (one permission grant covers everything inside, and a
 * rescan picks up new files); single-file picking exists for one-offs.
 */
@Composable
private fun LocalVideosSection(profiles: List<io.pickwick.app.data.Profile> = emptyList()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = remember { LocalLibrary(context.applicationContext) }
    // Local edits ride the downloads change signal — same shelf, same refresh.
    val changes by DownloadEvents.changes.collectAsState()
    val trees = remember(changes) { library.trees() }
    val items = remember(changes) { library.items() }
    val scope = rememberCoroutineScope()
    // Thumbnail/metadata extraction is ~0.1s per file: a folder of episodes
    // needs a narrated scan, not a frozen section.
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /** A picked folder awaiting the "who can watch these?" answer. */
    var pendingTree by remember { mutableStateOf<android.net.Uri?>(null) }

    fun rescan() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            library.rescan { done, total -> if (total > 0) progress = done to total }
            progress = null
        }
    }

    fun linkTree(uri: android.net.Uri, forKids: Set<String>) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            library.addTree(uri, forKids)
            library.rescan { done, total -> if (total > 0) progress = done to total }
            progress = null
        }
    }

    pendingTree?.let { uri ->
        WhoForDialog(
            title = "Who can watch this folder?",
            profiles = profiles,
            initialIds = emptySet(),
            confirmLabel = "Link folder",
            onDismiss = { pendingTree = null },
            onConfirm = { forKids ->
                pendingTree = null
                linkTree(uri, forKids)
            }
        )
    }

    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            if (profiles.size >= 2) pendingTree = uri
            else linkTree(uri, emptySet())
        }
    }
    val pickVideos = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            library.addFiles(uris) { done, total -> progress = done to total }
            progress = null
        }
    }

    Text(
        "Add videos already on this phone — home videos, rips, purchases. " +
            "Pickwick links to the files where they are (nothing is copied or " +
            "uploaded) and shows them on the kid's Downloads shelf, with the " +
            "folder name where the channel name usually goes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            modifier = Modifier.tvFocusHighlight(),
            onClick = { pickFolder.launch(null) }
        ) { Text("Add folder") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            modifier = Modifier.tvFocusHighlight(),
            onClick = { pickVideos.launch(arrayOf("video/*")) }
        ) { Text("Add videos") }
        if (trees.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { rescan() }
            ) { Text("Rescan") }
        }
    }

    progress?.let { (done, total) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text("Adding $done of $total", style = MaterialTheme.typography.bodySmall)
        }
    }

    trees.forEach { tree ->
        val count = items.count { it.treeUri == tree.uri && it.available }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "📁 ${tree.name}  ·  $count video(s)",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (profiles.size >= 2) {
                KidToggleChips(
                    profiles = profiles,
                    selectedIds = tree.profileIds,
                    onChanged = { forKids ->
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            library.setTreeProfiles(tree.uri, forKids)
                        }
                    }
                )
                Spacer(Modifier.width(4.dp))
            }
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { library.forgetTree(tree.uri) } }
            ) { Text("Forget") }
        }
    }

    val sorted = items.sortedWith(compareBy({ it.video.channelName }, { it.video.title }))
    sorted.forEach { item ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            AsyncImage(
                model = item.video.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.width(72.dp).height(40.dp).clip(MaterialTheme.shapes.small)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.video.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    if (item.available) {
                        "${item.video.channelName} · ${formatSeconds(item.video.durationSeconds)}"
                    } else "File missing — rescan, or forget its folder",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Folder videos are managed by their folder — a removed row would
            // silently come back on the next rescan.
            if (item.treeUri.isEmpty()) {
                IconButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { library.remove(item.video.url) }
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove video")
                }
            }
        }
    }

    if (items.isEmpty() && trees.isEmpty()) {
        Text(
            "Nothing linked yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatSeconds(s: Long): String =
    if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)

// --- Grants -----------------------------------------------------------------

@Composable
private fun GrantTimeSection(
    pairingStore: PairingStore,
    profiles: List<io.pickwick.app.data.Profile> = emptyList()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableIntStateOf(15) }
    var granted by remember { mutableStateOf<String?>(null) }
    var targetKidId by remember(profiles.map { it.id }) {
        mutableStateOf(profiles.firstOrNull()?.id)
    }

    // Granting to a child, not to a device: the minutes land on that kid's
    // guard here and on every paired device.
    if (profiles.size >= 2) {
        KidSelectorChips(profiles, targetKidId ?: profiles.first().id) { targetKidId = it }
        Spacer(Modifier.height(4.dp))
    }

    // Same stepper styling as the screen-time rows; Grant applies the amount.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text("Bonus watch time", modifier = Modifier.weight(1f))
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            onClick = { minutes = (minutes - 5).coerceAtLeast(5) }
        ) { Text("−") }
        Text(
            "$minutes min",
            modifier = Modifier.widthIn(min = 64.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        TextButton(
            modifier = Modifier.tvFocusHighlight(),
            onClick = { minutes = (minutes + 5).coerceAtMost(180) }
        ) { Text("+") }
        Spacer(Modifier.width(8.dp))
        Button(
            modifier = Modifier.tvFocusHighlight(),
            onClick = {
                val amount = minutes
                val kidId = if (profiles.isEmpty()) null else targetKidId
                val kidName = profiles.firstOrNull { it.id == kidId }?.name
                val suffix = io.pickwick.app.data.ProfileNamespace(context.applicationContext)
                    .suffixFor(kidId)
                SessionGuard(context.applicationContext, suffix).grantExtraMinutes(amount)
                val devices = pairingStore.paired()
                val who = kidName?.let { " for $it" } ?: ""
                if (devices.isEmpty()) {
                    granted = "Granted $amount extra minutes$who 🎉"
                } else {
                    scope.launch {
                        var ok = 0
                        devices.forEach { if (LanClient.grant(it, amount, kidId)) ok++ }
                        granted = "Granted $amount min$who here + $ok device(s) 🎉"
                    }
                }
            }
        ) { Text("Grant") }
    }
    granted?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Parent timeout: one tap turns all watching off until midnight, on every
 * device, effective immediately (the guard's next tick stops a playing video).
 * Deliberately minimal — no durations, no multi-day bans; Resume is the undo.
 */
@Composable
private fun PauseTodayRow(pausedUntil: Long?, onChanged: (Long?) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val active = pausedUntil != null && pausedUntil > System.currentTimeMillis()

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Pause screen time for the rest of today?") },
            text = {
                Text(
                    "All watching stops right away on every device and stays off " +
                        "until midnight. Normal limits return tomorrow. You can " +
                        "resume any time."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirming = false
                    onChanged(endOfToday())
                }) { Text("Pause") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        if (active) {
            Text(
                "⏸ Screen time is paused until tomorrow",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { onChanged(null) }
            ) { Text("Resume") }
        } else {
            Text("Turn off all watching until midnight", modifier = Modifier.weight(1f))
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { confirming = true }
            ) { Text("Pause for today") }
        }
    }
}

private fun endOfToday(): Long = java.util.Calendar.getInstance().apply {
    add(java.util.Calendar.DAY_OF_YEAR, 1)
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

// --- Pairing ----------------------------------------------------------------

/** "Settings #a1b2c3d4 · edited 3 Aug 2:14 pm" — the comparable version line. */
@Composable
private fun VersionLine(configStore: ConfigStore, refreshKey: Any? = null) {
    val text = remember(refreshKey) {
        val config = configStore.load()
        val hash = ConfigStore.fingerprint(config)
        val edited = configStore.updatedAt().takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.US).format(java.util.Date(it))
        }
        "Settings #$hash" + (edited?.let { " · edited $it" } ?: "")
    }
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun QrImage(content: String) {
    val bitmap = remember(content) {
        val size = 480
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        bmp.asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = "Pairing QR code",
        modifier = Modifier.size(220.dp)
    )
}

private sealed interface DeviceSync {
    data object Checking : DeviceSync
    data object Offline : DeviceSync
    data class Reachable(
        val hash: String,
        val updatedAt: Long,
        /** The device's own identity token — the key in deviceProfiles. */
        val deviceToken: String? = null
    ) : DeviceSync
}

@Composable
private fun PhoneDevicesSection(
    pairingStore: PairingStore,
    configStore: ConfigStore,
    profiles: List<io.pickwick.app.data.Profile> = emptyList(),
    deviceProfiles: Map<String, String> = emptyMap(),
    /** Saves the form's current state to disk and returns its JSON — what Push sends. */
    saveCurrent: () -> String,
    /** Assign a device (by its own token) to a kid; null = shared (picker). */
    onAssign: (String, String?) -> Unit = { _, _ -> },
    onOpenStats: (PairedDevice) -> Unit,
    onConfigReplaced: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(pairingStore.paired()) }
    var syncStates by remember { mutableStateOf<Map<String, DeviceSync>>(emptyMap()) }
    /** device.token → pending pairing requests (name, requestToken) awaiting approval. */
    var pendingByDevice by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    /** device.token → approved admin phones (name, adminToken). */
    var adminsByDevice by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    /** A revoke awaiting confirmation: (device, adminName, adminToken). */
    var pendingRevoke by remember { mutableStateOf<Triple<PairedDevice, String, String>?>(null) }
    /** A pull awaiting confirmation — it replaces this phone's settings wholesale. */
    var pendingPull by remember { mutableStateOf<PairedDevice?>(null) }
    /** A device being renamed (modal with a text field). */
    var renaming by remember { mutableStateOf<PairedDevice?>(null) }
    var pullMessage by remember { mutableStateOf<String?>(null) }
    val myToken = remember { pairingStore.deviceToken() }
    var localHash by remember { mutableStateOf(ConfigStore.fingerprint(configStore.load())) }

    fun checkAll() {
        devices.forEach { device ->
            syncStates = syncStates + (device.token to DeviceSync.Checking)
            scope.launch {
                val status = LanClient.fullStatus(device)
                syncStates = syncStates + (device.token to
                    (status?.let { DeviceSync.Reachable(it.hash, it.updatedAt, it.deviceToken) }
                        ?: DeviceSync.Offline))
                pendingByDevice = pendingByDevice + (device.token to LanClient.pendingRequests(device))
                adminsByDevice = adminsByDevice + (device.token to LanClient.admins(device))
            }
        }
    }

    /** "Watching as" chips: Shared or one kid — for this phone and each device. */
    @Composable
    fun assignmentRow(label: String, deviceToken: String?) {
        if (profiles.size < 2) return
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp)
            )
            if (deviceToken == null) {
                Text(
                    "device must be online to set this",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Row
            }
            val assigned = deviceProfiles[deviceToken]
            FilterChip(
                modifier = Modifier.tvFocusHighlight(),
                selected = assigned == null,
                onClick = { onAssign(deviceToken, null) },
                label = { Text("Shared") }
            )
            profiles.forEach { p ->
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    modifier = Modifier.tvFocusHighlight(),
                    selected = assigned == p.id,
                    onClick = { onAssign(deviceToken, p.id) },
                    label = { Text(p.name) }
                )
            }
        }
    }
    LaunchedEffect(devices) { checkAll() }

    pendingRevoke?.let { (device, adminName, adminToken) ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text("Remove \"$adminName\" as an admin?") },
            text = { Text("That phone will no longer be able to manage ${device.name}. It can re-pair later, but will need approval again.") },
            confirmButton = {
                Button(onClick = {
                    pendingRevoke = null
                    scope.launch { LanClient.revokeAdmin(device, adminToken); checkAll() }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) { Text("Cancel") }
            }
        )
    }

    pendingPull?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingPull = null },
            title = { Text("Copy settings from ${device.name}?") },
            text = {
                Text(
                    "This phone's channels, blocked videos, safe-list and screen-time " +
                        "rules will be replaced by what's on ${device.name}. Any unsaved " +
                        "edits on this screen are discarded."
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingPull = null
                    scope.launch {
                        val remote = LanClient.fetchConfig(device)
                        pullMessage = if (remote != null && configStore.saveRaw(remote)) {
                            localHash = ConfigStore.fingerprint(configStore.load())
                            onConfigReplaced()
                            checkAll()
                            "Copied ${device.name}'s settings to this phone ✓"
                        } else "Couldn't copy from ${device.name} — is it on home Wi-Fi?"
                    }
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPull = null }) { Text("Cancel") }
            }
        )
    }

    renaming?.let { device ->
        var name by remember(device.token) { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        // Display name only — pairing identity stays the token.
                        pairingStore.renamePaired(device.token, name.trim())
                        devices = pairingStore.paired()
                        renaming = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            }
        )
    }

    VersionLine(configStore, refreshKey = localHash)
    pullMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // A phone can be a kid's own device too — dedicate it and it never asks
    // who's watching.
    assignmentRow("This phone:", myToken)
    if (devices.isEmpty()) {
        Text(
            "No kid devices paired. On the TV: Settings → Pair with a parent phone, " +
                "then scan the QR code with this phone's camera.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    devices.forEach { device ->
        val sync = syncStates[device.token]
        // One tile per device: identity and state up top, actions on their own
        // line — buttons squeezed beside the sync text truncated both.
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(
                        onClick = { renaming = device },
                        modifier = Modifier.size(28.dp).tvFocusHighlight()
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Rename ${device.name}",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    when (sync) {
                        is DeviceSync.Checking, null -> "checking…"
                        is DeviceSync.Offline -> "offline — open Pickwick on it, on home Wi-Fi"
                        is DeviceSync.Reachable ->
                            if (sync.hash == localHash) "in sync ✓"
                            else "out of sync — #${sync.hash} on device"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (sync) {
                        is DeviceSync.Reachable ->
                            if (sync.hash == localHash) androidx.compose.ui.graphics.Color(0xFF81C784)
                            else MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    if (sync is DeviceSync.Reachable && sync.hash != localHash) {
                        Button(modifier = Modifier.tvFocusHighlight(), onClick = {
                            // Push = "make the device match this screen": saves the
                            // form (unsaved edits included), then overwrites the device.
                            val json = saveCurrent()
                            localHash = ConfigStore.fingerprint(configStore.load())
                            scope.launch {
                                LanClient.pushConfig(device, json)
                                checkAll()
                            }
                        }) { Text("Push") }
                        Spacer(Modifier.width(4.dp))
                        // The reverse direction: adopt the device's settings — the recovery
                        // path when this phone was reinstalled and the device still holds
                        // the family's blocks/safe-list.
                        TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
                            pendingPull = device
                        }) { Text("Pull") }
                        Spacer(Modifier.width(4.dp))
                    }
                    TextButton(modifier = Modifier.tvFocusHighlight(), onClick = { onOpenStats(device) }) {
                        Text("Stats")
                    }
                    TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
                        pairingStore.removePaired(device.token)
                        devices = pairingStore.paired()
                    }) { Text("Unpair") }
                }
                assignmentRow("Watching:", (sync as? DeviceSync.Reachable)?.deviceToken)
                // Admin phones approved on this device (revocable, except this phone).
                val admins = adminsByDevice[device.token].orEmpty()
                if (admins.size > 1 || (admins.size == 1 && admins[0].second != myToken)) {
                    admins.forEach { (adminName, adminToken) ->
                        val isThisPhone = adminToken == myToken
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "admin: $adminName" + if (isThisPhone) "  (this phone)" else "",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!isThisPhone) {
                                TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
                                    pendingRevoke = Triple(device, adminName, adminToken)
                                }) { Text("Revoke") }
                            }
                        }
                    }
                }
                // New phones asking to become admins for this device.
                pendingByDevice[device.token].orEmpty().forEach { (reqName, reqToken) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "“$reqName” asks to manage ${device.name}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(modifier = Modifier.tvFocusHighlight(), onClick = {
                            scope.launch { LanClient.approveRequest(device, reqToken); checkAll() }
                        }) { Text("Approve") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(modifier = Modifier.tvFocusHighlight(), onClick = {
                            scope.launch { LanClient.denyRequest(device, reqToken); checkAll() }
                        }) { Text("Deny") }
                    }
                }
            }
        }
    }
}

// --- Advanced: URL lists (power users) --------------------------------------
// --- App / updates ----------------------------------------------------------

@Composable
private fun UpdateSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updater = remember { Updater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<Updater.UpdateInfo?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Pickwick ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        val pending = update
        if (pending == null) {
            TextButton(
                modifier = Modifier.tvFocusHighlight(),
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        message = "Checking…"
                        val found = updater.check()
                        update = found
                        message = if (found != null) "Version ${found.versionName} is available"
                        else "You're up to date"
                        busy = false
                    }
                }
            ) { Text(if (busy) "Checking…" else "Check for updates") }
        } else {
            Button(
                modifier = Modifier.tvFocusHighlight(),
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        message = "Downloading ${pending.versionName}…"
                        runCatching { updater.downloadAndInstall(pending) }
                            .onSuccess { message = "Follow the install prompt" }
                            .onFailure { message = "Update failed: ${it.message}" }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Downloading…" else "Install ${pending.versionName}") }
        }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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