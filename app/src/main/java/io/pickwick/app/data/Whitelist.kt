package io.pickwick.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class SourceKind { CHANNEL, PLAYLIST }

data class WhitelistEntry(
    /** Stable key for caches: the YouTube ID when known, else the URL path form. */
    val id: String,
    /** Canonical URL handed to the extractor. */
    val url: String,
    /** Optional display name from the file; the real name is fetched from YouTube anyway. */
    val label: String?,
    val kind: SourceKind,
    /**
     * How fast watching this source drains the screen-time budget, in percent:
     * 100 = normal, 50 = half speed, 150 = "junk food" penalty, 0 = FREE
     * (doesn't count at all). One of [TIME_MULTIPLIERS].
     */
    val timeMultiplierPercent: Int = 100,
    /**
     * Which kids see this source. Empty = everyone, including kids added
     * later — so a family that never touches the per-kid switches shares one
     * list, and adding a channel defaults to all.
     */
    val profileIds: Set<String> = emptySet(),
    /**
     * Channel-specific screening instructions, applied by the AI on top of the
     * family rules ("mild cartoon slapstick is fine here; block 'prank' videos").
     * Null/blank = none. Verdicts remember the note they were judged under
     * ([ScreeningStore.Entry.noteHash]), so editing this re-screens only this
     * source's videos — except already-blocked ones, which stay blocked: the
     * note exists to catch more junk, not to relitigate old blocks.
     */
    val aiNote: String? = null
) {
    fun visibleTo(profileId: String?): Boolean =
        profileIds.isEmpty() || profileId == null || profileId in profileIds
}

/** Chip cycle order in settings: tap steps through these, long-press resets to 100. */
val TIME_MULTIPLIERS = listOf(100, 125, 150, 75, 50, 25, 0)

/**
 * Listening chip cycle: Off first — null disables screen-off listening
 * entirely (locking the phone pauses playback, the pre-listen behavior).
 * No penalty rates: listening is only ever as expensive as watching.
 */
val LISTEN_MULTIPLIERS = listOf<Int?>(null, 100, 75, 50, 25, 0)

/**
 * Screen-time drain while listening with the screen off: the source's own
 * rate scaled by the family listening rate. Integer math on purpose — FREE
 * (0) stays exactly 0, and 150% junk food at a 50% listen rate is exactly 75.
 */
fun listenDrainPercent(sourcePercent: Int, listenPercent: Int?): Int =
    sourcePercent * (listenPercent ?: 100) / 100

/** Every day of the week, in [java.util.Calendar]'s 1..7 (Sunday = 1). */
val ALL_DAYS: Set<Int> = (1..7).toSet()
val WEEKDAYS: Set<Int> = setOf(2, 3, 4, 5, 6)
val WEEKEND_DAYS: Set<Int> = setOf(1, 7)

/** Indexed by Calendar day-of-week minus one, so Sunday leads. */
val DAY_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/**
 * A recurring stretch of clock time when watching is blocked outright —
 * bedtime, school hours, homework. Start and end are minutes-of-day and the
 * window may cross midnight (19:30–07:00); [days] are the days it *starts* on.
 *
 * A list rather than one bedtime because the everyday cases already need more
 * than one: a later bedtime at the weekend, and school hours that only apply
 * Monday to Friday. Windows may overlap freely — any window covering the
 * moment blocks it — so a parent never has to reason about interactions.
 */
data class TimeWindow(
    /** Stable across edits; keys the per-window pass. */
    val id: String,
    /** Shown to the kid when this window is what's blocking ("School hours"). */
    val label: String,
    val startMin: Int,
    val endMin: Int,
    val days: Set<Int> = ALL_DAYS,
    /**
     * Parent pass for one occurrence — the sick day, the school holiday, the
     * film that runs past bedtime. Deliberately per-window: skipping bedtime
     * tonight must not also unlock tomorrow morning's school hours. Set to the
     * moment the occurrence would have ended, so it lapses on its own.
     */
    val passUntilMillis: Long? = null,
    /**
     * "Allow listening": this window blocks *watching* only — sound-only
     * playback goes on through it. Bedtime is the case that asks for it, since
     * a bedtime story is the main thing listening is for, and the alternative
     * is a window that either cuts the story off mid-sentence or has to be
     * skipped by hand every night. Per-window and off by default: school hours
     * usually want the plain block, and no window a parent already configured
     * quietly loosens on upgrade.
     *
     * Phones only, like listening itself — a TV can't play with its panel off,
     * so a TV enforces every window outright whatever this says.
     */
    val allowListening: Boolean = false
)

