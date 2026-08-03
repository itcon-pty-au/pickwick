package io.santatube.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
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
    val timeMultiplierPercent: Int = 100
)

/** Chip cycle order in settings: tap steps through these, long-press resets to 100. */
val TIME_MULTIPLIERS = listOf(100, 125, 150, 75, 50, 25, 0)

/** Screen-time rules, set in the parent settings UI. All optional. */
data class Limits(
    val sessionMinutes: Int? = null,
    val weekdaySessions: Int? = null,
    val weekendSessions: Int? = null,
    val breakMinutes: Int? = null,
    /** Minutes-of-day; the window may cross midnight (e.g. 19:30–07:00). */
    val bedtimeStartMin: Int? = null,
    val bedtimeEndMin: Int? = null,
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
    val aiAllowedVideoIds: Set<String> = emptySet()
)

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
 * can be shared with another parent (link import or per-line paste) or kept as
 * a backup. Screen-time rules are UI-managed, never file-driven — they are
 * written as comments only, for the human reading the file.
 */
object WhitelistExporter {

    fun toText(w: Whitelist, exportedOn: String? = null): String = buildString {
        append("# SantaTube whitelist")
        exportedOn?.let { append(" — exported ").append(it) }
        append('\n')
        append("# Host this file online and use Settings → \"Import from a whitelist link\",\n")
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
            append('\n')
        }

        if (w.blockedVideoIds.isNotEmpty()) {
            append("\n# Blocked videos:\n")
            w.blockedVideoIds.sorted().forEach { append("! ").append(it).append('\n') }
        }

        limitsComment(w.limits)?.let { append('\n').append(it) }
    }

    private fun limitsComment(l: Limits): String? {
        val lines = buildList {
            l.sessionMinutes?.let { add("time per session: $it min") }
            l.weekdaySessions?.let { add("weekday sessions: $it") }
            l.weekendSessions?.let { add("weekend sessions: $it") }
            l.breakMinutes?.let { add("break between sessions: $it min") }
            if (l.bedtimeStartMin != null && l.bedtimeEndMin != null) {
                add("bedtime: ${clock(l.bedtimeStartMin)}–${clock(l.bedtimeEndMin)}")
            }
        }
        if (lines.isEmpty()) return null
        return "# Screen time (reference only — set these in the settings UI):\n" +
            lines.joinToString("") { "#   $it\n" }
    }

    private fun clock(minOfDay: Int) = "%d:%02d".format(minOfDay / 60, minOfDay % 60)
}

/** The form-managed local config is the single source of truth. */
class WhitelistRepository(private val configStore: ConfigStore) {
    suspend fun load(): Whitelist = withContext(Dispatchers.IO) { configStore.load() }
}

/**
 * One-time import of a hosted whitelist file: fetches, parses, and returns the
 * links (channels/playlists) so they can be added to the form. Nothing is
 * remembered about the URL afterwards.
 */
object WhitelistImporter {
    suspend fun fetch(url: String): Whitelist = withContext(Dispatchers.IO) {
        val busted = url + (if ('?' in url) "&" else "?") + "cb=" + System.currentTimeMillis()
        val text = Http.client.newCall(Request.Builder().url(busted).build()).execute().use { resp ->
            check(resp.isSuccessful) { "Fetch failed: HTTP ${resp.code}" }
            resp.body?.string().orEmpty()
        }
        WhitelistParser.parse(text)
    }
}
