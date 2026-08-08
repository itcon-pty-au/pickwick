package io.pickwick.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pickwick.app.BuildConfig
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- Pairing ----------------------------------------------------------------

/** "Settings #a1b2c3d4 · edited 3 Aug 2:14 pm" — the comparable version line. */
@Composable
private fun VersionLine(configStore: ConfigStore, refreshKey: Any? = null) {
    // refreshKey follows the live form fingerprint, i.e. every keystroke — the
    // disk re-read behind it must not ride the recomposition on the main thread.
    val text by produceState("", refreshKey) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val config = configStore.load()
            val hash = ConfigStore.fingerprint(config)
            val edited = configStore.updatedAt().takeIf { it > 0 }?.let {
                java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.US).format(java.util.Date(it))
            }
            "Settings #$hash" + (edited?.let { " · edited $it" } ?: "")
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun QrImage(content: String) {
    // Built off-main: 480×480 is ~230k pixels, visible time on a TV CPU. One
    // setPixels over an IntArray, not a JNI call per pixel.
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, content) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val size = 480
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size) { i ->
                if (matrix[i % size, i / size]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            }
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
            bmp.setPixels(pixels, 0, size, 0, 0, size, size)
            bmp.asImageBitmap()
        }
    }
    val ready = bitmap
    if (ready != null) {
        Image(
            bitmap = ready,
            contentDescription = "Pairing QR code",
            modifier = Modifier.size(220.dp)
        )
    } else {
        // Same footprint as the image, so the layout doesn't jump when it lands.
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
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
internal fun PhoneDevicesSection(
    pairingStore: PairingStore,
    configStore: ConfigStore,
    profiles: List<io.pickwick.app.data.Profile> = emptyList(),
    deviceProfiles: Map<String, String> = emptyMap(),
    /**
     * Fingerprint of the form as it stands right now (unsaved edits included) —
     * what a device must match to count as in sync. Derived by the caller, so it
     * follows every edit; a value snapshotted from disk here would go stale the
     * moment the parent changed anything.
     */
    localHash: String,
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
                            // onConfigReplaced reloads the whole form from disk,
                            // which re-derives localHash on its own.
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
                            scope.launch {
                                val json = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    saveCurrent()
                                }
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
internal fun UpdateSection(tv: Boolean = false, onUpdateFound: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updater = remember { Updater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<Updater.UpdateInfo?>(null) }

    // Check the moment the screen opens — the parent shouldn't need to know
    // there's a button to press to find out. Offline falls back to the cached
    // answer from the daily launch check, so the Install offer still shows.
    LaunchedEffect(Unit) {
        busy = true
        val found = updater.check() ?: updater.pending()
        update = found
        if (found != null) {
            // On TV the button itself names the version, so the status line is
            // free to carry the thing a remote user actually needs told.
            message = if (tv) "Press OK on the remote to install"
            else "Version ${found.versionName} is available"
            onUpdateFound()
        }
        busy = false
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The rest of the TV screen is a centred column; a weighted text would
        // stretch this row to the full width and leave the version stranded on
        // the left of it. On the phone that same weight is what pushes the
        // check/install button to the trailing edge of the form row.
        horizontalArrangement = if (tv) Arrangement.Center else Arrangement.Start
    ) {
        Text(
            "Pickwick ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (tv) Modifier.padding(end = 12.dp) else Modifier.weight(1f)
        )
        val pending = update
        if (pending == null) {
            // Nothing on TV: this screen already re-checks every time it opens,
            // so a manual button there can never report anything the screen
            // isn't about to say on its own — it would just be a dead control
            // the D-pad can't reach. The phone keeps it: that form is long and
            // a parent may want to force a check without reopening settings.
            if (!tv) TextButton(
                modifier = Modifier.tvFocusHighlight(),
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        message = "Checking…"
                        val found = updater.check()
                        update = found
                        message = when {
                            found == null -> "You're up to date"
                            tv -> "Press OK on the remote to install"
                            else -> "Version ${found.versionName} is available"
                        }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Checking…" else "Check for updates") }
        } else {
            // The rest of the TV settings screen is static text and a QR image,
            // so this button is the only focusable thing on it — without an
            // explicit request nothing holds focus and the D-pad does nothing.
            // Retried because the node isn't placed on the first composition.
            val installFocus = remember { FocusRequester() }
            // Re-requested when [busy] clears: disabling the button during a
            // download drops focus, and with nothing else focusable a failed
            // attempt would leave the remote dead with no way to retry.
            LaunchedEffect(tv, pending.versionCode, busy) {
                if (!tv || busy) return@LaunchedEffect
                repeat(10) {
                    if (runCatching { installFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                    delay(100)
                }
            }
            // The shared 1.04 focus scale vanishes against a solid fill, and this
            // button arrives already focused — with nothing else on screen to
            // move focus between, a parent can't tell it's live. A ring makes the
            // selection legible from the couch; the hint line below names the key.
            var focused by remember { mutableStateOf(false) }
            // Same red as the settings-gear dot, so the nudge that got the
            // parent here and the button that resolves it read as one thing.
            Button(
                modifier = Modifier
                    .focusRequester(installFocus)
                    .tvFocusHighlight { focused = it }
                    .then(
                        if (tv && focused) Modifier.border(3.dp, Color.White, CircleShape)
                        else Modifier
                    ),
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UpdateDot,
                    contentColor = Color.White
                ),
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
            ) {
                // The TV has no comfortable place for a separate status line to
                // be read from ten feet, so the button states the version itself.
                Text(
                    when {
                        busy -> "Downloading…"
                        tv -> "Version ${pending.versionName} available"
                        else -> "Install ${pending.versionName}"
                    }
                )
            }
        }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
