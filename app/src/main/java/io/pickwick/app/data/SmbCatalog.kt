package io.pickwick.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.UUID

fun Whitelist.networkCatalogFor(id: String, profileId: String?): SmbCatalog? {
    if (profiles.isNotEmpty() && profiles.none { it.id == profileId }) return null
    return networkCatalogs.firstOrNull { it.id == id && it.visibleTo(profileId) }
}

data class SmbCatalog(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val share: String,
    val root: String = "",
    val username: String = "",
    val domain: String = "",
    val guest: Boolean = true,
    val password: String = "",
    val credentialVersion: String = UUID.randomUUID().toString(),
    val profileIds: Set<String> = emptySet(),
    val timePercent: Int = 100,
    val port: Int = 445,
    val shareId: String? = null
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/\\@" })
        require(share.isNotBlank() && share.none { it in "/\\" } && share !in listOf(".", ".."))
        SmbPaths.clean(root)
        require(timePercent in TIME_MULTIPLIERS)
        require(port in 1..65535)
    }

    fun visibleTo(profileId: String?) = profileIds.isEmpty() || profileId in profileIds
    override fun toString() = "SmbCatalog(id=$id, name=$name, host=$host, share=$share)"
    fun url(path: String): String = "pickwick://smb/$id/" +
        SmbPaths.clean(path).split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun toJson(secrets: Boolean = false) = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("share", share)
        put("root", SmbPaths.clean(root)); put("username", username); put("domain", domain)
        put("guest", guest); put("credentialVersion", credentialVersion)
        put("port", port)
        shareId?.let { put("shareId", it) }
        put("profiles", JSONArray(profileIds.sorted())); put("time", timePercent)
        if (secrets) put("password", password)
    }

    companion object {
        fun fromJson(o: JSONObject) = SmbCatalog(
            id = o.getString("id"), name = o.getString("name"), host = o.getString("host"),
            share = o.getString("share"), root = o.optString("root"), username = o.optString("username"),
            domain = o.optString("domain"), guest = o.optBoolean("guest", true), password = o.optString("password"),
            credentialVersion = o.optString("credentialVersion"),
            profileIds = o.optJSONArray("profiles")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
            timePercent = o.optInt("time", 100), port = o.optInt("port", 445),
            shareId = o.optString("shareId").ifBlank { null }
        )
    }
}

object SmbPaths {
    fun clean(path: String): String {
        val parts = path.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." || it.any { c -> c.code < 32 || c == ':' } }) { "Invalid folder path" }
        return parts.joinToString("/")
    }
    fun join(root: String, relative: String) = listOf(clean(root), clean(relative)).filter { it.isNotEmpty() }.joinToString("/")
    fun parse(url: String): Pair<String, String> {
        val uri = URI(url)
        require(uri.scheme == "pickwick" && uri.host == "smb" && uri.query == null && uri.fragment == null)
        val parts = uri.rawPath.trimStart('/').split('/', limit = 2)
        require(parts.size == 2 && parts[0].matches(Regex("[A-Za-z0-9-]{1,80}")))
        val path = clean(URLDecoder.decode(parts[1].replace("+", "%2B"), "UTF-8"))
        require(path.isNotEmpty())
        return parts[0] to path
    }
    fun isNetwork(url: String) = url.startsWith("pickwick://smb/")
    val naturalOrder = Comparator<String> { a, b ->
        val chunks = Regex("\\d+|\\D+")
        val aa = chunks.findAll(a.lowercase()).map { it.value }.toList()
        val bb = chunks.findAll(b.lowercase()).map { it.value }.toList()
        var result = 0
        for (i in 0 until minOf(aa.size, bb.size)) {
            val x = aa[i]; val y = bb[i]
            result = if (x[0].isDigit() && y[0].isDigit()) {
                val xx = x.trimStart('0').ifEmpty { "0" }; val yy = y.trimStart('0').ifEmpty { "0" }
                xx.length.compareTo(yy.length).takeIf { it != 0 } ?: xx.compareTo(yy)
            } else x.compareTo(y)
            if (result != 0) break
        }
        if (result != 0) result else aa.size.compareTo(bb.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
