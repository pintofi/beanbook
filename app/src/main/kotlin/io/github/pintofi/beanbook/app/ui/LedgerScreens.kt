package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.pintofi.beanbook.core.domain.LedgerSummary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasure
import io.github.pintofi.beanbook.app.ui.theme.AmountTextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.toMutableStateList
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.AccountNode
import io.github.pintofi.beanbook.core.domain.AccountTree
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.JournalErrors
import io.github.pintofi.beanbook.core.domain.LedgerError
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The native surfaces: the account balances list and the journal
 * (docs/distribution.md §4.4). Both read from a [LedgerSnapshot] that Beancount produced,
 * so the app displays accounting facts rather than computing its own.
 */

/**
 * Amounts are announced with sign and currency in words, and carry an explicit sign in
 * text — never colour alone (docs/ux.md §6).
 */
private fun Amount.accessibleDescription(
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat =
        io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN,
): String {
    val sign = when (number.signum()) {
        -1 -> "minus "
        else -> ""
    }
    // The same digits that are on the screen. Spoken from the raw value, a figure the file
    // asks to be shown to two places was read out to five, so what TalkBack said and what
    // the screen showed were different numbers.
    return "$sign${format.format(Amount(number.abs(), currency))}"
}

/**
 * One figure, in the ledger's own type.
 *
 * [signed] colours it by direction, and defaults to *off*, which is the part worth
 * explaining. Fava — whose reports this app also shows, so the two are seen together —
 * renders balances in the ordinary text colour (`td.num { color: var(--text-color) }`) and
 * reserves colour for a *difference* (`.tree-table .diff.negative`). That is the right rule
 * for double-entry rather than a stylistic one: an Income or Equity account carries a credit
 * balance and shows as negative all the time, so colouring balances by sign would paint the
 * healthiest accounts in the ledger as though something were wrong with them.
 *
 * So a *movement* is coloured — a posting is money going one way or the other, and the
 * direction is the point of it — and a *balance* is not.
 *
 * The colours stay this app's green and amber rather than Fava's green and red. That choice
 * is already argued in LedgerColours: red and green alone are the classic accessibility
 * failure, and the sign, not the colour, is what carries the meaning here.
 */
/**
 * Money, drawn one way.
 *
 * Everything that shows a figure goes through here so the fixed-width face and the trailing
 * alignment are properties of *money in this app*, not of whichever screen remembered. Home
 * used to build its own strings in the proportional default face, which is precisely the
 * column the Appearance preview points at when it says decimal points line up.
 *
 * [text] rather than an `Amount` because a figure is sometimes not one: Home shows an em dash
 * for "nothing to say" and bullets when amounts are hidden, and those should sit in the same
 * column, in the same face, as the numbers they stand in for.
 */
@Composable
internal fun MoneyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    // Applied after the merge, because `merge` lets the argument win and the argument here is
    // AmountTextStyle -- so a caller passing `style = ....copy(textAlign = Start)` was
    // silently overridden back to End. A figure standing alone under its own label reads
    // better beneath it than pushed to the far edge of the card; a figure in a column does
    // not. Both are money, so both keep the face, and only the alignment differs.
    textAlign: TextAlign = TextAlign.End,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.merge(AmountTextStyle).copy(textAlign = textAlign),
        color = color,
    )
}

@Composable
internal fun AmountText(
    amount: Amount,
    modifier: Modifier = Modifier,
    signed: Boolean = false,
    /** How the open ledger asks its figures to be written (B59). */
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat =
        io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN,
    /** Which size of the figure face: a row in a list, or a figure heading a group. */
    style: TextStyle = LedgerType.row,
) {
    // The helper below existed from M6 and was never applied, so every amount was announced
    // as its raw text: TalkBack reads "-12.34 USD" by pronouncing the hyphen or dropping it,
    // which is the one thing ux.md 6 says must not happen. detekt found it as dead code
    // (docs/milestones/m14-part16.md).
    val spoken = amount.accessibleDescription(format)
    MoneyText(
        text = format.format(amount),
        modifier = modifier.semantics { contentDescription = spoken },
        style = style,
        color = when {
            !signed -> MaterialTheme.colorScheme.onSurface
            amount.number.signum() < 0 -> BeancountTheme.ledger.negative
            else -> BeancountTheme.ledger.positive
        },
    )
}

