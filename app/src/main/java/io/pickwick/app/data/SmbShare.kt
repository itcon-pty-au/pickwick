package io.pickwick.app.data

import org.json.JSONObject
import java.util.UUID

/** A reusable connection. Catalog IDs and paths remain independent of it. */
data class SmbShare(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val share: String,
    val username: String = "",
    val domain: String = "",
    val guest: Boolean = true,
    val password: String = "",
    val credentialVersion: String = UUID.randomUUID().toString(),
    val port: Int = 445
) {
    init { catalog() }

    fun catalog(id: String = this.id, name: String = this.name, root: String = "") = SmbCatalog(
        id = id, name = name, host = host, share = share, root = root, username = username,
        domain = domain, guest = guest, password = password, credentialVersion = credentialVersion,
        port = port, shareId = this.id
    )

    fun applyTo(c: SmbCatalog) = catalog(c.id, c.name, c.root).copy(profileIds = c.profileIds, timePercent = c.timePercent)

    fun toJson(secrets: Boolean = false): JSONObject = catalog().toJson(secrets).apply {
        remove("shareId"); remove("root"); remove("profiles"); remove("time")
    }

    override fun toString() = "SmbShare(id=$id, name=$name, host=$host, share=$share)"

    companion object {
        fun fromCatalog(c: SmbCatalog, id: String = UUID.randomUUID().toString(), name: String = c.share) = SmbShare(
            id, name, c.host, c.share, c.username, c.domain, c.guest, c.password, c.credentialVersion, c.port
        )
        fun fromJson(o: JSONObject) = SmbCatalog.fromJson(o).let { fromCatalog(it, it.id, it.name) }
    }
}

fun Whitelist.resolveNetworkShares(): Whitelist = copy(networkCatalogs = networkCatalogs.map { c ->
    if (c.shareId == null) c else requireNotNull(networkShares.firstOrNull { it.id == c.shareId }) {
        "Catalog references an unknown network share"
    }.applyTo(c)
})

/** Called by the editor after secrets have been hydrated; differing logins never merge. */
fun Whitelist.migrateNetworkShares(): Whitelist {
    val shares = networkShares.toMutableList()
    fun sameConnection(s: SmbShare, c: SmbCatalog) =
        s.host.equals(c.host, ignoreCase = true) && s.port == c.port && s.share == c.share &&
            s.guest == c.guest && s.username == c.username && s.domain == c.domain && s.password == c.password
    val catalogs = networkCatalogs.map { c ->
        if (c.shareId != null) c else {
            val s = shares.firstOrNull { sameConnection(it, c) }
                ?: SmbShare.fromCatalog(c).also { shares.add(it) }
            s.applyTo(c)
        }
    }
    return copy(networkShares = shares, networkCatalogs = catalogs).resolveNetworkShares()
}
