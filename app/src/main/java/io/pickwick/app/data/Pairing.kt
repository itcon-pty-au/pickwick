package io.pickwick.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * App-lifetime scope for fire-and-forget LAN pushes. Config syncs must survive
 * the settings UI closing — a composable-tied scope would cancel them mid-push.
 */
object LanPushScope {
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )
}

/** A kid device (TV) this phone administers. */
data class PairedDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** OUR token, presented as X-Token — the credential, not the device's name. */
    val token: String,
    /**
     * The remote device's own identity token, learned from its /status reply.
     * host:port are just where the device happened to live when the QR was
     * scanned — DHCP re-leases and port drift both invalidate them — so this
     * is the only stable way to recognize "our TV" during re-discovery.
     * Null on entries written by older builds until the first successful
     * sync backfills it.
     */
    val id: String? = null
) {
    /**
     * Stable list key: the remote identity when known, else the address the
     * device was paired at. Never [token] — every entry carries THIS phone's
     * token, so token-keyed list operations match every entry at once and
     * collapse the list to a single device.
     */
    val key: String get() = id ?: "$host:$port"
}

/**
 * Add-or-replace, keyed the same way [PairedDevice.key] is: an entry is the
 * same device if the identities match, or failing that if it lives at the same
 * address — a re-pair of a moved TV (known id, new address) must replace the
 * stale entry, and a re-pair of an id-less legacy entry can only match on
 * where it lives.
 */
internal fun List<PairedDevice>.upsertPaired(device: PairedDevice): List<PairedDevice> =
    filterNot {
        (device.id != null && it.id == device.id) ||
            (it.host == device.host && it.port == device.port)
    } + device

internal fun List<PairedDevice>.removePaired(key: String): List<PairedDevice> =
    filterNot { it.key == key }

internal fun List<PairedDevice>.renamePaired(key: String, newName: String): List<PairedDevice> =
    map { if (it.key == key) it.copy(name = newName) else it }

/** Both roles: the TV's own pairing token, and the phone's list of paired devices. */
class PairingStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    /**
     * What this device IS in the family: a parent administers kid devices, a
     * kid device is administered. Explicit rather than inferred from the
     * paired/approved lists — a freshly reinstalled parent phone has an empty
     * paired list and must not start behaving like a kid device (its search
     * index and master claim ride on this). Set by the first pairing act in
     * either direction; TV hardware defaults to kid.
     */
    enum class Role { PARENT, KID }

    fun role(): Role? =
        prefs.getString("role", null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }

    fun setRole(role: Role) {
        if (role() == null) prefs.edit().putString("role", role.name).apply()
    }

    /** This device's stable identity token (a phone presents it when pairing). */
    fun deviceToken(): String =
        prefs.getString("device_token", null) ?: ByteArray(16)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString("device_token", it).apply() }

    // --- TV role: which phones may administer this device -------------------

    private fun readMap(key: String): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    private fun writeMap(key: String, map: Map<String, String>) {
        prefs.edit().putString(key, JSONObject(map).toString()).apply()
    }

    /** token → phone name. Empty until the first phone is approved. */
    fun approvedPhones(): Map<String, String> = readMap("approved")

    fun isApproved(token: String): Boolean = approvedPhones().containsKey(token)

    fun approvePhone(token: String, name: String) {
        writeMap("approved", approvedPhones() + (token to name))
        writeMap("pending", pendingRequests() - token)
    }

    fun pendingRequests(): Map<String, String> = readMap("pending")

    fun addPendingRequest(token: String, name: String) {
        val pending = pendingRequests()
        if (pending.size < 5 && token !in pending) {
            // The name is attacker-supplied and lands in SharedPreferences —
            // bound it so a request can't bloat the prefs file.
            writeMap("pending", pending + (token to name.take(40)))
        }
    }

    fun denyRequest(token: String) {
        writeMap("pending", pendingRequests() - token)
    }

    fun revokePhone(token: String) {
        writeMap("approved", approvedPhones() - token)
    }

    /** Phone role: devices this phone administers. */
    fun paired(): List<PairedDevice> =
        runCatching {
            val arr = JSONArray(prefs.getString("paired", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PairedDevice(
                    o.getString("name"), o.getString("host"), o.getInt("port"),
                    o.getString("token"), o.optString("id").ifEmpty { null }
                )
            }
        }.getOrDefault(emptyList())

    fun addPaired(device: PairedDevice) = savePaired(paired().upsertPaired(device))

    fun removePaired(key: String) = savePaired(paired().removePaired(key))

    /** Parent-chosen display name ("Living room TV"); identity stays [PairedDevice.key]. */
    fun renamePaired(key: String, newName: String) = savePaired(paired().renamePaired(key, newName))

    /**
     * Re-point an entry after re-discovery or an identity backfill. Matched on
     * the old host:port (the only stable key an id-less legacy entry has);
     * everything else about the entry is replaced.
     */
    fun replacePaired(old: PairedDevice, new: PairedDevice) = savePaired(
        paired().map { if (it.host == old.host && it.port == old.port) new else it }
    )

    private fun savePaired(list: List<PairedDevice>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.name); put("host", d.host); put("port", d.port); put("token", d.token)
                d.id?.let { put("id", it) }
            })
        }
        prefs.edit().putString("paired", arr.toString()).apply()
    }
}

