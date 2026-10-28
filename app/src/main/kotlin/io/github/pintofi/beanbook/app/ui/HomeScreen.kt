package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasure
import io.github.pintofi.beanbook.core.domain.AttentionItem
import io.github.pintofi.beanbook.core.domain.CategorySpend
import io.github.pintofi.beanbook.core.domain.HomeInsights
import io.github.pintofi.beanbook.core.domain.Valuation
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.LedgerFormat
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.domain.LedgerSummary
import io.github.pintofi.beanbook.core.domain.SpendingPeriod
import java.time.LocalDate

/**
 * What the app opens on: what the ledger says, before what it contains.
 *
 * It used to be three figures and the five most recent entries, and the entries were the
 * Journal with a lower row count -- a second copy of a screen one tap away. What a home
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
    /** What the documents screen reconciled, so Home can say it without looking. */
    unfiledDocuments: Int = 0,
    brokenDocumentLinks: Int = 0,
    mirrorConflicts: Int = 0,
    onOpenJournal: () -> Unit = {},
    onOpenEntry: (JournalEntry) -> Unit = {},
    onOpenDocuments: () -> Unit = {},
    onOpenMirror: () -> Unit = {},
    onOpenPlugins: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
    layout: HomePreferences.Layout? = null,
    onLayoutChange: (HomePreferences.Layout) -> Unit = {},
    /** Above the cards: the one-time support notice, when it is due. */
    header: @Composable () -> Unit = {},
    /** The ledger went from errors to none while the app was open: stamp it. */
    stamped: Boolean = false,
    /** Opens Balances, where a headline's left-out commodities are listed in full. */
    onOpenBalances: () -> Unit = {},
    /** The period the figures cover, as Fava's time filter expresses it, and how to change it. */
    dateFrom: String = "",
    dateTo: String = "",
    onDateRange: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val store = remember(context) { HomePreferences(context) }
    val valuationStore = remember(context) { ValuationPreference(context) }
    val valuation by valuationStore.flow.collectAsState()
    var current by remember(layout) { mutableStateOf(layout ?: store.read()) }
    var customising by remember { mutableStateOf(false) }

    fun apply(next: HomePreferences.Layout) {
        current = next
        store.write(next)
        onLayoutChange(next)
    }

    @Composable
    fun Card(card: HomeCard, snapshot: LedgerSnapshot) {
        when (card) {
            HomeCard.FIGURES -> FiguresCard(snapshot, hideAmounts, valuation, onOpenBalances) {
                valuationStore.write(it)
            }
            HomeCard.SPENDING -> SpendingCard(snapshot, current, hideAmounts, today, valuation, onOpenJournal)
            HomeCard.ATTENTION -> AttentionCard(
                stamped = stamped,
                ledger = snapshot,
                unfiledDocuments = unfiledDocuments,
                brokenDocumentLinks = brokenDocumentLinks,
                mirrorConflicts = mirrorConflicts,
                onOpenJournal = onOpenJournal,
                onOpenDocuments = onOpenDocuments,
                onOpenMirror = onOpenMirror,
                onOpenPlugins = onOpenPlugins,
            )
            HomeCard.PENDING -> PendingCard(snapshot, hideAmounts, onOpenEntry)
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        // Two columns once the window is wide enough for two readable ones: in landscape the
        // single column used about 55% of the width and left the rest empty (device audit
        // 2026-10-10). Cards keep the person's order, read left then right.
        val twoColumns = maxWidth >= TWO_COLUMN_WIDTH
        Column(
            modifier = Modifier
                .then(if (twoColumns) Modifier.widthIn(max = WIDE_MEASURE) else Modifier.readableMeasure())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Measure.gutter)
                .padding(top = Measure.gap, bottom = Measure.actionClearance),
            verticalArrangement = Arrangement.spacedBy(Measure.section),
        ) {
            header()
            // The same search, filters and period as the Journal and Balances. Home shows what they
            // match too, so a figure here is never from a different slice of the books than the one
            // the other two screens are looking at.
            EntryFilterBar(ledger, dateFrom, dateTo, onDateRange, inset = 0.dp)
            if (ledger == null) {
                EmptyState(
                    title = "No ledger open",
                    detail = "Open a project to see where it stands.",
                    art = EmptyArt.SHEET,
                )
                return@Column
            }

            if (twoColumns && current.cards.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(Measure.columnGap)) {
                    listOf(0, 1).forEach { side ->
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Measure.section),
                        ) {
                            current.cards.filterIndexed { index, _ -> index % 2 == side }
                                .forEach { Card(it, ledger) }
                        }
                    }
                }
            } else {
                current.cards.forEach { Card(it, ledger) }
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
    }

    if (customising) {
        CustomiseHomeSheet(
            layout = current,
            onApply = { apply(it) },
            onDismiss = { customising = false },
        )
    }
}