/**
 * What a posting was held or exchanged at, under its amount (B63).
 *
 * Beside the figure rather than in place of it: `-151.88 ASSF` and `{400.00 PKR} @ 483.0500
 * PKR` answer different questions, and a row that shows only the first cannot tell two lots
 * of the same fund apart or say what a sale realised. On its own line because a cost, a date,
 * a label and a price do not fit beside an account name on a phone.
 *
 * The date and label are part of the cost's identity -- they are what `{"avcoassf"}` selects
 * -- so they are shown as the file writes them rather than summarised away.
 */
@Composable
internal fun PostingLotText(
    posting: io.github.pintofi.beanbook.core.domain.JournalPosting,
    modifier: Modifier = Modifier,
    /** How the open ledger asks its figures to be written (B59). */
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat =
        io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN,
) {
    val text = buildString {
        posting.cost?.let { append(format.format(it)) }
        posting.price?.let {
            if (isNotEmpty()) append(' ')
            append("@ ").append(format.format(it))
        }
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = modifier.fillMaxWidth().semantics {
            contentDescription = buildString {
                posting.cost?.let {
                    append("held at ").append(it.amount.accessibleDescription(format))
                    it.date?.let { date -> append(", acquired ").append(date) }
                    it.label?.let { label -> append(", lot ").append(label) }
                }
                posting.price?.let {
                    if (isNotEmpty()) append("; ")
                    append("at ").append(it.accessibleDescription(format)).append(" each")
                }
            }
        },
    )
}

