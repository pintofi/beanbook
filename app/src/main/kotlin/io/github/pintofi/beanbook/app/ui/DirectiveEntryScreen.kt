package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.DirectiveDraft
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import java.time.LocalDate
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import androidx.compose.foundation.background
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme

/**
 * The directive types this form composes, in the order people reach for them.
 *
 * Opening and closing an account are not here: the Accounts page does both, with the account
 * names checked against the ledger's own roots, and writes them to `accounts.beancount`.
 */
enum class DirectiveKind(val label: String) {
    BALANCE("Balance"),
    PAD("Pad"),
    NOTE("Note"),
    PRICE("Price"),
    DOCUMENT("Document"),
    EVENT("Event"),
    COMMODITY("Commodity"),
    QUERY("Query"),
    CUSTOM("Custom"),
}

/**
 * Editors for every directive that is not a transaction.
 *
 * A ledger where you can record a payment but cannot open the account it came from is not
 * a usable ledger, so these sit alongside transactions.
 */
@Composable
fun DirectiveEntryScreen(
    vocabulary: LedgerVocabulary,
    onSave: (DirectiveDraft) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    saveState: String? = null,
    /** Directives committed from the form this session; a change marks the preview. */
    committed: Int = 0,
    /** Whether anything has been typed that leaving would lose. */
    onDirtyChange: (Boolean) -> Unit = {},
    /** The ledger's own `currency-column`, so the preview is the text that will be written. */
    currencyColumn: Int = DirectiveDraft.ALIGN_COLUMN,
) {
    var kind by remember { mutableStateOf(DirectiveKind.BALANCE) }
    var date by remember { mutableStateOf(today.toString()) }
    var account by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    // What the ledger's commodity_attr plugin asks of a commodity, keyed by attribute.
    val attributes = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    // The operating currency first, as the transaction form does: it is what the ledger says
    // it counts in.
    var currency by remember {
        mutableStateOf(vocabulary.defaultCurrency ?: vocabulary.currencies.firstOrNull() ?: "USD")
    }

    // The date and the pre-selected currency are the form's own doing, so neither counts.
    val dirty = account.isNotBlank() || second.isNotBlank() || text.isNotBlank() ||
        amount.isNotBlank() || attributes.values.any { it.isNotBlank() }
    androidx.compose.runtime.LaunchedEffect(dirty) { onDirtyChange(dirty) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onDirtyChange(false) } }

    val parsedAmount = remember(amount, currency) {
        parseTypedAmount(amount)?.let { number -> runCatching { Amount(number, currency.trim()) }.getOrNull() }
    }

    val draft: DirectiveDraft = when (kind) {
        DirectiveKind.BALANCE -> DirectiveDraft.Balance(date.trim(), account.trim(), parsedAmount)
        DirectiveKind.PAD -> DirectiveDraft.Pad(date.trim(), account.trim(), second.trim())
        DirectiveKind.NOTE -> DirectiveDraft.Note(date.trim(), account.trim(), text)
        DirectiveKind.PRICE -> DirectiveDraft.Price(date.trim(), account.trim(), parsedAmount)
        DirectiveKind.DOCUMENT -> DirectiveDraft.Document(date.trim(), account.trim(), text.trim())
        DirectiveKind.EVENT -> DirectiveDraft.Event(date.trim(), account.trim(), text)
        DirectiveKind.COMMODITY -> DirectiveDraft.Commodity(
            date.trim(), account.trim(),
            vocabulary.commodityAttributes.associateWith { attributes[it].orEmpty() },
        )
        DirectiveKind.QUERY -> DirectiveDraft.Query(date.trim(), account.trim(), text)
        DirectiveKind.CUSTOM -> DirectiveDraft.Custom(
            date.trim(), account.trim(),
            text.split(' ').filter { it.isNotBlank() },
        )
    }
    val problems = draft.validate()

    Column(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.gap, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Wrapped onto as many lines as they need rather than scrolled sideways (device audit
        // 2026-10-10): "Document" was cut in half at the edge and Commodity, Query and Custom
        // were off screen with nothing to say they were there. Every kind is in view.
        Text(
            "To open or close an account, use More, then Accounts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DirectiveKind.entries.forEach { option ->
                FilterChip(
                    selected = kind == option,
                    onClick = { kind = option },
                    label = { Text(option.label) },
                    modifier = Modifier.semantics {
                        contentDescription = "${option.label} directive"
                    },
                )
            }
        }

        DateField(
            value = date,
            onValueChange = { date = it },
            modifier = Modifier.fillMaxWidth(),
            today = today,
        )

        // The first field means something different per directive, so it is labelled for
        // what it actually is rather than a generic "name".
        val firstLabel = when (kind) {
            DirectiveKind.PRICE, DirectiveKind.COMMODITY -> "Commodity"
            DirectiveKind.EVENT -> "Event type"
            DirectiveKind.QUERY -> "Query name"
            DirectiveKind.CUSTOM -> "Custom type"
            else -> "Account"
        }
        SuggestingField(
            value = account,
            onValueChange = { account = it },
            label = firstLabel,
            // Commodity directives name a commodity, everything else here names an account,
            // so the list offered is the one the field is actually about.
            suggestions = if (firstLabel == "Account") vocabulary.accounts else vocabulary.currencies,
            modifier = Modifier.fillMaxWidth(),
        )

        if (kind == DirectiveKind.PAD) {
            SuggestingField(
                value = second,
                onValueChange = { second = it },
                label = "Padded from",
                suggestions = vocabulary.accounts,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (kind in setOf(DirectiveKind.BALANCE, DirectiveKind.PRICE)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField(
                    value = amount,
                    onValueChange = { amount = it },
                    modifier = Modifier.weight(2f),
                )
                SuggestingField(
                    value = currency,
                    onValueChange = { currency = it.uppercase() },
                    label = "Commodity",
                    suggestions = vocabulary.currencies,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (kind == DirectiveKind.COMMODITY && vocabulary.commodityAttributes.isNotEmpty()) {
            Text(
                "This ledger asks every commodity for these details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            vocabulary.commodityAttributes.forEach { key ->
                OutlinedTextField(
                    value = attributes[key].orEmpty(),
                    onValueChange = { attributes[key] = it },
                    label = { Text(key) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val textLabel = when (kind) {
            DirectiveKind.NOTE -> "Note"
            DirectiveKind.DOCUMENT -> "Path inside the project"
            DirectiveKind.EVENT -> "Description"
            DirectiveKind.QUERY -> "BQL query"
            DirectiveKind.CUSTOM -> "Values, separated by spaces"
            else -> null
        }
        textLabel?.let {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value },
                label = { Text(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // The same promise the transaction form makes: the lines about to be written, in
        // the source face, on a surface a shade off the page.
        RuledColumn {
            LedgerHeading("Will be written as")
            Text(
                text = draft.render(currencyColumn),
                style = LedgerType.source,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .filedSweep(key = committed.takeIf { it > 0 }, colour = BeancountTheme.ledger.rule)
                    .horizontalScroll(rememberScrollState())
                    .padding(Measure.indent)
                    .semantics { contentDescription = "Preview of the directive to be saved" },
            )
        }

    }

    // On the bar, beside Save, and only once something has been typed (device audit
    // 2026-10-10): an untouched form greeted the person with a red `"" is not a valid
    // account name`. The button stays disabled either way.
    FormSaveBar(
        problems = if (dirty) problems.map { it.describe() } else emptyList(),
        message = saveState,
    ) {
        Button(
            onClick = { onSave(draft) },
            enabled = problems.isEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Save ${draft.label.lowercase()}") }
    }
    }
}
