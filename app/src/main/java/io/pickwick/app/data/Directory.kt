package io.pickwick.app.data

import io.pickwick.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * One reviewed entry from the community directory at pickwick.tv/directory —
 * the same JSON the website's browse page renders. Every entry has passed AI
 * screening and human review before publishing, but adding one here still goes
 * through this family's own flow (per-kid assignment, screening) like any
 * hand-added channel.
 */
data class DirectoryEntry(
    val name: String,
    val url: String,
    val kind: SourceKind,
    val ages: List<String>,
    val topics: List<String>,
    val note: String
)

object Directory {

    /** Pure JSON→entries parsing, kept free of Android/network deps for unit tests. */
    fun parseEntries(text: String): List<DirectoryEntry> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("entries") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                val name = o.optString("name")
                if (url.isBlank() || name.isBlank()) continue
                fun strings(field: String): List<String> {
                    val a = o.optJSONArray(field) ?: return emptyList()
                    return (0 until a.length()).map { a.getString(it) }
                }
                add(
                    DirectoryEntry(
                        name = name,
                        url = url,
                        kind = if (o.optString("kind") == "playlist") {
                            SourceKind.PLAYLIST
                        } else SourceKind.CHANNEL,
                        ages = strings("ages"),
                        topics = strings("topics"),
                        note = o.optString("note")
                    )
                )
            }
        }
    }

    /**
     * All entries across every published language file. index.json lists the
     * languages, so shipping a new one never needs an app update. Site and app
     * read the same files from the same host — one source of truth.
     */
    suspend fun fetch(): List<DirectoryEntry> = withContext(Dispatchers.IO) {
        val index = JSONObject(get(BuildConfig.DIRECTORY_URL + "index.json"))
        val languages = index.optJSONArray("languages") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until languages.length()) {
                val file = languages.optJSONObject(i)?.optString("file").orEmpty()
                if (file.isBlank()) continue
                runCatching { addAll(parseEntries(get(BuildConfig.DIRECTORY_URL + file))) }
            }
        }
    }

    private fun get(url: String): String {
        // GitHub Pages caches aggressively; bust it like WhitelistImporter does
        // so a just-merged suggestion shows up on the next browse.
        val busted = url + (if ('?' in url) "&" else "?") + "cb=" + System.currentTimeMillis()
        return Http.client.newCall(Request.Builder().url(busted).build()).execute().use { resp ->
            check(resp.isSuccessful) { "Fetch failed: HTTP ${resp.code}" }
            resp.body?.string().orEmpty()
        }
    }
}
