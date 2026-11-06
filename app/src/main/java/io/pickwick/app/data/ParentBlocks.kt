package io.pickwick.app.data

/**
 * The videos a parent blocked by hand, as rows the settings page can list and
 * undo. The config stores only ids, and a video the parent blocked drops out of
 * every flagged list, so title and thumbnail have to be looked up from whatever
 * this phone still knows; a video it knows nothing about is still listed, since
 * a block the parent can't see is a block they can't undo.
 */
object ParentBlocks {

    data class Details(val title: String, val channel: String, val thumb: String?)

    data class Row(
        val videoId: String,
        /** Blank when no store on this phone has the video's details. */
        val title: String,
        val channel: String,
        val thumb: String,
        /** Empty = blocked for everyone; otherwise only these kids. */
        val kidIds: Set<String>
    )

    fun rows(
        everyone: Set<String>,
        perKid: Map<String, Set<String>>,
        details: (String) -> Details?
    ): List<Row> = (everyone + perKid.keys).map { id ->
        val d = details(id)
        Row(
            videoId = id,
            title = d?.title.orEmpty(),
            channel = d?.channel.orEmpty(),
            // Video ids map straight onto a thumbnail, known details or not.
            thumb = d?.thumb ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg",
            // A family-wide block outranks any per-kid one on the same video.
            kidIds = if (id in everyone) emptySet() else perKid[id].orEmpty()
        )
    }.sortedWith(compareBy<Row> { it.title.isBlank() }.thenBy { it.title.lowercase() }.thenBy { it.videoId })

    /** Search box: title, channel or a pasted video id. Blank matches everything. */
    fun matching(rows: List<Row>, query: String): List<Row> {
        val q = query.trim()
        if (q.isEmpty()) return rows
        return rows.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.channel.contains(q, ignoreCase = true) ||
                it.videoId.contains(q, ignoreCase = true)
        }
    }
}