/** Screen-time rules, set in the parent settings UI. All optional. */
data class Limits(
    val sessionMinutes: Int? = null,
    val weekdaySessions: Int? = null,
    val weekendSessions: Int? = null,
    val breakMinutes: Int? = null,
    /**
     * Parent pass over the next break — the film that runs past the sitting
     * cap. One break only: the first break it waives consumes it (per device),
     * and set to the next midnight so an unused skip quietly expires. Kept in
     * the config like a window's pass so it reaches every device and moves the
     * fingerprint.
     */
    val breakPassUntilMillis: Long? = null,
    /**
     * Blocked clock windows. Empty = no window at all; there is no default
     * bedtime for a parent who never set one.
     */
    val windows: List<TimeWindow> = emptyList(),
    /**
     * Hide videos shorter than this many minutes, everywhere a video can be
     * listed — channel grids, Surprise, search, saved lists. Null = no rule.
     * Shorts never reach the app at all (only a channel's Videos tab is
     * fetched), so this is for the clip-length uploads that live alongside
     * real episodes. A video with an unknown (0) duration is never hidden.
     */
    val minVideoMinutes: Int? = null,
    /**
     * Parent timeout: all watching is off until this wall-clock moment (normally
     * the next midnight). A transient override, kept apart from the recurring
     * rules above so pausing never disturbs the configured schedule. Overrides
     * grants while active; the parent's Resume clears it.
     */
    val pausedUntilMillis: Long? = null
)

/**
 * Parent-configured AI screening of new videos. Lives in the synced config so
 * each kid device can screen the feeds it fetches itself. Any OpenAI-compatible
 * chat-completions endpoint works (OpenRouter, OpenAI, Anthropic, Gemini, or a
 * local Ollama — the last keeps all data in the house).
 */
data class AiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val model: String = "",
    val apiKey: String = "",
    /** Free-text house rules the model judges titles against. */
    val rules: String = "",
    val childAge: Int? = null,
    /** Bumped when rules/age/model change — cached verdicts for older versions are re-screened. */
    val rulesVersion: Int = 0
)

data class Whitelist(
    val sources: List<WhitelistEntry>,
    /** Individual videos a parent has blocked with a leading '!' line. */
    val blockedVideoIds: Set<String>,
    val limits: Limits = Limits(),
    val ai: AiConfig = AiConfig(),
    /** Parent overrides: videos the AI blocked but a parent explicitly allowed. */
    val aiAllowedVideoIds: Set<String> = emptySet(),
    /**
     * The family's kids. Empty = pre-profile behavior everywhere: no picker,
     * the device is the kid, [limits] and [AiConfig.childAge] apply directly.
     */
    val profiles: List<Profile> = emptyList(),
    /**
     * Per-kid overlays from a long-press ruling ("fine for the 12-year-old,
     * not the 5-year-old"): videoId → the kids it's blocked/allowed for. A
     * plain tap uses the family-wide sets above instead.
     */
    val blockedFor: Map<String, Set<String>> = emptyMap(),
    val allowedFor: Map<String, Set<String>> = emptyMap(),
    /**
     * Device pairing-token → the kid that device is dedicated to. Devices not
     * listed are shared and show the who's-watching screen.
     */
    val deviceProfiles: Map<String, String> = emptyMap(),
    /**
     * The one parent device that builds the search index (its YouTube crawl is
     * rate-limit-expensive — doing it once per family, not once per admin
     * phone). null = never chosen: the next admin phone to see that claims it.
     */
    val masterDeviceToken: String? = null,
    /**
     * Auto-skip community-marked promotional stretches (SponsorBlock): sponsor
     * reads, merch plugs, intros/outros, "subscribe" pleas. On by default —
     * this is the ad-free promise of the app extended to baked-in ads — with
     * a parent switch in settings.
     */
    val sponsorSkip: Boolean = true,
    /**
     * Family-wide screen-off listening rate, percent (one of
     * [LISTEN_MULTIPLIERS]). Null = the feature is off: locking the phone
     * pauses playback, exactly the pre-listen behavior — no hidden default
     * rate. Set, audio keeps playing with the screen off and those minutes
     * drain at [listenDrainPercent]. Phones only: a TV can't play with its
     * panel off, so TVs ignore it.
     */
    val listenPercent: Int? = null
) {
    fun profile(id: String?): Profile? = profiles.firstOrNull { it.id == id }

    /**
     * Effective screen-time rules for one kid. Two pauses can apply: the
     * kid's own ("pause today" on their page) and the family-wide "pause
     * everyone". Whichever runs later wins — a per-kid Resume must not undo a
     * whole-family timeout, and vice versa.
     */
    fun limitsFor(profileId: String?): Limits {
        val p = profile(profileId) ?: return limits
        val family = limits.pausedUntilMillis ?: return p.limits
        val own = p.limits.pausedUntilMillis ?: 0L
        return p.limits.copy(pausedUntilMillis = maxOf(family, own))
    }

    fun isBlockedFor(videoId: String?, profileId: String?): Boolean {
        videoId ?: return false
        if (videoId in blockedVideoIds) return true
        return profileId != null && profileId in blockedFor[videoId].orEmpty()
    }

    /** Parent allow-overrides that apply to this kid (feeds the screener). */
    fun allowedIdsFor(profileId: String?): Set<String> =
        if (profileId == null) aiAllowedVideoIds
        else aiAllowedVideoIds + allowedFor.filterValues { profileId in it }.keys
}

