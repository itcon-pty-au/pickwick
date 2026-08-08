package io.pickwick.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray

/**
 * SponsorBlock (sponsor.ajay.app): crowd-sourced markers for the advertising
 * baked into the footage itself — sponsor reads, merch plugs, intros/outros,
 * "like and subscribe" pleas. YouTube's own ad breaks never reach us (the
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

    /** The parent-chosen set: everything promotional, plus intros/outros. */
    private val CATEGORIES =
        listOf("sponsor", "selfpromo", "intro", "outro", "interaction")

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
    fun segmentsFor(videoId: String): List<Segment> = runCatching {
        val prefix = java.security.MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .take(2)
            .joinToString("") { "%02x".format(it) }
        val url = "$API/$prefix".toHttpUrl().newBuilder()
            .addQueryParameter(
                "categories",
                CATEGORIES.joinToString(",", "[", "]") { "\"$it\"" }
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
