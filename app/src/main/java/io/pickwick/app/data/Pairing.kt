package io.pickwick.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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
    val token: String
)

/** Both roles: the TV's own pairing token, and the phone's list of paired devices. */
class PairingStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("pairing", Context.MODE_PRIVATE)

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

    /** token → phone name. Migrates the legacy single-token model on first read. */
    fun approvedPhones(): Map<String, String> {
        if (!prefs.contains("approved") && prefs.contains("device_token")) {
            // Pre-approval builds handed the device token to the paired phone —
            // keep that phone working as the first approved admin.
            writeMap("approved", mapOf(deviceToken() to "Family phone"))
        }
        return readMap("approved")
    }

    fun isApproved(token: String): Boolean = approvedPhones().containsKey(token)

    fun approvePhone(token: String, name: String) {
        writeMap("approved", approvedPhones() + (token to name))
        writeMap("pending", pendingRequests() - token)
    }

    fun pendingRequests(): Map<String, String> = readMap("pending")

    fun addPendingRequest(token: String, name: String) {
        val pending = pendingRequests()
        if (pending.size < 5 && token !in pending) {
            writeMap("pending", pending + (token to name))
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
                PairedDevice(o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("token"))
            }
        }.getOrDefault(emptyList())

    fun addPaired(device: PairedDevice) {
        val list = paired().filter { it.token != device.token } + device
        savePaired(list)
    }

    fun removePaired(token: String) = savePaired(paired().filter { it.token != token })

    /** Parent-chosen display name ("Living room TV"); identity stays the token. */
    fun renamePaired(token: String, newName: String) = savePaired(
        paired().map { if (it.token == token) it.copy(name = newName) else it }
    )

    private fun savePaired(list: List<PairedDevice>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.name); put("host", d.host); put("port", d.port); put("token", d.token)
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
 * Minimal HTTP listener the TV runs so a paired phone can push config and grants.
 * Plain HTTP on the home LAN, gated by the pairing token — appropriate for the
 * household threat model this app lives in.
 */
class LanServer(
    private val configStore: ConfigStore,
    /** Applies a grant to the right kid's guard (null profile = legacy/active). */
    private val grantHandler: (minutes: Int, profileId: String?) -> Unit,
    private val pairingStore: PairingStore,
    private val statsProvider: () -> String = { "{}" },
    private val watchStateProvider: () -> String = { "{}" },
    private val watchStateMerger: (String) -> Unit = {}
) {
    @Volatile
    var port: Int = 0
        private set

    private var serverSocket: ServerSocket? = null

    fun start() {
        if (serverSocket != null) return
        for (candidate in 8765..8775) {
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
                thread(isDaemon = true) { runCatching { handle(client) } }
            }
        }
    }

    private fun handle(client: Socket) = client.use { sock ->
        sock.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val (method, target) = parts

        var contentLength = 0
        var reqToken: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            if (lower.startsWith("x-token:")) reqToken = line.substringAfter(':').trim()
        }
        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        } else ""

        fun respond(code: Int, text: String) {
            val payload = text.toByteArray()
            sock.getOutputStream().apply {
                write("HTTP/1.1 $code OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(payload)
                flush()
            }
        }

        val path = target.substringBefore('?')

        // Unauthenticated pairing endpoints: requesting is open (approval is the
        // security), so a photographed QR grants nothing by itself.
        if (method == "POST" && path == "/pair-request") {
            val json = runCatching { JSONObject(body) }.getOrNull()
            val phoneToken = json?.optString("token").orEmpty()
            val phoneName = json?.optString("name").orEmpty().ifEmpty { "Phone" }
            if (!Regex("^[0-9a-f]{32}$").matches(phoneToken)) {
                respond(400, "bad token"); return
            }
            val status = when {
                pairingStore.isApproved(phoneToken) -> "approved"
                pairingStore.approvedPhones().isEmpty() -> {
                    // Bootstrap: the very first phone becomes the admin.
                    pairingStore.approvePhone(phoneToken, phoneName)
                    "approved"
                }
                else -> {
                    pairingStore.addPendingRequest(phoneToken, phoneName)
                    "pending"
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
                watchStateMerger(body)
                respond(200, "merged")
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
                if (configStore.saveRaw(body)) {
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

    /** Status including the device's own identity token (for profile assignment). */
    suspend fun fullStatus(device: PairedDevice): DeviceStatus? = withContext(Dispatchers.IO) {
        runCatching {
            request(device, "GET", "/status", null).use { resp ->
                if (!resp.isSuccessful) return@withContext null
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
