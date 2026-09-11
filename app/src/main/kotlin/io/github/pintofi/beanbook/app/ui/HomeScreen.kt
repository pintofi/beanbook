package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.readableMeasure
import io.github.pintofi.beanbook.core.domain.AttentionItem
import io.github.pintofi.beanbook.core.domain.CategorySpend
import io.github.pintofi.beanbook.core.domain.HomeInsights
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.LedgerFormat
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.domain.LedgerSummary
import io.github.pintofi.beanbook.core.domain.SpendingPeriod
import io.github.pintofi.beanbook.core.domain.recentEntries
import java.time.LocalDate

/**
 * What the app opens on: what the ledger says, before what it contains.
 *
 * It used to be three figures and the five most recent entries, and the entries were the
 * Journal with a lower row count -- a second copy of a screen one tap away (B71). What a home
 * screen can do that a list cannot is *total*, *compare* and *point at what needs a person*,
 * so that is what it does now: where the money went by category against the period before,
 * everything that needs attention gathered from the four screens it was scattered across, and
 * the entries the file marks `!` as a to-do list rather than a log.
 *
 * **Which cards, and in what order, is the user's choice.** The right home screen is not the
 * same for a business being reconciled and a budget being watched, and the person who wants
 * the recent entries back can have them.
 *
 * Nothing is computed here. Every figure comes from `HomeInsights` or `LedgerSummary`, in
 * pure Kotlin with tests, because a number somebody makes a decision on must not be arithmetic
 * done inside a composable.
 */