@Composable
fun BalancesScreen(
    snapshot: LedgerSnapshot,
    modifier: Modifier = Modifier,
    /** The period the figures cover, as Fava's time filter expresses it (B53). */
    dateFrom: String = "",
    dateTo: String = "",
    onDateRange: (String, String) -> Unit = { _, _ -> },
) {
    // What *this* ledger calls them (B70). A file with `option "name_expenses" "Ausgaben"`
    // used to get five headings with nothing under any of them.
    val roots = snapshot.accountNames.roots
    var filter by rememberSaveable { mutableStateOf("") }
    var tagFilter by rememberSaveable { mutableStateOf("") }
    // Remembered across launches, not just recompositions (B53). Somebody who keeps their
    // books at market value sets that once; asking again on every start is the app forgetting
    // something it was told.
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { ValuationPreference(context) }
    var basis by remember { mutableStateOf(store.read()) }

    Column(modifier = modifier.fillMaxSize()) {
        // Two bounds and an account, which is the pair of filters Fava puts above its own
        // reports. The dates go to the engine and narrow what is computed; the account
        // filter narrows what is drawn from that.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Measure.gutter, vertical = Measure.snug),
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
        ) {
            DateField(
                value = dateFrom,
                onValueChange = { onDateRange(it, dateTo) },
                label = "From",
                modifier = Modifier.weight(1f),
            )
            DateField(
                value = dateTo,
                onValueChange = { onDateRange(dateFrom, it) },
                label = "To",
                modifier = Modifier.weight(1f),
            )
        }
        if (dateFrom.isNotBlank() || dateTo.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Measure.gutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    // Said plainly, because a balance for part of a year looks exactly like a
                    // balance for all of it and is a different number.
                    "Showing " + listOfNotNull(
                        dateFrom.takeIf { it.isNotBlank() }?.let { "from $it" },
                        dateTo.takeIf { it.isNotBlank() }?.let { "to $it" },
                    ).joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = { onDateRange("", "") },
                    modifier = Modifier.semantics { contentDescription = "Show every date again" },
                ) { Text("Clear dates") }
            }
        }

        SuggestingField(
            value = filter,
            onValueChange = { filter = it },
            label = "Filter accounts",
            suggestions = snapshot.accounts.map { it.name },
            describe = "Filter accounts by name",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Measure.gutter, vertical = Measure.snug),
        )

        // Tags and links, as Fava's filter bar takes them (B78): `#trip`, `^invoice-4411`, or
        // both. The totals below then come from the matching transactions only.
        val tagSuggestions = remember(snapshot.journal) {
            (snapshot.journal.flatMap { it.tags }.distinct().sorted().map { "#$it" } +
                snapshot.journal.flatMap { it.links }.distinct().sorted().map { "^$it" })
        }
        SuggestingField(
            value = tagFilter,
            onValueChange = { tagFilter = it },
            label = "Tags or links",
            suggestions = tagSuggestions,
            describe = "Filter balances by tags or links",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Measure.gutter)
                .padding(bottom = Measure.snug),
        )

        // Segmented buttons, not chips. These are one basis at a time -- the same figures
        // valued three ways -- and a segmented row is what Material uses to say "pick one
        // of these". Chips read as filters that combine, which is the opposite.
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Measure.gutter),
        ) {
            Valuation.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = basis == option,
                    onClick = { basis = option; store.write(option) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index,
                        Valuation.entries.size,
                        baseShape = BeanbookShape.control,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "Show balances ${option.label}"
                    },
                ) { Text(option.label) }
            }
        }
    

        val needle = filter.trim()
        val tagged = remember(tagFilter) {
            io.github.pintofi.beanbook.core.domain.TagBalances.Filter.parse(tagFilter)
        }
        // The engine's balances, or the matching transactions' own totals when a tag or link
        // is asked for. Computed here from the journal the snapshot already carries, so the
        // filter answers as it is typed with no second parse.
        val source = remember(snapshot.accounts, snapshot.journal, tagged) {
            if (tagged.isEmpty) {
                snapshot.accounts
            } else {
                io.github.pintofi.beanbook.core.domain.TagBalances.of(snapshot.journal, tagged)
            }
        }

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
        // FAB always carries, and one the surfaces it appears on have to pay (B26).
        LazyColumn(
            modifier = Modifier.fillMaxSize().readableMeasure(),
            contentPadding = PaddingValues(bottom = Measure.actionClearance),
        ) {
            if (snapshot.hasErrors) {
                item { ErrorBanner(snapshot.errors) }
            }
            if (!tagged.isEmpty) {
                item(key = "tagNotice") {
                    Text(
                        text = if (basis == Valuation.VALUE) {
                            "Totals of the entries with these tags or links. Market value is " +
                                "shown at cost here, because prices are not part of a filtered set."
                        } else {
                            "Totals of the entries with these tags or links."
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
                val open = needle.isNotEmpty() || !tagged.isEmpty
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
                        totals = if (needle.isEmpty() && tagged.isEmpty) {
                            snapshot.totalFor(root, basis::of)
                        } else {
                            LedgerSummary.sumByCurrency(accounts.flatMap { basis.of(it) })
                        },
                        expanded = open || root in expanded,
                        onToggle = { if (root in expanded) expanded.remove(root) else expanded.add(root) },
                        format = snapshot.format,
                    )
                }
                if (open || root in expanded) {
                    items(rows, key = { it.name }) { node ->
                        AccountRow(
                            // No placement animation (B77): opening a branch slid the rows
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
                        )
                    }
                }
            }
        }
    }
}

/** Which of an account's three figures to show (B14). */
enum class Valuation(val label: String) {
    UNITS("Units"),
    COST("At cost"),
    VALUE("Market value");

