package io.pickwick.app.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Enforces the parent's screen-time rules, configured in the settings UI
 * (see [Limits]).
 *
 * Model (no forfeiting):
 *  - Daily budget = session minutes × weekday/weekend session count.
 *    Only actual watch time draws it down; stopping early wastes nothing.
 *  - The session length also caps one *sitting*: after that much
 *    continuous-ish watching, a break lock (default 60m) forces a rest.
 *  - A gap of the break length since last watching starts a fresh sitting.
 *  - Bedtime blocks a clock window outright.
 *  - Rules left unset simply don't apply.
 */
class SessionGuard(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("limits", Context.MODE_PRIVATE)

    companion object {
        private const val DEFAULT_BREAK_MIN = 60

        /** Deliberately parent-attributed, so the kid doesn't read it as a bug. */
        private const val PAUSED_MESSAGE =
            "A parent paused screen time for today. See you tomorrow 💛"
    }

    // ---- limits config (persisted at whitelist refresh) ----

    fun saveLimits(l: Limits) {
        prefs.edit()
            .putInt("l_session", l.sessionMinutes ?: -1)
            .putInt("l_wd", l.weekdaySessions ?: -1)
            .putInt("l_we", l.weekendSessions ?: -1)
            .putInt("l_break", l.breakMinutes ?: -1)
            .putInt("l_bt_start", l.bedtimeStartMin ?: -1)
            .putInt("l_bt_end", l.bedtimeEndMin ?: -1)
            .putLong("l_paused", l.pausedUntilMillis ?: -1L)
            .apply()
    }

    private fun limits(): Limits {
        fun get(key: String) = prefs.getInt(key, -1).takeIf { it >= 0 }
        return Limits(
            sessionMinutes = get("l_session"),
            weekdaySessions = get("l_wd"),
            weekendSessions = get("l_we"),
            breakMinutes = get("l_break"),
            bedtimeStartMin = get("l_bt_start"),
            bedtimeEndMin = get("l_bt_end"),
            pausedUntilMillis = prefs.getLong("l_paused", -1L).takeIf { it > 0 }
        )
    }

    /**
     * Parent timeout in force right now. Checked ahead of every other rule and
     * not waivable by grants or bedtime passes — only the parent's Resume (which
     * clears the field) or the deadline passing lifts it.
     */
    private fun isPaused(l: Limits): Boolean =
        System.currentTimeMillis() < (l.pausedUntilMillis ?: 0L)

    private fun breakMs(l: Limits) = (l.breakMinutes ?: DEFAULT_BREAK_MIN) * 60_000L

    /** Daily watch budget in ms (incl. parent-granted bonus), or null when not configured. */
    private fun dailyBudgetMs(l: Limits): Long? {
        val perSession = l.sessionMinutes ?: return null
        val count = (if (isWeekend()) l.weekendSessions else l.weekdaySessions) ?: return null
        return perSession * count * 60_000L + prefs.getLong("bonusMs", 0)
    }

    /**
     * Parent grant: adds minutes to today's budget, ends any break lock, starts a
     * fresh sitting, and waives bedtime for the granted minutes. Resets at midnight.
     */
    fun grantExtraMinutes(minutes: Int) {
        rolloverIfNewDay()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong("bonusMs", prefs.getLong("bonusMs", 0) + minutes * 60_000L)
            .putLong("lockUntil", 0)
            .putLong("sittingWatchedMs", 0)
            .putLong(
                "bedtimePassUntil",
                maxOf(prefs.getLong("bedtimePassUntil", 0), now + minutes * 60_000L)
            )
            .apply()
    }

    // ---- enforcement ----

    /**
     * Null if playback may start; otherwise a kid-friendly reason.
     * [multiplierPercent] is the source's screen-time drain rate: at 0 (FREE)
     * an exhausted budget doesn't block — but bedtime and break locks still do.
     */
    fun checkStart(multiplierPercent: Int = 100): String? {
        rolloverIfNewDay()
        val l = limits()
        val now = System.currentTimeMillis()

        if (isPaused(l)) return PAUSED_MESSAGE
        if (inBedtime(l)) return "It's bedtime! See you tomorrow 🌙"

        val lockUntil = prefs.getLong("lockUntil", 0)
        if (now < lockUntil) {
            return "Time for a break! You can watch again at ${timeOf(lockUntil)} ⏰"
        }

        startFreshSittingAfterGap(l, now)

        if (multiplierPercent > 0) dailyBudgetMs(l)?.let { budget ->
            if (prefs.getLong("dailyWatchedMs", 0) >= budget) {
                return "That's all the watching for today! 🌟"
            }
        }
        return null
    }

    /**
     * Called every few seconds while playback is actually running. Null to
     * continue; otherwise a kid-friendly reason to stop now.
     */
    fun tick(deltaMs: Long): String? {
        rolloverIfNewDay()
        val l = limits()
        val now = System.currentTimeMillis()
        prefs.edit().putLong("lastWatchAt", now).apply()

        // Mid-playback too: the pushed config lands, the next tick stops the video.
        if (isPaused(l)) return PAUSED_MESSAGE
        if (inBedtime(l)) return "It's bedtime! See you tomorrow 🌙"

        val daily = prefs.getLong("dailyWatchedMs", 0) + deltaMs
        val sitting = prefs.getLong("sittingWatchedMs", 0) + deltaMs
        prefs.edit().putLong("dailyWatchedMs", daily).putLong("sittingWatchedMs", sitting).apply()

        dailyBudgetMs(l)?.let { budget ->
            if (daily >= budget) return "That's all the watching for today! 🌟"
        }
        val sittingCapMs = l.sessionMinutes?.let { it * 60_000L } ?: return null
        if (sitting >= sittingCapMs) {
            prefs.edit().putLong("lockUntil", now + breakMs(l)).apply()
            return "Time for a break! Great watching 🎉"
        }
        return null
    }

    /**
     * Wall-clock milliseconds of watching left before some rule will stop
     * playback, or null when no rule applies. Budget and sitting-cap remainders
     * are converted through the source's drain rate ([multiplierPercent]: at 50,
     * 10 budget-minutes last 20 real minutes; at 0 they never run out), while
     * bedtime distance is clock time and never scales. Drives the kid's
     * "5 minutes left" warning, so it must never say more time than tick() will
     * actually allow.
     */
    fun remainingMs(multiplierPercent: Int = 100): Long? {
        rolloverIfNewDay()
        val l = limits()
        if (isPaused(l)) return 0
        val candidates = mutableListOf<Long>()
        if (multiplierPercent > 0) {
            dailyBudgetMs(l)?.let { budget ->
                candidates += (budget - prefs.getLong("dailyWatchedMs", 0))
                    .coerceAtLeast(0) * 100 / multiplierPercent
            }
            l.sessionMinutes?.let { cap ->
                candidates += (cap * 60_000L - prefs.getLong("sittingWatchedMs", 0))
                    .coerceAtLeast(0) * 100 / multiplierPercent
            }
        }
        msUntilBedtime(l)?.let { candidates += it }
        return candidates.minOrNull()?.coerceAtLeast(0)
    }

    /** Clock ms until the bedtime window closes playback, or null when unset. */
    private fun msUntilBedtime(l: Limits): Long? {
        val start = l.bedtimeStartMin ?: return null
        val end = l.bedtimeEndMin ?: return null
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val inWindow = if (start <= end) nowMin in start..end
        else nowMin >= start || nowMin <= end
        return if (inWindow) {
            // Only a parent's bedtime pass keeps playback alive inside the window.
            (prefs.getLong("bedtimePassUntil", 0) - now).coerceAtLeast(0)
        } else {
            val minutesUntil = (start - nowMin).let { if (it > 0) it else it + 24 * 60 }
            minutesUntil * 60_000L
        }
    }

    /** A break-length gap since last watching starts a new sitting (nothing lost). */
    private fun startFreshSittingAfterGap(l: Limits, now: Long) {
        val lastWatch = prefs.getLong("lastWatchAt", 0)
        if (lastWatch > 0 && now - lastWatch >= breakMs(l)) {
            prefs.edit().putLong("sittingWatchedMs", 0).apply()
        }
    }

    // ---- clock helpers ----

    private fun rolloverIfNewDay() {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val previous = prefs.getString("day", null)
        if (previous != today) {
            // Archive the finished day before clearing, so trends have history.
            val watched = prefs.getLong("dailyWatchedMs", 0)
            if (previous != null && watched > 0) {
                val history = (prefs.getString("history", "") ?: "")
                    .lines().filter { it.isNotBlank() }
                    .takeLast(59) // keep ~60 days
                prefs.edit()
                    .putString("history", (history + "$previous=${watched / 60_000}").joinToString("\n"))
                    .apply()
            }
            prefs.edit()
                .putString("day", today)
                .putLong("dailyWatchedMs", 0)
                .putLong("sittingWatchedMs", 0)
                .putLong("lockUntil", 0)
                .putLong("bonusMs", 0)
                .putLong("bedtimePassUntil", 0)
                .apply()
        }
    }

    /** yyyyMMdd → minutes watched, for the trend chart (excludes today). */
    fun history(): List<Pair<String, Int>> =
        (prefs.getString("history", "") ?: "")
            .lines().filter { it.isNotBlank() }
            .mapNotNull { line ->
                val (day, mins) = line.split('=').let {
                    if (it.size == 2) it[0] to it[1].toIntOrNull() else null to null
                }
                if (day != null && mins != null) day to mins else null
            }

    /** Everything the phone's stats screen needs about screen time. */
    data class Snapshot(
        val watchedTodayMin: Int,
        val budgetTodayMin: Int?,
        val bonusTodayMin: Int,
        val sittingWatchedMin: Int,
        val sittingCapMin: Int?,
        val state: String,
        val breakUntil: String?
    )

    fun snapshot(): Snapshot {
        rolloverIfNewDay()
        val l = limits()
        val now = System.currentTimeMillis()
        val lockUntil = prefs.getLong("lockUntil", 0)
        val budget = dailyBudgetMs(l)
        val watched = prefs.getLong("dailyWatchedMs", 0)
        val state = when {
            isPaused(l) -> "Paused by parent"
            inBedtime(l) -> "Bedtime"
            budget != null && watched >= budget -> "Daily limit reached"
            now < lockUntil -> "On a break"
            else -> "Can watch"
        }
        return Snapshot(
            watchedTodayMin = (watched / 60_000).toInt(),
            budgetTodayMin = budget?.let { (it / 60_000).toInt() },
            bonusTodayMin = (prefs.getLong("bonusMs", 0) / 60_000).toInt(),
            sittingWatchedMin = (prefs.getLong("sittingWatchedMs", 0) / 60_000).toInt(),
            sittingCapMin = l.sessionMinutes,
            state = state,
            breakUntil = lockUntil.takeIf { it > now }?.let { timeOf(it) }
        )
    }

    private fun isWeekend(): Boolean {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    private fun inBedtime(l: Limits): Boolean {
        if (System.currentTimeMillis() < prefs.getLong("bedtimePassUntil", 0)) return false
        val start = l.bedtimeStartMin ?: return false
        val end = l.bedtimeEndMin ?: return false
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (start <= end) nowMin in start..end
        else nowMin >= start || nowMin <= end // window crosses midnight
    }

    private fun timeOf(epochMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.US).format(Date(epochMs))
}
