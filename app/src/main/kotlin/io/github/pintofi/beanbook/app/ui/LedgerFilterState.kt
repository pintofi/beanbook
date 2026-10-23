package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.core.domain.EntryFilter

/**
 * The one filter that Home, Journal and Balances share.
 *
 * Held by the application rather than by a screen, so it survives switching tabs and turning the
 * phone, and is gone when the app is. Not written to disk: a filter somebody forgot about would
 * make the first look at the books after a restart a look at part of them.
 */
class LedgerFilterState {
    var filter by mutableStateOf(EntryFilter())
        private set

    fun setQuery(query: String) {
        filter = filter.copy(query = query)
    }

    fun setTags(tags: String) {
        filter = filter.copy(tags = tags)
    }

    fun clear() {
        filter = EntryFilter()
    }
}

/**
 * The filter the app provides. Null where nothing provides one (a screen on its own, as in a UI
 * test), and [ledgerFilter] then gives that screen a filter of its own. A default shared by
 * everything that reads it would carry one screen's search into the next one's.
 */
val LocalLedgerFilter = staticCompositionLocalOf<LedgerFilterState?> { null }

/** The app's shared filter, or a private one for a screen shown without it. */
@Composable
fun ledgerFilter(): LedgerFilterState =
    LocalLedgerFilter.current ?: androidx.compose.runtime.remember { LedgerFilterState() }

/**
 * What the three screens are filtered by, one chip each with a cross, and a way to drop them all.
 *
 * Shown at the top of every screen that obeys the filter, so a narrowed Home or Balances never
 * looks like the whole ledger. Draws nothing when there is no filter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerFilterBar(modifier: Modifier = Modifier, inset: androidx.compose.ui.unit.Dp = Measure.gutter) {
    val state = ledgerFilter()
    val filter = state.filter
    if (!filter.isActive) return
    // One block, so a parent that spaces its children out does not pull the chips and the
    // sentence beneath them apart.
    androidx.compose.foundation.layout.Column(modifier = modifier.padding(horizontal = inset)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (filter.query.isNotBlank()) {
                val words = filter.query.trim()
                FilterChipWithCross("Search: $words", "Remove the search $words") { state.setQuery("") }
            }
            if (filter.tags.isNotBlank()) {
                val terms = filter.tags.trim()
                FilterChipWithCross("Tags: $terms", "Remove the tags and links $terms") { state.setTags("") }
            }
            TextButton(
                onClick = state::clear,
                modifier = Modifier.semantics { contentDescription = "Remove every filter" },
            ) { Text("Clear all") }
        }
        Text(
            "Home, Journal and Balances show only the matching entries. Balances are at cost while this is on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Measure.snug),
        )
    }
}

@Composable
private fun FilterChipWithCross(text: String, describe: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(text, maxLines = 1) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
        modifier = Modifier.semantics { contentDescription = describe },
    )
}
