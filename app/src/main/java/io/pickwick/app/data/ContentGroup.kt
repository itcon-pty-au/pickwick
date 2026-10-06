package io.pickwick.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ContentGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val youtubeSources: Set<String> = emptySet(),
    val catalogIds: Set<String> = emptySet(),
    val sessionMinutes: Map<String, Int> = emptyMap()
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(name.isNotBlank() && name.length <= 200)
        require(sessionMinutes.values.all { it in 0..1440 })
    }
    fun minutesFor(profileId: String?) = sessionMinutes[profileId.orEmpty()]
    fun toJson() = JSONObject().put("id", id).put("name", name)
        .put("youtubeSources", JSONArray(youtubeSources.sorted())).put("catalogIds", JSONArray(catalogIds.sorted()))
        .put("sessionMinutes", JSONObject().apply { sessionMinutes.toSortedMap().forEach { (id, minutes) -> put(id, minutes) } })

    companion object {
        fun fromJson(o: JSONObject): ContentGroup {
            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val limits = o.optJSONObject("sessionMinutes") ?: JSONObject()
            return ContentGroup(o.getString("id"), o.getString("name"), strings("youtubeSources"), strings("catalogIds"),
                limits.keys().asSequence().associateWith { limits.getInt(it) })
        }
    }
}

/** Membership survives feed-cache replacement and saved-list/queue launches. */
class ContentMembershipStore(context: Context) {
    private val dir = File(context.filesDir, "content_membership")
    private fun file(url: String) = File(dir, LocalLibrary.idFor(url) + ".json")
    fun sources(url: String): Set<String> = synchronized(LOCK) {
        val f = file(url)
        if (!f.exists()) emptySet() else JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    }
    fun remember(url: String, sources: Set<String>) = synchronized(LOCK) {
        if (sources.isEmpty()) return@synchronized
        val previous = this.sources(url)
        if (!previous.containsAll(sources)) {
            dir.mkdirs()
            val target = file(url)
            val atomic = android.util.AtomicFile(target)
            val stream = atomic.startWrite()
            try {
                stream.write(JSONArray((previous + sources).sorted()).toString().toByteArray())
                atomic.finishWrite(stream)
            } catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
    }
    companion object { private val LOCK = Any() }
}

/** All I/O here is called off the UI thread. Display names are never identities. */
fun matchingContentGroups(context: Context, config: Whitelist, videoUrl: String,
    originUrl: String? = null, uploaderUrl: String? = null): List<ContentGroup> {
    // Podcasts sit outside screen time entirely, content-group caps included.
    if (PodcastPaths.isPodcast(videoUrl)) return emptyList()
    if (SmbPaths.isNetwork(videoUrl)) {
        val id = SmbPaths.parse(videoUrl).first
        return config.contentGroups.filter { id in it.catalogIds }
    }
    val membership = ContentMembershipStore(context)
    val known = membership.sources(videoUrl).toMutableSet()
    originUrl?.takeIf { it.isNotBlank() }?.let { known.add(it) }
    uploaderUrl?.takeIf { it.isNotBlank() }?.let { known.add(it) }
    if (config.contentGroups.isEmpty()) {
        membership.remember(videoUrl, known)
        return emptyList()
    }
    val resolved = SourceCache(context).load()
    val cache = VideoCache(context)
    config.sources.forEach { source ->
        val aliases = resolved.filter { it.url == source.url || it.id == source.id }
        val ids = setOf(source.id) + aliases.map { it.id }
        val uploaderMatches = source.kind == SourceKind.CHANNEL && uploaderUrl != null &&
            (source.url.trimEnd('/') == uploaderUrl.trimEnd('/') || ids.any { uploaderUrl.trimEnd('/').endsWith("/channel/$it") })
        if (source.url in known || uploaderMatches || ids.any { "id:$it" in known } ||
            ids.any { id -> cache.load(id).any { it.url == videoUrl } }) known.add(source.url)
    }
    membership.remember(videoUrl, known)
    return config.contentGroups.filter { it.youtubeSources.any(known::contains) }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object ContentUsageIo {
    val dispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
}