    fun of(account: AccountBalance): List<Amount> = when (this) {
        UNITS -> account.positions
        COST -> account.atCost
        VALUE -> account.atValue
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
) {
    val rule = BeancountTheme.ledger.rule
    Row(
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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            DisclosureChevron(expanded, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                root,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = Measure.snug),
            )
        }
        // The root's total, double-ruled (B87): the line a bookkeeper draws under a column
        // that is closed.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = if (totals.isEmpty()) Modifier else Modifier.totalRule(rule),
        ) {
            if (totals.isEmpty()) {
                Text("—", style = LedgerType.row, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // Shown with the sign this ledger asks for (B67). Fava's
                // `invert-income-liabilities-equity` turns income, liabilities and equity the
                // way a person reads them, and a file that asks for it and gets the raw
                // credit sign has every income figure on screen with a minus in front of it.
                totals.forEach {
                    AmountText(format.signed(it, root), format = format, style = LedgerType.figure)
                }
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
) {
    // A parent shows what its whole branch holds, so folding it hides rows rather than
    // money. A leaf shows its own balance, which is the same figure by definition.
    val amounts = node.subtotal(basis::of).map { format.signed(it, node.name) }
    val spoken = buildString {
        append(node.name)
        if (node.hasChildren) {
            append(if (expanded) ", expanded. Tap to collapse" else ", collapsed. Tap to expand")
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (node.hasChildren) {
                    Modifier.clickable(role = Role.Button, onClick = onToggle)
                } else {
                    Modifier
                }
            )
            // Indented from the rule its tree hangs from, one step per level, so depth is
            // read off the page rather than off the account name.
            .padding(
                start = Measure.gutter + Measure.rule + Measure.gap + (node.depth * 12).dp,
                end = Measure.gutter,
                top = Measure.snug,
                bottom = Measure.snug,
            )
            .semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (node.hasChildren) {
                // Tinted, not left to the icon default (B78): the default is black, and a black
                // chevron on the dark and pure-black themes could not be seen at all.
                DisclosureChevron(expanded, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The leaf alone. A ledger with both Expenses:Rent and Expenses:Home:Rent used to
            // show two rows called "Rent" with different figures and nothing to tell them
            // apart, because a parent with no balance of its own was not listed and the
            // indent had no row to sit under (D9). Every ancestor is a row now, so the indent
            // says which "Rent" this is.
            Text(
                text = node.leaf,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = if (node.hasChildren) Measure.tight else 28.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            amounts.forEach { AmountText(it, format = format) }
        }
    }
    LedgerHairline(Modifier.padding(start = Measure.gutter + Measure.rule + Measure.gap))
}

@Composable
fun JournalScreen(
    snapshot: LedgerSnapshot,
    modifier: Modifier = Modifier,
    onOpenEntry: (JournalEntry) -> Unit = {},
    /** Deletes the entries picked by holding one and tapping others (B89). */
    onDeleteEntries: (List<JournalEntry>) -> Unit = {},
) {
    // Picked by holding a row; then a tap adds or removes rather than opening (B89). Keyed by
    // file and line, which is what identifies an entry until the ledger is read again.
    var picked by remember(snapshot) { mutableStateOf(emptySet<String>()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    fun keyOf(entry: JournalEntry) = "${entry.file}:${entry.line}"
    val selecting = picked.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { picked = emptySet() }
    // How many rows are composed, not how many the engine returned. The engine now sends the
    // whole journal, and drawing 25 000 rows at once is neither necessary nor free -- but the
    // entries are already in memory, so growing this window costs a recomposition rather than
    // a re-parse. That is why it grows on scroll instead of behind a button: there is nothing
    // to wait for, so asking the user to ask would be ceremony.
    var shown by rememberSaveable(snapshot.journal.size) { mutableStateOf(JOURNAL_PAGE) }
    // Kept while the ledger reloads, because a save or a pull-to-refresh should not throw
    // away what the reader was looking for.
    var query by rememberSaveable { mutableStateOf("") }
    // Which entries the ledger's errors are about (B64). They stay in date order with every
    // other entry (B77): pinning them to the top moved the journal's own ordering every time a
    // file had a problem, so the list no longer read as the book. They are marked where they
    // sit, the message is on the entry's own page, and a filter shows only them.
    val faults = remember(snapshot.journal, snapshot.errors) {
        JournalErrors.attach(snapshot.journal, snapshot.errors)
    }
    var onlyErrors by rememberSaveable { mutableStateOf(false) }
    // A filter that is on while there is nothing left to filter would hide the whole journal
    // behind a switch the person can no longer see the reason for.
    val filteringErrors = onlyErrors && faults.isNotEmpty()
    val found = remember(snapshot.journal, faults, query, filteringErrors) {
        snapshot.journal
            .let { all -> if (filteringErrors) all.filter { JournalErrors.keyOf(it) in faults } else all }
            .let { rows -> if (query.isBlank()) rows else rows.filter { it.matches(query) } }
    }
    val visible = remember(found, shown) { found.take(shown) }
    // What no entry owns. The rest is shown on the entries themselves, and repeating it here
    // would say the same thing twice at the top of the screen.
    val loose = remember(snapshot.journal, snapshot.errors) {
        JournalErrors.unattached(snapshot.journal, snapshot.errors)
    }

    Column(modifier = modifier.fillMaxSize()) {
    if (selecting) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Measure.gutter, vertical = Measure.snug),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { picked = emptySet() },
                modifier = Modifier.semantics { contentDescription = "Cancel selection" },
            ) { Icon(Icons.Filled.Close, contentDescription = null) }
            Text(
                "${picked.size} selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            )
            androidx.compose.material3.TextButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.semantics { contentDescription = "Delete the selected entries" },
            ) { Text("Delete") }
        }
    } else {
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search entries") },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { query = "" },
                    modifier = Modifier.semantics { contentDescription = "Clear the search" },
                ) { Icon(Icons.Filled.Close, contentDescription = null) }
            }
        },
        shape = BeanbookShape.control,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Measure.gutter, vertical = Measure.snug)
            .semantics { contentDescription = "Search entries by date, payee, description or account" },
    )
    }

    if (confirmingDelete) {
        val chosen = snapshot.journal.filter { keyOf(it) in picked }
        val count = chosen.size
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(if (count == 1) "Delete 1 entry?" else "Delete $count entries?") },
            text = {
                Text("They are removed from your ledger files.")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        confirmingDelete = false
                        picked = emptySet()
                        onDeleteEntries(chosen)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().readableMeasure(),
        contentPadding = PaddingValues(bottom = Measure.actionClearance),
    ) {
        if (loose.isNotEmpty()) {
            item { ErrorBanner(loose) }
        }
        if (faults.isNotEmpty()) {
            item(key = "errorFilter") {
                val count = faults.values.sumOf { it.size }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Measure.gutter, vertical = Measure.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (count == 1) "1 error is on an entry." else "$count errors are on entries.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = Measure.snug),
                    )
                    FilterChip(
                        selected = onlyErrors,
                        onClick = { onlyErrors = !onlyErrors },
                        label = { Text("Only errors") },
                        modifier = Modifier.semantics {
                            contentDescription = if (onlyErrors) {
                                "Showing only entries with errors. Tap to show every entry"
                            } else {
                                "Show only entries with errors"
                            }
                        },
                    )
                }
            }
        }
        if (found.isEmpty() && query.isNotBlank()) {
            item {
                EmptyState(
                    title = "Nothing matches \"$query\"",
                    detail = "Every word has to appear somewhere in the entry. Try fewer of them.",
                    art = EmptyArt.SEARCH,
                    modifier = Modifier.padding(horizontal = Measure.gutter),
                )
            }
        }
        if (snapshot.journal.isEmpty()) {
            item {
                // Centred in the viewport rather than pinned to the top: an empty state
                // anchored to the top reads as content that failed to load, and this one is
                // the screen's whole message. It says what to do next, not only that there
                // is nothing (D9).
                Column(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .fillMaxWidth()
                        .padding(horizontal = Measure.gutter),
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        title = "No transactions yet",
                        detail = "Tap + to write your first one.",
                        art = EmptyArt.SHEET,
                    )
                }
            }
        }
        items(visible, key = { "${it.file}:${it.line}" }) { entry ->
            // No placement animation (B77). Filtering moved the surviving rows up while the rows
            // that left vanished and the rows that stayed slid past each other, so for a
            // fraction of a second while typing in the search box two entries were drawn on
            // top of one another. A journal is read row by row; a filter that simply shows its
            // result is clearer than one that shows its working.
            val key = keyOf(entry)
            JournalRow(
                entry = entry,
                format = snapshot.format,
                onClick = {
                    if (selecting) {
                        picked = if (key in picked) picked - key else picked + key
                    } else {
                        onOpenEntry(entry)
                    }
                },
                onLongClick = { picked = picked + key },
                selected = key in picked,
                errors = faults[JournalErrors.keyOf(entry)].orEmpty(),
            )
            // Indented to where the entry's own text starts, so the hairlines run down the
            // page as one column rather than cutting across the marks in the gutter.
            LedgerHairline(
                Modifier.padding(start = Measure.gutter + Measure.stamp + Measure.gap),
            )
        }
        if (shown < found.size) {
            item {
                // Composing this row is what asks for the next page: reaching it means the
                // reader is at the end of what is drawn. It states the count rather than
                // spinning, because the wait is a frame and a spinner would imply otherwise.
                val remaining = found.size - shown
                LaunchedEffect(shown, found.size) {
                    shown = (shown + JOURNAL_PAGE).coerceAtMost(found.size)
                }
                Text(
                    text = "$remaining older " + if (remaining == 1) "entry" else "entries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(Measure.indent),
                )
            }
        }
    }
    }
}

