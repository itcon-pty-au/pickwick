package io.pickwick.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray

/**
 * SponsorBlock (sponsor.ajay.app): crowd-sourced markers for the advertising
 * baked into the footage itself — sponsor reads, merch plugs, "like and
 * subscribe" pleas, and optionally intros/outros. YouTube's own ad breaks never reach us (the
 * extractor pulls raw streams), so these segments are the only ads a kid
 * still sees.
 *
 * Privacy: lookups use the hash-prefix endpoint. The server sees only the
 * first 4 hex chars of sha256(videoId) and answers with every video matching
 * that prefix, so it cannot tell which one the kid is actually watching.
 */
object SponsorBlock {

    data class Segment(val startMs: Long, val endMs: Long)

    private const val API = "https://sponsor.ajay.app/api/skipSegments"

    /**
     * One kind of marked stretch a parent can choose to skip. [id] is the
     * API's category name; unknown ids in a config are ignored, so a newer
     * build's extra choice never reaches the query string of an older one.
     */
    enum class Category(
        val id: String,
        val label: String,
        val blurb: String,
        val defaultOn: Boolean
    ) {
        Sponsor("sponsor", "Sponsor messages", "Paid promotion read out inside the video.", true),
        SelfPromo("selfpromo", "Self-promotion", "Merch, the creator's other products, shout-outs.", true),
        Interaction("interaction", "Like & subscribe reminders", "Pleas to like, subscribe or follow.", true),
        Intro("intro", "Intros", "The animated logo or recurring opening.", false),
        Outro("outro", "Outros & end cards", "Credits and closing screens.", false),
        Preview("preview", "Recaps & previews", "\"Previously…\" and \"coming up…\" clips.", false),
        Filler("filler", "Filler & tangents", "Off-topic chatter that isn't needed to follow the video.", false),
        MusicOffTopic("music_offtopic", "Non-music parts of music videos", "Talking or skits inside a music video.", false);
    }

    /**
     * What applies until a parent changes it: the actual advertising. Intros
     * and outros are part of the show a kid chose, so they're opt-in.
     */
    val DEFAULT_CATEGORIES: Set<String> =
        Category.values().filter { it.defaultOn }.map { it.id }.toSet()

    private val VIDEO_ID = Regex("[?&]v=([A-Za-z0-9_-]{11})")
    private val BARE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Watch-page or youtu.be URL → video id; null for local/sideloaded URIs. */
    fun videoIdOf(pageUrl: String): String? =
        VIDEO_ID.find(pageUrl)?.groupValues?.get(1)
            ?: pageUrl.substringAfterLast("youtu.be/", "").substringBefore('?')
                .takeIf { BARE_ID.matches(it) }

    /**
     * Marked segments for one video, merged and sorted. Empty on any failure —
     * playback must never wait on or break over this. Blocking; call on IO.
     */
    fun segmentsFor(
        videoId: String,
        categories: Set<String> = DEFAULT_CATEGORIES
    ): List<Segment> = runCatching {
        // Only ids this build knows go on the wire, in a stable order.
        val wanted = Category.values().map { it.id }.filter { it in categories }
        if (wanted.isEmpty()) return@runCatching emptyList()
        val prefix = java.security.MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .take(2)
            .joinToString("") { "%02x".format(it) }
        val url = "$API/$prefix".toHttpUrl().newBuilder()
            .addQueryParameter(
                "categories",
                wanted.joinToString(",", "[", "]") { "\"$it\"" }
            )
            .build()
        val body = Http.client.newCall(Request.Builder().url(url).build())
            .execute().use { resp ->
                // 404 is the API's "no segments known", not an error.
                if (!resp.isSuccessful) return@runCatching emptyList()
                resp.body?.string().orEmpty()
            }
        parse(body, videoId)
    }.getOrDefault(emptyList())

    /** Response body → merged segments; pure so the JVM tests can reach it. */
    internal fun parse(body: String, videoId: String): List<Segment> = runCatching {
        val matches = JSONArray(body)
        val raw = mutableListOf<Segment>()
        for (i in 0 until matches.length()) {
            val video = matches.getJSONObject(i)
            // The hash-prefix endpoint answers with every video sharing the
            // prefix; only this one's segments apply.
            if (video.optString("videoID") != videoId) continue
            val segments = video.optJSONArray("segments") ?: continue
            for (j in 0 until segments.length()) {
                val s = segments.getJSONObject(j)
                // Other action types (mute, full-video label, chapters) are not
                // stretches of footage to jump over.
                if (s.optString("actionType", "skip") != "skip") continue
                val times = s.optJSONArray("segment") ?: continue
                val startMs = (times.getDouble(0) * 1000).toLong()
                val endMs = (times.getDouble(1) * 1000).toLong()
                // Sub-second marks aren't worth a visible seek hiccup.
                if (endMs - startMs >= 1000) raw += Segment(startMs, endMs)
            }
        }
        merge(raw)
    }.getOrDefault(emptyList())

    /**
     * Overlapping/adjacent community marks become one stretch, so the skip
     * loop makes a single jump instead of chaining seeks through each.
     */
    private fun merge(segments: List<Segment>): List<Segment> {
        val sorted = segments.sortedBy { it.startMs }
        val out = mutableListOf<Segment>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs) {
                if (s.endMs > last.endMs) out[out.lastIndex] = last.copy(endMs = s.endMs)
            } else out += s
        }
        return out
    }
}
