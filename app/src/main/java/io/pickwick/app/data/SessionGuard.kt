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
 *    continuous-ish watching, a break lock forces a rest — but only while a
 *    break length is set. Break "Off" = sittings merge freely; the daily
 *    budget and the blocked windows still apply.
 *  - A gap of the break length since last watching starts a fresh sitting.
 *  - Each [TimeWindow] (bedtime, school hours) blocks its clock stretch
 *    outright, whatever budget is left — unless it is marked "Allow
 *    listening", which blocks watching only and lets sound-only playback
 *    through (the minutes still draw the daily budget down).
 *  - Rules left unset simply don't apply — no rule has a hidden default; the
 *    parent's settings screen is the whole truth.
 */
class SessionGuard(context: Context, private val profileSuffix: String = "") {

    private val prefs = context.applicationContext
        .getSharedPreferences("limits$profileSuffix", Context.MODE_PRIVATE)

    companion object {
        /** Deliberately parent-attributed, so the kid doesn't read it as a bug. */
        private const val PAUSED_MESSAGE =
            "A parent paused screen time for today. See you tomorrow 💛"
    }

    // ---- limits config (persisted at whitelist refresh) ----

    fun saveLimits(l: Limits) {
        // Which store got which rules — the first question in any "the TV is
        // still blocking" report, and invisible without this line.
        android.util.Log.i(
            "Pickwick",
            "limits[$profileSuffix] <- session=${l.sessionMinutes} " +
                "wd=${l.weekdaySessions} we=${l.weekendSessions} break=${l.breakMinutes} " +
                "breakPass=${l.breakPassUntilMillis} " +
                "windows=${l.windows.joinToString { it.label }} paused=${l.pausedUntilMillis}"
        )
        prefs.edit()
            .putInt("l_session", l.sessionMinutes ?: -1)
            .putInt("l_wd", l.weekdaySessions ?: -1)
            .putInt("l_we", l.weekendSessions ?: -1)
            .putInt("l_break", l.breakMinutes ?: -1)
            .putString("l_windows", ConfigStore.windowsToJson(l.windows))
            .putLong("l_paused", l.pausedUntilMillis ?: -1L)
            .putLong("l_breakPass", l.breakPassUntilMillis ?: -1L)
            .apply()
    }

    private fun limits(): Limits {
        fun get(key: String) = prefs.getInt(key, -1).takeIf { it >= 0 }
        return Limits(
            sessionMinutes = get("l_session"),
            weekdaySessions = get("l_wd"),
            weekendSessions = get("l_we"),
            breakMinutes = get("l_break"),
            windows = ConfigStore.windowsFromJson(prefs.getString("l_windows", null)),
            pausedUntilMillis = prefs.getLong("l_paused", -1L).takeIf { it > 0 },
            breakPassUntilMillis = prefs.getLong("l_breakPass", -1L).takeIf { it > 0 }
        )
    }

    /**
     * Parent timeout in force right now. Checked ahead of every other rule and
     * not waivable by grants or bedtime passes — only the parent's Resume (which
     * clears the field) or the deadline passing lifts it.
     */
    private fun isPaused(l: Limits): Boolean =
        System.currentTimeMillis() < (l.pausedUntilMillis ?: 0L)

    /**
     * Parent's "skip the next break", still unspent. One break only, per
     * device: the first break it waives writes the pass's own timestamp as
     * spent, so the same pass never covers a second one here. Expires at
     * midnight on its own (it's set to end-of-today and scrubbed on load).
     */
    private fun breakPassActive(l: Limits): Boolean {
        val pass = l.breakPassUntilMillis ?: return false
        return System.currentTimeMillis() < pass && prefs.getLong("breakPassSpent", 0) != pass
    }

    private fun spendBreakPass(l: Limits) {
        prefs.edit().putLong("breakPassSpent", l.breakPassUntilMillis ?: return).apply()
    }