/** Fired on the TV when a paired phone pushes new config. */
object ConfigEvents {
    @Volatile
    var onConfigChanged: (() -> Unit)? = null
}

/**
 * Open only while the TV is actually showing its pairing QR, and gates
 * first-admin approval.
 *
 * Without it, any device on the LAN — including a web page in a browser, since
 * a cross-site POST needs no preflight — could claim the admin slot on a fresh
 * install just by asking first. That is worse than it sounds: only an approved
 * phone can approve another one, so a stolen slot leaves the family with no
 * route back except an uninstall, which wipes their curation.
 *
 * The QR on screen is the parent's consent. [keepOpen] is pumped by the
 * pairing screen's existing tick, so the window lapses seconds after that
 * screen goes away.
 */
object PairingWindow {
    private const val LINGER_MS = 15_000L

    @Volatile
    private var openUntil = 0L

    // elapsedRealtime, not wall clock: an NTP correction or timezone change
    // while the QR is up must not silently close (or stretch) the window.
    fun keepOpen() {
        openUntil = android.os.SystemClock.elapsedRealtime() + LINGER_MS
    }

    fun close() {
        openUntil = 0L
    }

    fun isOpen(): Boolean = android.os.SystemClock.elapsedRealtime() < openUntil
}

/**
 * Minimal HTTP listener the TV runs so a paired phone can push config and grants.
 * Plain HTTP on the home LAN, gated by the pairing token — appropriate for the
 * household threat model this app lives in.
 *
 * It does face the whole LAN before any token has been checked, though, so
 * every read here is bounded and connections run on a fixed pool: an
 * unbounded header, body or thread count is a remote crash of the kids' TV
 * that needs no credential at all.
 */