/**
 * Fetches and parses the parent-maintained list.
 *
 * File format, one entry per line ('#' starts a comment, blanks ignored):
 *   UC...                        a whole channel (24-char channel ID)
 *   PL... / UU... / OLAK...      a single playlist
 *   @handle                      a channel by its handle
 *   https://youtube.com/...      any channel or playlist URL, pasted as-is:
 *                                /channel/UC..., /user/name, /c/name, /@handle,
 *                                /playlist?list=PL...
 *   ! <videoId or URL>           block one specific video even inside an allowed source
 * Any entry may carry an optional "| Display Name" suffix.
 */
/** Pure text→Whitelist parsing, kept free of Android deps so it is unit-testable. */
object WhitelistParser {

    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val PLAYLIST_ID = Regex("^(PL|UU|FL|OLAK5uy_)[A-Za-z0-9_-]{10,}$")
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HANDLE = Regex("^@[A-Za-z0-9._-]{3,}$")

    /** youtube.com / www. / m. / music., with or without scheme. */
    private val YT_HOST = Regex("^(https?://)?((www|m|music)\\.)?youtube\\.com/", RegexOption.IGNORE_CASE)
    private val V_PARAM = Regex("[?&]v=([A-Za-z0-9_-]{11})")
    private val LIST_PARAM = Regex("[?&]list=([A-Za-z0-9_-]+)")

    fun parse(text: String): Whitelist {
        val sources = mutableListOf<WhitelistEntry>()
        val blocked = mutableSetOf<String>()

        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                if (line.startsWith("!")) {
                    blockedVideoId(line.removePrefix("!").trim())?.let { blocked += it }
                    return@forEach
                }
                val token = line.substringBefore('|').trim()
                val label = line.substringAfter('|', "").trim().ifEmpty { null }
                entryFor(token, label)?.let { sources += it }
            }

        // Screen-time rules are set only via the settings UI — files carry links.
        return Whitelist(sources.distinctBy { it.id }, blocked)
    }

    private fun blockedVideoId(token: String): String? =
        V_PARAM.find(token)?.groupValues?.get(1)
            ?: token.substringAfterLast("youtu.be/", "").substringBefore('?')
                .takeIf { VIDEO_ID.matches(it) }
            ?: token.takeIf { VIDEO_ID.matches(it) }

    /** Accepts bare IDs, handles, and any YouTube channel/playlist URL. */
    private fun entryFor(token: String, label: String?): WhitelistEntry? {
        // Bare IDs and handles first — the common, unambiguous cases.
        when {
            CHANNEL_ID.matches(token) ->
                return WhitelistEntry(token, channelUrl(token), label, SourceKind.CHANNEL)
            PLAYLIST_ID.matches(token) ->
                return WhitelistEntry(token, playlistUrl(token), label, SourceKind.PLAYLIST)
            HANDLE.matches(token) ->
                return WhitelistEntry(token, "https://www.youtube.com/$token", label, SourceKind.CHANNEL)
        }

        if (!YT_HOST.containsMatchIn(token)) return null

        // A ?list= playlist URL (watch?v=..&list=.. counts as a playlist too).
        LIST_PARAM.find(token)?.groupValues?.get(1)?.let { listId ->
            if (PLAYLIST_ID.matches(listId)) {
                return WhitelistEntry(listId, playlistUrl(listId), label, SourceKind.PLAYLIST)
            }
        }

        // Channel URL forms: /channel/UC..., /user/name, /c/name, /@handle
        val path = token.replace(YT_HOST, "").substringBefore('?').substringBefore('#').trimEnd('/')
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        return when {
            segments[0] == "channel" && segments.size >= 2 && CHANNEL_ID.matches(segments[1]) ->
                WhitelistEntry(segments[1], channelUrl(segments[1]), label, SourceKind.CHANNEL)

            segments[0].startsWith("@") ->
                WhitelistEntry(
                    segments[0], "https://www.youtube.com/${segments[0]}", label, SourceKind.CHANNEL
                )

            (segments[0] == "user" || segments[0] == "c") && segments.size >= 2 ->
                WhitelistEntry(
                    "${segments[0]}/${segments[1]}",
                    "https://www.youtube.com/${segments[0]}/${segments[1]}",
                    label,
                    SourceKind.CHANNEL
                )

            else -> null
        }
    }

    private fun channelUrl(id: String) = "https://www.youtube.com/channel/$id"
    private fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"
}