    /** Daily watch budget in ms (incl. parent-granted bonus), or null when not configured. */
    private fun dailyBudgetMs(l: Limits): Long? {
        val perSession = l.sessionMinutes ?: return null
        val count = (if (isWeekend()) l.weekendSessions else l.weekdaySessions) ?: return null
        return perSession * count * 60_000L + prefs.getLong("bonusMs", 0)
    }

    /**
     * Parent grant: adds minutes to today's budget, ends any break lock, starts a
     * fresh sitting, and waives every blocked window for the granted minutes.
     * Resets at midnight.
     */
    fun grantExtraMinutes(minutes: Int) {
        rolloverIfNewDay()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong("bonusMs", prefs.getLong("bonusMs", 0) + minutes * 60_000L)
            .putLong("lockUntil", 0)
            .putLong("sittingWatchedMs", 0)
            .putLong(
                "windowPassUntil",
                maxOf(prefs.getLong("windowPassUntil", 0), now + minutes * 60_000L)
            )
            .apply()
    }

    // ---- enforcement ----

    /**
     * Null if playback may start; otherwise a kid-friendly reason.
     * [multiplierPercent] is the source's screen-time drain rate: at 0 (FREE)
     * an exhausted budget doesn't block — but bedtime and break locks still do.
     * [listening] means sound-only with the screen off, which windows marked
     * "Allow listening" let through; everything else (the pause, the budget,
     * break locks, every other window) applies exactly as it does to watching.
     */
    fun checkStart(multiplierPercent: Int = 100, listening: Boolean = false): String? =
        checkStartInner(multiplierPercent, listening).also { reason ->
            // The enforced store + the rule state behind the verdict. Pairs with
            // the saveLimits line: matching suffixes and values = config applied;
            // anything else points straight at the broken link.
            android.util.Log.i(
                "Pickwick",
                "checkStart[$profileSuffix listening=$listening] -> ${reason ?: "ok"} " +
                    "(break=${prefs.getInt("l_break", -1)} " +
                    "lockUntil=${prefs.getLong("lockUntil", 0)} " +
                    "sitting=${prefs.getLong("sittingWatchedMs", 0) / 60_000}m " +
                    "daily=${prefs.getLong("dailyWatchedMs", 0) / 60_000}m)"
            )
        }

