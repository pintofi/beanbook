package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import io.github.pintofi.beanbook.core.domain.ViewRequest
import io.github.pintofi.beanbook.core.domain.AmountBasis
import androidx.compose.foundation.layout.FlowRow
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
 * opens (period, text, tags and links, amount and account), and the search box beside it. Period, text, tags and
 * amounts are asked of Fava in the engine; the search and the account name narrow what is listed.
 */

/** How many of the filters are on: period, text, tags, amounts (one) and account. The search has its own box. */
@Composable
internal fun activeFilterCount(): Int {
    val filter = ledgerFilter().filter
    val view = filter.view
    return listOf(
        view.time, view.text, view.tags, view.amountMin + view.amountMax, filter.account,
    ).count { it.isNotBlank() }
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

/**
 * The search box, the Filters button beside it, what is on, and the period: the top of Home and
 * Journal. Balances has its own box (it searches accounts) and uses the same button, line and sheet.
 */
@Composable
internal fun EntryFilterBar(
    snapshot: LedgerSnapshot?,
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
                active = activeFilterCount(),
                onClick = { filtering = true },
                // Lined up with the field's box, under the floating label's notch.
                modifier = Modifier.padding(top = Measure.snug),
            )
        }
        LedgerFilterBar(inset = inset)
    }
    if (filtering) {
        FiltersSheet(
            view = shared.filter.view,
            onView = shared::setView,
            account = shared.filter.account,
            onAccount = shared::setAccount,
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
    val describe = if (active == 0) "Filters: period, text, tags, amount and account" else "Filters, $active on"
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

/**
 * Every filter, in one sheet: the same on Home, Journal and Balances. Period, text, tags and links and an amount
 * range are asked of Fava in the engine and applied when the sheet closes, because a half-typed filter such as
 * `#trip,` is an error until it is finished. The account name narrows what is listed.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
)
@Composable
internal fun FiltersSheet(
    view: ViewRequest,
    onView: (ViewRequest) -> Unit,
    account: String,
    onAccount: (String) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    tagSuggestions: List<String>,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(stateSaver = ViewRequestSaver) { mutableStateOf(view) }
    var accountDraft by rememberSaveable { mutableStateOf(account) }
    val close = {
        onView(
            draft.copy(
                time = draft.time.trim(), text = draft.text.trim(), tags = draft.tags.trim(),
                amountMin = draft.amountMin.trim(), amountMax = draft.amountMax.trim(),
            ),
        )
        onAccount(accountDraft.trim())
        onDismiss()
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = close,
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
            FolioNote("Home, Journal and Balances share these filters.")
            FolioHeading("Period")
            FilterField(
                value = draft.time,
                onValueChange = { draft = draft.copy(time = it) },
                label = "Time",
                describe = "Filter by period",
                placeholder = "2020-03",
            )
            FolioNote(
                "Fava's time filter: 2020, 2020-03, 2020-Q1, FY2020, month-1 or 2020-01-15 to 2020-03-10. " +
                    "A period shows what each account held at its end, with earlier history carried in.",
            )
            FolioHeading("Text, tags and links")
            FilterField(
                value = draft.text,
                onValueChange = { draft = draft.copy(text = it) },
                label = "Text",
                describe = "Filter by text",
                placeholder = "grocer",
            )
            FolioNote("Searches payees, descriptions and notes. A comma means or.")
            SuggestingField(
                value = draft.tags,
                onValueChange = { draft = draft.copy(tags = it) },
                label = "Tags and links",
                suggestions = tagSuggestions,
                describe = "Filter by tags or links",
                placeholder = "#trip -#reimbursed, ^invoice-4411",
                modifier = Modifier.fillMaxWidth(),
            )
            FolioNote(
                "#tag and ^link, a minus to leave out, a comma for or, parentheses to group, any(account:Food).",
            )
            FolioHeading("Amount")
            Row(horizontalArrangement = Arrangement.spacedBy(Measure.snug)) {
                AmountBox(draft.amountMin, { draft = draft.copy(amountMin = it) }, "At least", Modifier.weight(1f))
                AmountBox(draft.amountMax, { draft = draft.copy(amountMax = it) }, "At most", Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Measure.snug)) {
                AmountBasis.entries.forEach { basis ->
                    androidx.compose.material3.FilterChip(
                        selected = draft.amountBasis == basis,
                        onClick = { draft = draft.copy(amountBasis = basis) },
                        label = { Text(basis.label) },
                    )
                }
            }
            FolioNote(
                "A posting at least and at most this much. Units is Fava's own amount filter; at cost and at " +
                    "market value weigh each posting on that basis first.",
            )
            FolioHeading("Account")
            FilterField(
                value = accountDraft,
                onValueChange = { accountDraft = it },
                label = "Account",
                describe = "Filter by account",
                placeholder = "Expenses:Food",
            )
            FolioNote(
                "Lists only entries with a posting to an account whose name contains this, and Balances lists only " +
                    "those accounts. Figures stay as they are.",
            )
            FolioHeading("Search")
            FilterField(
                value = query,
                onValueChange = onQuery,
                label = "Search entries",
                describe = "Search entries in the sheet",
                placeholder = "date, payee, account, amount",
            )
            FolioNote(
                "Narrows the entries listed by any word, as the box above does. Then figures are worked out again " +
                    "from those entries, at cost.",
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Measure.gap),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.Button(onClick = close) { Text("Done") }
            }
        }
    }
}

@Composable
private fun FilterField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    describe: String,
    placeholder: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = describe },
    )
}

@Composable
private fun AmountBox(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier) {
    // The shared number box. A figure here is a size, so a minus is refused by the engine with a reason.
    AmountField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier.semantics { contentDescription = "Amount $label" },
    )
}

/** Keeps the sheet's fields across rotation. */
private val ViewRequestSaver = androidx.compose.runtime.saveable.listSaver<ViewRequest, String>(
    save = { listOf(it.time, it.text, it.tags, it.amountMin, it.amountMax, it.amountBasis.name) },
    restore = { ViewRequest(it[0], it[1], it[2], it[3], it[4], AmountBasis.valueOf(it[5])) },
)

/** The sheet's own scroll, so a tall keyboard never hides the last field. */
@Composable
private fun Modifier.androidxVerticalScroll(): Modifier =
    this.verticalScroll(androidx.compose.foundation.rememberScrollState())
