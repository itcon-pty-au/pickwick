package io.pickwick.app.data

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom

/**
 * One kid in the family config. Everything here syncs device-to-device inside
 * config.json — an empty profiles list means the pre-profile behavior (the
 * device is the kid), so existing installs upgrade with zero visible change.
 *
 * The PIN is a 4-step D-pad sequence ("UDLR" characters), entered blind on the
 * TV so a sibling watching the screen learns nothing. Stored plaintext in the
 * config like the AI API key: the file lives in app-private storage on
 * parent-controlled devices, and the threat model is a 6-year-old, not root.
 */
data class Profile(
    /** Stable random id — names are display-only and freely editable. */
    val id: String,
    val name: String,
    /** Tile background, one of [PROFILE_COLORS] (any ARGB survives sync). */
    val colorArgb: Long = PROFILE_COLORS.first(),
    /** Emoji from [PROFILE_AVATARS], or "fluent:<res>" for a bundled image. */
    val avatar: String = PROFILE_AVATARS.first(),
    /** Feeds AI screening ("Dave is 4") — null screens against the family rules alone. */
    val age: Int? = null,
    val limits: Limits = Limits(),
    /** 4 chars of U/D/L/R; null = anyone can pick this profile. */
    val pin: String? = null
) {
    companion object {
        fun newId(): String = ByteArray(4)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
    }
}

/** Picker-tile palette — distinct at ten feet, friendly at arm's length. */
val PROFILE_COLORS = listOf(
    0xFFE53935L, // red
    0xFFF57C00L, // orange
    0xFFFBC02DL, // amber
    0xFF43A047L, // green
    0xFF00897BL, // teal
    0xFF1E88E5L, // blue
    0xFF8E24AAL, // purple
    0xFFD81B60L  // pink
)

/**
 * Curated avatar set (animals, transport, faces) for kids who can't read yet.
 * Each maps to a bundled Fluent Emoji image when the APK carries one (see
 * ui/ProfileAvatar.kt); otherwise the emoji itself renders large.
 */
val PROFILE_AVATARS = listOf(
    "🦊", "🐼", "🦁", "🐸", "🐰", "🦄", "🐙", "🦖",
    "🚗", "🚀", "🚂", "🚜", "🚁", "⛵",
    "🤖", "👻", "🌟", "🌈", "🍉", "⚽", "🎸", "🧁"
)

/** True when [pin] is a valid stored PIN: exactly four D-pad steps. */
fun isValidDirectionPin(pin: String): Boolean =
    pin.length == 4 && pin.all { it in "UDLR" }

/**
 * Device-local mapping profileId → SharedPreferences/file suffix. The first
 * profile this device ever sees keeps the empty suffix, so it silently inherits
 * everything the device recorded back when "the device was the profile" —
 * watch history, resume points, screen-time budget. No copy, no migration.
 */
class ProfileNamespace(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("profile_ns", Context.MODE_PRIVATE)

    /**
     * Ensure every profile has a suffix. Call whenever a config with profiles
     * loads. Only this path may hand out the legacy "" suffix, and only to the
     * first-listed profile while the map is empty — profiles[0] is the one the
     * parent grew out of the original single-kid setup.
     */
    @Synchronized
    fun register(profileIds: List<String>) {
        if (profileIds.isEmpty()) return
        val map = load()
        if (map.isEmpty()) map[profileIds.first()] = ""
        profileIds.forEach { id -> map.getOrPut(id) { "_$id" } }
        prefs.edit().putString("map", JSONObject(map as Map<String, String>).toString()).apply()
    }

    /** Suffix for a profile ("" = legacy stores). Null/unknown ids fall back to legacy. */
    @Synchronized
    fun suffixFor(profileId: String?): String {
        profileId ?: return ""
        return load()[profileId] ?: run {
            register(listOf(profileId))
            load()[profileId] ?: "_$profileId"
        }
    }

    private fun load(): MutableMap<String, String> = runCatching {
        val o = JSONObject(prefs.getString("map", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())
}

/**
 * Which kid this device is currently showing. On a dedicated device (assigned
 * in the parent settings) the answer never changes; on a shared one the
 * who's-watching screen sets it, and a sitting-length gap re-asks — the same
 * rhythm as the session guard's break timer, so "new sitting" means the same
 * thing everywhere.
 */
class ActiveProfileStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("active_profile", Context.MODE_PRIVATE)

    fun activeId(): String? = prefs.getString("active", null)

    fun setActive(profileId: String) {
        prefs.edit()
            .putString("active", profileId)
            .putLong("last_active_at", System.currentTimeMillis())
            .apply()
    }

    /** Poked while the app is in use, so the re-ask gap measures real absence. */
    fun touch() {
        prefs.edit().putLong("last_active_at", System.currentTimeMillis()).apply()
    }

    /**
     * Whether a shared device should ask who's watching again. Mid-sitting
     * (back from the player, between episodes) it must not nag.
     */
    fun needsReask(breakMinutes: Int?): Boolean {
        val last = prefs.getLong("last_active_at", 0L)
        if (last == 0L) return true
        val gapMs = (breakMinutes ?: 60) * 60_000L
        return System.currentTimeMillis() - last >= gapMs
    }
}
