package io.pickwick.app.data

import android.content.Context

/** Watch minutes attributed per channel name — "where does the time actually go". */
class ChannelUsage(context: Context, profileSuffix: String = "") {

    private val prefs = context.applicationContext
        .getSharedPreferences("channel_usage$profileSuffix", Context.MODE_PRIVATE)

    fun addSeconds(channelName: String, seconds: Long) {
        if (channelName.isBlank() || seconds <= 0) return
        val key = "s_$channelName"
        prefs.edit().putLong(key, prefs.getLong(key, 0) + seconds).apply()
    }

    /** Channel name → minutes watched, biggest first. */
    fun topChannels(limit: Int = 8): List<Pair<String, Int>> =
        prefs.all.entries
            .filter { it.key.startsWith("s_") }
            .mapNotNull { e ->
                val secs = (e.value as? Long) ?: return@mapNotNull null
                e.key.removePrefix("s_") to (secs / 60).toInt()
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
}
