package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot

/*
 * The filters Home, Journal and Balances share, written once: the Filters button, the sheet it
 * opens (period, search, tags and links), and the line that says which period the figures cover.
 * The search and the tags are the app's one shared filter; the period is the engine's.
 */

/** How many of the filters are on: the period's two ends, the tags and the search. */
@Composable
internal fun activeFilterCount(dateFrom: String, dateTo: String): Int {
    val shared = ledgerFilter()
    return listOf(dateFrom, dateTo, shared.filter.tags, shared.filter.query).count { it.isNotBlank() }
}

/** Tags and links the ledger has, as Fava's filter bar takes them: `#trip` and `^invoice-4411`. */
@Composable
internal fun offeredTags(snapshot: LedgerSnapshot?, given: List<String>? = null): List<String> =
    given ?: remember(snapshot?.journal) {
        snapshot?.journal.orEmpty().let { journal ->
            journal.flatMap { it.tags }.distinct().sorted().map { "#$it" } +
                journal.flatMap { it.links }.distinct().sorted().map { "^$it" }
        }
    }

/** The period the figures are limited to, with a way to drop it. Draws nothing when there is none. */
@Composable
internal fun PeriodLine(
    dateFrom: String,
    dateTo: String,
    onDateRange: (String, String) -> Unit,
    inset: Dp = Measure.gutter,
) {
    if (dateFrom.isBlank() && dateTo.isBlank()) return
    // Said plainly, because a balance for part of a year looks exactly like a balance for all of
    // it and is a different number.
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = inset, end = Measure.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            filterSummary(dateFrom, dateTo, ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { onDateRange("", "") },
            modifier = Modifier.semantics { contentDescription = "Clear the period" },
        ) { Text("Clear") }
    }
}

/**
 * The search box, the Filters button beside it, what is on, and the period: the top of Home and
 * Journal. Balances has its own box (it searches accounts) and uses the same button, line and sheet.
 */
@Composable
internal fun EntryFilterBar(
    snapshot: LedgerSnapshot?,
    dateFrom: String,
    dateTo: String,
    onDateRange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    inset: Dp = Measure.gutter,
    tagSuggestions: List<String>? = null,
) {
    val shared = ledgerFilter()
    val query = shared.filter.query
    var filtering by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = inset, vertical = Measure.snug),
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = shared::setQuery,
                label = { Text("Search entries") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { shared.setQuery("") },
                            modifier = Modifier.semantics { contentDescription = "Clear the search" },
                        ) { Icon(Icons.Filled.Close, contentDescription = null) }
                    }
                },
                shape = BeanbookShape.control,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Search entries by date, payee, description or account" },
            )
            FiltersButton(
                active = activeFilterCount(dateFrom, dateTo),
                onClick = { filtering = true },
                // Lined up with the field's box, under the floating label's notch.
                modifier = Modifier.padding(top = Measure.snug),
            )
        }
        LedgerFilterBar(inset = inset)
        PeriodLine(dateFrom, dateTo, onDateRange, inset)
    }
    if (filtering) {
        FiltersSheet(
            dateFrom = dateFrom,
            dateTo = dateTo,
            onDateRange = onDateRange,
            tagFilter = shared.filter.tags,
            onTagFilter = shared::setTags,
            query = query,
            onQuery = shared::setQuery,
            tagSuggestions = offeredTags(snapshot, tagSuggestions),
            onDismiss = { filtering = false },
        )
    }
}

/**
 * The button that opens the period and tag filters, saying how many are on.
 *
 * Tonal while any is on, outlined while none is, and the count is in the words as well as on
 * the badge: "Filters, 2 on".
 */
@Composable
internal fun FiltersButton(active: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val describe = if (active == 0) "Filters: period, tags and links" else "Filters, $active on"
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Filled.Tune,
            contentDescription = null,
            modifier = Modifier.padding(end = Measure.tight),
        )
        Text(if (active == 0) "Filters" else "Filters · $active")
    }
    if (active == 0) {
        androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            shape = io.github.pintofi.beanbook.app.ui.theme.BeanbookShape.control,
            modifier = modifier
                .heightIn(min = 56.dp)
                .semantics { contentDescription = describe },
            content = content,
        )
    } else {
        androidx.compose.material3.FilledTonalButton(
            onClick = onClick,
            shape = io.github.pintofi.beanbook.app.ui.theme.BeanbookShape.control,
            modifier = modifier
                .heightIn(min = 56.dp)
                .semantics { contentDescription = describe },
            content = content,
        )
    }
}

/** "From 2026-01-01 to 2026-06-30 · #trip": what the figures below are limited to. */
internal fun filterSummary(dateFrom: String, dateTo: String, tags: String): String {
    val period = listOfNotNull(
        dateFrom.takeIf { it.isNotBlank() }?.let { "from $it" },
        dateTo.takeIf { it.isNotBlank() }?.let { "to $it" },
    ).joinToString(" ")
    val parts = listOfNotNull(
        period.takeIf { it.isNotEmpty() }?.let { "Showing $it" },
        tags.trim().takeIf { it.isNotEmpty() }?.let { "only $it" },
    )
    return parts.joinToString(" · ")
}

/**
 * The period and the tags, in a sheet. The two bounds are what Fava puts
 * above its own reports: they go to the engine and narrow what is computed.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun FiltersSheet(
    dateFrom: String,
    dateTo: String,
    onDateRange: (String, String) -> Unit,
    tagFilter: String,
    onTagFilter: (String) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    tagSuggestions: List<String>,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = io.github.pintofi.beanbook.app.ui.theme.BeanbookShape.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .androidxVerticalScroll()
                .navigationBarsPadding()
                .padding(horizontal = Measure.gutter)
                .padding(bottom = Measure.section),
            verticalArrangement = Arrangement.spacedBy(Measure.snug),
        ) {
            Text(
                "Filters",
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            FolioHeading("Period")
            FolioGroup {
                item {
                    FolioBody {
                        DateField(
                            value = dateFrom,
                            onValueChange = { onDateRange(it, dateTo) },
                            label = "From",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DateField(
                            value = dateTo,
                            onValueChange = { onDateRange(dateFrom, it) },
                            label = "To",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (dateFrom.isNotBlank() || dateTo.isNotBlank()) {
                            OutlinedButton(
                                onClick = { onDateRange("", "") },
                                modifier = Modifier.semantics { contentDescription = "Show every date again" },
                            ) { Text("Clear dates") }
                        }
                    }
                }
            }
            FolioNote(
                "The period narrows Home, Journal and Balances. A balance for part of a year is a different " +
                    "number from the balance for all of it.",
            )
            FolioHeading("Search")
            FolioGroup {
                item {
                    FolioBody {
                        OutlinedTextField(
                            value = query,
                            onValueChange = onQuery,
                            label = { Text("Search entries") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "Search entries by date, payee, description or account"
                                },
                        )
                    }
                }
            }
            FolioNote("Home, Journal and Balances share this search and the tags below.")
            FolioHeading("Tags and links")
            FolioGroup {
                item {
                    FolioBody {
                        SuggestingField(
                            value = tagFilter,
                            onValueChange = onTagFilter,
                            label = "Tags or links",
                            suggestions = tagSuggestions,
                            describe = "Filter by tags or links",
                            placeholder = "#trip ^invoice-4411",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            FolioNote("Figures then come from the entries with these tags or links only.")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Measure.gap),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}


/** The sheet's own scroll, so a tall keyboard never hides the last field. */
@Composable
private fun Modifier.androidxVerticalScroll(): Modifier =
    this.verticalScroll(androidx.compose.foundation.rememberScrollState())
