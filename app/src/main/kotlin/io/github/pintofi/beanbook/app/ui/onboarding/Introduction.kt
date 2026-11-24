package io.github.pintofi.beanbook.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.Highlight
import io.github.pintofi.beanbook.app.ui.LedgerHairline
import io.github.pintofi.beanbook.app.ui.RuledColumn
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred

/** The two pages that say what Beancount and Fava are, before (or apart from) setting a ledger up. */
internal enum class IntroPage { BEANCOUNT, FAVA }

/**
 * One page of the introduction. It sits between the welcome page and the ways to add a ledger, and can be
 * reopened from About.
 *
 * Plain on purpose, like the welcome page: a heading, a few facts hung off the ledger's rule, and one small
 * drawing. Each fact is something the app really does, and none of it is needed to use the app. [onSkip] is
 * shown when the page is part of setup, so a person who already keeps a Beancount ledger is one tap from
 * the ways to add one and never has to read it.
 */
@Composable
internal fun IntroductionPage(
    page: IntroPage,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    skipLabel: String? = null,
    onSkip: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onPrimary,
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                text = { Text(primaryLabel) },
                // Named on the control itself, as the welcome page's button is: a button whose words are in a
                // child is announced by some screen readers without them.
                modifier = Modifier.semantics { contentDescription = primaryLabel },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .readableMeasureCentred()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = "Back" },
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                Spacer(Modifier.weight(1f))
                if (skipLabel != null) {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier.semantics { contentDescription = skipLabel },
                    ) { Text(skipLabel) }
                }
            }
            when (page) {
                IntroPage.BEANCOUNT -> BeancountContent()
                IntroPage.FAVA -> FavaContent()
            }
            Spacer(Modifier.padding(bottom = 56.dp))
        }
    }
}

/** The two pages on their own, for About: Next from the first, Done from the second, Back leaves. */
@Composable
internal fun IntroductionView(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    if (page == 0) {
        IntroductionPage(IntroPage.BEANCOUNT, "Next", onPrimary = { page = 1 }, onBack = onClose, modifier = modifier)
    } else {
        IntroductionPage(IntroPage.FAVA, "Done", onPrimary = onClose, onBack = { page = 0 }, modifier = modifier)
    }
}

@Composable
private fun PageHeading(title: String, lead: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Measure.gap)) {
        Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
        Text(lead, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BeancountContent() {
    PageHeading(
        title = "Beancount",
        lead = "Free, open-source accounting software that reads a ledger written as plain text.",
    )
    WelcomeExample()
    RuledColumn {
        Highlight(
            "Five kinds of account",
            "Every account starts with one of five names: Assets, Liabilities, Equity, Income and Expenses.",
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Measure.gap),
            verticalArrangement = Arrangement.spacedBy(Measure.gap),
            modifier = Modifier.padding(bottom = Measure.gap),
        ) {
            listOf("Assets", "Liabilities", "Equity", "Income", "Expenses").forEach { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        LedgerHairline()
        Highlight(
            "A ledger is a few text files",
            "A new ledger here is five files, starting with main.beancount. Beancount reads them when the " +
                "ledger loads, and the app shows any errors it finds.",
        )
        LedgerHairline()
        Highlight(
            "The files are yours",
            "They stay in the app's storage on this phone until you export a copy, keep a mirror in a folder " +
                "you choose, or make a backup.",
        )
    }
}

@Composable
private fun FavaContent() {
    PageHeading(
        title = "Fava",
        lead = "The web interface made for Beancount. Beanbook runs it on this phone and shows it under " +
            "Reports, with no internet needed.",
    )
    IncomeStatementSketch()
    RuledColumn {
        Highlight(
            "What Reports shows",
            "The income statement, balance sheet, trial balance, holdings, commodities, a query page and any " +
                "errors Beancount found.",
        )
        LedgerHairline()
        Highlight(
            "Everyday screens are Beanbook's own",
            "Home, Journal and Balances are for everyday use. Reports is where Fava's full pages are.",
        )
        LedgerHairline()
        Highlight(
            "Your own server",
            "If you run Fava on your own server, Beanbook can show that instead. Set it up under More, then Remote.",
        )
    }
}

/** A drawing of what Reports looks like: two rows, each with a bar. Not the person's figures, and says so. */
@Composable
private fun IncomeStatementSketch() {
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    Column(
        verticalArrangement = Arrangement.spacedBy(Measure.snug),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "An example income statement: income 2,000.00 USD, expenses 1,240.50 USD. " +
                    "Not your figures."
            },
    ) {
        Text("Income statement", style = MaterialTheme.typography.labelLarge)
        val rows = listOf(Triple("Income", "2,000.00 USD", 1f), Triple("Expenses", "1,240.50 USD", 0.62f))
        rows.forEach { (name, amount, share) ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(name, style = mono)
                Text(amount, style = mono)
            }
            Box(
                Modifier
                    .fillMaxWidth(share)
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
            )
        }
        Text(
            "An illustration, not your figures.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
