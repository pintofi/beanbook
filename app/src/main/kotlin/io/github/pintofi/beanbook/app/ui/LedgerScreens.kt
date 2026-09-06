package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.pintofi.beanbook.app.ui.theme.readableMeasure
import io.github.pintofi.beanbook.app.ui.theme.AmountTextStyle
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
import io.github.pintofi.beanbook.core.domain.LedgerError
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot

/**
 * The free-tier native surfaces: the account balances list and the journal
 * (docs/distribution.md §4.4). Both read from a [LedgerSnapshot] that Beancount produced,
 * so the app displays accounting facts rather than computing its own.
 */

/**
 * Amounts are announced with sign and currency in words, and carry an explicit sign in
 * text — never colour alone (docs/ux.md §6).
 */
private fun Amount.accessibleDescription(): String {
    val sign = when (number.signum()) {
        -1 -> "minus "
        else -> ""
    }
    return "$sign${number.abs().toPlainString()} $currency"
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
internal fun AmountText(amount: Amount, modifier: Modifier = Modifier, signed: Boolean = false) {
    // The helper below existed from M6 and was never applied, so every amount was announced
    // as its raw text: TalkBack reads "-12.34 USD" by pronouncing the hyphen or dropping it,
    // which is the one thing ux.md 6 says must not happen. detekt found it as dead code
    // (docs/milestones/m14-part16.md).
    val spoken = amount.accessibleDescription()
    MoneyText(
        text = amount.format(),
        modifier = modifier.semantics { contentDescription = spoken },
        color = when {
            !signed -> MaterialTheme.colorScheme.onSurface
            amount.number.signum() < 0 -> BeancountTheme.ledger.negative
            else -> BeancountTheme.ledger.positive
        },
    )
}

@Composable
fun BalancesScreen(
    snapshot: LedgerSnapshot,
    modifier: Modifier = Modifier,
    /** Cost and market value are Tier 3; the units everyone can see (B14). */
    valuationAllowed: Boolean = false,
) {
    val roots = listOf("Assets", "Liabilities", "Equity", "Income", "Expenses")
    var filter by remember { mutableStateOf("") }
    var basis by remember { mutableStateOf(Valuation.UNITS) }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter accounts") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "Filter accounts by name" },
        )

        if (valuationAllowed) {
            // Segmented buttons, not chips. These are one basis at a time -- the same figures
            // valued three ways -- and a segmented row is what Material uses to say "pick one
            // of these". Chips read as filters that combine, which is the opposite.
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Valuation.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = basis == option,
                        onClick = { basis = option },
                        shape = SegmentedButtonDefaults.itemShape(index, Valuation.entries.size),
                        modifier = Modifier.semantics {
                            contentDescription = "Show balances ${option.label}"
                        },
                    ) { Text(option.label) }
                }
            }
        }

        val needle = filter.trim()

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
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (snapshot.hasErrors) {
                item { ErrorBanner(snapshot.errors) }
            }
            roots.forEach { root ->
                val accounts = snapshot.accounts
                    .filter { it.name == root || it.name.startsWith("$root:") }
                    .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
                if (accounts.isEmpty()) return@forEach

                val tree = AccountTree.of(accounts, root)
                // A filter is a search, and a search that hides its own results behind a
                // fold has not answered anything: while one is typed every branch is open.
                val open = needle.isNotEmpty()
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
                        totals = if (needle.isEmpty()) {
                            snapshot.totalFor(root, basis::of)
                        } else {
                            LedgerSummary.sumByCurrency(accounts.flatMap { basis.of(it) })
                        },
                        expanded = open || root in expanded,
                        onToggle = { if (root in expanded) expanded.remove(root) else expanded.add(root) },
                    )
                }
                if (open || root in expanded) {
                    items(rows, key = { it.name }) { node ->
                        AccountRow(
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

@Composable
private fun RootHeader(
    root: String,
    totals: List<Amount>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics {
                contentDescription =
                    if (expanded) "$root, expanded. Tap to collapse" else "$root, collapsed. Tap to expand"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
            Text(
                root,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (totals.isEmpty()) {
                Text("—", style = MaterialTheme.typography.bodyMedium)
            } else {
                totals.forEach { AmountText(it) }
            }
        }
    }
}

@Composable
private fun AccountRow(
    node: AccountNode,
    basis: Valuation,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    // A parent shows what its whole branch holds, so folding it hides rows rather than
    // money. A leaf shows its own balance, which is the same figure by definition.
    val amounts = node.subtotal(basis::of)
    val spoken = buildString {
        append(node.name)
        if (node.hasChildren) {
            append(if (expanded) ", expanded. Tap to collapse" else ", collapsed. Tap to expand")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (node.hasChildren) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(
                start = (16 + node.depth * 12).dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 8.dp,
            )
            .semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (node.hasChildren) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            // The leaf alone. A ledger with both Expenses:Rent and Expenses:Home:Rent used to
            // show two rows called "Rent" with different figures and nothing to tell them
            // apart, because a parent with no balance of its own was not listed and the
            // indent had no row to sit under (D9). Every ancestor is a row now, so the indent
            // says which "Rent" this is.
            Text(
                text = node.leaf,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = if (node.hasChildren) 4.dp else 28.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            amounts.forEach { AmountText(it) }
        }
    }
    HorizontalDivider()
}

@Composable
fun JournalScreen(
    snapshot: LedgerSnapshot,
    modifier: Modifier = Modifier,
    onOpenEntry: (JournalEntry) -> Unit = {},
) {
    // How many rows are composed, not how many the engine returned. The engine now sends the
    // whole journal, and drawing 25 000 rows at once is neither necessary nor free -- but the
    // entries are already in memory, so growing this window costs a recomposition rather than
    // a re-parse. That is why it grows on scroll instead of behind a button: there is nothing
    // to wait for, so asking the user to ask would be ceremony.
    var shown by rememberSaveable(snapshot.journal.size) { mutableStateOf(JOURNAL_PAGE) }
    val visible = remember(snapshot.journal, shown) { snapshot.journal.take(shown) }

    LazyColumn(
        modifier = modifier.fillMaxSize().readableMeasure(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        if (snapshot.hasErrors) {
            item { ErrorBanner(snapshot.errors) }
        }
        if (snapshot.journal.isEmpty()) {
            item {
                // Says what to do next, not just that there is nothing. A bare "No
                // transactions yet." in an otherwise empty screen tells the user a fact they
                // can already see and leaves them to find the way forward themselves (D9).
                //
                // `fillParentMaxHeight` so it settles in the middle of the screen rather than
                // against the top with two thirds of the viewport empty beneath it. An empty
                // state anchored to the top reads as content that failed to load; centred, it
                // reads as the screen's whole message, which is what it is.
                Column(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        text = "No transactions yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Tap + to write your first one.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        items(visible, key = { "${it.file}:${it.line}" }) { entry ->
            JournalRow(entry, onClick = { onOpenEntry(entry) })
            HorizontalDivider()
        }
        if (shown < snapshot.journal.size) {
            item {
                // Composing this row is what asks for the next page: reaching it means the
                // reader is at the end of what is drawn. It states the count rather than
                // spinning, because the wait is a frame and a spinner would imply otherwise.
                val remaining = snapshot.journal.size - shown
                LaunchedEffect(shown) { shown = (shown + JOURNAL_PAGE).coerceAtMost(snapshot.journal.size) }
                Text(
                    text = "$remaining older " + if (remaining == 1) "entry" else "entries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

/** Rows added each time the reader reaches the end of what is drawn. */
private const val JOURNAL_PAGE = 30

@Composable
private fun JournalRow(entry: JournalEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // The whole row, not the text inside it: a row that reads as one thing and
            // responds only where the words happen to be is one people tap twice.
            .semantics { contentDescription = "${entry.date}, ${entry.description}" }
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(entry.date, fontFamily = FontFamily.Monospace)
            // The flag is shown as a character, not a colour: "!" means needs review.
            entry.flag?.let { Text(it, fontFamily = FontFamily.Monospace) }
        }
        Text(entry.description, style = MaterialTheme.typography.bodyLarge)
        // Says a receipt is attached without opening anything: the row is a list item, and
        // the document itself belongs on the detail screen (B17).
        if (entry.linkedDocuments.isNotEmpty()) {
            Text(
                if (entry.linkedDocuments.size == 1) {
                    "Has a document"
                } else {
                    "Has ${entry.linkedDocuments.size} documents"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (entry.tags.isNotEmpty() || entry.links.isNotEmpty()) {
            Text(
                text = (entry.tags.map { "#$it" } + entry.links.map { "^$it" }).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        entry.postings.forEach { posting ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = posting.account,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                posting.amount?.let { AmountText(it, signed = true) }
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
        if (expanded) {
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
