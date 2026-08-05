package io.pickwick.app.data

import android.content.Context

/** Counts how often each source is opened — drives the popularity-ordered home grid. */
class UsageStore(context: Context, profileSuffix: String = "") {

    private val prefs = context.applicationContext
        .getSharedPreferences("usage$profileSuffix", Context.MODE_PRIVATE)

    fun opens(sourceId: String): Int = prefs.getInt("opens_$sourceId", 0)

    fun bump(sourceId: String) {
        prefs.edit().putInt("opens_$sourceId", opens(sourceId) + 1).apply()
    }

    /** Latest video URL the kid has seen for this source — drives the NEW badge. */
    fun lastSeenLatest(sourceId: String): String? =
        prefs.getString("latest_$sourceId", null)

    fun setLastSeenLatest(sourceId: String, videoUrl: String) {
        prefs.edit().putString("latest_$sourceId", videoUrl).apply()
    }
}