class LanServer(
    private val configStore: ConfigStore,
    /** Applies a grant to the right kid's guard (null profile = legacy/active). */
    private val grantHandler: (minutes: Int, profileId: String?) -> Unit,
    private val pairingStore: PairingStore,
    private val statsProvider: () -> String = { "{}" },
    private val watchStateProvider: () -> String = { "{}" },
    private val watchStateMerger: (String) -> Unit = {},
    /** AI verdict-sharing: serve ours, merge a peer's — see ScreeningStore. */
    private val verdictsProvider: () -> String = { "{}" },
    private val verdictsMerger: (String) -> Unit = {},
    /** Search-index sync: per-source status, one source's payload, and a merger. */
    private val indexStatusProvider: () -> String = { "{}" },
    private val indexSourceProvider: (String) -> String? = { null },
    private val indexMerger: (sourceId: String, body: String) -> Unit = { _, _ -> }
) {
    @Volatile
    var port: Int = 0
        private set

    private var serverSocket: ServerSocket? = null

    /**
     * Fixed pool rather than a thread per connection: a flood of open sockets
     * must cost a bounded number of threads. When both the pool and its short
     * queue are full the connection is dropped, which keeps the TV responsive
     * instead of letting it be talked into exhaustion.
     */
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        CORE_WORKERS, MAX_WORKERS, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(MAX_QUEUED)
    ) { r -> Thread(r, "Pickwick-lan-worker").apply { isDaemon = true } }
        // Core threads time out too: an idle TV should hold none, but a status
        // poll shouldn't have to wait behind a slow config push either.
        .apply { allowCoreThreadTimeOut(true) }

    fun start() {
        if (serverSocket != null) return
        for (candidate in PORT_RANGE) {
            try {
                serverSocket = ServerSocket(candidate)
                port = candidate
                break
            } catch (_: Exception) { /* port busy — try next */ }
        }
        val socket = serverSocket ?: return
        thread(isDaemon = true, name = "Pickwick-lan") {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                val queued = runCatching {
                    workers.execute { runCatching { handle(client) } }
                }.isSuccess
                if (!queued) runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) = client.use { sock ->
        sock.soTimeout = 10_000
        // Bytes, not a Reader: Content-Length counts bytes, and a Reader-based
        // body loop stalls forever on any multi-byte UTF-8 (kid-profile avatar
        // emoji were the first non-ASCII to ever enter a config push — every
        // push then died in a silent socket timeout).
        val input = java.io.BufferedInputStream(sock.getInputStream())
        // A line that never ends is as good as an OOM, so give up past the cap
        // instead of growing the buffer. `aborted` separates that from a clean
        // end of stream, which reads the same to the caller.
        var aborted = false
        fun readLine(): String? {
            val buf = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) break
                if (buf.size() >= MAX_LINE_BYTES) {
                    aborted = true
                    return null
                }
                if (b != '\r'.code) buf.write(b)
            }
            return buf.toString("UTF-8")
        }
        val requestLine = readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val (method, target) = parts

        var contentLength = 0
        var reqToken: String? = null
        var contentType = ""
        var hasOrigin = false
        var headerCount = 0
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            if (++headerCount > MAX_HEADERS) {
                aborted = true
                break
            }
            val lower = line.lowercase()
            when {
                lower.startsWith("content-length:") ->
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                lower.startsWith("x-token:") -> reqToken = line.substringAfter(':').trim()
                lower.startsWith("content-type:") -> contentType = lower.substringAfter(':').trim()
                // Browsers attach Origin to every cross-site request; no real
                // client of this server ever sets it. See the /pair-request gate.
                lower.startsWith("origin:") -> hasOrigin = true
            }
        }
        if (aborted) return

        fun respond(code: Int, text: String) {
            val payload = text.toByteArray()
            sock.getOutputStream().apply {
                // text/plain + nosniff for every reply, JSON included: nothing
                // here is meant for a browser, and the clients parse by shape
                // rather than by content type.
                write(
                    ("HTTP/1.1 $code ${reason(code)}\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "X-Content-Type-Options: nosniff\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                write(payload)
                flush()
            }
        }

        // Read only once we've decided the request is worth reading — see the
        // auth gate below, which answers unauthenticated callers without ever
        // allocating their body.
        fun readBody(): String {
            if (contentLength <= 0) return ""
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bytes, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            return String(bytes, 0, read, Charsets.UTF_8)
        }

        val path = target.substringBefore('?')

        // Declared length is attacker-controlled and allocated in one go, so
        // this check has to come before any body read. The index push carries a
        // whole channel's video list (a deep channel passes 1 MB easily) and is
        // only ever read behind the approved-token gate, so it gets a larger
        // cap — the declared number alone allocates nothing.
        val bodyCap = if (path == "/index") MAX_INDEX_BODY_BYTES else MAX_BODY_BYTES
        if (contentLength > bodyCap) {
            respond(413, "too large")
            return
        }

        // Unauthenticated pairing endpoints: requesting is open (approval is the
        // security), so a photographed QR grants nothing by itself.
        if (method == "POST" && path == "/pair-request") {
            // Drive-by defence. A page open in a browser anywhere on this LAN
            // can post here — a simple cross-site POST triggers no preflight —
            // and on a TV with no admin yet that is enough to take the slot.
            // A browser cannot send application/json cross-site without a
            // preflight, and always tells us where it came from.
            if (hasOrigin || !contentType.startsWith("application/json")) {
                respond(403, "forbidden"); return
            }
            val json = runCatching { JSONObject(readBody()) }.getOrNull()
            val phoneToken = json?.optString("token").orEmpty()
            val phoneName = json?.optString("name").orEmpty().ifEmpty { "Phone" }
            if (!Regex("^[0-9a-f]{32}$").matches(phoneToken)) {
                respond(400, "bad token"); return
            }
            // One decision at a time: two requests racing through the
            // empty-approved check would otherwise both bootstrap as admin.
            val status = synchronized(pairingStore) {
                when {
                    pairingStore.isApproved(phoneToken) -> "approved"
                    pairingStore.approvedPhones().isNotEmpty() -> {
                        pairingStore.addPendingRequest(phoneToken, phoneName)
                        "pending"
                    }
                    // Bootstrap: the very first phone becomes the admin, but only
                    // while the TV is showing its pairing QR — see [PairingWindow].
                    PairingWindow.isOpen() -> {
                        pairingStore.approvePhone(phoneToken, phoneName.take(40))
                        // Accepting an admin makes this device a managed (kid) one.
                        pairingStore.setRole(PairingStore.Role.KID)
                        "approved"
                    }
                    // Nothing to approve against and no open window: say so rather
                    // than parking the request where no one can ever reach it.
                    else -> "closed"
                }
            }
            respond(200, JSONObject().put("status", status).toString())
            return
        }
        if (method == "GET" && path == "/pair-status") {
            val me = Regex("me=([0-9a-f]{32})").find(target)?.groupValues?.get(1)
            val status = when {
                me == null -> "unknown"
                pairingStore.isApproved(me) -> "approved"
                me in pairingStore.pendingRequests() -> "pending"
                else -> "unknown"
            }
            respond(200, JSONObject().put("status", status).toString())
            return
        }

        // Everything else requires an approved phone token.
        if (reqToken == null || !pairingStore.isApproved(reqToken)) {
            respond(403, "forbidden"); return
        }
        when {
            method == "GET" && path == "/pair-pending" -> {
                val arr = org.json.JSONArray()
                pairingStore.pendingRequests().forEach { (token, name) ->
                    arr.put(JSONObject().put("token", token).put("name", name))
                }
                respond(200, arr.toString())
            }
            method == "POST" && path == "/pair-approve" -> {
                val t = Regex("token=([0-9a-f]{32})").find(target)?.groupValues?.get(1)
                val name = pairingStore.pendingRequests()[t]
                if (t != null && name != null) {
                    pairingStore.approvePhone(t, name)
                    respond(200, "approved")
                } else respond(400, "unknown request")
            }
            method == "POST" && path == "/pair-deny" -> {
                val t = Regex("token=([0-9a-f]{32})").find(target)?.groupValues?.get(1)
                if (t != null) {
                    pairingStore.denyRequest(t)
                    respond(200, "denied")
                } else respond(400, "bad token")
            }
            method == "GET" && path == "/stats" -> respond(200, statsProvider())
            method == "GET" && path == "/watchstate" -> respond(200, watchStateProvider())
            method == "POST" && path == "/watchstate" -> {
                watchStateMerger(readBody())
                respond(200, "merged")
            }
            method == "GET" && path == "/verdicts" -> respond(200, verdictsProvider())
            method == "POST" && path == "/verdicts" -> {
                verdictsMerger(readBody())
                respond(200, "merged")
            }
            method == "GET" && path == "/index-status" -> respond(200, indexStatusProvider())
            method == "GET" && path == "/index" -> {
                val id = Regex("source=([A-Za-z0-9_-]+)").find(target)?.groupValues?.get(1)
                val json = id?.let(indexSourceProvider)
                if (json != null) respond(200, json) else respond(404, "unknown source")
            }
            method == "POST" && path == "/index" -> {
                val id = Regex("source=([A-Za-z0-9_-]+)").find(target)?.groupValues?.get(1)
                if (id == null) {
                    respond(400, "bad source")
                } else {
                    // First line is the source state, rest is the video array —
                    // one body, no second round trip.
                    val body = readBody()
                    indexMerger(id, body)
                    respond(200, "merged")
                }
            }
            method == "GET" && path == "/admins" -> {
                val arr = org.json.JSONArray()
                pairingStore.approvedPhones().forEach { (token, name) ->
                    arr.put(JSONObject().put("token", token).put("name", name))
                }
                respond(200, arr.toString())
            }
            method == "POST" && path == "/admin-revoke" -> {
                val t = Regex("token=([0-9a-f]{32})").find(target)?.groupValues?.get(1)
                when {
                    t == null -> respond(400, "bad token")
                    // Lockout guard: a phone can never revoke itself.
                    t == reqToken -> respond(400, "cannot revoke yourself")
                    !pairingStore.isApproved(t) -> respond(400, "unknown admin")
                    else -> {
                        pairingStore.revokePhone(t)
                        respond(200, "revoked")
                    }
                }
            }
            method == "GET" && path == "/status" -> respond(
                200,
                JSONObject()
                    .put("hash", ConfigStore.fingerprint(configStore.load()))
                    .put("updatedAt", configStore.updatedAt())
                    // Our own identity token, so the admin phone can key this
                    // device in deviceProfiles (dedicated-to-a-kid assignment).
                    .put("token", pairingStore.deviceToken())
                    .toString()
            )
            // Disaster recovery: a freshly reinstalled phone starts with an empty
            // config while this device still holds the family's full one (blocks,
            // safe-list, channels, rules). Serving it back means a reinstall costs
            // one re-pair, not the curation history.
            method == "GET" && path == "/config" -> respond(200, configStore.rawJson())
            method == "POST" && path == "/config" -> {
                if (configStore.saveRaw(readBody())) {
                    ConfigEvents.onConfigChanged?.invoke()
                    respond(200, "saved")
                } else respond(400, "bad config")
            }
            method == "POST" && path == "/player" -> {
                val cmd = Regex("cmd=(pause|play)").find(target)?.groupValues?.get(1)
                when {
                    cmd == null -> respond(400, "bad command")
                    RemotePlayerControl.handler?.invoke(cmd) == true -> respond(200, "ok")
                    else -> respond(409, "nothing playing")
                }
            }
            method == "POST" && path == "/grant" -> {
                val minutes = Regex("minutes=(\\d+)").find(target)?.groupValues?.get(1)?.toIntOrNull()
                val profileId = Regex("profile=([0-9a-f]{8})").find(target)?.groupValues?.get(1)
                if (minutes != null && minutes in 1..240) {
                    grantHandler(minutes, profileId)
                    respond(200, "granted")
                } else respond(400, "bad minutes")
            }
            else -> respond(404, "not found")
        }
    }

    companion object {
        /** Where the server may live: first free port wins. One spelling for
         *  the bind loop and the re-discovery sweep, so they can't drift. */
        val PORT_RANGE = 8765..8775

        /** Generous for a request line or header, far below anything harmful. */
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADERS = 50

        /**
         * Config pushes and verdict merges are the big ones — a family with a
         * long safe-list and a season of cached verdicts still lands well
         * inside this, and it caps what a single request can make us allocate.
         */
        private const val MAX_BODY_BYTES = 1024 * 1024

        /** /index only (see the cap check): ~250 bytes/video means 1 MB tops out
         *  near 4,000 videos, and big channels go far past that. */
        private const val MAX_INDEX_BODY_BYTES = 8 * 1024 * 1024

        private const val CORE_WORKERS = 2
        private const val MAX_WORKERS = 8
        private const val MAX_QUEUED = 16

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            else -> "OK"
        }

        /** The TV's LAN IPv4, for the pairing QR. */
        fun localIp(): String? =
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it.isSiteLocalAddress && it is java.net.Inet4Address }
                ?.hostAddress
    }
}

