package io.pickwick.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The kid-facing search field, shown only after the header's search icon is
 * tapped (it costs a full row of home-screen space, so it stays collapsed by
 * default). Whitelist-scoped — it only ever searches the family's indexed
 * channels, so there is no "whole of YouTube" to leak into.
 *
 * No mic, no button: the IME's search action submits, which is also what the
 * Google TV on-screen keyboard offers on its enter key.
 */
@Composable
internal fun SearchField(
    onSearch: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        placeholder = { Text("Search your channels") },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = { if (query.isNotBlank()) onSearch(query.trim()) }
        )
    )
}
