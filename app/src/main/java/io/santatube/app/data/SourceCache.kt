package io.santatube.app.data

import android.content.Context

/**
 * Persists the last successfully resolved home-screen tiles so the app can paint
 * instantly on launch while the fresh list loads in the background.
 */
class SourceCache(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("source_cache", Context.MODE_PRIVATE)

    fun load(): List<Source> =
        prefs.getString("sources", null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                // id \t kind \t name \t avatar \t url \t multiplier% — older 4-field
                // rows are dropped and simply re-fetched once; 5-field rows (from
                // pre-multiplier builds) default to 100%.
                val parts = line.split('\t')
                if (parts.size < 5) return@mapNotNull null
                val kind = runCatching { SourceKind.valueOf(parts[1]) }.getOrNull()
                    ?: return@mapNotNull null
                Source(
                    parts[0], parts[4], parts[2], parts[3].ifEmpty { null }, kind,
                    parts.getOrNull(5)?.toIntOrNull() ?: 100
                )
            }
            ?.toList()
            ?: emptyList()

    fun save(sources: List<Source>) {
        val text = sources.joinToString("\n") { s ->
            listOf(
                s.id, s.kind.name, s.name.replace('\t', ' '), s.avatarUrl.orEmpty(), s.url,
                s.timeMultiplierPercent.toString()
            ).joinToString("\t")
        }
        prefs.edit().putString("sources", text).apply()
    }
}
