package io.pickwick.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.SponsorBlock

/**
 * SponsorBlock skipping: the master switch, then one checkbox per kind of
 * marked stretch so a parent who likes a channel's intro can keep it while
 * still cutting the sponsor reads. The list is [SponsorBlock.Category], not a
 * copy of it here — a category added there shows up in this page by itself.
 */
@Composable
internal fun ColumnScope.SponsorSkipSection(
    enabled: Boolean,
    categories: Set<String>,
    onEnabled: (Boolean) -> Unit,
    onCategories: (Set<String>) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Skip marked parts of videos", modifier = Modifier.weight(1f))
        Switch(
            modifier = Modifier.tvFocusHighlight(),
            checked = enabled,
            onCheckedChange = onEnabled
        )
    }
    Text(
        "Skips the parts of a video the SponsorBlock community has marked. " +
            "Marked parts show in green on the TV's playback bar. Lookups send " +
            "only an anonymous fingerprint of the video, never what is being watched.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!enabled) return

    Text(
        "Choose what to skip",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp)
    )
    SponsorBlock.Category.values().forEach { c ->
        val checked = c.id in categories
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCategories(if (checked) categories - c.id else categories + c.id) }
        ) {
            Checkbox(
                modifier = Modifier.tvFocusHighlight(),
                checked = checked,
                onCheckedChange = { onCategories(if (it) categories + c.id else categories - c.id) }
            )
            Column(Modifier.weight(1f)) {
                Text(c.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    c.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (SponsorBlock.Category.values().none { it.id in categories }) {
        Text(
            "Nothing selected — nothing will be skipped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}
