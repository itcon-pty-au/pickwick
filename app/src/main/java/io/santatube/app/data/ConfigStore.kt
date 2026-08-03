package io.santatube.app.data

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

    private val file = File(context.filesDir, "config.json")

    fun load(): Whitelist = runCatching {
        if (!file.exists()) return Whitelist(emptyList(), emptySet())
        fromJson(file.readText())
    }.getOrDefault(Whitelist(emptyList(), emptySet()))

    fun save(whitelist: Whitelist) {
        runCatching { file.writeText(toJson(whitelist)) }
    }

    fun saveRaw(json: String): Boolean = runCatching {
        fromJson(json) // validate before accepting
        file.writeText(json)
        true
    }.getOrDefault(false)

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
                    })
                }
            })
            root.put("blocked", JSONArray(w.blockedVideoIds.toList()))
            root.put("limits", JSONObject().apply {
                w.limits.sessionMinutes?.let { put("session", it) }
                w.limits.weekdaySessions?.let { put("weekdaySessions", it) }
                w.limits.weekendSessions?.let { put("weekendSessions", it) }
                w.limits.breakMinutes?.let { put("breakMinutes", it) }
                w.limits.bedtimeStartMin?.let { put("bedtimeStart", it) }
                w.limits.bedtimeEndMin?.let { put("bedtimeEnd", it) }
                w.limits.pausedUntilMillis?.let { put("pausedUntil", it) }
            })
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
            return root.toString(2)
        }

        fun fromJson(text: String): Whitelist {
            val root = JSONObject(text)
            val entries = mutableListOf<WhitelistEntry>()
            val arr = root.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = runCatching { SourceKind.valueOf(o.getString("kind")) }
                    .getOrDefault(SourceKind.CHANNEL)
                entries += WhitelistEntry(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    label = o.optString("label").ifEmpty { null },
                    kind = kind,
                    timeMultiplierPercent = o.optInt("time", 100)
                )
            }
            val blocked = mutableSetOf<String>()
            val blockedArr = root.optJSONArray("blocked") ?: JSONArray()
            for (i in 0 until blockedArr.length()) blocked += blockedArr.getString(i)

            val lo = root.optJSONObject("limits") ?: JSONObject()
            fun opt(name: String): Int? = if (lo.has(name)) lo.getInt(name) else null

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

            return Whitelist(
                sources = entries.distinctBy { it.id },
                blockedVideoIds = blocked,
                limits = Limits(
                    sessionMinutes = opt("session"),
                    weekdaySessions = opt("weekdaySessions"),
                    weekendSessions = opt("weekendSessions"),
                    breakMinutes = opt("breakMinutes"),
                    bedtimeStartMin = opt("bedtimeStart"),
                    bedtimeEndMin = opt("bedtimeEnd"),
                    pausedUntilMillis = if (lo.has("pausedUntil")) lo.getLong("pausedUntil") else null
                ),
                ai = ai,
                aiAllowedVideoIds = aiAllowed
            )
        }
    }
}
