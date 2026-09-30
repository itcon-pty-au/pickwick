package io.pickwick.app.ui

import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.Locale

internal data class EmbeddedTrack(
    val name: String,
    val language: String,
    val mimeType: String,
    val override: TrackSelectionOverride,
    val selected: Boolean
)

internal fun embeddedTracks(tracks: Tracks, type: Int): List<EmbeddedTrack> =
    tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
            val format = group.getTrackFormat(index)
            val language = format.language.orEmpty()
            EmbeddedTrack(
                name = format.label?.takeIf { it.isNotBlank() }
                    ?: language.takeIf { it.isNotBlank() && it != "und" }
                        ?.let { Locale.forLanguageTag(it).displayLanguage } ?: "Track",
                language = language,
                mimeType = format.sampleMimeType.orEmpty(),
                override = TrackSelectionOverride(group.mediaTrackGroup, index),
                selected = group.isTrackSelected(index)
            )
        }
    }.let { choices ->
        choices.mapIndexed { index, choice ->
            if (choices.count { it.name == choice.name } > 1) choice.copy(name = "${choice.name} ${index + 1}")
            else choice
        }
    }
