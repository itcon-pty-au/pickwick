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

    // Lazy on purpose: opening this is a Keystore round trip and load() sits on
    // the cold-start path, so a family that never turns on AI screening never
    // pays for it. See [withSecrets].
    private val secrets by lazy { SecretStore(appContext) }

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
        scrubLapsedPasses(
            runCatching {
                if (file.exists()) withSecrets(fromJson(file.readText())) else null
            }.getOrNull() ?: Whitelist(emptyList(), emptySet())
        )
    )

    /**
     * A lapsed pass is spent and must fall out of the config, not linger.
     * Enforcement already ignores it, but the fingerprint and the legacy
     * bedtime keys (omitted while a pass is active — see [limitsToJson])
     * don't. Dropping it here changes the fingerprint exactly once, at the
     * first load after the lapse, and that mismatch is what re-pushes the
     * restored bedtime keys to a device still on a pre-windows build.
     */
    private fun scrubLapsedPasses(w: Whitelist): Whitelist {
        val now = System.currentTimeMillis()
        fun scrub(l: Limits): Limits {
            val windows =
                if (l.windows.none { (it.passUntilMillis ?: Long.MAX_VALUE) < now }) l.windows
                else l.windows.map {
                    if ((it.passUntilMillis ?: Long.MAX_VALUE) < now) it.copy(passUntilMillis = null) else it
                }
            val breakPass = l.breakPassUntilMillis?.takeIf { it >= now }
            return if (windows === l.windows && breakPass == l.breakPassUntilMillis) l
            else l.copy(windows = windows, breakPassUntilMillis = breakPass)
        }
        val limits = scrub(w.limits)
        val profiles = w.profiles.map { p -> p.copy(limits = scrub(p.limits)) }
        return if (limits === w.limits && profiles == w.profiles) w
        else w.copy(limits = limits, profiles = profiles)
    }

    /** Whether this config has AI screening set up at all. */
    private fun aiInUse(w: Whitelist) = w.ai.enabled || w.ai.model.isNotBlank()

    /**
     * The API key is kept out of `config.json` (see [SecretStore]); put it back
     * on the way out so every in-memory [Whitelist] is complete — screening,
     * the settings form and [fingerprint] all expect it to be there.
     */
    private fun withSecrets(w: Whitelist): Whitelist {
        if (w.ai.apiKey.isNotBlank()) {
            // Written by a build that still stored the key on disk. Move it and
            // rewrite the file without it, once — even when AI isn't set up,
            // because the key rides cloud backup for as long as it sits there.
            secrets.setAiApiKey(w.ai.apiKey)
            runCatching { file.writeText(stripSecrets(file.readText())) }
            return w
        }
        if (!aiInUse(w)) return w
        return w.copy(ai = w.ai.copy(apiKey = secrets.aiApiKey()))
    }

    /**
     * Persist the key separately, but only when this config actually speaks to
     * the key — otherwise a save from a screen that never loaded it (AI off and
     * unconfigured) would quietly wipe a key the parent had set.
     */
    private fun rememberSecrets(w: Whitelist) {
        if (w.ai.apiKey.isNotBlank() || aiInUse(w)) secrets.setAiApiKey(w.ai.apiKey)
    }

    fun save(whitelist: Whitelist) {
        runCatching {
            val w = registered(whitelist)
            rememberSecrets(w)
            file.writeText(toJson(w, includeSecrets = false))
        }
    }

    fun saveRaw(json: String): Boolean = runCatching {
        val w = registered(fromJson(json)) // validate before accepting
        rememberSecrets(w)
        // The pushed payload carries the key so this device can screen; the copy
        // that lands on disk must not. Stripped surgically rather than
        // re-serialized, so a field a newer phone knows about and this build
        // doesn't still survives the round trip.
        file.writeText(stripSecrets(json))
        // Symmetric with the reject log below: an accepted push names its hash
        // and the break rules it carried, so "the TV never got it" vs "it got
        // it but didn't enforce it" is answerable from logcat alone. Kids appear
        // by id rather than name — logcat is readable by anyone with the device
        // on a cable, and the id answers the same question.
        android.util.Log.i(
            "Pickwick",
            "config accepted #${fingerprint(w)} break=${w.limits.breakMinutes} " +
                "perKid=${w.profiles.map { "${it.id}=${it.limits.breakMinutes}" }}"
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
                    // Note text is part of the fingerprint (an edit must push),
                    // newline-flattened so it can't fake an entry boundary.
                    if (!it.aiNote.isNullOrBlank()) {
                        append("|n"); append(it.aiNote.trim().replace('\n', ' '))
                    }
                    append('\n')
                }
                append("B:"); append(w.blockedVideoIds.sorted().joinToString(","))
                append(";L:")
                append(limitsCanon(w.limits))
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
                                limitsCanon(p.limits)
                            ).joinToString("|")
                        )
                        // A kid's own pause, append-only-when-set like the
                        // family one above: it has to move the hash or the
                        // offline reconcile would never carry it to the TV.
                        p.limits.pausedUntilMillis?.let { append(";P:"); append(it) }
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
                // Append-only-when-set, same reasoning as pauses: the offline
                // reconcile only re-pushes on a mismatch, so a master change
                // that didn't move the hash would never reach a sleeping TV.
                w.masterDeviceToken?.let { append(";MS:"); append(it) }
                // Appended only when switched off, so every existing config
                // keeps its hash across the build that introduced the flag.
                if (!w.sponsorSkip) append(";SB:off")
                // Append-only-when-set, same reasoning — and it must be in the
                // hash so the offline reconcile re-pushes a rate change.
                w.listenPercent?.let { append(";LN:"); append(it) }
            }
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray())
                .take(4)
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * Drops the API key from a config payload, leaving every other field —
         * including ones this build doesn't know about — byte-for-byte alone.
         * Returns the input unchanged if it isn't parseable.
         */
        fun stripSecrets(json: String): String = runCatching {
            val root = JSONObject(json)
            val ai = root.optJSONObject("ai") ?: return@runCatching json
            if (!ai.has("apiKey")) return@runCatching json
            ai.remove("apiKey")
            root.toString(2)
        }.getOrDefault(json)

        /**
         * [includeSecrets] is true for anything that goes to another device (a
         * paired TV needs the key to screen), false for the copy that lands on
         * disk, which is cloud-backed-up.
         */
        fun toJson(w: Whitelist, includeSecrets: Boolean = true): String {
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
                        if (!e.aiNote.isNullOrBlank()) put("note", e.aiNote.trim())
                    })
                }
            })
            root.put("blocked", JSONArray(w.blockedVideoIds.toList()))
            root.put("limits", limitsToJson(w.limits))
            root.put("ai", JSONObject().apply {
                put("enabled", w.ai.enabled)
                put("baseUrl", w.ai.baseUrl)
                put("model", w.ai.model)
                if (includeSecrets) put("apiKey", w.ai.apiKey)
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
            // Written only when chosen — absent means unclaimed (older builds).
            w.masterDeviceToken?.let { root.put("master", it) }
            // Written only when off — absent means on, including in configs
            // saved by builds that predate the flag.
            if (!w.sponsorSkip) root.put("sponsorSkip", false)
            // Written only when set — absent means listening off (see Whitelist).
            w.listenPercent?.let { root.put("listen", it) }
            return root.toString(2)
        }

        /**
         * Limits in the fingerprint's canonical form. The legacy trailing
         * `start,end` pair is kept for the one shape older builds could
         * express — a single every-day window — so a family whose bedtime was
         * migrated into the list keeps its existing hash and doesn't trigger a
         * pointless re-push. Richer schedules append instead, which is what
         * changes the hash for them exactly once, at the upgrade.
         *
         * Passes belong in here for the same reason pauses do: the offline
         * reconcile only re-pushes on a mismatch, so a pass that didn't move
         * the hash would never reach a device that slept through the push.
         */
        private fun limitsCanon(l: Limits): String {
            // "Allow listening" is outside what the legacy pair can say, so a
            // window carrying it takes the long form — otherwise ticking the
            // box on an ordinary every-day bedtime wouldn't move the hash and
            // would never reach the kid's phone.
            val legacy = l.windows.singleOrNull()
                ?.takeIf { it.days == ALL_DAYS && it.passUntilMillis == null && !it.allowListening }
            val base = listOf(
                l.sessionMinutes, l.weekdaySessions, l.weekendSessions, l.breakMinutes,
                legacy?.startMin, legacy?.endMin
            ).joinToString(",")
            // Appended only when set (same reason as pauses: the offline
            // reconcile only re-pushes on a mismatch, so a break skip that
            // didn't move the hash would never reach a sleeping device) — and
            // here rather than at the Whitelist level, so a per-kid skip moves
            // that kid's part of the hash too.
            val breakPass = l.breakPassUntilMillis?.let { ";BP:$it" } ?: ""
            // Same shape as the break pass: only present when set, so families
            // without the rule keep their hash across the build that added it.
            val minVideo = l.minVideoMinutes?.let { ";MV:$it" } ?: ""
            if (legacy != null || l.windows.isEmpty()) return base + breakPass + minVideo
            return base + l.windows.joinToString(";", prefix = ";W:") { w ->
                // Parent-typed text is scrubbed of this format's separators so
                // two different window lists can't canonicalize identically.
                "${w.id},${w.label.replace(Regex("[,;]"), " ")},${w.startMin},${w.endMin}," +
                    "${w.days.sorted().joinToString(".")},${w.passUntilMillis ?: 0}," +
                    if (w.allowListening) "1" else "0"
            } + breakPass + minVideo
        }

        private fun limitsToJson(l: Limits) = JSONObject().apply {
            l.sessionMinutes?.let { put("session", it) }
            l.weekdaySessions?.let { put("weekdaySessions", it) }
            l.weekendSessions?.let { put("weekendSessions", it) }
            l.breakMinutes?.let { put("breakMinutes", it) }
            l.minVideoMinutes?.let { put("minVideoMinutes", it) }
            if (l.windows.isNotEmpty()) put("windows", JSONArray(windowsToJson(l.windows)))
            // A single every-day window is exactly what pre-windows builds called
            // bedtime, so keep writing their keys for it: a family whose phone
            // updates before its TV keeps an enforced bedtime in the meantime.
            // Anything richer has no legacy equivalent and is left out rather
            // than flattened into a wrong one. "Allow listening" is the one
            // thing still written flat: a build that can't express it enforces
            // the window outright, which errs toward the stricter rule and is
            // what a TV does anyway. While a pass is active the keys
            // are omitted too — absent bedtime IS the pass, as far as an old
            // build can express it; they come back via the fingerprint change
            // when the lapsed pass is scrubbed on load.
            l.windows.singleOrNull()?.takeIf { it.days == ALL_DAYS && it.passUntilMillis == null }?.let {
                put("bedtimeStart", it.startMin)
                put("bedtimeEnd", it.endMin)
            }
            l.pausedUntilMillis?.let { put("pausedUntil", it) }
            l.breakPassUntilMillis?.let { put("breakPassUntil", it) }
        }

        /** Windows as a JSON array string — also the SharedPreferences form. */
        fun windowsToJson(windows: List<TimeWindow>): String =
            JSONArray().apply {
                windows.forEach { w ->
                    put(JSONObject().apply {
                        put("id", w.id)
                        put("label", w.label)
                        put("start", w.startMin)
                        put("end", w.endMin)
                        // Omitted when it applies every day, the common case.
                        if (w.days != ALL_DAYS) put("days", JSONArray(w.days.sorted()))
                        w.passUntilMillis?.let { put("passUntil", it) }
                        // Written only when set: absent means the plain block,
                        // which is what every pre-listening config meant.
                        if (w.allowListening) put("allowListening", true)
                    })
                }
            }.toString()

        fun windowsFromJson(text: String?): List<TimeWindow> = runCatching {
            val arr = JSONArray(text ?: return emptyList())
            (0 until arr.length()).mapNotNull { i ->
                // Per-window, so one malformed window (a newer build's shape, a
                // hand edit) drops alone instead of disabling every window this
                // list guards — bedtime included. Out-of-range minutes or days
                // would misfire in the week-minute math (negative modulo reads
                // as "always blocking"), so they drop too rather than enforce
                // something nobody configured.
                runCatching {
                    val w = arr.getJSONObject(i)
                    val start = w.getInt("start")
                    val end = w.getInt("end")
                    if (start !in 0..1439 || end !in 0..1439) return@runCatching null
                    val days = w.optJSONArray("days")?.let { d ->
                        (0 until d.length()).map { d.getInt(it) }
                            .filter { it in 1..7 } // Calendar.SUNDAY..SATURDAY
                            .toSet()
                    } ?: ALL_DAYS
                    // Present-but-empty days: block nothing, never inflate an
                    // ambiguous rule to every day.
                    if (days.isEmpty()) return@runCatching null
                    TimeWindow(
                        id = w.optString("id").ifEmpty { "w$i" },
                        label = w.optString("label", "Quiet time"),
                        startMin = start,
                        endMin = end,
                        days = days,
                        passUntilMillis = if (w.has("passUntil")) w.getLong("passUntil") else null,
                        allowListening = w.optBoolean("allowListening", false)
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())

        private fun limitsFromJson(lo: JSONObject): Limits {
            fun opt(name: String): Int? = if (lo.has(name)) lo.getInt(name) else null
            // A config written before windows existed carries only the bedtime
            // pair; migrate it in place so an upgrade never silently drops a
            // family's bedtime.
            val windows = if (lo.has("windows")) {
                windowsFromJson(lo.optJSONArray("windows")?.toString())
            } else {
                val start = opt("bedtimeStart")
                val end = opt("bedtimeEnd")
                if (start != null && end != null) {
                    listOf(TimeWindow(id = "bedtime", label = "Bedtime", startMin = start, endMin = end))
                } else emptyList()
            }
            return Limits(
                sessionMinutes = opt("session"),
                weekdaySessions = opt("weekdaySessions"),
                weekendSessions = opt("weekendSessions"),
                breakMinutes = opt("breakMinutes"),
                minVideoMinutes = opt("minVideoMinutes"),
                windows = windows,
                pausedUntilMillis = if (lo.has("pausedUntil")) lo.getLong("pausedUntil") else null,
                breakPassUntilMillis = if (lo.has("breakPassUntil")) lo.getLong("breakPassUntil") else null
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
                    } ?: emptySet(),
                    aiNote = o.optString("note").ifEmpty { null }
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
                deviceProfiles = deviceProfiles,
                masterDeviceToken = root.optString("master").ifEmpty { null },
                sponsorSkip = root.optBoolean("sponsorSkip", true),
                listenPercent = if (root.has("listen")) root.getInt("listen") else null
            )
        }
    }
}
