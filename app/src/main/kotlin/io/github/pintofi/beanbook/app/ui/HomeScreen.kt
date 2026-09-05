package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.pintofi.beanbook.app.ui.theme.readableMeasure
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.recentEntries
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.domain.LedgerSummary

/**
 * What the app opens on: what the ledger says, before what it contains.
 *
 * The Journal used to be the first screen, which is a wall of directives — accurate, and the
 * least legible thing to greet somebody with. The figures here are the same three the widgets
 * show, from the same `LedgerSummary`, so a glance at the home screen and a glance at the
 * home *screen* can never disagree.
 *
 * Nothing is computed here. A number the user reads to decide something has to come from the
 * parsed ledger, not from arithmetic done in a composable.
 */
@Composable
fun HomeScreen(
    ledger: LedgerSnapshot?,
    hideAmounts: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.readableMeasure().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ledger == null) {
            Text("Open a project to see where it stands.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        val summary = LedgerSummary.of(ledger)

        Figure("Net worth", summary.netWorth, hideAmounts, prominent = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Figure("Income", summary.income, hideAmounts, modifier = Modifier.weight(1f))
            Figure("Expenses", summary.expenses, hideAmounts, modifier = Modifier.weight(1f))
        }

        // The ledger's own errors matter more than any figure above them: the figures were
        // computed from a file Beancount could not fully read, and saying so second would be
        // saying it too late.
        if (ledger.errors.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "This ledger has errors" },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    "The figures above are what could be read, not the whole file.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("Recent entries", style = MaterialTheme.typography.titleSmall)
        val recent = ledger.recentEntries(RECENT_ENTRIES)
        if (recent.isEmpty()) {
            Text(
                "Nothing yet. Anything you add shows up here.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            recent.forEach { entry -> RecentEntry(entry, hideAmounts) }
            Text(
                "Showing ${recent.size} of ${ledger.journal.size}. The Journal has all of them.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
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
            // fixed-width face exists for. Left-aligned within the card because a card is not
            // a column; the face is what matters here, not the trailing edge.
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
private fun RecentEntry(entry: JournalEntry, hideAmounts: Boolean) {
    val amount = entry.postings.firstOrNull()?.amount
    val shown = when {
        amount == null -> ""
        hideAmounts -> "•••"
        else -> "${amount.number.toPlainString()} ${amount.currency}"
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                // These *are* a column -- five rows read straight down -- so this is the case
                // the fixed-width face was argued for, and the one that was rendering
                // proportionally. Also the date above uses the same face for the same reason.
                MoneyText(shown)
            }
        }
    }
}

/** Enough to recognise the last few days without becoming a second journal. */
private const val RECENT_ENTRIES = 5