/**
 * The width Home's own area must have for two columns: two cards of about 290dp and the gap.
 * Measured inside the rail, so a phone in landscape (about 650dp beside the rail) qualifies.
 */
private val TWO_COLUMN_WIDTH = 600.dp

/** How wide two columns may grow together: two readable measures and the gap between. */
private val WIDE_MEASURE = 1160.dp

@Composable
private fun FiguresCard(
    ledger: LedgerSnapshot,
    hideAmounts: Boolean,
    valuation: Valuation,
    onOpenBalances: () -> Unit,
    onValuation: (Valuation) -> Unit,
) {
    val summary = remember(ledger, valuation) { LedgerSummary.of(ledger, valuation) }
    val totals = remember(ledger, valuation) { LedgerSummary.totals(ledger, valuation) }
    // The one signature composition in the app: the headline figure set large in the
    // figure face, hung off the column rule, with the two supporting figures under it in a
    // ruled pair. No card, no container -- this is the page itself, which is why it reads as
    // a ledger rather than as a dashboard tile.
    RuledColumn {
        // Capped at three commodities and "+N more": in Units a ledger
        // of twenty holdings stacked twenty 34sp lines here, taller than the screen.
        val headline = remember(totals, ledger.operatingCurrencies) {
            io.github.pintofi.beanbook.core.domain.HeadlineFigures.of(totals.netWorth, ledger.operatingCurrencies)
        }
        val introduce = HomeIntro.claim()
        if (headline.shown.size <= 1 && headline.more == 0) {
            KeyFigure(
                label = "Net worth",
                value = shown(summary.netWorth, hideAmounts),
                emphasis = true,
                introduce = introduce,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Measure.tight)) {
                HeadlineFigure(
                    figures = headline,
                    format = ledger.format,
                    hideAmounts = hideAmounts,
                    onMore = onOpenBalances,
                    modifier = Modifier.semantics(mergeDescendants = false) {
                        contentDescription = "Net worth: " + headline.shown.joinToString(", ") {
                            if (hideAmounts) "hidden" else ledger.format.format(it)
                        }
                    },
                )
                Text(
                    "Net worth",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Measure.snug))
        // The same three bases as Fava's top-right switch, and the same one Balances uses:
        // a figure on this page and its account on that one are always on the same basis.
        ConnectedChoiceRow(
            options = Valuation.entries,
            selected = valuation,
            onSelect = onValuation,
            label = { it.label },
            describe = { "Show figures ${it.label}" },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Measure.gap))
        // Labelled with this ledger's own names for the two trees, because the
        // figures beneath them are the totals of exactly those trees. One description per
        // tile, so a screen reader says "Income, 3000.00 USD" rather than reading a number
        // and a word as two unrelated things.
        //
        // Side by side while a figure fits beside another; one above the other at large text,
        // where two tiles in one row clipped "2,345,678.90 PKR" to "2,345,678." (audit).
        val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        val income: @Composable (Modifier) -> Unit = { tile ->
            SupportingFigure(
                label = ledger.accountNames.income,
                value = shownHeadline(totals.income, ledger, hideAmounts),
                modifier = tile,
            )
        }
        val expenses: @Composable (Modifier) -> Unit = { tile ->
            SupportingFigure(
                label = ledger.accountNames.expenses,
                value = shownHeadline(totals.expenses, ledger, hideAmounts),
                modifier = tile,
            )
        }
        // One tinted band holding both, split by a hairline of paper, so the two read as a pair
        // of equal tiles even when one figure takes two lines -- without asking the figures for
        // intrinsic sizes, which a rolling figure cannot give.
        val paper = MaterialTheme.colorScheme.background
        val band = MaterialTheme.colorScheme.surfaceContainerLow
        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(band),
            ) {
                income(Modifier.fillMaxWidth())
                LedgerHairline(Modifier.padding(horizontal = Measure.indent))
                expenses(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(band)
                    .drawBehind {
                        val seam = Measure.hairline.toPx()
                        drawRect(
                            color = paper,
                            topLeft = androidx.compose.ui.geometry.Offset(size.width / 2f - seam / 2f, 0f),
                            size = androidx.compose.ui.geometry.Size(seam, size.height),
                        )
                    },
            ) {
                income(Modifier.weight(1f))
                expenses(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A supporting figure's text: up to two commodities, one per line, and "+N more" -- the same
 * cap as the headline, narrower because a tile is half a page wide.
 */
private fun shownHeadline(
    amounts: List<io.github.pintofi.beanbook.core.domain.Amount>,
    ledger: LedgerSnapshot,
    hideAmounts: Boolean,
): String {
    val capped = io.github.pintofi.beanbook.core.domain.HeadlineFigures.of(
        amounts,
        ledger.operatingCurrencies,
        max = SUPPORTING_LINES,
    )
    if (capped.shown.isEmpty()) return "—"
    if (hideAmounts) return "•••"
    val lines = capped.shown.map { ledger.format.format(it) }
    return (lines + listOfNotNull(capped.more.takeIf { it > 0 }?.let { "+$it more" })).joinToString("\n")
}

/** How many commodities a supporting tile names before "+N more". */
private const val SUPPORTING_LINES = 2

/** One of the two figures under the headline: the total of an account tree. */
@Composable
private fun SupportingFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" }
            .heightIn(min = Measure.rowMinHeight)
            .padding(horizontal = Measure.indent, vertical = Measure.gap),
        verticalArrangement = Arrangement.spacedBy(Measure.tight),
    ) {
        if ('\n' in value) {
            value.lines().forEach { line ->
                FitFigure(
                    line,
                    style = if (line.startsWith("+")) LedgerType.small else LedgerType.figure,
                    color = if (line.startsWith("+")) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                )
            }
        } else {
            RollingFigure(
                text = value,
                style = LedgerType.figure.copy(textAlign = TextAlign.Start),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A figure, or the dots that stand in for one when amounts are hidden. */
private fun shown(value: String?, hideAmounts: Boolean): String = when {
    value == null -> "—"
    hideAmounts -> "•••"
    else -> value
}

/**
 * Where the money went, by category, against the period before.
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
    valuation: Valuation,
    onOpenJournal: () -> Unit,
) {
    val report = remember(ledger, layout.period, layout.categories, today, valuation) {
        HomeInsights.spending(ledger, layout.period, today, layout.categories, valuation)
    }
    RuledColumn {
        LedgerHeading("Where the money went") {
            Text(
                layout.period.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (report.isEmpty) {
            Text(
                "Nothing spent in this period.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@RuledColumn
        }

        MoneyText(
            money(report.total, ledger.format, hideAmounts),
            style = LedgerType.figure.copy(textAlign = TextAlign.Start),
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        if (layout.compare) {
            report.previousTotal?.let { previous ->
                Text(
                    changeLine(report.total, previous, ledger.format, hideAmounts, layout.period),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Measure.tight),
                )
            }
        }

        Spacer(Modifier.height(Measure.gap))
        report.categories.forEachIndexed { index, spend ->
            if (index > 0) LedgerHairline()
            CategoryRow(spend, report.total, ledger.format, hideAmounts, layout.compare)
        }
        LedgerHairline()

        if (report.ignoredCurrencies.isNotEmpty()) {
            // Named rather than folded in: converting them would be the app inventing a
            // rate, and dropping them silently would make the total quietly wrong.
            Text(
                "Not counted: spending in ${report.ignoredCurrencies.joinToString(", ")}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Measure.snug),
            )
        }

        OutlinedButton(
            onClick = onOpenJournal,
            modifier = Modifier
                .padding(top = Measure.tight)
                .semantics { contentDescription = "Open the Journal" },
        ) { Text("See the entries") }
    }
}

/**
 * One category of spending: the name, the figure, and the share as a measured length.
 *
 * The bar is drawn as a **rule segment**, not as a rounded progress track -- same thickness
 * and same colour as the column rule the section hangs from, so a row reads as a short rule
 * measured against the page width rather than as a widget borrowed from a dashboard. The
 * track behind it is a hairline, which is what the eye needs to see that a short bar is short.
 */
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
            }
            .padding(vertical = Measure.snug),
        verticalArrangement = Arrangement.spacedBy(Measure.tight),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                leaf,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(end = Measure.snug),
            )
            MoneyText(money(spend.amount, format, hideAmounts), style = LedgerType.row)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Measure.rule)
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share)
                    .height(Measure.rule)
                    .background(BeancountTheme.ledger.rule),
            )
        }
        if (compare) {
            spend.change?.takeIf { it.number.signum() != 0 }?.let { change ->
                val up = change.number.signum() > 0
                Text(
                    (if (up) "+" else "") + money(change, format, hideAmounts) + " against last period",
                    style = MaterialTheme.typography.labelSmall,
                    // Spending more than last period is not an error, so it is not error red:
                    // it is the ledger's own negative colour, and the sign says which way.
                    color = if (up) {
                        BeancountTheme.ledger.negative
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
    stamped: Boolean,
    ledger: LedgerSnapshot,
    unfiledDocuments: Int,
    brokenDocumentLinks: Int,
    mirrorConflicts: Int,
    onOpenJournal: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenMirror: () -> Unit,
    onOpenPlugins: () -> Unit,
) {
    val items = remember(ledger, unfiledDocuments, brokenDocumentLinks, mirrorConflicts) {
        HomeInsights.attention(ledger, unfiledDocuments, brokenDocumentLinks, mirrorConflicts)
    }
    // This is the app talking about the ledger rather than the ledger showing itself, so it
    // is a notice: rounded and tinted where the data around it is square and on paper. When
    // there is nothing to say it stays on the page as a ruled line, because a tinted panel
    // announcing that nothing is wrong is the app congratulating itself.
    // What needs a person changes while the app is open -- a document is filed, a mirror
    // conflict is resolved -- so the two states dissolve into each other rather than
    // replacing each other between frames.
    io.github.pintofi.beanbook.app.ui.LedgerContent(
        targetState = items,
        label = "attention",
        contentKey = { it.isEmpty() },
        move = { _, _ -> io.github.pintofi.beanbook.app.ui.Move.none },
    ) { shown ->
    if (shown.isEmpty()) {
        RuledColumn {
            LedgerHeading("Needs attention")
            Row(verticalAlignment = Alignment.CenterVertically) {
                StampMark(LedgerMark.CLEARED)
                Text(
                    "Nothing. The ledger has no errors, and every document is linked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Measure.gap).weight(1f),
                )
                // Stamped beside the line that says so, when the last error has just been
                // fixed: on the ledger's own health line, in space the text leaves, so
                // it adds no row and covers no figure.
                if (stamped) {
                    InBalanceStamp(size = 64.dp, modifier = Modifier.padding(start = Measure.gap))
                }
            }
        }
        return@LedgerContent
    }

    // Calm ground, marked items: the attention marks and the heading carry
    // the alarm, so one ordinary error no longer turns a third of Home red.
    Notice(NoticeTone.QUIET) {
        Text(
            "Needs attention",
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.error,
        )
        shown.forEach { item ->
            val go = when (item.destination) {
                AttentionItem.Destination.JOURNAL -> onOpenJournal
                AttentionItem.Destination.DOCUMENTS -> onOpenDocuments
                AttentionItem.Destination.MIRROR -> onOpenMirror
                AttentionItem.Destination.PLUGINS -> onOpenPlugins
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = go)
                    .semantics { contentDescription = "${item.headline}. ${item.detail}" }
                    .padding(vertical = Measure.snug),
            ) {
                StampMark(
                    LedgerMark.ATTENTION,
                    modifier = Modifier.padding(top = Measure.tight, end = Measure.gap),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Measure.tight),
                ) {
                    Text(item.headline, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Measure.snug).align(Alignment.CenterVertically),
                )
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
    RuledColumn {
        LedgerHeading("Uncleared and pending") {
            if (pending.isNotEmpty()) {
                Text(
                    "$total",
                    style = LedgerType.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (pending.isEmpty()) {
            Text(
                "Nothing is waiting. Every entry is cleared.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@RuledColumn
        }
        pending.forEachIndexed { index, entry ->
            if (index > 0) LedgerHairline()
            EntryRow(entry, hideAmounts, onOpenEntry, LedgerMark.PENDING, ledger.format)
        }
        LedgerHairline()
        if (total > pending.size) {
            Text(
                "${total - pending.size} more are pending.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Measure.snug),
            )
        }
    }
}

/**
 * Choose the cards, their order, and how the spending card is set up.
 *
 * A bottom sheet rather than a dialog (device audit 2026-10-10): the dialog stood at 60% of the
 * screen over the page it was changing, with its controls at the top where a thumb is not. The
 * sheet rises from the bottom, built from folios like every other set of choices in the app,
 * and Save and Cancel sit at the end of it, under the choices they confirm.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CustomiseHomeSheet(
    layout: HomePreferences.Layout,
    onApply: (HomePreferences.Layout) -> Unit,
    onDismiss: () -> Unit,
) {
    var cards by remember(layout) { mutableStateOf(layout.cards) }
    var period by remember(layout) { mutableStateOf(layout.period) }
    var categories by remember(layout) { mutableStateOf(layout.categories) }
    var compare by remember(layout) { mutableStateOf(layout.compare) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = io.github.pintofi.beanbook.app.ui.theme.BeanbookShape.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            // Clear of the navigation bar: Save and Cancel sat under the gesture bar,
            // half drawn, until the sheet was scrolled.
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Measure.gutter)
                .padding(bottom = Measure.section),
            verticalArrangement = Arrangement.spacedBy(Measure.snug),
        ) {
            Text(
                "What Home shows",
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            FolioHeading("Cards, in order")
            // Enabled cards first, in their order, then the ones that are off: the list is
            // what the screen will look like, read top to bottom.
            val ordered = cards + HomeCard.entries.filterNot { it in cards }
            FolioGroup {
                ordered.forEach { card ->
                    item {
                        val on = card in cards
                        FolioCheckRow(
                            title = card.label,
                            detail = card.description,
                            checked = on,
                            onCheckedChange = { checked ->
                                cards = if (checked) cards + card else cards - card
                            },
                            describe = "Show ${card.label}",
                            trailing = if (on) {
                                {
                                    val at = cards.indexOf(card)
                                    IconButton(
                                        onClick = { cards = cards.swap(at, at - 1) },
                                        enabled = at > 0,
                                        modifier = Modifier.semantics { contentDescription = "Move ${card.label} up" },
                                    ) { Icon(Icons.Filled.ArrowUpward, contentDescription = null) }
                                    IconButton(
                                        onClick = { cards = cards.swap(at, at + 1) },
                                        enabled = at < cards.lastIndex,
                                        modifier = Modifier.semantics {
                                            contentDescription = "Move ${card.label} down"
                                        },
                                    ) { Icon(Icons.Filled.ArrowDownward, contentDescription = null) }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            if (HomeCard.SPENDING in cards) {
                FolioHeading("Where the money went")
                FolioGroup {
                    item {
                        FolioBody {
                            // The short name, on one line. The full ones -- "This month", "Last 30
                            // days", "This year" -- wrapped to two lines in two of the three
                            // segments and to one in the other, so the row came out with three
                            // different heights. The card's own header still says the long name.
                            ConnectedChoiceRow(
                                options = SpendingPeriod.entries,
                                selected = period,
                                onSelect = { period = it },
                                label = { it.shortLabel },
                                describe = { it.label },
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item {
                        // Fewer, then more: minus before plus, the order a stepper reads in, and
                        // with the count between them where the eye checks it.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Measure.rowMinHeight)
                                .padding(start = Measure.indent, end = Measure.tight),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Categories shown",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    categories = (categories - 1).coerceAtLeast(HomePreferences.MIN_CATEGORIES)
                                },
                                enabled = categories > HomePreferences.MIN_CATEGORIES,
                                modifier = Modifier.semantics { contentDescription = "Show fewer categories" },
                            ) { Icon(Icons.Filled.Remove, contentDescription = null) }
                            Text(
                                "$categories",
                                style = LedgerType.row,
                                modifier = Modifier.semantics {
                                    contentDescription = "$categories categories shown"
                                },
                            )
                            IconButton(
                                onClick = {
                                    categories = (categories + 1).coerceAtMost(HomePreferences.MAX_CATEGORIES)
                                },
                                enabled = categories < HomePreferences.MAX_CATEGORIES,
                                modifier = Modifier.semantics { contentDescription = "Show more categories" },
                            ) { Icon(Icons.Filled.Add, contentDescription = null) }
                        }
                    }
                    item {
                        FolioSwitchRow(
                            title = "Compare with the period before",
                            checked = compare,
                            onCheckedChange = { compare = it },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Measure.gap),
                horizontalArrangement = Arrangement.spacedBy(Measure.snug, Alignment.End),
            ) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                androidx.compose.material3.Button(
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
            }
        }
    }
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

/** What the file's own flag says about an entry, in the app's mark vocabulary. */
private fun markOf(entry: JournalEntry): LedgerMark = markForFlag(entry.flag)

/**
 * One entry on a ruled page: mark, description, date, amount.
 *
 * The amount is set in the figure face and right-aligned against the page edge, so a column
 * of entries lines up on the decimal point; the date sits under the description in the small
 * figure face rather than beside it, because a phone row has one line of horizontal room and
 * the description is what a person is scanning for.
 */
@Composable
private fun EntryRow(
    entry: JournalEntry,
    hideAmounts: Boolean,
    onOpen: (JournalEntry) -> Unit,
    mark: LedgerMark = markOf(entry),
    /** How the open ledger asks its figures to be written. */
    format: LedgerFormat = LedgerFormat.PLAIN,
) {
    val amount = entry.postings.firstOrNull()?.amount
    val shown = when {
        amount == null -> ""
        hideAmounts -> "•••"
        // Through the ledger's own format like every other figure: this row was building its
        // own string, so a file asking for thousands separators got them everywhere except
        // on its own home screen.
        else -> format.format(amount)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpen(entry) }
            .semantics { contentDescription = "${entry.date}, ${entry.description}" }
            .heightIn(min = Measure.rowMinHeight)
            .padding(vertical = Measure.snug),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StampMark(mark, flag = entry.flag, modifier = Modifier.padding(end = Measure.gap))
        Column(Modifier.weight(1f).padding(end = Measure.snug)) {
            Text(
                entry.description.ifBlank { NO_DESCRIPTION },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MoneyText(
                entry.date,
                style = LedgerType.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
        if (shown.isNotEmpty()) {
            MoneyText(shown, style = LedgerType.row)
        }
    }
}

/**
 * Whether Home's figures have rolled in yet in this process. Once per launch: the opening
 * flourish belongs to opening the app, not to every return to Home.
 */
internal object HomeIntro {
    private var played = false

    fun claim(): Boolean = if (played) false else true.also { played = true }
}
