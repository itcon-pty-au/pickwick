package io.pickwick.app.data

import io.pickwick.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offers a parent's whole curated list to the community directory in one go.
 * The worker does all the judging — shape, existence, dedup against what's
 * already published, and the open-queue cap — so nothing here filters or
 * pre-validates; a client-side guess about "already listed" would only go
 * stale. Only the links and a language code leave the device.
 */
object DirectorySubmitter {

    /** What the worker did with the batch, one count per outcome. */
    data class Result(
        val status: String,
        val submitted: Int = 0,
        val duplicates: Int = 0,
        val invalid: Int = 0,
        val queueFull: Int = 0,
        /** GitHub-side trouble, not the parent's links — retried, not re-edited. */
        val errors: Int = 0
    )

    /** ISO 639-1 shape, matching the worker's own check on the language field. */
    fun isValidLang(lang: String): Boolean = Regex("^[a-z]{2}$").matches(lang)

    /** Pure request building, kept free of Android/network deps for unit tests. */
    fun buildRequest(entries: List<WhitelistEntry>, lang: String): String {
        val urls = JSONArray()
        entries.forEach { urls.put(it.url) }
        return JSONObject()
            .put("urls", urls)
            .put("lang", lang)
            .toString()
    }

    /** Pure response parsing; an unreadable body reads as an error, not a success. */
    fun parseResponse(text: String): Result {
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: return Result(status = "error")
        return Result(
            status = o.optString("status", "error").ifBlank { "error" },
            submitted = o.optInt("submitted"),
            duplicates = o.optInt("duplicates"),
            invalid = o.optInt("invalid"),
            queueFull = o.optInt("queueFull"),
            errors = o.optInt("errors")
        )
    }

    /** Parent-facing summary of a [Result] — plain counts, no worker vocabulary. */
    fun summarize(r: Result): String = when (r.status) {
        "ok" -> {
            val parts = buildList {
                if (r.submitted > 0) add("Sent ${r.submitted} for review")
                if (r.duplicates > 0) add("${r.duplicates} already in the directory")
                if (r.queueFull > 0) add("the review queue filled before ${r.queueFull} could go — submit again in a few days and only those will be sent")
                if (r.invalid > 0) add("${r.invalid} couldn't be read as YouTube links")
                if (r.errors > 0) add("${r.errors} hit a glitch on the directory's side — submitting again later will retry just those")
            }
            if (parts.isEmpty()) "Nothing to send." else parts.joinToString("; ") + "."
        }
        // A long list goes up in chunks, so some may have landed before the
        // queue filled or the worker failed — don't hide that progress.
        "busy" ->
            (if (r.submitted > 0) "Sent ${r.submitted} for review, then the queue filled" else "The review queue is full right now") +
                " — please try again in a few days."
        else ->
            (if (r.submitted > 0) "Sent ${r.submitted} for review, then something went wrong" else "Couldn't submit the list") +
                ". Please try again later."
    }

    /**
     * Small on purpose, not the worker's 50-URL bound: each accepted channel
     * costs the worker ~6 upstream fetches and the free Cloudflare plan allows
     * 50 subrequests per request, so a bigger chunk would start failing
     * mid-batch. Resubmission is idempotent, so many small chunks lose nothing.
     */
    const val MAX_BATCH = 6

    /** Sum of two results; the sticky status is whichever isn't "ok". */
    fun combine(a: Result, b: Result): Result = Result(
        status = if (a.status == "ok") b.status else a.status,
        submitted = a.submitted + b.submitted,
        duplicates = a.duplicates + b.duplicates,
        invalid = a.invalid + b.invalid,
        queueFull = a.queueFull + b.queueFull,
        errors = a.errors + b.errors
    )

    suspend fun submit(entries: List<WhitelistEntry>, lang: String): Result =
        withContext(Dispatchers.IO) {
            var total = Result(status = "ok")
            for (chunk in entries.chunked(MAX_BATCH)) {
                val body = buildRequest(chunk, lang)
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(BuildConfig.SUGGEST_WORKER_URL.trimEnd('/') + "/submit-list")
                    .post(body)
                    .build()
                val r = Http.client.newCall(request).execute().use { resp ->
                    // 429 carries {"status":"busy"} and 422 the invalid counts —
                    // the body is the answer at every status, so don't gate on
                    // isSuccessful.
                    parseResponse(resp.body?.string().orEmpty())
                }
                total = combine(total, r)
                // The review queue caps open submissions; once it's full (or the
                // worker errors, or GitHub is glitching) further chunks would
                // only repeat the answer.
                if (r.status != "ok" || r.queueFull > 0 || r.errors > 0) break
            }
            total
        }
}
