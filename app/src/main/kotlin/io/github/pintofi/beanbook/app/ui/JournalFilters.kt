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

/** What the Journal is narrowed to. One at a time: errors, pending, or entries with some other flag. */
internal enum class JournalFilter { ERRORS, PENDING, OTHER_FLAGS }

/**
 * The Journal's filter: show everything, or only entries with errors, only pending ones (`!`), or
 * only those with another flag (`P`, `S`, `T` and the like).
 *
 * One choice, as chips that act like radio buttons: choosing one replaces the last, and choosing
 * the chosen one shows every entry again. A chosen filter carries a check as well as its tint, so
 * "on" is said in shape and not in colour alone. The row scrolls sideways rather than wrapping, so
 * at 200% text every chip stays whole and reachable.
 *
 * The descriptions say the state and what a tap will do.
 */
@Composable
internal fun JournalFilterChips(
    chosen: JournalFilter?,
    onChoose: (JournalFilter?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Measure.snug),
    ) {
        JournalFilterChip(
            label = "Errors",
            on = chosen == JournalFilter.ERRORS,
            onChange = { onChoose(if (it) JournalFilter.ERRORS else null) },
            describeOn = "Showing only entries with errors. Tap to show every entry",
            describeOff = "Show only entries with errors",
        )
        JournalFilterChip(
            label = "Pending",
            on = chosen == JournalFilter.PENDING,
            onChange = { onChoose(if (it) JournalFilter.PENDING else null) },
            describeOn = "Showing pending entries. Tap to show every entry",
            describeOff = "Show only pending entries, the ones with the flag !",
        )
        JournalFilterChip(
            label = "Other flags",
            on = chosen == JournalFilter.OTHER_FLAGS,
            onChange = { onChoose(if (it) JournalFilter.OTHER_FLAGS else null) },
            describeOn = "Showing entries with another flag. Tap to show every entry",
            describeOff = "Show only entries with a flag other than * and !",
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