/**
 * Whitelist → text in the same file format WhitelistParser reads, so an export
 * can be shared with another parent (file import or per-line paste) or kept as
 * a backup. Screen-time rules are UI-managed, never file-driven — they are
 * written as comments only, for the human reading the file.
 */
object WhitelistExporter {

    fun toText(w: Whitelist, exportedOn: String? = null): String = buildString {
        append("# Pickwick whitelist")
        exportedOn?.let { append(" — exported ").append(it) }
        append('\n')
        append("# Open this file with Settings → \"Import, export & backup\" → \"Import from file\",\n")
        append("# or paste entries one by one under \"Channels & playlists\".\n\n")

        w.sources.forEach { e ->
            append(e.url)
            // '#' starts a comment and '|' separates the label — keep labels clear of both.
            e.label?.filterNot { it == '#' || it == '|' }?.trim()?.ifEmpty { null }
                ?.let { append(" | ").append(it) }
            // Reference only, like the limits below — multipliers are UI-managed
            // and sync device-to-device, not through files.
            if (e.timeMultiplierPercent != 100) {
                append("  # screen time ")
                append(if (e.timeMultiplierPercent == 0) "FREE" else "${e.timeMultiplierPercent}%")
            }
            // Reference only, like the multiplier: notes are UI-managed and
            // sync device-to-device, not through files.
            e.aiNote?.trim()?.ifEmpty { null }?.let {
                append("  # AI note: ").append(it.replace('\n', ' '))
            }
            append('\n')
        }

        if (w.blockedVideoIds.isNotEmpty()) {
            append("\n# Blocked videos:\n")
            w.blockedVideoIds.sorted().forEach { append("! ").append(it).append('\n') }
        }

        limitsComment(w.limits)?.let { append('\n').append(it) }

        w.listenPercent?.let {
            append("\n# Listening with the screen off (reference only — set in the settings UI): ")
            append(if (it == 0) "FREE" else "$it%")
            append('\n')
        }
    }

    private fun limitsComment(l: Limits): String? {
        val lines = buildList {
            l.sessionMinutes?.let { add("time per session: $it min") }
            l.weekdaySessions?.let { add("weekday sessions: $it") }
            l.weekendSessions?.let { add("weekend sessions: $it") }
            l.breakMinutes?.let { add("break between sessions: $it min") }
            l.minVideoMinutes?.let { add("hide videos shorter than: $it min") }
            l.windows.forEach { w ->
                add(
                    "${w.label.lowercase()}: ${clock(w.startMin)}–${clock(w.endMin)} " +
                        "(${dayNames(w.days)})" +
                        if (w.allowListening) ", listening still allowed" else ""
                )
            }
        }
        if (lines.isEmpty()) return null
        return "# Screen time (reference only — set these in the settings UI):\n" +
            lines.joinToString("") { "#   $it\n" }
    }

    private fun clock(minOfDay: Int) = "%d:%02d".format(minOfDay / 60, minOfDay % 60)

    private fun dayNames(days: Set<Int>): String = when (days) {
        ALL_DAYS -> "every day"
        WEEKDAYS -> "Mon–Fri"
        WEEKEND_DAYS -> "weekends"
        else -> days.sorted().joinToString(", ") { DAY_LABELS[it - 1] }
    }
}

/** The form-managed local config is the single source of truth. */
class WhitelistRepository(private val configStore: ConfigStore) {
    suspend fun load(): Whitelist = withContext(Dispatchers.IO) { configStore.load() }
}
