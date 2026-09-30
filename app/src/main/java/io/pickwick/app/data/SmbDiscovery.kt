package io.pickwick.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object SmbDiscovery {
    /** Parent-triggered, bounded scan of the local IPv4 subnet only. No authentication. */
    suspend fun findServers(): List<String> = coroutineScope {
        val addresses = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { network -> network.interfaceAddresses }
            .filter { it.address is Inet4Address && it.address.isSiteLocalAddress && it.networkPrefixLength >= 24 }
            .flatMap { local ->
                val bytes = local.address.address
                val base = bytes.take(3).joinToString(".") { (it.toInt() and 255).toString() }
                val mask = (0xff shl (32 - local.networkPrefixLength.toInt())) and 255
                val first = (bytes[3].toInt() and 255) and mask
                val last = first + (255 xor mask)
                (first + 1 until last).map { "$base.$it" }
            }.distinct().take(254)
        val slots = Semaphore(16)
        addresses.map { host -> async(Dispatchers.IO) {
            slots.withPermit {
                runCatching { Socket().use { it.connect(InetSocketAddress(host, 445), 500) }; host }.getOrNull()
            }
        } }.awaitAll().filterNotNull()
    }
}
