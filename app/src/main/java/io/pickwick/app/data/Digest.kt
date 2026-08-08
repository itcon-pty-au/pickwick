package io.pickwick.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Phone-side memory for the weekly digest.
 *
 * The stats payload already carries per-day minutes (SessionGuard's history),
 * but channel totals are lifetime cumulative — "most watched this week" needs
 * a baseline to diff against. So every time the phone caches a device's stats
 * it also records that day's channel totals here; a week later the difference
 * between today's lifetime numbers and the row from seven days ago is exactly
 * the week's viewing.
 *
 * One file per device token, newest-last rows of {day, channels}. Same-day
 * records overwrite (the row is "totals as of the end of that day"), and only
 * the last [KEEP_DAYS] rows are kept.
 */
class DigestStore(private val dir: File) {

    fun record(deviceToken: String, dayKey: String, channelMinutes: Map<String, Int>) {
        runCatching {
            synchronized(LOCK) {
                dir.mkdirs()
                val file = File(dir, "$deviceToken.json")
                val rows = load(deviceToken).filterNot { it.day == dayKey }
                    .plus(Row(dayKey, channelMinutes))
                    .sortedBy { it.day }
                    .takeLast(KEEP_DAYS)
                // Temp-then-rename: the sync loop, the digest screen and the AI
                // settings poll all save stats concurrently, and a torn file
                // reads back as "no baselines" — two weeks of history gone.
                val tmp = File(dir, "$deviceToken.json.tmp")
                tmp.writeText(JSONArray().apply {
                    rows.forEach { row ->
                        put(JSONObject()
                            .put("day", row.day)
                            .put("channels", JSONObject().apply {
                                row.channels.forEach { (name, min) -> put(name, min) }
                            }))
                    }
                }.toString())
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    fun load(deviceToken: String): List<Row> = synchronized(LOCK) { runCatching {
        val file = File(dir, "$deviceToken.json")
        if (!file.exists()) return@runCatching emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ch = o.getJSONObject("channels")
            Row(
                day = o.getString("day"),
                channels = ch.keys().asSequence().associateWith { ch.getInt(it) }
            )
        }
    }.getOrDefault(emptyList()) }

    data class Row(val day: String, val channels: Map<String, Int>)

    companion object {
        const val KEEP_DAYS = 14

        /** record() is a read-modify-write with three concurrent callers (sync
         *  loop, digest screen, AI settings poll) — same hazard QueueStore
         *  locks against. */
        private val LOCK = Any()

        /**
         * Storage key for one kid's rows on one device. A shared TV's totals
         * belong to whichever profile is active — mixing kids in one file makes
         * the week's delta compare across kids and invent viewing. Profile
         * names carry emoji, so they're hashed rather than put in a filename.
         */
        fun key(deviceToken: String, profileName: String?): String =
            if (profileName.isNullOrBlank()) deviceToken
            else "$deviceToken-p" + Integer.toHexString(profileName.hashCode())
    }
}

/**
 * Pure assembly of the week view from a cached stats payload plus the
 * DigestStore's baseline rows. Android-free so the windowing and delta math
 * are unit-testable — all "when is now" inputs are passed in.
 */
object Digest {

    /** One kid-device's week, ready to render. */
    data class Weekly(
        /** Oldest→newest, exactly 7 entries ending on today; absent days are 0. */
        val days: List<Pair<String, Int>>,
        val totalMin: Int,
        /**
         * Channel → minutes watched inside the window, largest first. Empty when
         * no baseline exists yet (first week of recording) — the UI must say so
         * rather than pass lifetime totals off as the week's.
         */
        val topChannels: List<Pair<String, Int>>,
        /**
         * The day the channel numbers actually count from — the day after the
         * baseline row they were diffed against. Equals the window start when a
         * baseline from the day before the window exists; later while the store
         * is young; *earlier* when the phone hasn't synced in over a week (the
         * span is then longer than the window and the UI must say so). Null
         * when [topChannels] is empty.
         */
        val channelsSinceDay: String?,
        /** AI holds/blocks whose screening time falls inside the window. */
        val blocked: List<Stats.AiFlagged>
    )

    private fun format() = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** todayKey and the 6 days before it, oldest first. */
    fun weekDayKeys(todayKey: String): List<String> {
        val fmt = format()
        val cal = Calendar.getInstance().apply { time = fmt.parse(todayKey)!! }
        cal.add(Calendar.DAY_OF_YEAR, -6)
        return (0 until 7).map {
            fmt.format(cal.time).also { _ -> cal.add(Calendar.DAY_OF_YEAR, 1) }
        }
    }

    fun dayAfter(dayKey: String): String {
        val fmt = format()
        val cal = Calendar.getInstance().apply { time = fmt.parse(dayKey)!! }
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return fmt.format(cal.time)
    }

    /** Epoch ms of 00:00 local on the window's first day. */
    fun weekStartMs(todayKey: String): Long {
        val fmt = format()
        return fmt.parse(weekDayKeys(todayKey).first())!!.time
    }

    fun assemble(
        payload: Stats.Payload,
        baselines: List<DigestStore.Row>,
        todayKey: String,
        /** The day the payload was actually fetched — earlier than [todayKey]
         *  when it came from the cache of a TV that hasn't answered in days.
         *  watchedTodayMin belongs to *that* day, not the phone's today. */
        payloadDayKey: String = todayKey
    ): Weekly {
        val keys = weekDayKeys(todayKey)
        // History excludes today (SessionGuard archives a day only at rollover);
        // today's number rides separately in the payload.
        val byDay = payload.history.toMap()
        val days = keys.map { day ->
            day to if (day == payloadDayKey) payload.watchedTodayMin else (byDay[day] ?: 0)
        }

        // Baseline: ideally totals as of the end of the day *before* the window;
        // when the store is younger or hasn't synced in a while, the closest
        // pre-today row available. Either way the delta's true span is "the day
        // after the baseline row, onward" — channelsSinceDay reports exactly
        // that (which can predate the window, e.g. a phone that last synced
        // last month), so the UI never passes a longer span off as the week.
        val beforeWindow = keys.first()
        val baseline = baselines
            .filter { it.day < todayKey }
            .let { rows ->
                rows.lastOrNull { it.day < beforeWindow } ?: rows.firstOrNull()
            }
        val current = payload.topChannels.toMap()
        val topChannels = if (baseline == null) emptyList() else {
            current.mapNotNull { (name, min) ->
                val delta = min - (baseline.channels[name] ?: 0)
                if (delta > 0) name to delta else null
            }.sortedByDescending { it.second }
        }
        val sinceDay = baseline?.let { dayAfter(it.day) }
            ?.takeIf { topChannels.isNotEmpty() }

        val startMs = weekStartMs(todayKey)
        return Weekly(
            days = days,
            totalMin = days.sumOf { it.second },
            topChannels = topChannels,
            channelsSinceDay = sinceDay,
            blocked = payload.aiFlagged
                .filter { it.at >= startMs }
                .sortedByDescending { it.at }
        )
    }

    /**
     * The facts handed to the AI for its two-sentence note — plain lines, no
     * JSON, so a small model can't trip on shape. Titles are capped: the model
     * needs the gist, not the payload.
     */
    fun summaryFacts(kidName: String?, weekly: Weekly): String = buildString {
        append("Child: ").append(kidName ?: "the child").append('\n')
        append("Minutes per day (oldest to today): ")
        append(weekly.days.joinToString(", ") { (day, min) -> "$day=$min" })
        append('\n')
        append("Total: ${weekly.totalMin} min\n")
        if (weekly.topChannels.isNotEmpty()) {
            append("Top channels: ")
            append(weekly.topChannels.take(5).joinToString(", ") { (n, m) -> "$n ($m min)" })
            append('\n')
        }
        // The payload carries only unresolved holds — say so, or the summary
        // reads as the week's full screening record.
        if (weekly.blocked.isEmpty()) {
            append("No screening holds from this week are awaiting parent review.\n")
        } else {
            append("Held back by screening this week, awaiting parent review:\n")
            weekly.blocked.take(10).forEach {
                append("- ${it.title.take(80)} (${it.verdict}): ${it.reason.take(120)}\n")
            }
        }
    }
}