/** Rows added each time the reader reaches the end of what is drawn. */
private const val JOURNAL_PAGE = 30

/**
 * One entry in the journal, as a line of a ruled book (B74).
 *
 * The row is a gutter and a column. In the gutter is the mark -- what the file's flag says,
 * or that Beancount could not read this entry -- and in the column are the words, the date,
 * and the postings underneath, with every amount right-aligned on the same edge so a page of
 * entries can be read down rather than across. The postings hang off a hairline of their own,
 * which is what makes an entry read as one thing with parts rather than as five loose lines.
 */
@Composable
private fun JournalRow(
    entry: JournalEntry,
    format: io.github.pintofi.beanbook.core.domain.LedgerFormat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** What Beancount said about this entry, when it said anything (B64). */
    errors: List<LedgerError> = emptyList(),
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val mark = when {
        errors.isNotEmpty() -> LedgerMark.ATTENTION
        entry.flag == "!" -> LedgerMark.PENDING
        else -> LedgerMark.CLEARED
    }
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressResponse(interactions)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Select",
                interactionSource = interactions,
                indication = androidx.compose.material3.ripple(),
            )
            // The whole row, not the text inside it: a row that reads as one thing and
            // responds only where the words happen to be is one people tap twice.
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = buildString {
                    append(entry.date).append(", ").append(entry.description)
                    if (errors.isNotEmpty()) {
                        append(if (errors.size == 1) ". Has an error: " else ". Has errors: ")
                        append(errors.joinToString(". ") { it.message })
                    }
                }
            }
            .padding(horizontal = Measure.gutter, vertical = Measure.rowPadding),
    ) {
        StampMark(mark, modifier = Modifier.padding(top = Measure.tight, end = Measure.gap))
        Column(Modifier.weight(1f)) {
            // The message is on the entry's own page (B77). A list row says only that there is
            // one, next to the mark, so a page of entries stays a page of entries.
            if (errors.isNotEmpty()) {
                Text(
                    text = if (errors.size == 1) "Has an error" else "Has ${errors.size} errors",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = Measure.tight),
                )
            }
            Text(entry.description, style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Measure.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.date,
                    style = LedgerType.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Says a receipt is attached without opening anything: the row is a list
                // item, and the document itself belongs on the detail screen (B17).
                if (entry.linkedDocuments.isNotEmpty()) {
                    Text(
                        if (entry.linkedDocuments.size == 1) {
                            "Has a document"
                        } else {
                            "Has ${entry.linkedDocuments.size} documents"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = BeancountTheme.ledger.rule,
                        modifier = Modifier.padding(start = Measure.gap),
                    )
                }
            }
            if (entry.tags.isNotEmpty() || entry.links.isNotEmpty()) {
                Text(
                    text = (entry.tags.map { "#$it" } + entry.links.map { "^$it" }).joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Measure.tight),
                )
            }
            if (entry.postings.isNotEmpty()) {
                val hairline = MaterialTheme.colorScheme.outlineVariant
                Column(
                    modifier = Modifier
                        .padding(top = Measure.snug)
                        // The postings hang off a hairline of their own, so an entry reads as
                        // one thing with parts rather than as five loose lines.
                        .drawBehind {
                            drawRect(
                                color = hairline,
                                size = Size(width = 1.dp.toPx(), height = size.height),
                            )
                        }
                        .padding(start = Measure.gap),
                ) {
                    entry.postings.forEach { posting ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Measure.tight),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = posting.account,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).padding(end = Measure.snug),
                            )
                            posting.amount?.let {
                                AmountText(it, signed = true, format = format, style = LedgerType.row)
                            }
                        }
                        PostingLotText(posting, format = format)
                    }
                }
            }
        }
    }
}

