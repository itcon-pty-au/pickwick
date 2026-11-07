package io.pickwick.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A parent-named row on the TV home. Deliberately separate from [ContentGroup]:
 * groups carry session time limits, and rearranging the TV must never change a
 * child's screen time.
 */
data class HomeRow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Matched by URL, never id: resolution rewrites ids to their UC… form. */
    val sourceUrls: Set<String> = emptySet(),
    /** Which kids get this row. Empty = everyone, including kids added later. */
    val profileIds: Set<String> = emptySet()
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(name.isNotBlank() && name.length <= MAX_NAME)
        require(sourceUrls.size <= MAX_SOURCES)
    }

    fun visibleTo(profileId: String?): Boolean =
        profileIds.isEmpty() || profileId == null || profileId in profileIds

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("sourceUrls", JSONArray(sourceUrls.sorted()))
        .put("profileIds", JSONArray(profileIds.sorted()))

    companion object {
        const val MAX_ROWS = 50
        const val MAX_NAME = 60
        const val MAX_SOURCES = 5000

        fun fromJson(o: JSONObject): HomeRow {
            fun strings(key: String) = o.optJSONArray(key)
                ?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            return HomeRow(o.getString("id"), o.getString("name"), strings("sourceUrls"), strings("profileIds"))
        }
    }
}

/** A kid's channels split into their rows; [rest] is whatever no row claimed. */
data class HomeLayout(val rows: List<Pair<HomeRow, List<Source>>>, val rest: List<Source>)

/**
 * Keeps [channels]' order inside each row, so most-watched still leads. A channel
 * shows once per home, in the first row that has it. Anything unclaimed lands in
 * [HomeLayout.rest], so a newly added channel can't go missing. [rows] must already
 * be filtered to the kid: a row with no visible channels simply drops out.
 */
fun arrangeHome(channels: List<Source>, rows: List<HomeRow>): HomeLayout {
    val placed = HashSet<String>()
    val filled = rows.mapNotNull { row ->
        channels.filter { it.url in row.sourceUrls && placed.add(it.url) }
            .takeIf { it.isNotEmpty() }?.let { row to it }
    }
    return HomeLayout(filled, channels.filterNot { it.url in placed })
}
