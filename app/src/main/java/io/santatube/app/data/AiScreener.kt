package io.santatube.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Screens batches of videos against the parent's rules via any OpenAI-compatible
 * chat-completions endpoint. Only titles, channel names and durations are sent —
 * never watch history. Prompt assembly and response parsing are pure functions,
 * kept Android-free so they are unit-testable.
 */
object AiScreener {

    enum class Verdict { ALLOW, BLOCK, REVIEW }

    data class Result(val videoId: String, val verdict: Verdict, val reason: String)

    /** Longer read timeout than the shared client: batch verdicts can take a while. */
    private val llmClient by lazy {
        Http.client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun systemPrompt(cfg: AiConfig): String = buildString {
        append("You review YouTube videos for a child")
        cfg.childAge?.let { append(" aged $it") }
        append(", judging only by title, channel name and duration.\n")
        append("Family rules:\n")
        append(cfg.rules.ifBlank { "Content must be broadly appropriate for a young child." })
        append("\n\n")
        append("The videos come from channels the parents already trust, so most are fine — ")
        append("block only real rule violations, and use \"review\" when genuinely unsure.\n")
        append("Reply with JSON only, no other text, in this exact shape:\n")
        append("{\"verdicts\":[{\"id\":\"<video id>\",\"v\":\"allow|block|review\",\"why\":\"<max 12 words>\"}]}\n")
        append("Include every id you were given exactly once.")
    }

    fun userPrompt(videos: List<Video>): String {
        val arr = JSONArray()
        videos.forEach { v ->
            val id = v.videoId ?: return@forEach
            arr.put(JSONObject().apply {
                put("id", id)
                put("title", v.title)
                put("channel", v.channelName)
                if (v.durationSeconds > 0) put("minutes", v.durationSeconds / 60)
            })
        }
        return arr.toString()
    }

    /**
     * Parses the model's reply. Tolerates code fences and stray prose around the
     * JSON. Requested ids the model skipped come back as REVIEW — a video is never
     * silently allowed or endlessly retried because the model dropped it.
     */
    fun parseVerdicts(content: String, requestedIds: Set<String>): List<Result> {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        require(start in 0 until end) { "No JSON object in model reply" }
        val root = JSONObject(content.substring(start, end + 1))
        val arr = root.optJSONArray("verdicts") ?: JSONArray()

        val byId = mutableMapOf<String, Result>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id !in requestedIds) continue
            val verdict = when (o.optString("v").lowercase()) {
                "allow" -> Verdict.ALLOW
                "block" -> Verdict.BLOCK
                else -> Verdict.REVIEW
            }
            byId[id] = Result(id, verdict, o.optString("why"))
        }
        requestedIds.forEach { id ->
            byId.getOrPut(id) { Result(id, Verdict.REVIEW, "model returned no verdict") }
        }
        return byId.values.toList()
    }

    /**
     * Model ids the endpoint offers, for the settings dropdown. All OpenAI-compatible
     * providers expose GET /models with the same {"data":[{"id":...}]} shape; Anthropic's
     * native endpoint wants x-api-key instead of Bearer, so 401s get one retry that way.
     */
    suspend fun listModels(cfg: AiConfig): List<String> = withContext(Dispatchers.IO) {
        fun fetch(authorize: Request.Builder.() -> Request.Builder): Pair<Int, String> {
            val request = Request.Builder()
                .url(cfg.baseUrl.trimEnd('/') + "/models")
                .authorize()
                .build()
            return llmClient.newCall(request).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        }

        var (code, body) = fetch {
            if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") else this
        }
        if (code in listOf(401, 403) && cfg.apiKey.isNotBlank()) {
            val retry = fetch {
                header("x-api-key", cfg.apiKey).header("anthropic-version", "2023-06-01")
            }
            code = retry.first
            body = retry.second
        }
        check(code in 200..299) { "HTTP $code: ${body.take(120)}" }

        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        (0 until arr.length())
            .mapNotNull { i -> arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() } }
            .distinct()
            .sorted()
    }

    /** One chat-completions round trip; returns the assistant's text content. */
    private suspend fun chatCompletion(cfg: AiConfig, system: String, user: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", cfg.model)
                .put("temperature", 0)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
                .toString()

            val request = Request.Builder()
                .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
                .header("X-Title", "SantaTube")
                .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val reply = llmClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                check(resp.isSuccessful) {
                    "HTTP ${resp.code}: ${text.take(200)}"
                }
                text
            }
            JSONObject(reply)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }

    /**
     * One batched screening call. Throws on network/HTTP/parse failure — callers
     * leave the batch unscreened (and therefore hidden) and retry on a later feed load.
     */
    suspend fun screen(cfg: AiConfig, videos: List<Video>): List<Result> {
        val ids = videos.mapNotNull { it.videoId }.toSet()
        if (ids.isEmpty()) return emptyList()
        return parseVerdicts(chatCompletion(cfg, systemPrompt(cfg), userPrompt(videos)), ids)
    }

    // --- Discovery: parent's natural-language ask → channel/playlist candidates ---

    /**
     * An unverified candidate from the model. Never shown or added directly:
     * [searchQuery] is resolved against real YouTube first, and candidates that
     * don't resolve are dropped — hallucinated channels can't reach the whitelist.
     */
    data class Suggestion(
        val name: String,
        val kind: SourceKind,
        val searchQuery: String,
        val why: String
    )

    fun discoveryPrompt(cfg: AiConfig): String = buildString {
        append("You recommend YouTube channels and playlists for a child")
        cfg.childAge?.let { append(" aged $it") }
        append(".\n")
        if (cfg.rules.isNotBlank()) {
            append("Respect the family's rules:\n").append(cfg.rules).append('\n')
        }
        append("Given the parent's request, suggest up to 10 real, well-established ")
        append("YouTube channels or playlists. Only ones you are confident actually exist. ")
        append("For each, give a YouTube search query that reliably finds it.\n")
        append("Reply with JSON only, in this exact shape:\n")
        append("{\"suggestions\":[{\"name\":\"...\",\"type\":\"channel|playlist\",")
        append("\"query\":\"...\",\"why\":\"<max 15 words, for the parent>\"}]}")
    }

    fun parseSuggestions(content: String): List<Suggestion> {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        require(start in 0 until end) { "No JSON object in model reply" }
        val arr = JSONObject(content.substring(start, end + 1))
            .optJSONArray("suggestions") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            Suggestion(
                name = name,
                kind = if (o.optString("type").equals("playlist", ignoreCase = true)) {
                    SourceKind.PLAYLIST
                } else SourceKind.CHANNEL,
                searchQuery = o.optString("query").trim().ifEmpty { name },
                why = o.optString("why")
            )
        }
    }

    suspend fun suggest(cfg: AiConfig, parentQuery: String): List<Suggestion> =
        parseSuggestions(chatCompletion(cfg, discoveryPrompt(cfg), parentQuery))
}
