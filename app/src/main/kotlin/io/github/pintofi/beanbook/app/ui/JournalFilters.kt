package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.pintofi.beanbook.app.ui.theme.Measure

/**
 * The Journal's three filters: errors, pending, flagged (B95).
 *
 * Chips, not a connected choice: these combine -- pending *and* flagged is a real question --
 * and chips are what Material uses for filters that combine. What Expressive changes here is
 * the mark: a chosen filter carries a check as well as its tint, so "on" is said in shape and
 * not in colour alone. The row scrolls sideways rather than wrapping, so at 200% text every
 * chip stays whole and reachable.
 *
 * The descriptions say the state and what a tap will do, and are unchanged from B95.
 */
@Composable
internal fun JournalFilterChips(
    onlyErrors: Boolean,
    onErrors: (Boolean) -> Unit,
    onlyPending: Boolean,
    onPending: (Boolean) -> Unit,
    onlyFlagged: Boolean,
    onFlagged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Measure.snug),
    ) {
        JournalFilterChip(
            label = "Errors",
            on = onlyErrors,
            onChange = onErrors,
            describeOn = "Showing only entries with errors. Tap to show every entry",
            describeOff = "Show only entries with errors",
        )
        JournalFilterChip(
            label = "Pending",
            on = onlyPending,
            onChange = onPending,
            describeOn = "Showing pending entries. Tap to stop filtering by pending",
            describeOff = "Show only pending entries, the ones marked with an exclamation mark",
        )
        JournalFilterChip(
            label = "Flagged",
            on = onlyFlagged,
            onChange = onFlagged,
            describeOn = "Showing flagged entries. Tap to stop filtering by flag",
            describeOff = "Show only entries with a flag other than the plain star or the pending mark",
        )
    }
}

@Composable
private fun JournalFilterChip(
    label: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
    describeOn: String,
    describeOff: String,
) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(label) },
        leadingIcon = if (on) {
            {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
        modifier = Modifier.semantics {
            contentDescription = if (on) describeOn else describeOff
        },
    )
}