/** Phone side: pushes to paired TVs. */
object LanClient {

    data class DeviceStatus(val hash: String, val updatedAt: Long, val deviceToken: String?)

    /** The device's config fingerprint + last-edit time, or null when unreachable. */
    suspend fun status(device: PairedDevice): Pair<String, Long>? =
        fullStatus(device)?.let { it.hash to it.updatedAt }

    /**
     * A paired device stopped answering at its stored host:port — usually a
     * DHCP re-lease after the TV sat powered off, sometimes the server binding
     * a different port in its 8765..8775 range. Sweep our own /24 for a device
     * that (a) accepts our token and (b) reports the identity we paired with,
     * and return the entry re-pointed at wherever it actually is.
     *
     * Identity is the gate against adopting the wrong device: a kid tablet and
     * the TV both accept this phone's token, and both answer /status. An
     * id-less legacy entry (pre-backfill) is only re-pointed when the sweep
     * finds exactly one candidate — ambiguity means keep the stale address and
     * let the parent re-pair, rather than silently binding to the wrong kid.
     *
     * Null result = nothing found (device off / other network) or the cooldown
     * hasn't lapsed. The cooldown keeps the periodic sync from sweeping the
     * LAN every minute while a TV is legitimately powered down.
     */
    suspend fun rediscover(device: PairedDevice): PairedDevice? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = lastSweepAt.get()
        if (now - last < SWEEP_COOLDOWN_MS || !lastSweepAt.compareAndSet(last, now)) {
            return@withContext null
        }
        val prefixes = localV4Prefixes()
        if (prefixes.isEmpty()) return@withContext null

