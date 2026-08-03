package io.santatube.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-device sync of watch progress and the saved-for-later list.
 * Merge is symmetric last-write-wins, so any exchange order converges.
 */
object WatchSync {

    fun exportJson(context: Context): String {
        val app = context.applicationContext
        val root = JSONObject()

        root.put("history", JSONObject().apply {
            WatchHistoryStore(app).all().forEach { (url, p) ->
                put(url, JSONArray().put(p.positionMs).put(p.durationMs).put(p.lastWatchedAt))
            }
        })
        root.put("watchlist", JSONArray().apply {
            WatchlistStore(app).loadEntries().forEach { e ->
                put(JSONObject()
                    .put("u", e.video.url)
                    .put("t", e.video.title)
                    .put("c", e.video.channelName)
                    .put("th", e.video.thumbnailUrl.orEmpty())
                    .put("d", e.video.durationSeconds)
                    .put("a", e.addedAt))
            }
        })
        root.put("removed", JSONObject().apply {
            WatchlistStore(app).removedMap().forEach { (url, ts) -> put(url, ts) }
        })
        return root.toString()
    }

    /** Merge a peer's payload into this device's stores. */
    fun mergeJson(context: Context, json: String): Boolean = runCatching {
        val app = context.applicationContext
        val root = JSONObject(json)

        val historyObj = root.optJSONObject("history") ?: JSONObject()
        val history = historyObj.keys().asSequence().mapNotNull { url ->
            val arr = historyObj.optJSONArray(url) ?: return@mapNotNull null
            if (arr.length() < 3) return@mapNotNull null
            url to WatchProgress(arr.getLong(0), arr.getLong(1), arr.getLong(2))
        }.toMap()
        WatchHistoryStore(app).mergeAll(history)

        val listArr = root.optJSONArray("watchlist") ?: JSONArray()
        val entries = (0 until listArr.length()).map { i ->
            val o = listArr.getJSONObject(i)
            WatchlistStore.Entry(
                Video(
                    url = o.getString("u"),
                    title = o.getString("t"),
                    channelName = o.optString("c"),
                    thumbnailUrl = o.optString("th").ifEmpty { null },
                    durationSeconds = o.optLong("d")
                ),
                addedAt = o.optLong("a", 1L)
            )
        }
        val removedObj = root.optJSONObject("removed") ?: JSONObject()
        val removed = removedObj.keys().asSequence()
            .associateWith { removedObj.optLong(it) }
        WatchlistStore(app).merge(entries, removed)
        true
    }.getOrDefault(false)
}
