package io.pickwick.app.data

import android.content.Context
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.SAXParserFactory

fun Whitelist.podcastFor(id: String, profileId: String?): PodcastFeed? {
    if (profiles.isNotEmpty() && profiles.none { it.id == profileId }) return null
    return podcasts.firstOrNull { it.id == id && it.visibleTo(profileId) }
}

/**
 * A parent-added RSS podcast. Lives in its own config array rather than as a
 * [SourceKind]: builds that predate podcasts read an unknown entry kind as a
 * YouTube channel, while an unknown top-level key is simply carried along.
 * No time multiplier on purpose — podcasts are audio-only and sit outside
 * screen-time rules entirely.
 */
data class PodcastFeed(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val name: String,
    val profileIds: Set<String> = emptySet()
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(isFeedUrl(url)) { "Enter an http(s) feed address" }
    }

    fun visibleTo(profileId: String?) = profileIds.isEmpty() || profileId in profileIds

    fun toJson() = JSONObject().apply {
        put("id", id); put("url", url); put("name", name)
        put("profiles", JSONArray(profileIds.sorted()))
    }

    companion object {
        fun fromJson(o: JSONObject) = PodcastFeed(
            id = o.getString("id"), url = o.getString("url"), name = o.optString("name"),
            profileIds = o.optJSONArray("profiles")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        )

        fun isFeedUrl(url: String): Boolean = runCatching {
            val u = java.net.URI(url.trim())
            u.scheme?.lowercase() in listOf("http", "https") && !u.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}

data class PodcastEpisode(
    /** Hash of the item's guid: stable even when hosts rotate tracking-redirect audio URLs. */
    val key: String,
    val title: String,
    val audioUrl: String,
    val durationSeconds: Long,
    val publishedMillis: Long,
    val imageUrl: String?,
    val description: String
) {
    fun toJson() = JSONObject().apply {
        put("key", key); put("title", title); put("audio", audioUrl); put("duration", durationSeconds)
        put("published", publishedMillis); imageUrl?.let { put("image", it) }; put("description", description)
    }

    companion object {
        fun fromJson(o: JSONObject) = PodcastEpisode(
            key = o.getString("key"), title = o.getString("title"), audioUrl = o.getString("audio"),
            durationSeconds = o.optLong("duration"), publishedMillis = o.optLong("published"),
            imageUrl = o.optString("image").ifBlank { null }, description = o.optString("description")
        )
    }
}

data class PodcastChannel(val title: String, val imageUrl: String?, val episodes: List<PodcastEpisode>)

/** Episode URLs are synthetic — `pickwick://podcast/<feedId>/<episodeKey>` — like network files. */
object PodcastPaths {
    private const val PREFIX = "pickwick://podcast/"
    fun url(feedId: String, episodeKey: String) = "$PREFIX$feedId/$episodeKey"
    fun isPodcast(url: String) = url.startsWith(PREFIX)
    fun parse(url: String): Pair<String, String>? {
        if (!isPodcast(url)) return null
        val parts = url.removePrefix(PREFIX).split('/')
        if (parts.size != 2 || !parts[0].matches(Regex("[A-Za-z0-9-]{1,80}")) ||
            !parts[1].matches(Regex("[0-9a-f]{16}"))) return null
        return parts[0] to parts[1]
    }
}

object PodcastParser {
    /** Feeds of long-running shows reach several MB; anything past this is not a feed we want. */
    const val MAX_FEED_BYTES = 12L * 1024 * 1024
    private const val MAX_EPISODES = 2_000
    private const val MAX_DESCRIPTION = 2_000

    fun parse(input: InputStream): PodcastChannel {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        // A feed is stranger-written XML: no DTDs, no external entities. Android's
        // parser never resolves them and rejects some of these flags, hence runCatching.
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false
        ).forEach { (feature, on) -> runCatching { factory.setFeature(feature, on) } }
        val handler = Handler()
        try {
            factory.newSAXParser().parse(InputSource(input), handler)
        } catch (_: Done) {
        }
        require(handler.sawChannel) { "This address isn't a podcast feed" }
        return PodcastChannel(handler.channelTitle.trim(), handler.channelImage, handler.episodes)
    }

    private class Done : RuntimeException()

    private class Handler : DefaultHandler() {
        var sawChannel = false
        var channelTitle = ""
        var channelImage: String? = null
        val episodes = mutableListOf<PodcastEpisode>()

        private val text = StringBuilder()
        private var inItem = false
        private var inChannelImage = false
        private var title = ""
        private var guid = ""
        private var audio: String? = null
        private var audioIsMedia = false
        private var duration = 0L
        private var published = 0L
        private var image: String? = null
        private var description = ""
        private var summary = ""

        override fun startElement(uri: String, local: String, qName: String, attrs: Attributes) {
            text.setLength(0)
            val name = local.ifEmpty { qName.substringAfter(':') }
            when {
                name == "channel" -> sawChannel = true
                name == "item" -> {
                    inItem = true
                    title = ""; guid = ""; audio = null; audioIsMedia = false; duration = 0L
                    published = 0L; image = null; description = ""; summary = ""
                }
                name == "image" && uri.equals(ITUNES, ignoreCase = true) -> attrs.getValue("href")?.takeIf { it.isNotBlank() }?.let {
                    if (inItem) image = it else channelImage = it
                }
                name == "image" && !inItem -> inChannelImage = true
                name == "enclosure" && inItem -> {
                    val url = attrs.getValue("url")?.trim()
                    val type = attrs.getValue("type").orEmpty()
                    // Some feeds attach a cover image or transcript before the audio.
                    val media = type.startsWith("audio/") || type.startsWith("video/") || type.isEmpty()
                    if (!url.isNullOrBlank() && (audio == null || (media && !audioIsMedia))) {
                        audio = url; audioIsMedia = media
                    }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            // Bounded: a single hostile text node must not grow without limit.
            if (text.length < 64_000) text.append(ch, start, minOf(length, 64_000 - text.length))
        }

        override fun endElement(uri: String, local: String, qName: String) {
            val name = local.ifEmpty { qName.substringAfter(':') }
            val value = text.toString().trim()
            if (inItem) when {
                name == "title" && uri.isEmpty() -> title = value
                name == "guid" -> guid = value
                name == "duration" && uri.equals(ITUNES, ignoreCase = true) -> duration = parseDuration(value)
                name == "pubDate" -> published = parseDate(value)
                name == "description" && uri.isEmpty() -> description = value
                name == "summary" && uri.equals(ITUNES, ignoreCase = true) -> summary = value
                name == "item" -> {
                    inItem = false
                    val url = audio
                    if (url != null && PodcastFeed.isFeedUrl(url) && title.isNotBlank()) {
                        episodes += PodcastEpisode(
                            key = LocalLibrary.idFor(guid.ifBlank { url }),
                            title = title, audioUrl = url, durationSeconds = duration,
                            publishedMillis = published, imageUrl = image ?: channelImage,
                            description = stripHtml(description.ifBlank { summary }).take(MAX_DESCRIPTION)
                        )
                        if (episodes.size >= MAX_EPISODES) throw Done()
                    }
                }
            } else when {
                name == "title" && uri.isEmpty() && !inChannelImage && channelTitle.isEmpty() -> channelTitle = value
                name == "url" && inChannelImage && channelImage == null -> channelImage = value.ifBlank { null }
                name == "image" && uri.isEmpty() -> inChannelImage = false
            }
            text.setLength(0)
        }
    }

    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    /** `itunes:duration` is either plain seconds or `[HH:]MM:SS`. */
    fun parseDuration(value: String): Long {
        val parts = value.trim().split(':')
        if (parts.any { it.toDoubleOrNull() == null }) return 0L
        return parts.fold(0.0) { acc, p -> acc * 60 + p.toDouble() }.toLong().coerceAtLeast(0L)
    }

    fun parseDate(value: String): Long {
        val patterns = listOf("EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz",
            "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z")
        for (p in patterns) {
            runCatching { SimpleDateFormat(p, Locale.US).parse(value.trim())?.time }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private fun stripHtml(s: String) = s.replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()
}

/** Fetches feeds and keeps the last good parse per feed on disk, for instant (and offline) browsing. */
class PodcastLibrary(context: Context) {
    private val dir = File(context.filesDir, "podcasts").apply { mkdirs() }
    private fun file(feedId: String) = File(dir, LocalLibrary.idFor(feedId) + ".json")

    fun cached(feedId: String): PodcastChannel? = runCatching {
        val o = JSONObject(file(feedId).readText())
        val a = o.getJSONArray("episodes")
        PodcastChannel(o.optString("title"), o.optString("image").ifBlank { null },
            (0 until a.length()).map { PodcastEpisode.fromJson(a.getJSONObject(it)) })
    }.getOrNull()

    fun isFresh(feedId: String) = file(feedId).let { it.exists() && System.currentTimeMillis() - it.lastModified() < FRESH_MS }

    fun refresh(feed: PodcastFeed): PodcastChannel = fetch(feed.url).also { save(feed.id, it) }

    fun episode(feedId: String, key: String): PodcastEpisode? = cached(feedId)?.episodes?.firstOrNull { it.key == key }

    fun drop(feedId: String) { file(feedId).delete() }

    private fun save(feedId: String, c: PodcastChannel) {
        val o = JSONObject().put("title", c.title).put("image", c.imageUrl ?: "")
            .put("episodes", JSONArray().apply { c.episodes.forEach { put(it.toJson()) } })
        val tmp = File(dir, file(feedId).name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file(feedId))
    }

    companion object {
        private const val FRESH_MS = 30 * 60 * 1000L

        fun fetch(url: String): PodcastChannel {
            val request = Request.Builder().url(url.trim()).header("User-Agent", "Pickwick").build()
            return Http.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("The feed answered ${resp.code}")
                val body = resp.body ?: throw IOException("Empty feed")
                if (body.contentLength() > PodcastParser.MAX_FEED_BYTES) throw IOException("The feed is too large")
                PodcastParser.parse(Capped(body.byteStream(), PodcastParser.MAX_FEED_BYTES))
            }
        }
    }

    /** Stops reading past [limit]: Content-Length is optional and chunked bodies can be endless. */
    private class Capped(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var read = 0L
        private fun count(n: Int): Int {
            if (n > 0) read += n
            if (read > limit) throw IOException("The feed is too large")
            return n
        }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
    }
}