        val candidates = kotlinx.coroutines.coroutineScope {
            val gate = Semaphore(48)
            prefixes.flatMap { prefix -> (1..254).map { "$prefix.$it" } }
                .map { host ->
                    async {
                        gate.withPermit { probeHost(host, device.token) }
                    }
                }.awaitAll().filterNotNull()
        }

        // One line per sweep so "found nothing" and "found the wrong thing"
        // are distinguishable from logcat without a debugger.
        android.util.Log.i(
            "Pickwick",
            "lan sweep for ${device.name}: ${candidates.size} candidate(s) " +
                candidates.joinToString { "${it.first.first}:${it.first.second}" }
        )
        val match = when {
            device.id != null -> candidates.firstOrNull { it.second == device.id }
            candidates.size == 1 -> candidates.single()
            else -> null
        } ?: return@withContext null

        val (hostPort, identity) = match
        device.copy(host = hostPort.first, port = hostPort.second, id = identity ?: device.id)
    }

    /**
     * One host's slice of the sweep: walk the server's port range in order.
     * A raw connect classifies each port BEFORE any HTTP: no SYN-ACK inside
     * the timeout means nobody lives at this address at all — skip the whole
     * host, which is what keeps a full-subnet sweep at seconds, not minutes.
     * Refused means the host is alive on some other port; connected-but-mute
     * (a hung app still holding the port) means move on to the next port —
     * an HTTP-level timeout must NOT be read as "dead host".
     */
    private fun probeHost(host: String, token: String): Pair<Pair<String, Int>, String?>? {
        for (port in LanServer.PORT_RANGE) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(host, port), CONNECT_PROBE_MS)
                }
            } catch (e: java.net.SocketTimeoutException) {
                return null // dead host: don't walk ten more ports into the void
            } catch (e: java.io.IOException) {
                continue // refused: alive, just nothing on this port
            }
            try {
                scanClient.newCall(
                    Request.Builder()
                        .url("http://$host:$port/status")
                        .header("X-Token", token)
                        .build()
                ).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        return (host to port) to json.optString("token").ifEmpty { null }
                    }
                    // Answered but refused us (403: another family's device, or
                    // our approval was revoked — nothing a rescan can fix).
                    return null
                }
            } catch (e: java.io.IOException) {
                // Listening but not speaking our HTTP: try the next port.
            }
        }
        return null
    }

    /** The /24 prefixes of our own site-local IPv4 addresses ("192.168.0"). */
    private fun localV4Prefixes(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress.substringBeforeLast('.') }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    /** Sweep-only client: hundreds of probes, most of them dead addresses —
     *  the shared client's 5 s connect timeout and retry interceptor would
     *  stretch one sweep into minutes. */
    private val scanClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val lastSweepAt = java.util.concurrent.atomic.AtomicLong(0)
    private const val SWEEP_COOLDOWN_MS = 5 * 60_000L
    private const val CONNECT_PROBE_MS = 400

    /** Status including the device's own identity token (for profile assignment). */
    suspend fun fullStatus(device: PairedDevice): DeviceStatus? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/status", null).use { resp ->
                if (!resp.isSuccessful) {
                    // An HTTP answer is NOT "unreachable" — a 403 here means the
                    // device dropped our approval, which re-pairing fixes and
                    // nothing else will. Say so, or it debugs like a network bug.
                    android.util.Log.i(
                        "Pickwick", "status ${device.name}: HTTP ${resp.code}"
                    )
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                DeviceStatus(
                    json.getString("hash"),
                    json.optLong("updatedAt", 0L),
                    json.optString("token").ifEmpty { null }
                )
            }
        }.getOrNull()
    }

    suspend fun pushConfig(device: PairedDevice, configJson: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/config", configJson).use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** The device's full config JSON (channels, blocks, safe-list, rules), or null. */
    suspend fun fetchConfig(device: PairedDevice): String? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/config", null).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /** Pause/resume what the TV is playing. False when unreachable or idle. */
    suspend fun playerCommand(device: PairedDevice, cmd: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/player?cmd=$cmd", "").use { it.isSuccessful }
            }.getOrDefault(false)
        }

    suspend fun grant(device: PairedDevice, minutes: Int, profileId: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val profileParam = profileId?.let { "&profile=$it" } ?: ""
            runCatching {
                request(device, "POST", "/grant?minutes=$minutes$profileParam", "")
                    .use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** Ask a TV to pair; returns "approved", "pending", or null (unreachable). */
    suspend fun requestPairing(host: String, port: Int, myName: String, myToken: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                raw(host, port, "POST", "/pair-request",
                    JSONObject().put("name", myName).put("token", myToken).toString(), null
                ).use { resp ->
                    if (!resp.isSuccessful) null
                    else JSONObject(resp.body?.string().orEmpty()).optString("status").ifEmpty { null }
                }
            }.getOrNull()
        }

    /** Poll our request's fate: "approved", "pending", or "unknown" (denied). */
    suspend fun pairStatus(host: String, port: Int, myToken: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                raw(host, port, "GET", "/pair-status?me=$myToken", null, null).use { resp ->
                    if (!resp.isSuccessful) null
                    else JSONObject(resp.body?.string().orEmpty()).optString("status").ifEmpty { null }
                }
            }.getOrNull()
        }

    /** Pending pairing requests on a TV (approver's view): name to token. */
    suspend fun pendingRequests(device: PairedDevice): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "GET", "/pair-pending", null).use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val arr = org.json.JSONArray(resp.body?.string().orEmpty())
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        o.getString("name") to o.getString("token")
                    }
                }
            }.getOrDefault(emptyList())
        }

    /** Raw stats JSON from a device, or null when unreachable. */
    suspend fun stats(device: PairedDevice): String? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/stats", null).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    suspend fun fetchWatchState(device: PairedDevice): String? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/watchstate", null).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    suspend fun pushWatchState(device: PairedDevice, json: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/watchstate", json).use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** A device's AI screening verdicts, or null (unreachable, or a pre-verdict-sharing build). */
    suspend fun fetchVerdicts(device: PairedDevice): String? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/verdicts", null).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    suspend fun pushVerdicts(device: PairedDevice, json: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/verdicts", json).use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** A device's per-source index status (hashes), or null when unreachable. */
    suspend fun indexStatus(device: PairedDevice): String? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/index-status", null).use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /** Push one indexed source (state line + video array) to a device. */
    suspend fun pushIndexSource(device: PairedDevice, sourceId: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/index?source=$sourceId", body).use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** Approved admin phones on a TV: name to token. */
    suspend fun admins(device: PairedDevice): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "GET", "/admins", null).use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val arr = org.json.JSONArray(resp.body?.string().orEmpty())
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        o.getString("name") to o.getString("token")
                    }
                }
            }.getOrDefault(emptyList())
        }

    suspend fun revokeAdmin(device: PairedDevice, token: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/admin-revoke?token=$token", "").use { it.isSuccessful }
            }.getOrDefault(false)
        }

    suspend fun approveRequest(device: PairedDevice, token: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/pair-approve?token=$token", "").use { it.isSuccessful }
            }.getOrDefault(false)
        }

    suspend fun denyRequest(device: PairedDevice, token: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                request(device, "POST", "/pair-deny?token=$token", "").use { it.isSuccessful }
            }.getOrDefault(false)
        }

    private fun request(device: PairedDevice, method: String, path: String, body: String?) =
        raw(device.host, device.port, method, path, body, device.token)

    private fun raw(
        host: String, port: Int, method: String, path: String, body: String?, token: String?
    ) = Http.client.newCall(
        Request.Builder()
            .url("http://$host:$port$path")
            .apply { token?.let { header("X-Token", it) } }
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
    ).execute()
}
