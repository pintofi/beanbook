package dev.beancount.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.beancount.core.domain.AccountBalance
import dev.beancount.core.domain.Amount
import dev.beancount.core.domain.JournalEntry
import dev.beancount.core.domain.LedgerError
import dev.beancount.core.domain.LedgerSnapshot

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

@Composable
private fun AmountText(amount: Amount, modifier: Modifier = Modifier) {
    // The helper below existed from M6 and was never applied, so every amount was announced
    // as its raw text: TalkBack reads "-12.34 USD" by pronouncing the hyphen or dropping it,
    // which is the one thing ux.md 6 says must not happen. detekt found it as dead code
    // (docs/milestones/m14-part16.md).
    val spoken = amount.accessibleDescription()
    Text(
        text = amount.format(),
        modifier = modifier.semantics { contentDescription = spoken },
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun BalancesScreen(snapshot: LedgerSnapshot, modifier: Modifier = Modifier) {
    val roots = listOf("Assets", "Liabilities", "Equity", "Income", "Expenses")

    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (snapshot.hasErrors) {
            item { ErrorBanner(snapshot.errors) }
        }
        roots.forEach { root ->
            val accounts = snapshot.accounts.filter {
                it.name == root || it.name.startsWith("$root:")
            }
            if (accounts.isEmpty()) return@forEach

            item(key = "header-$root") {
                RootHeader(root = root, totals = snapshot.totalFor(root))
            }
            items(accounts, key = { it.name }) { account -> AccountRow(account) }
        }
    }
}

@Composable
private fun RootHeader(root: String, totals: List<Amount>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(root, style = MaterialTheme.typography.titleMedium)
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
private fun AccountRow(account: AccountBalance) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = (16 + account.depth * 12).dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = account.leaf,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            account.positions.forEach { AmountText(it) }
        }
    }
    HorizontalDivider()
}

@Composable
fun JournalScreen(snapshot: LedgerSnapshot, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (snapshot.hasErrors) {
            item { ErrorBanner(snapshot.errors) }
        }
        if (snapshot.journal.isEmpty()) {
            item {
                Text(
                    text = "No transactions yet.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        items(snapshot.journal, key = { "${it.file}:${it.line}" }) { entry ->
            JournalRow(entry)
            HorizontalDivider()
        }
    }
}

@Composable
private fun JournalRow(entry: JournalEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(entry.date, fontFamily = FontFamily.Monospace)
            // The flag is shown as a character, not a colour: "!" means needs review.
            entry.flag?.let { Text(it, fontFamily = FontFamily.Monospace) }
        }
        Text(entry.description, style = MaterialTheme.typography.bodyLarge)
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
                posting.amount?.let { AmountText(it) }
            }
        }
    }
}

/**
 * A ledger with errors is a normal state, so this informs rather than blocks
 * (docs/ux.md §2). Positions are shown in `file:line` form, which is what the user needs
 * to fix them.
 */
@Composable
fun ErrorBanner(errors: List<LedgerError>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
    ) {
        Text(
            text = if (errors.size == 1) "1 error in this ledger" else "${errors.size} errors in this ledger",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
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
            )
        }
    }
}
