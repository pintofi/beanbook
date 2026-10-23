package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasure
import io.github.pintofi.beanbook.core.domain.AccountNode
import io.github.pintofi.beanbook.core.domain.AccountTree
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.domain.LedgerSummary
import io.github.pintofi.beanbook.core.domain.Valuation

// The account balances surface, split out of LedgerScreens.kt.

@Composable
fun BalancesScreen(
    snapshot: LedgerSnapshot,
    modifier: Modifier = Modifier,
    /** The period the figures cover, as Fava's time filter expresses it. */
    dateFrom: String = "",
    dateTo: String = "",
    onDateRange: (String, String) -> Unit = { _, _ -> },
    /** Tags and links the ledger has, for the filter sheet; the snapshot's own when not given. */
    tagSuggestions: List<String>? = null,
) {
    // What *this* ledger calls them. A file with `option "name_expenses" "Ausgaben"`
    // used to get five headings with nothing under any of them.
    val roots = snapshot.accountNames.roots
    var filter by rememberSaveable { mutableStateOf("") }
    // The search and tags are the app's one shared filter, already applied to the snapshot this
    // screen is given; they are shown and changed here, not computed here.
    val shared = ledgerFilter()
    val tagFilter = shared.filter.tags
    // Remembered across launches, not just recompositions. Somebody who keeps their
    // books at market value sets that once; asking again on every start is the app forgetting
    // something it was told.
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { ValuationPreference(context) }
    val basis by store.flow.collectAsState()

    // Tags and links, as Fava's filter bar takes them: `#trip`, `^invoice-4411`, or
    // both. The totals below then come from the matching transactions only.
    val offeredTags = tagSuggestions ?: remember(snapshot.journal) {
        (snapshot.journal.flatMap { it.tags }.distinct().sorted().map { "#$it" } +
            snapshot.journal.flatMap { it.links }.distinct().sorted().map { "^$it" })
    }
    var filtering by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // The filters, folded: four stacked controls took 45% of the first
        // screen before any balance was visible (device audit 2026-10-10). The account filter
        // stays on the page -- it is the one typed while reading -- and the period and the tags
        // move behind one button that says how many are on.
        val extra = listOf(dateFrom, dateTo, tagFilter, shared.filter.query).count { it.isNotBlank() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Measure.gutter, vertical = Measure.snug),
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
            verticalAlignment = Alignment.Top,
        ) {
            SuggestingField(
                value = filter,
                onValueChange = { filter = it },
                label = "Filter accounts",
                suggestions = snapshot.accounts.map { it.name },
                describe = "Filter accounts by name",
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            FiltersButton(
                active = extra,
                onClick = { filtering = true },
                // Lined up with the field's box, under the floating label's notch.
                modifier = Modifier.padding(top = Measure.snug),
            )
        }
        // Said plainly, because a balance for part of a year looks exactly like a balance for
        // all of it and is a different number.
        LedgerFilterBar()
        if (dateFrom.isNotBlank() || dateTo.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Measure.gutter, end = Measure.tight),
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

        // A connected choice, not chips. These are one basis at a time -- the same figures
        // valued three ways -- and a connected row is what Material uses to say "pick one
        // of these". Chips read as filters that combine, which is the opposite.
        ConnectedChoiceRow(
            options = Valuation.entries,
            selected = basis,
            onSelect = { store.write(it) },
            label = { it.label },
            describe = { "Show balances ${it.label}" },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Measure.gutter)
                .padding(bottom = Measure.snug),
        )

        if (filtering) {
            BalanceFiltersSheet(
                dateFrom = dateFrom,
                dateTo = dateTo,
                onDateRange = onDateRange,
                tagFilter = tagFilter,
                onTagFilter = shared::setTags,
                query = shared.filter.query,
                onQuery = shared::setQuery,
                tagSuggestions = offeredTags,
                onDismiss = { filtering = false },
            )
        }


        val needle = filter.trim()
        // The engine's balances, or -- when the shared filter is on -- the matching entries' own
        // totals, which the snapshot already carries (see `LedgerSnapshot.filteredBy`).
        // A closed account with nothing in it is history, not a balance; one that still holds
        // something stays, since that is a mistake worth seeing.
        val source = snapshot.accounts.filterNot { it.isClosed && it.positions.all { p -> p.number.signum() == 0 } }

        // Which branches are open, by full account name. Saved, so rotating the phone or
        // coming back from another surface does not fold the tree the user just opened.
        val expanded = rememberSaveable(
            saver = listSaver<SnapshotStateList<String>, String>(
                save = { it.toList() },
                restore = { it.toMutableStateList() },
            ),
        ) { roots.toMutableStateList() }

        // Room at the end for the floating action button, which draws over the list rather
        // than beside it. Without this the last row's figures sit under the FAB — the cost a
        // FAB always carries, and one the surfaces it appears on have to pay.
        LazyColumn(
            modifier = Modifier.fillMaxSize().readableMeasure(),
            contentPadding = PaddingValues(bottom = Measure.actionClearance),
        ) {
            if (snapshot.hasErrors) {
                item { ErrorBanner(snapshot.errors) }
            }
            if (shared.filter.isActive) {
                item(key = "tagNotice") {
                    Text(
                        text = if (basis == Valuation.VALUE) {
                            "Totals of the matching entries. Market value is " +
                                "shown at cost here, because prices are not part of a filtered set."
                        } else {
                            "Totals of the matching entries."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Measure.gutter, vertical = Measure.snug),
                    )
                }
            }
            roots.forEach { root ->
                val accounts = source
                    .filter { it.name == root || it.name.startsWith("$root:") }
                    .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
                if (accounts.isEmpty()) return@forEach

                val tree = AccountTree.of(accounts, root)
                // A filter is a search, and a search that hides its own results behind a
                // fold has not answered anything: while one is typed every branch is open.
                val open = needle.isNotEmpty() || shared.filter.isActive
                val rows = mutableListOf<AccountNode>()
                fun collect(nodes: List<AccountNode>) {
                    nodes.forEach { node ->
                        rows += node
                        if (open || node.name in expanded) collect(node.children)
                    }
                }
                collect(tree)

                item(key = "header-$root") {
                    // The header totals the accounts *shown*, not the whole root: a total
                    // that ignored the filter would be a different number from the rows
                    // under it, which reads as an arithmetic error.
                    RootHeader(
                        root = root,
                        totals = if (needle.isEmpty() && !shared.filter.isActive) {
                            snapshot.totalFor(root, basis::of)
                        } else {
                            LedgerSummary.sumByCurrency(accounts.flatMap { basis.of(it) })
                        },
                        expanded = open || root in expanded,
                        onToggle = { if (root in expanded) expanded.remove(root) else expanded.add(root) },
                        format = snapshot.format,
                        operating = snapshot.operatingCurrencies,
                    )
                }
                if (open || root in expanded) {
                    items(rows, key = { it.name }) { node ->
                        AccountRow(
                            // No placement animation: opening a branch slid the rows
                            // below it down while the new rows appeared at once in the space
                            // they were still leaving, so the two overlapped mid-move.
                            node = node,
                            basis = basis,
                            expanded = open || node.name in expanded,
                            onToggle = {
                                if (node.name in expanded) {
                                    expanded.remove(node.name)
                                } else {
                                    expanded.add(node.name)
                                }
                            },
                            format = snapshot.format,
                            operating = snapshot.operatingCurrencies,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The head of one account tree: its name, what the whole tree holds, and the rule it hangs
 * from.
 *
 * A band rather than a card, and square, because it is data: a heading in a ledger, printed
 * a shade darker than the page. The leading rule is the same 2dp the rest of the app hangs
 * its sections from, so the five trees read as five sections of one page rather than as five
 * grey bars.
 */
@Composable
private fun RootHeader(
    root: String,
    totals: List<Amount>,
    expanded: Boolean,
    onToggle: () -> Unit,
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat,
    operating: List<String>,
) {
    val rule = BeancountTheme.ledger.rule
    // The root's total, double-ruled: the line a bookkeeper draws under a column that is
    // closed. Shown with the sign this ledger asks for: Fava's
    // `invert-income-liabilities-equity` turns income, liabilities and equity the way a person
    // reads them.
    //
    // Three commodities and "+N more": in Units the Assets band listed
    // about twenty, one heading taller than the screen. The rows under it list them all.
    val capped = remember(totals, root, format, operating) {
        io.github.pintofi.beanbook.core.domain.HeadlineFigures.of(
            totals.map { format.signed(it, root) },
            operating,
        )
    }
    LabelAndFigures(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .drawBehind {
                drawRect(
                    color = rule,
                    topLeft = Offset(Measure.gutter.toPx(), 0f),
                    size = Size(width = Measure.rule.toPx(), height = size.height),
                )
            }
            .padding(
                start = Measure.gutter + Measure.rule + Measure.gap,
                end = Measure.gutter,
                top = Measure.gap,
                bottom = Measure.gap,
            )
            .semantics {
                contentDescription =
                    if (expanded) "$root, expanded. Tap to collapse" else "$root, collapsed. Tap to expand"
            },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DisclosureChevron(expanded, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    root,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = Measure.snug),
                )
            }
        },
        figures = {
            FigureColumn(
                figures = capped,
                format = format,
                style = LedgerType.figure,
                modifier = if (capped.shown.isEmpty()) Modifier else Modifier.totalRule(rule),
            )
        },
    )
}

/**
 * A label and its figures: side by side with a gap between them, or -- at large text, where
 * the two crowded each other into "Opening-Balances51,234.56 PKR" (device audit 2026-10-10) --
 * the figures on their own line under the label, still against the end edge.
 */
@Composable
private fun LabelAndFigures(
    modifier: Modifier,
    label: @Composable () -> Unit,
    figures: @Composable () -> Unit,
) {
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > LARGE_TEXT
    if (large) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Measure.tight)) {
            label()
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) { figures() }
        }
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = Measure.gap),
            ) { label() }
            figures()
        }
    }
}