    private fun checkStartInner(multiplierPercent: Int, listening: Boolean): String? {
        rolloverIfNewDay()
        val l = limits()
        val now = System.currentTimeMillis()

        if (isPaused(l)) return PAUSED_MESSAGE
        activeWindow(l, listening)?.let { return windowMessage(l, it, listening) }

        val lockUntil = prefs.getLong("lockUntil", 0)
        if (l.breakMinutes == null) {
            // The break rule was turned off while a lock was armed — the parent
            // did that to end the lockout, so honor it now, not at midnight.
            // The sitting counter goes too: it only feeds the lock, and letting
            // it accumulate would spring an instant lock if the rule comes back.
            if (lockUntil != 0L || prefs.getLong("sittingWatchedMs", 0) != 0L) {
                prefs.edit().putLong("lockUntil", 0).putLong("sittingWatchedMs", 0).apply()
            }
        } else if (now < lockUntil) {
            // Skip mid-break lifts the running break — that's the one the
            // parent is looking at.
            if (breakPassActive(l)) {
                spendBreakPass(l)
                prefs.edit().putLong("lockUntil", 0).putLong("sittingWatchedMs", 0).apply()
            } else {
                return "Time for a break! You can watch again at ${timeOf(lockUntil)} ⏰"
            }
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
     * continue; otherwise a kid-friendly reason to stop now. [listening] is
     * the caller's current mode, so a window marked "Allow listening" arriving
     * mid-story doesn't stop it — the player switches to sound-only instead.
     */
    fun tick(deltaMs: Long, listening: Boolean = false): String? {
        rolloverIfNewDay()
        val l = limits()
        val now = System.currentTimeMillis()
        prefs.edit().putLong("lastWatchAt", now).apply()

        // Mid-playback too: the pushed config lands, the next tick stops the video.
        if (isPaused(l)) return PAUSED_MESSAGE
        activeWindow(l, listening)?.let { return windowMessage(l, it, listening) }

        val daily = prefs.getLong("dailyWatchedMs", 0) + deltaMs
        val sitting = prefs.getLong("sittingWatchedMs", 0) + deltaMs
        prefs.edit().putLong("dailyWatchedMs", daily).putLong("sittingWatchedMs", sitting).apply()

        dailyBudgetMs(l)?.let { budget ->
            if (daily >= budget) return "That's all the watching for today! 🌟"
        }
        // No break rule → nothing to arm; the sitting cap only exists to force
        // a rest of the configured length. The daily budget above still caps
        // the day, so this isn't unlimited watching.
        val breakLen = l.breakMinutes ?: return null
        val sittingCapMs = l.sessionMinutes?.let { it * 60_000L } ?: return null
        if (sitting >= sittingCapMs) {
            // The parent's skip: no lock, a fresh sitting, and the film plays
            // on. The daily budget above still caps the day.
            if (breakPassActive(l)) {
                spendBreakPass(l)
                prefs.edit().putLong("sittingWatchedMs", 0).apply()
                return null
            }
            prefs.edit().putLong("lockUntil", now + breakLen * 60_000L).apply()
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
    fun remainingMs(multiplierPercent: Int = 100, listening: Boolean = false): Long? {
        rolloverIfNewDay()
        val l = limits()
        if (isPaused(l)) return 0
        val candidates = mutableListOf<Long>()
        if (multiplierPercent > 0) {
            dailyBudgetMs(l)?.let { budget ->
                candidates += (budget - prefs.getLong("dailyWatchedMs", 0))
                    .coerceAtLeast(0) * 100 / multiplierPercent
            }
            // The sitting cap only counts while it can actually lock (break
            // set, and no unspent skip waiting to waive it).
            if (l.breakMinutes != null && !breakPassActive(l)) l.sessionMinutes?.let { cap ->
                candidates += (cap * 60_000L - prefs.getLong("sittingWatchedMs", 0))
                    .coerceAtLeast(0) * 100 / multiplierPercent
            }
        }
        msUntilWindow(l, listening)?.let { candidates += it }
        return candidates.minOrNull()?.coerceAtLeast(0)
    }

    /** Clock ms until the next window closes playback, or null when there are none. */
    private fun msUntilWindow(l: Limits, listening: Boolean): Long? {
        val windows = liveWindows(l, listening)
        if (windows.isEmpty()) return null
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (TimeWindows.activeAt(windows, day, nowMin) != null) {
            // Inside a window only a grant keeps playback alive, and only for
            // the minutes it bought.
            (prefs.getLong("windowPassUntil", 0) - now).coerceAtLeast(0)
        } else {
            (TimeWindows.minutesUntilNextStart(windows, day, nowMin) ?: return null) * 60_000L
        }
    }

    /** A break-length gap since last watching starts a new sitting (nothing lost).
     *  No break rule → no sitting rhythm to reset (checkStart zeroes it instead). */
    private fun startFreshSittingAfterGap(l: Limits, now: Long) {
        val gapMs = l.breakMinutes?.let { it * 60_000L } ?: return
        val lastWatch = prefs.getLong("lastWatchAt", 0)
        if (lastWatch > 0 && now - lastWatch >= gapMs) {
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
                .putLong("windowPassUntil", 0)
                .putLong("breakPassSpent", 0)
                .apply()
        }
    }

    /**
     * Minutes left in today's budget under [l] (limits straight from the
     * config, so the who's-watching tiles don't depend on this profile having
     * been active since the last rules push). Null when no budget is set.
     */
    fun remainingTodayMin(l: Limits): Int? {
        rolloverIfNewDay()
        if (isPaused(l)) return 0
        val perSession = l.sessionMinutes ?: return null
        val count = (if (isWeekend()) l.weekendSessions else l.weekdaySessions) ?: return null
        val budget = perSession * count * 60_000L + prefs.getLong("bonusMs", 0)
        return ((budget - prefs.getLong("dailyWatchedMs", 0)).coerceAtLeast(0) / 60_000L).toInt()
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
        // A lock left over from before the break rule was disabled is dead
        // (checkStart clears it on the next play attempt) — don't report it.
        val lockUntil = if (l.breakMinutes == null) 0 else prefs.getLong("lockUntil", 0)
        val budget = dailyBudgetMs(l)
        val watched = prefs.getLong("dailyWatchedMs", 0)
        val blocking = activeWindow(l)
        val state = when {
            isPaused(l) -> "Paused by parent"
            // The parent's stats screen shows this next to what's playing, so a
            // bedtime that lets a story through has to say so — otherwise it
            // reads as "Bedtime" while the kid is audibly still listening.
            blocking != null ->
                if (blocking.allowListening) "${blocking.label} (listening)" else blocking.label
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

    /**
     * Windows still in force: a parent's pass takes one out for its occurrence,
     * and while [listening] (sound only, screen off) the ones marked "Allow
     * listening" don't apply — see [TimeWindow.allowListening].
     */
    private fun liveWindows(l: Limits, listening: Boolean = false): List<TimeWindow> {
        val now = System.currentTimeMillis()
        return l.windows.filter {
            now >= (it.passUntilMillis ?: 0L) && !(listening && it.allowListening)
        }
    }

    /**
     * A window that blocks watching right now but lets sound-only playback
     * through, or null — either nothing is blocking, or what blocks isn't
     * waivable this way. An overlapping strict window wins: the stricter rule
     * is the one a parent means when two cover the same minute.
     *
     * Drives the player's switch into listening: what this returns non-null
     * for is exactly what [checkStart] refuses at 100% but allows while
     * listening.
     */
    fun listenOnlyWindow(): TimeWindow? {
        rolloverIfNewDay()
        val l = limits()
        if (isPaused(l)) return null
        val blocking = activeWindow(l) ?: return null
        return blocking.takeIf { activeWindow(l, listening = true) == null }
    }

    /** The window blocking playback right now, or null. */
    private fun activeWindow(l: Limits, listening: Boolean = false): TimeWindow? {
        // A grant waives every window for the minutes it bought — a parent
        // handing out 20 more minutes at 19:40 means them, not "except bedtime".
        if (System.currentTimeMillis() < prefs.getLong("windowPassUntil", 0)) return null
        val cal = Calendar.getInstance()
        return TimeWindows.activeAt(
            liveWindows(l, listening),
            cal.get(Calendar.DAY_OF_WEEK),
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        )
    }

    /**
     * Names the window and says when it lifts — "when can I watch again" is the
     * question a blocked kid actually has, and the reopening time answers it
     * even when the label ("School hours") wouldn't.
     */
    private fun windowMessage(l: Limits, w: TimeWindow, listening: Boolean = false): String {
        val cal = Calendar.getInstance()
        val mins = TimeWindows.blockedForMin(
            liveWindows(l, listening),
            cal.get(Calendar.DAY_OF_WEEK),
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        )
        val label = w.label.trim().ifEmpty { "Quiet time" }
        val emoji = if (label.equals("bedtime", ignoreCase = true)) "🌙" else "⏰"
        // Overlapping windows chain, so the time comes from the chain, not from
        // this window's own end — otherwise we'd promise a reopening that the
        // very next window immediately takes back.
        return if (mins == null) "It's ${label.lowercase(Locale.getDefault())} right now $emoji"
        else "It's ${label.lowercase(Locale.getDefault())} — " +
            "you can watch again at ${timeOf(System.currentTimeMillis() + mins * 60_000L)} $emoji"
    }

    private fun timeOf(epochMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.US).format(Date(epochMs))
}