/**
 * A ledger with errors is a normal state, so this informs rather than blocks
 * (docs/ux.md §2). Positions are shown in `file:line` form, which is what the user needs
 * to fix them.
 *
 * **Collapsed by default, because it was reading as an alarm.** It used to print five
 * messages plus "and N more" unconditionally, which on a phone took about a third of the
 * viewport and pushed the journal below the fold — permanently, since a ledger with errors
 * stays that way until someone fixes it, and ux.md calls that normal. In dark mode the
 * error container is a strong red, so a third of the screen went vivid for a state the
 * specification says is ordinary.
 *
 * So the count leads and the detail is behind a tap. The count is where the emphasis
 * belongs anyway: the header above already says "14 errors" in plain body text, and this
 * was shouting the same fact underneath it.
 */
@Composable
fun ErrorBanner(errors: List<LedgerError>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val count = if (errors.size == 1) "1 error in this ledger" else "${errors.size} errors in this ledger"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics {
                contentDescription = if (expanded) {
                    "$count. Showing the first ${minOf(errors.size, 5)}. Tap to hide them."
                } else {
                    "$count. Tap to see them."
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = count,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                // The row already carries a description saying what tapping does; announcing
                // the chevron separately would say it twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        // The messages push the banner open rather than appearing inside a box that was
        // already the right size: what a tap did is then visible as the page making room.
        LedgerReveal(visible = expanded, label = "errorDetail") {
            Column {
            errors.take(5).forEach { error ->
                Text(
                    text = listOfNotNull(error.position, error.message).joinToString("  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (errors.size > 5) {
                Text(
                    text = "and ${errors.size - 5} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            }
        }
    }
}

/**
 * The mark on a row that opens: one chevron, turned.
 *
 * Two icons swapped between frames say "something changed"; one icon rotating says *this row
 * opened*, which is the thing that actually happened (B76). Rotation is a graphics-layer
 * property, so the row is redrawn rather than measured again.
 */
@Composable
private fun DisclosureChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val animate = io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled.current
    val turn by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (animate) {
            io.github.pintofi.beanbook.app.ui.theme.MotionRole.local()
        } else {
            androidx.compose.animation.core.snap()
        },
        label = "chevron",
    )
    Icon(
        imageVector = Icons.Filled.ExpandMore,
        contentDescription = null,
        tint = tint,
        modifier = modifier.graphicsLayer { rotationZ = turn },
    )
}