@Composable
fun HomeScreen(
    ledger: LedgerSnapshot?,
    hideAmounts: Boolean,
    modifier: Modifier = Modifier,
    /** What the documents screen reconciled, so Home can say it without looking (B71). */
    unfiledDocuments: Int = 0,
    brokenDocumentLinks: Int = 0,
    mirrorConflicts: Int = 0,
    onOpenJournal: () -> Unit = {},
    onOpenEntry: (JournalEntry) -> Unit = {},
    onOpenDocuments: () -> Unit = {},
    onOpenMirror: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
    layout: HomePreferences.Layout? = null,
    onLayoutChange: (HomePreferences.Layout) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember(context) { HomePreferences(context) }
    var current by remember(layout) { mutableStateOf(layout ?: store.read()) }
    var customising by remember { mutableStateOf(false) }

    fun apply(next: HomePreferences.Layout) {
        current = next
        store.write(next)
        onLayoutChange(next)
    }

    Column(
        modifier = modifier.readableMeasure().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ledger == null) {
            Text("Open a project to see where it stands.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        current.cards.forEach { card ->
            when (card) {
                HomeCard.FIGURES -> FiguresCard(ledger, hideAmounts)
                HomeCard.SPENDING -> SpendingCard(ledger, current, hideAmounts, today, onOpenJournal)
                HomeCard.ATTENTION -> AttentionCard(
                    ledger = ledger,
                    unfiledDocuments = unfiledDocuments,
                    brokenDocumentLinks = brokenDocumentLinks,
                    mirrorConflicts = mirrorConflicts,
                    onOpenJournal = onOpenJournal,
                    onOpenDocuments = onOpenDocuments,
                    onOpenMirror = onOpenMirror,
                )
                HomeCard.PENDING -> PendingCard(ledger, hideAmounts, onOpenEntry)
                HomeCard.RECENT -> RecentCard(ledger, hideAmounts, onOpenEntry)
            }
        }

        if (current.cards.isEmpty()) {
            Text(
                "Home is empty because every card is turned off. Choose what it shows below.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        OutlinedButton(
            onClick = { customising = true },
            modifier = Modifier.semantics { contentDescription = "Choose what Home shows" },
        ) {
            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Customise Home")
        }
    }

    if (customising) {
        CustomiseHomeDialog(
            layout = current,
            onApply = { apply(it) },
            onDismiss = { customising = false },
        )
    }
}

@Composable
private fun FiguresCard(ledger: LedgerSnapshot, hideAmounts: Boolean) {
    val summary = LedgerSummary.of(ledger)
    Figure("Net worth", summary.netWorth, hideAmounts, prominent = true)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Labelled with this ledger's own names for the two trees (B70), because the
        // figures beneath them are the totals of exactly those trees.
        Figure(ledger.accountNames.income, summary.income, hideAmounts, modifier = Modifier.weight(1f))
        Figure(ledger.accountNames.expenses, summary.expenses, hideAmounts, modifier = Modifier.weight(1f))
    }

    // The ledger's own errors matter more than any figure above them: the figures were
    // computed from a file Beancount could not fully read, and saying so second would be
    // saying it too late.
    if (ledger.errors.isNotEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "This ledger has errors" },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(
                "The figures above are what could be read, not the whole file.",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Where the money went, by category, against the period before (B71).
 *
 * A bar per category rather than a chart: the question is which two or three categories the
 * month went on, and a proportion is read faster as a length than as a percentage. The change
 * against last period is the second question, so it sits beside the figure rather than above
 * it.
 */
@Composable
private fun SpendingCard(
    ledger: LedgerSnapshot,
    layout: HomePreferences.Layout,
    hideAmounts: Boolean,
    today: LocalDate,
    onOpenJournal: () -> Unit,
) {
    val report = remember(ledger, layout.period, layout.categories, today) {
        HomeInsights.spending(ledger, layout.period, today, layout.categories)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Where the money went", style = MaterialTheme.typography.titleSmall)
                Text(layout.period.label, style = MaterialTheme.typography.labelMedium)
            }

            if (report.isEmpty) {
                Text(
                    "Nothing spent in this period.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            MoneyText(
                money(report.total, ledger.format, hideAmounts),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            if (layout.compare) {
                report.previousTotal?.let { previous ->
                    Text(
                        changeLine(report.total, previous, ledger.format, hideAmounts, layout.period),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            report.categories.forEach { spend ->
                CategoryRow(spend, report.total, ledger.format, hideAmounts, layout.compare)
            }

            if (report.ignoredCurrencies.isNotEmpty()) {
                // Named rather than folded in: converting them would be the app inventing a
                // rate, and dropping them silently would make the total quietly wrong.
                Text(
                    "Not counted: spending in ${report.ignoredCurrencies.joinToString(", ")}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(
                onClick = onOpenJournal,
                modifier = Modifier.semantics { contentDescription = "Open the Journal" },
            ) { Text("See the entries") }
        }
    }
}

@Composable
private fun CategoryRow(
    spend: CategorySpend,
    total: io.github.pintofi.beanbook.core.domain.Amount,
    format: LedgerFormat,
    hideAmounts: Boolean,
    compare: Boolean,
) {
    val share = spend.shareOf(total)
    val leaf = spend.account.substringAfter(':', spend.account)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$leaf, ${money(spend.amount, format, hideAmounts)}"
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(leaf, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            MoneyText(money(spend.amount, format, hideAmounts), style = MaterialTheme.typography.bodyMedium)
        }
        // The bar is the point of the row: five lengths say where a month went faster than
        // five numbers do.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
            )
        }
        if (compare) {
            spend.change?.takeIf { it.number.signum() != 0 }?.let { change ->
                val up = change.number.signum() > 0
                Text(
                    (if (up) "+" else "") + money(change, format, hideAmounts) + " against last period",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (up) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Everything that needs a person, from the four screens it used to be scattered across. */
@Composable
private fun AttentionCard(
    ledger: LedgerSnapshot,
    unfiledDocuments: Int,
    brokenDocumentLinks: Int,
    mirrorConflicts: Int,
    onOpenJournal: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenMirror: () -> Unit,
) {
    val items = remember(ledger, unfiledDocuments, brokenDocumentLinks, mirrorConflicts) {
        HomeInsights.attention(ledger, unfiledDocuments, brokenDocumentLinks, mirrorConflicts)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (items.isEmpty()) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Needs attention", style = MaterialTheme.typography.titleSmall)
            if (items.isEmpty()) {
                Text(
                    "Nothing. The ledger loads, and every document is accounted for.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            items.forEach { item ->
                val go = when (item.destination) {
                    AttentionItem.Destination.JOURNAL -> onOpenJournal
                    AttentionItem.Destination.DOCUMENTS -> onOpenDocuments
                    AttentionItem.Destination.MIRROR -> onOpenMirror
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = go)
                        .semantics { contentDescription = "${item.headline}. ${item.detail}" }
                        .padding(vertical = 4.dp),
                ) {
                    Text(item.headline, style = MaterialTheme.typography.bodyMedium)
                    Text(item.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The entries the file marks `!`: a to-do list, which is what makes it not the Journal. */
@Composable
private fun PendingCard(
    ledger: LedgerSnapshot,
    hideAmounts: Boolean,
    onOpenEntry: (JournalEntry) -> Unit,
) {
    val pending = remember(ledger) { HomeInsights.pending(ledger) }
    val total = remember(ledger) { HomeInsights.pendingCount(ledger) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Uncleared and pending", style = MaterialTheme.typography.titleSmall)
            if (pending.isEmpty()) {
                Text("Nothing is waiting. Every entry is cleared.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            pending.forEach { entry -> EntryRow(entry, hideAmounts, onOpenEntry) }
            if (total > pending.size) {
                Text(
                    "${total - pending.size} more are pending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Kept as a choice rather than removed: some people want it, and it costs one card. */
@Composable
private fun RecentCard(
    ledger: LedgerSnapshot,
    hideAmounts: Boolean,
    onOpenEntry: (JournalEntry) -> Unit,
) {
    val recent = ledger.recentEntries(RECENT_ENTRIES)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recent entries", style = MaterialTheme.typography.titleSmall)
            if (recent.isEmpty()) {
                Text("Nothing yet. Anything you add shows up here.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            recent.forEach { entry -> EntryRow(entry, hideAmounts, onOpenEntry) }
            Text(
                "Showing ${recent.size} of ${ledger.journal.size}. The Journal has all of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Choose the cards, their order, and how the spending card is set up (B71). */
@Composable
private fun CustomiseHomeDialog(
    layout: HomePreferences.Layout,
    onApply: (HomePreferences.Layout) -> Unit,
    onDismiss: () -> Unit,
) {
    var cards by remember(layout) { mutableStateOf(layout.cards) }
    var period by remember(layout) { mutableStateOf(layout.period) }
    var categories by remember(layout) { mutableStateOf(layout.categories) }
    var compare by remember(layout) { mutableStateOf(layout.compare) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What Home shows") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Enabled cards first, in their order, then the ones that are off: the list
                // is what the screen will look like, read top to bottom.
                val ordered = cards + HomeCard.entries.filterNot { it in cards }
                ordered.forEach { card ->
                    val on = card in cards
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { checked ->
                                cards = if (checked) cards + card else cards - card
                            },
                            modifier = Modifier.semantics { contentDescription = "Show ${card.label}" },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(card.label, style = MaterialTheme.typography.bodyMedium)
                            Text(card.description, style = MaterialTheme.typography.bodySmall)
                        }
                        if (on) {
                            val at = cards.indexOf(card)
                            IconButton(
                                onClick = { cards = cards.swap(at, at - 1) },
                                enabled = at > 0,
                                modifier = Modifier.semantics { contentDescription = "Move ${card.label} up" },
                            ) { Icon(Icons.Filled.ArrowUpward, contentDescription = null) }
                            IconButton(
                                onClick = { cards = cards.swap(at, at + 1) },
                                enabled = at < cards.lastIndex,
                                modifier = Modifier.semantics { contentDescription = "Move ${card.label} down" },
                            ) { Icon(Icons.Filled.ArrowDownward, contentDescription = null) }
                        }
                    }
                }

                if (HomeCard.SPENDING in cards) {
                    Text("Where the money went", style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SpendingPeriod.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = period == option,
                                onClick = { period = option },
                                shape = SegmentedButtonDefaults.itemShape(index, SpendingPeriod.entries.size),
                                modifier = Modifier.semantics { contentDescription = option.label },
                            ) {
                                // The short name, on one line. The full ones -- "This month",
                                // "Last 30 days", "This year" -- wrapped to two lines in two
                                // of the three segments and to one in the other, so the row
                                // came out with three different heights. The card's own
                                // header still says the long name, where there is room.
                                Text(option.shortLabel, maxLines = 1)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Categories shown: $categories", modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { categories = (categories - 1).coerceAtLeast(HomePreferences.MIN_CATEGORIES) },
                            modifier = Modifier.semantics { contentDescription = "Show fewer categories" },
                        ) { Icon(Icons.Filled.ArrowDownward, contentDescription = null) }
                        IconButton(
                            onClick = { categories = (categories + 1).coerceAtMost(HomePreferences.MAX_CATEGORIES) },
                            modifier = Modifier.semantics { contentDescription = "Show more categories" },
                        ) { Icon(Icons.Filled.ArrowUpward, contentDescription = null) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Compare with the period before", modifier = Modifier.weight(1f))
                        Switch(
                            checked = compare,
                            onCheckedChange = { compare = it },
                            modifier = Modifier.semantics { contentDescription = "Compare with the period before" },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        HomePreferences.Layout(
                            cards = cards,
                            period = period,
                            categories = categories,
                            compare = compare,
                        ),
                    )
                    onDismiss()
                },
                modifier = Modifier.semantics { contentDescription = "Save what Home shows" },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun List<HomeCard>.swap(from: Int, to: Int): List<HomeCard> {
    if (from !in indices || to !in indices) return this
    val copy = toMutableList()
    val moved = copy.removeAt(from)
    copy.add(to, moved)
    return copy
}

/** One figure, formatted the way the ledger asks, or hidden if the user hid amounts. */
private fun money(
    amount: io.github.pintofi.beanbook.core.domain.Amount,
    format: LedgerFormat,
    hideAmounts: Boolean,
): String = if (hideAmounts) "•••" else format.format(amount)

private fun changeLine(
    total: io.github.pintofi.beanbook.core.domain.Amount,
    previous: io.github.pintofi.beanbook.core.domain.Amount,
    format: LedgerFormat,
    hideAmounts: Boolean,
    period: SpendingPeriod,
): String {
    val difference = total.number.subtract(previous.number)
    val against = when (period) {
        SpendingPeriod.THIS_MONTH -> "last month"
        SpendingPeriod.LAST_30_DAYS -> "the 30 days before"
        SpendingPeriod.THIS_YEAR -> "last year"
    }
    if (difference.signum() == 0) return "The same as $against."
    val word = if (difference.signum() > 0) "more than" else "less than"
    val shown = money(
        io.github.pintofi.beanbook.core.domain.Amount(difference.abs(), total.currency),
        format,
        hideAmounts,
    )
    return "$shown $word $against, which was ${money(previous, format, hideAmounts)}."
}

/** One headline figure. `null` reads as "nothing to say", never as zero. */
@Composable
private fun Figure(
    label: String,
    value: String?,
    hideAmounts: Boolean,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    val shown = when {
        value == null -> "—"
        hideAmounts -> "•••"
        else -> value
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            // One description for the pair, so a screen reader says "Net worth, 940.00 USD"
            // rather than reading a label and a number as two unrelated things.
            .semantics { contentDescription = "$label: $shown" },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            // Through MoneyText like every other figure. These are read against each other --
            // Income beside Expenses, both against Net worth -- which is the comparison the
            // fixed-width face exists for.
            MoneyText(
                shown,
                modifier = Modifier.fillMaxWidth(),
                style = if (prominent) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun EntryRow(entry: JournalEntry, hideAmounts: Boolean, onOpen: (JournalEntry) -> Unit) {
    val amount = entry.postings.firstOrNull()?.amount
    val shown = when {
        amount == null -> ""
        hideAmounts -> "•••"
        else -> "${amount.number.toPlainString()} ${amount.currency}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpen(entry) }
            .semantics { contentDescription = "${entry.date}, ${entry.description}" }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                entry.description.ifBlank { "(no description)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            MoneyText(
                entry.date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
        if (shown.isNotEmpty()) {
            MoneyText(shown)
        }
    }
}

/** Enough to recognise the last few days without becoming a second journal. */
private const val RECENT_ENTRIES = 5
