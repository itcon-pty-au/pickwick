package io.pickwick.app.data

import java.text.Normalizer

/**
 * Word-based matching and ranking for the local search index.
 *
 * Plain substring matching made short queries useless: "pi" hit "Peppa *Pi*g"
 * (and "spider", "pizza", "happiness"), and because the channel name is part
 * of the haystack, one such hit dragged in that channel's entire back catalog.
 * So terms match whole words only, and a term may match as a word *prefix*
 * only once it is long enough to be deliberate ("dino" -> "dinosaur", but
 * "pi" stays "pi").
 */
object SearchMatcher {

    /** Below this length a term must equal a whole word; at or above it, a
     *  word prefix counts too. */
    private const val MIN_PREFIX = 3

    // Per-term weights. A title hit always outranks a channel-name-only hit,
    // and an exact word outranks a prefix, so "pi" ranks "Pi Day" above
    // "Pie recipes" when the query is long enough to reach both.
    private const val TITLE_EXACT = 4
    private const val TITLE_PREFIX = 2
    private const val CHANNEL = 1

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{Mn}+")

    /** Lowercased, accent-folded words. "π" is spelled out so a kid typing
     *  either form finds the other. */
    fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase().replace("π", " pi "), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .split(SPLIT)
            .filter { it.isNotEmpty() }

    /**
     * Relevance of a video to [terms] (from [words] on the query); 0 means no
     * match. Every term must match somewhere — adding words narrows, as a kid
     * expects.
     */
    fun score(terms: List<String>, title: String, channelName: String): Int {
        if (terms.isEmpty()) return 0
        val titleWords = words(title).map(::stem)
        val channelWords by lazy { words(channelName).map(::stem) }
        var total = 0
        for (raw in terms) {
            val t = stem(raw)
            total += when {
                titleWords.any { it == t } -> TITLE_EXACT
                titleWords.any { prefixHit(t, it) } -> TITLE_PREFIX
                channelWords.any { it == t || prefixHit(t, it) } -> CHANNEL
                else -> return 0
            }
        }
        return total
    }

    private fun prefixHit(term: String, word: String) =
        term.length >= MIN_PREFIX && word.startsWith(term)

    /** Crude plural folding so "dinosaurs" finds "dinosaur" and back. Applied
     *  to both query and title, so a wrong fold ("bus" is spared by the
     *  length check; "atlas" is not) still matches itself. */
    private fun stem(w: String) =
        if (w.length > 3 && w.endsWith('s') && !w.endsWith("ss")) w.dropLast(1) else w
}
