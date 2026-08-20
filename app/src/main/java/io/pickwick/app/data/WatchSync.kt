package io.pickwick.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-device sync of watch progress and the kid's saved lists (Favorites
 * and Watch later). Merge is symmetric last-write-wins, so any exchange order
 * converges.
 *
 * With kid profiles, each kid's state travels keyed by profile id under
 * "profilesState" — device-local store suffixes differ per device (the first
 * kid owns the legacy stores), so the id is the only portable key. The legacy
 * top-level keys still carry the first kid's state, which keeps a mid-update
 * fleet (old TV, new phone) syncing that kid instead of nothing.
 */
object WatchSync {

    fun exportJson(context: Context): String {
        val app = context.applicationContext
        val profiles = ConfigStore(app).load().profiles
        val ns = ProfileNamespace(app)

        val root = exportOne(app, if (profiles.isEmpty()) "" else ns.suffixFor(profiles.first().id))
        if (profiles.isNotEmpty()) {
            root.put("profilesState", JSONObject().apply {
                profiles.forEach { p ->
                    put(p.id, exportOne(app, ns.suffixFor(p.id)))
                }
            })
        }
        return root.toString()
    }

    private fun exportOne(app: Context, suffix: String): JSONObject {
        val root = JSONObject()
        root.put("history", JSONObject().apply {
            WatchHistoryStore(app, suffix).all().forEach { (url, p) ->
                put(url, JSONArray().put(p.positionMs).put(p.durationMs).put(p.lastWatchedAt))
            }
        })
        // Favorites keeps the original key names so a device on an older
        // build still exchanges hearts with a device on this one; Watch later
        // simply goes missing there, which merges as "nothing to add".
        SAVED_LISTS.forEach { (listName, keys) ->
            val store = SavedListStore(app, suffix, listName)
            root.put(keys.first, JSONArray().apply {
                store.loadEntries().forEach { e ->
                    put(JSONObject()
                        .put("u", e.video.url)
                        .put("t", e.video.title)
                        .put("c", e.video.channelName)
                        .put("th", e.video.thumbnailUrl.orEmpty())
                        .put("d", e.video.durationSeconds)
                        .put("a", e.addedAt))
                }
            })
            root.put(keys.second, JSONObject().apply {
                store.removedMap().forEach { (url, ts) -> put(url, ts) }
            })
        }
        return root
    }

    /** Merge a peer's payload into this device's stores. */
    fun mergeJson(context: Context, json: String): Boolean = runCatching {
        val app = context.applicationContext
        val root = JSONObject(json)

        val perProfile = root.optJSONObject("profilesState")
        if (perProfile != null) {
            val ns = ProfileNamespace(app)
            perProfile.keys().forEach { pid ->
                mergeOne(app, ns.suffixFor(pid), perProfile.getJSONObject(pid))
            }
        } else {
            // Pre-profile peer: its whole state belongs to the legacy stores.
            mergeOne(app, "", root)
        }
        true
    }.getOrDefault(false)

    private fun mergeOne(app: Context, suffix: String, root: JSONObject) {
        val historyObj = root.optJSONObject("history") ?: JSONObject()
        val history = historyObj.keys().asSequence().mapNotNull { url ->
            val arr = historyObj.optJSONArray(url) ?: return@mapNotNull null
            if (arr.length() < 3) return@mapNotNull null
            url to WatchProgress(arr.getLong(0), arr.getLong(1), arr.getLong(2))
        }.toMap()
        WatchHistoryStore(app, suffix).mergeAll(history)

        SAVED_LISTS.forEach { (listName, keys) ->
            val listArr = root.optJSONArray(keys.first) ?: JSONArray()
            val entries = (0 until listArr.length()).map { i ->
                val o = listArr.getJSONObject(i)
                SavedListStore.Entry(
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
            val removedObj = root.optJSONObject(keys.second) ?: JSONObject()
            val removed = removedObj.keys().asSequence()
                .associateWith { removedObj.optLong(it) }
            SavedListStore(app, suffix, listName).merge(entries, removed)
        }
    }

    /** list name → (entries key, tombstones key) in the payload. */
    private val SAVED_LISTS = listOf(
        SavedListStore.FAVORITES to ("watchlist" to "removed"),
        SavedListStore.WATCH_LATER to ("watchLater" to "watchLaterRemoved")
    )
}