/** The text scale above which a label and its figures stop sharing a line. */
private const val LARGE_TEXT = 1.3f

/**
 * Figures stacked at the end of a row, capped with "+N more", or a dash when there are none --
 * the same dash for an empty root and an empty account, so "nothing here" always looks the same
 * (device audit 2026-10-10: one account showed no figure at all where others showed a dash).
 */
@Composable
private fun FigureColumn(
    figures: io.github.pintofi.beanbook.core.domain.HeadlineFigures,
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        if (figures.shown.isEmpty()) {
            Text(
                "—",
                style = style.merge(io.github.pintofi.beanbook.app.ui.theme.AmountTextStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "No balance" },
            )
        } else {
            figures.shown.forEach { AmountText(it, format = format, style = style) }
            if (figures.more > 0) {
                Text(
                    "+${figures.more} more",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = if (figures.more == 1) {
                            "and 1 more commodity"
                        } else {
                            "and ${figures.more} more commodities"
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AccountRow(
    node: AccountNode,
    basis: Valuation,
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onToggle: () -> Unit,
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat,
    operating: List<String>,
) {
    // A parent shows what its whole branch holds, so folding it hides rows rather than
    // money. A leaf shows its own balance, which is the same figure by definition. A parent
    // is capped like a heading (three and "+N more"): its children list the rest. A leaf
    // shows every commodity it holds, because there is nowhere further to look.
    val amounts = node.subtotal(basis::of).map { format.signed(it, node.name) }
    val figures = remember(amounts, operating, node.hasChildren) {
        io.github.pintofi.beanbook.core.domain.HeadlineFigures.of(
            amounts,
            operating,
            max = if (node.hasChildren) {
                io.github.pintofi.beanbook.core.domain.HeadlineFigures.DEFAULT_LINES
            } else {
                Int.MAX_VALUE
            },
        )
    }
    val spoken = buildString {
        append(node.name)
        if (node.hasChildren) {
            append(if (expanded) ", expanded. Tap to collapse" else ", collapsed. Tap to expand")
        }
    }
    LabelAndFigures(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (node.hasChildren) {
                    Modifier.clickable(role = Role.Button, onClick = onToggle)
                } else {
                    Modifier
                }
            )
            .heightIn(min = 48.dp)
            // Indented from the rule its tree hangs from, one step per level, so depth is
            // read off the page rather than off the account name.
            .padding(
                start = Measure.gutter + Measure.rule + Measure.gap + (node.depth * 12).dp,
                end = Measure.gutter,
                top = Measure.snug,
                bottom = Measure.snug,
            )
            .semantics { contentDescription = spoken },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.hasChildren) {
                    // Tinted, not left to the icon default: the default is black, and a
                    // black chevron on the dark and pure-black themes could not be seen at all.
                    DisclosureChevron(expanded, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The leaf alone. A ledger with both Expenses:Rent and Expenses:Home:Rent used
                // to show two rows called "Rent" with different figures and nothing to tell them
                // apart, because a parent with no balance of its own was not listed and the
                // indent had no row to sit under. Every ancestor is a row now, so the
                // indent says which "Rent" this is. Breakable at its hyphens, so a long leaf
                // wraps between words rather than inside one.
                Text(
                    text = breakableAccount(node.leaf),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = if (node.hasChildren) Measure.tight else 28.dp),
                )
            }
        },
        figures = { FigureColumn(figures = figures, format = format, style = LedgerType.row) },
    )
    LedgerHairline(Modifier.padding(start = Measure.gutter + Measure.rule + Measure.gap))
}

/**
 * The button that opens the period and tag filters, saying how many are on.
 *
 * Tonal while any is on, outlined while none is, and the count is in the words as well as on
 * the badge: "Filters, 2 on".
 */
@Composable
private fun FiltersButton(active: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
private fun filterSummary(dateFrom: String, dateTo: String, tags: String): String {
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
private fun BalanceFiltersSheet(
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
                "Filter balances",
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
            FolioNote("A balance for part of a year is a different number from the balance for all of it.")
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
                            describe = "Filter balances by tags or links",
                            placeholder = "#trip ^invoice-4411",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            FolioNote("Totals then come from the entries with these tags or links only.")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Measure.gap),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.Button(onClick = onDismiss) { Text("Show balances") }
            }
        }
    }
}

/** The sheet's own scroll, so a tall keyboard never hides the last field. */
@Composable
private fun Modifier.androidxVerticalScroll(): Modifier =
    this.verticalScroll(androidx.compose.foundation.rememberScrollState())
