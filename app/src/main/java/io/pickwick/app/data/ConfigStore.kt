package io.pickwick.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Form-managed local configuration (channels, blocks, screen-time rules).
 * Serialized as JSON so it can also be pushed verbatim to paired devices.
 * Feeds the same Whitelist pipeline as the (advanced) URL-based lists.
 */
class ConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(context.filesDir, "config.json")

    /**
     * Every config that touches disk registers its kids with the device-local
     * [ProfileNamespace] — the one place the first kid claims the legacy
     * (unsuffixed) stores, on every device, before any per-kid store is opened.
     */
    private fun registered(w: Whitelist): Whitelist {
        if (w.profiles.isNotEmpty()) {
            ProfileNamespace(appContext).register(w.profiles.map { it.id })
        }
        return w
    }

    fun load(): Whitelist = registered(
        runCatching {
            if (file.exists()) fromJson(file.readText()) else null
        }.getOrNull() ?: Whitelist(emptyList(), emptySet())
    )

    fun save(whitelist: Whitelist) {
        runCatching { file.writeText(toJson(registered(whitelist))) }
    }

    fun saveRaw(json: String): Boolean = runCatching {
        val w = registered(fromJson(json)) // validate before accepting
        file.writeText(json)
        // Symmetric with the reject log below: an accepted push names its hash
        // and the break rules it carried, so "the TV never got it" vs "it got
        // it but didn't enforce it" is answerable from logcat alone.
        android.util.Log.i(
            "Pickwick",
            "config accepted #${fingerprint(w)} break=${w.limits.breakMinutes} " +
                "perKid=${w.profiles.map { "${it.name}=${it.limits.breakMinutes}" }}"
        )
        true
    }.getOrElse { e ->
        // A rejected push is otherwise invisible on both ends — the phone shows
        // "out of sync" forever and nobody learns why.
        android.util.Log.w("Pickwick", "incoming config rejected", e)
        false
    }

    fun rawJson(): String = toJson(load())

    /** When this device's config last changed (locally or via push). */
    fun updatedAt(): Long = runCatching {
        if (!file.exists()) 0L else JSONObject(file.readText()).optLong("updatedAt", 0L)
    }.getOrDefault(0L)

    companion object {
        /**
         * Short content hash of what actually matters (channels, blocks, rules) —
         * two devices with equal fingerprints are provably in sync.
         */
        fun fingerprint(w: Whitelist): String {
            val canonical = buildString {
                w.sources.forEach {
                    append(it.id); append('|'); append(it.kind.name); append('|')
                    append(it.label ?: "")
                    // Appended only when non-default, so configs that never use
                    // multipliers keep the same hash as pre-multiplier builds.
                    if (it.timeMultiplierPercent != 100) {
                        append("|t"); append(it.timeMultiplierPercent)
                    }
                    // Same append-only rule: all-kids entries keep their old hash.
                    if (it.profileIds.isNotEmpty()) {
                        append("|v"); append(it.profileIds.sorted().joinToString(","))
                    }
                    append('\n')
                }
                append("B:"); append(w.blockedVideoIds.sorted().joinToString(","))
                append(";L:")
                append(
                    listOf(
                        w.limits.sessionMinutes, w.limits.weekdaySessions,
                        w.limits.weekendSessions, w.limits.breakMinutes,
                        w.limits.bedtimeStartMin, w.limits.bedtimeEndMin
                    ).joinToString(",")
                )
                // Appended only when set, so untouched configs keep their hash
                // across builds. Must be in the hash: the offline reconcile
                // (syncConfigState) only re-pushes on a fingerprint mismatch —
                // a pause that didn't change the hash would never reach a
                // device that slept through the original push.
                w.limits.pausedUntilMillis?.let { append(";P:"); append(it) }
                append(";AI:")
                append(
                    listOf(
                        w.ai.enabled, w.ai.baseUrl, w.ai.model, w.ai.apiKey,
                        w.ai.rules, w.ai.childAge, w.ai.rulesVersion
                    ).joinToString("|")
                )
                append(";AA:"); append(w.aiAllowedVideoIds.sorted().joinToString(","))
                // Everything profile-shaped is append-only-when-present, so a
                // family that never adds a second kid keeps its pre-profile hash.
                if (w.profiles.isNotEmpty()) {
                    append(";PR:")
                    w.profiles.forEach { p ->
                        append(
                            listOf(
                                p.id, p.name, p.colorArgb, p.avatar, p.age ?: -1,
                                p.pin ?: "",
                                listOf(
                                    p.limits.sessionMinutes, p.limits.weekdaySessions,
                                    p.limits.weekendSessions, p.limits.breakMinutes,
                                    p.limits.bedtimeStartMin, p.limits.bedtimeEndMin
                                ).joinToString(",")
                            ).joinToString("|")
                        )
                        append('\n')
                    }
                }
                fun appendOverlay(tag: String, overlay: Map<String, Set<String>>) {
                    if (overlay.isEmpty()) return
                    append(";$tag:")
                    overlay.entries.sortedBy { it.key }.forEach { (id, pids) ->
                        append(id); append('='); append(pids.sorted().joinToString(",")); append(';')
                    }
                }
                appendOverlay("BF", w.blockedFor)
                appendOverlay("AF", w.allowedFor)
                if (w.deviceProfiles.isNotEmpty()) {
                    append(";DP:")
                    w.deviceProfiles.entries.sortedBy { it.key }
                        .forEach { (t, p) -> append("$t=$p;") }
                }
            }
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray())
                .take(4)
                .joinToString("") { "%02x".format(it) }
        }

        fun toJson(w: Whitelist): String {
            val root = JSONObject()
            root.put("updatedAt", System.currentTimeMillis())
            root.put("entries", JSONArray().apply {
                w.sources.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("url", e.url)
                        e.label?.let { put("label", it) }
                        put("kind", e.kind.name)
                        // Omitted at 100 — old builds ignore it, new builds default it.
                        if (e.timeMultiplierPercent != 100) put("time", e.timeMultiplierPercent)
                        // Omitted when visible to everyone, same reasoning.
                        if (e.profileIds.isNotEmpty()) put("profiles", JSONArray(e.profileIds.toList()))
                    })
                }
            })
            root.put("blocked", JSONArray(w.blockedVideoIds.toList()))
            root.put("limits", limitsToJson(w.limits))
            root.put("ai", JSONObject().apply {
                put("enabled", w.ai.enabled)
                put("baseUrl", w.ai.baseUrl)
                put("model", w.ai.model)
                put("apiKey", w.ai.apiKey)
                put("rules", w.ai.rules)
                w.ai.childAge?.let { put("childAge", it) }
                put("rulesVersion", w.ai.rulesVersion)
            })
            root.put("aiAllowed", JSONArray(w.aiAllowedVideoIds.toList()))
            // Profile fields are written only when used, so a single-kid family's
            // config stays byte-compatible with what older builds understand.
            if (w.profiles.isNotEmpty()) {
                root.put("profiles", JSONArray().apply {
                    w.profiles.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("color", p.colorArgb)
                            put("avatar", p.avatar)
                            p.age?.let { put("age", it) }
                            p.pin?.let { put("pin", it) }
                            put("limits", limitsToJson(p.limits))
                        })
                    }
                })
            }
            fun overlayJson(overlay: Map<String, Set<String>>) = JSONObject().apply {
                overlay.forEach { (id, pids) -> put(id, JSONArray(pids.toList())) }
            }
            if (w.blockedFor.isNotEmpty()) root.put("blockedFor", overlayJson(w.blockedFor))
            if (w.allowedFor.isNotEmpty()) root.put("allowedFor", overlayJson(w.allowedFor))
            if (w.deviceProfiles.isNotEmpty()) {
                root.put("deviceProfiles", JSONObject(w.deviceProfiles as Map<String, String>))
            }
            return root.toString(2)
        }

        private fun limitsToJson(l: Limits) = JSONObject().apply {
            l.sessionMinutes?.let { put("session", it) }
            l.weekdaySessions?.let { put("weekdaySessions", it) }
            l.weekendSessions?.let { put("weekendSessions", it) }
            l.breakMinutes?.let { put("breakMinutes", it) }
            l.bedtimeStartMin?.let { put("bedtimeStart", it) }
            l.bedtimeEndMin?.let { put("bedtimeEnd", it) }
            l.pausedUntilMillis?.let { put("pausedUntil", it) }
        }

        private fun limitsFromJson(lo: JSONObject): Limits {
            fun opt(name: String): Int? = if (lo.has(name)) lo.getInt(name) else null
            return Limits(
                sessionMinutes = opt("session"),
                weekdaySessions = opt("weekdaySessions"),
                weekendSessions = opt("weekendSessions"),
                breakMinutes = opt("breakMinutes"),
                bedtimeStartMin = opt("bedtimeStart"),
                bedtimeEndMin = opt("bedtimeEnd"),
                pausedUntilMillis = if (lo.has("pausedUntil")) lo.getLong("pausedUntil") else null
            )
        }

        fun fromJson(text: String): Whitelist {
            val root = JSONObject(text)
            val entries = mutableListOf<WhitelistEntry>()
            val arr = root.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = runCatching { SourceKind.valueOf(o.getString("kind")) }
                    .getOrDefault(SourceKind.CHANNEL)
                val pidArr = o.optJSONArray("profiles")
                entries += WhitelistEntry(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    label = o.optString("label").ifEmpty { null },
                    kind = kind,
                    timeMultiplierPercent = o.optInt("time", 100),
                    profileIds = pidArr?.let { arrPids ->
                        (0 until arrPids.length()).map { arrPids.getString(it) }.toSet()
                    } ?: emptySet()
                )
            }
            val blocked = mutableSetOf<String>()
            val blockedArr = root.optJSONArray("blocked") ?: JSONArray()
            for (i in 0 until blockedArr.length()) blocked += blockedArr.getString(i)

            val lo = root.optJSONObject("limits") ?: JSONObject()

            val ao = root.optJSONObject("ai") ?: JSONObject()
            val ai = AiConfig(
                enabled = ao.optBoolean("enabled", false),
                baseUrl = ao.optString("baseUrl").ifEmpty { AiConfig().baseUrl },
                model = ao.optString("model"),
                apiKey = ao.optString("apiKey"),
                rules = ao.optString("rules"),
                childAge = if (ao.has("childAge")) ao.getInt("childAge") else null,
                rulesVersion = ao.optInt("rulesVersion", 0)
            )
            val aiAllowed = mutableSetOf<String>()
            val aiAllowedArr = root.optJSONArray("aiAllowed") ?: JSONArray()
            for (i in 0 until aiAllowedArr.length()) aiAllowed += aiAllowedArr.getString(i)

            val profiles = mutableListOf<Profile>()
            root.optJSONArray("profiles")?.let { arr2 ->
                for (i in 0 until arr2.length()) {
                    val o = arr2.getJSONObject(i)
                    profiles += Profile(
                        id = o.getString("id"),
                        name = o.optString("name").ifEmpty { "Kid ${i + 1}" },
                        colorArgb = o.optLong("color", PROFILE_COLORS.first()),
                        avatar = o.optString("avatar").ifEmpty { PROFILE_AVATARS.first() },
                        age = if (o.has("age")) o.getInt("age") else null,
                        limits = limitsFromJson(o.optJSONObject("limits") ?: JSONObject()),
                        pin = o.optString("pin").takeIf { isValidDirectionPin(it) }
                    )
                }
            }
            fun overlay(name: String): Map<String, Set<String>> {
                val o = root.optJSONObject(name) ?: return emptyMap()
                return o.keys().asSequence().associateWith { id ->
                    val a = o.optJSONArray(id) ?: JSONArray()
                    (0 until a.length()).map { a.getString(it) }.toSet()
                }
            }
            val deviceProfiles = root.optJSONObject("deviceProfiles")?.let { o ->
                o.keys().asSequence().associateWith { o.getString(it) }
            } ?: emptyMap()

            return Whitelist(
                sources = entries.distinctBy { it.id },
                blockedVideoIds = blocked,
                limits = limitsFromJson(lo),
                ai = ai,
                aiAllowedVideoIds = aiAllowed,
                profiles = profiles,
                blockedFor = overlay("blockedFor"),
                allowedFor = overlay("allowedFor"),
                deviceProfiles = deviceProfiles
            )
        }
    }
}
