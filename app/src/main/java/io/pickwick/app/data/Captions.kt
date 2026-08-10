package io.pickwick.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Turns a caption track into the plain transcript the deep check reads.
 * Parsing is pure and format-driven (VTT/SRT/TTML — the three
 * [YouTubeRepository.subtitlesOf] emits), kept Android-free for unit tests.
 */
object Captions {

    /**
     * The track worth screening: English, and human-authored over auto-generated
     * — [YouTubeRepository.subtitlesOf] already sorts humans first, so the first
     * English hit is the best one. "en", "en-US", "en-GB" all count.
     */
    fun pickEnglish(subtitles: List<YouTubeRepository.Subtitle>): YouTubeRepository.Subtitle? =
        subtitles.firstOrNull {
            it.languageTag.equals("en", ignoreCase = true) ||
                it.languageTag.startsWith("en-", ignoreCase = true)
        }

    /** Downloads and flattens a track; null on any failure — a missing transcript
     *  is an expected state (the deep check falls back to description + tags),
     *  never an error worth surfacing. */
    suspend fun fetchText(subtitle: YouTubeRepository.Subtitle): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(Request.Builder().url(subtitle.url).build())
                    .execute().use { resp ->
                        check(resp.isSuccessful) { "HTTP ${resp.code}" }
                        toPlainText(resp.body?.string().orEmpty())
                    }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    private val CUE_TIMING = Regex("""\d{1,2}:\d{2}(:\d{2})?[.,]\d{3}\s+-->""")
    private val TAG = Regex("<[^>]*>")

    /**
     * Format sniffing over trusting the declared mime type: the URL decides
     * what YouTube actually serves, and a wrong branch would feed cue timings
     * to the model as "spoken words".
     */
    fun toPlainText(body: String): String {
        val trimmed = body.trimStart('﻿', ' ', '\n', '\r', '\t')
        val lines = when {
            trimmed.startsWith("<") -> ttmlLines(trimmed)
            else -> cueLines(trimmed) // WEBVTT and SRT share the line discipline
        }
        // Rolling auto-captions repeat each line in the next cue (line 2 of cue
        // N is line 1 of cue N+1) — collapsing consecutive duplicates recovers
        // the transcript instead of doubling it.
        val out = StringBuilder()
        var last: String? = null
        lines.forEach { raw ->
            val line = decodeEntities(TAG.replace(raw, "")).trim()
            if (line.isEmpty() || line == last) return@forEach
            last = line
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    /** VTT/SRT: drop headers, cue numbers, timing lines and NOTE/STYLE blocks. */
    private fun cueLines(body: String): List<String> {
        var lines = body.lines()
        // The WEBVTT header block ("Kind: captions", "Language: en", …) runs to
        // the first blank line and looks nothing like a timing line — drop it
        // whole rather than pattern-matching text that could also be dialogue.
        if (lines.firstOrNull()?.trimStart()?.startsWith("WEBVTT") == true) {
            val firstBlank = lines.indexOfFirst { it.isBlank() }
            lines = if (firstBlank >= 0) lines.drop(firstBlank + 1) else emptyList()
        }
        var inMetaBlock = false
        return lines.filter { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> { inMetaBlock = false; false }
                inMetaBlock -> false
                t.startsWith("WEBVTT") -> false
                t.startsWith("NOTE") || t.startsWith("STYLE") || t.startsWith("REGION") -> {
                    inMetaBlock = true; false
                }
                CUE_TIMING.containsMatchIn(t) -> false
                t.all { it.isDigit() } -> false // SRT cue counter (or a VTT cue id that's a number)
                else -> true
            }
        }.toList()
    }

    /** TTML: the text inside <p> cues; <br/> is a line break, tags are noise. */
    private fun ttmlLines(body: String): List<String> =
        Regex("<p\\b[^>]*>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(body)
            .map { it.groupValues[1].replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n") }
            .flatMap { it.lineSequence() }
            .toList()

    private fun decodeEntities(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        // Last, so "&amp;lt;" decodes to the literal "&lt;" and not to "<".
        .replace("&amp;", "&")
}
