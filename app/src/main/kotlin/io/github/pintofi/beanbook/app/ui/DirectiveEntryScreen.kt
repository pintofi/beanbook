package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import java.math.BigDecimal
import java.time.LocalDate
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import androidx.compose.foundation.background

/** The directive types the app can compose, in the order people reach for them. */
enum class DirectiveKind(val label: String) {
    OPEN("Open"),
    CLOSE("Close"),
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
 * Editors for every directive that is not a transaction (SPEC.md §2.1).
 *
 * A ledger where you can record a payment but cannot open the account it came from is not
 * a usable ledger, so these are part of the free tier alongside transactions.
 */
@Composable
fun DirectiveEntryScreen(
    vocabulary: LedgerVocabulary,
    onSave: (DirectiveDraft) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    saveState: String? = null,
    /** Whether anything has been typed that leaving would lose (B61). */
    onDirtyChange: (Boolean) -> Unit = {},
) {
    var kind by remember { mutableStateOf(DirectiveKind.OPEN) }
    var date by remember { mutableStateOf(today.toString()) }
    var account by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(vocabulary.currencies.firstOrNull() ?: "USD") }

    // The date and the pre-selected currency are the form's own doing, so neither counts.
    val dirty = account.isNotBlank() || second.isNotBlank() || text.isNotBlank() || amount.isNotBlank()
    androidx.compose.runtime.LaunchedEffect(dirty) { onDirtyChange(dirty) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onDirtyChange(false) } }

    val parsedAmount = remember(amount, currency) {
        runCatching { Amount(BigDecimal(amount.trim()), currency.trim()) }.getOrNull()
    }

    val draft: DirectiveDraft = when (kind) {
        DirectiveKind.OPEN -> DirectiveDraft.Open(
            date.trim(), account.trim(),
            currency.trim().takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty(),
        )
        DirectiveKind.CLOSE -> DirectiveDraft.Close(date.trim(), account.trim())
        DirectiveKind.BALANCE -> DirectiveDraft.Balance(date.trim(), account.trim(), parsedAmount)
        DirectiveKind.PAD -> DirectiveDraft.Pad(date.trim(), account.trim(), second.trim())
        DirectiveKind.NOTE -> DirectiveDraft.Note(date.trim(), account.trim(), text)
        DirectiveKind.PRICE -> DirectiveDraft.Price(date.trim(), account.trim(), parsedAmount)
        DirectiveKind.DOCUMENT -> DirectiveDraft.Document(date.trim(), account.trim(), text.trim())
        DirectiveKind.EVENT -> DirectiveDraft.Event(date.trim(), account.trim(), text)
        DirectiveKind.COMMODITY -> DirectiveDraft.Commodity(date.trim(), account.trim())
        DirectiveKind.QUERY -> DirectiveDraft.Query(date.trim(), account.trim(), text)
        DirectiveKind.CUSTOM -> DirectiveDraft.Custom(
            date.trim(), account.trim(),
            text.split(' ').filter { it.isNotBlank() },
        )
    }
    val problems = draft.validate()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.gap, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(2f),
                )
                SuggestingField(
                    value = currency,
                    onValueChange = { currency = it.uppercase() },
                    label = "Currency",
                    suggestions = vocabulary.currencies,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (kind == DirectiveKind.OPEN) {
            SuggestingField(
                value = currency,
                onValueChange = { currency = it.uppercase() },
                label = "Currency (optional)",
                suggestions = vocabulary.currencies,
                modifier = Modifier.fillMaxWidth(),
            )
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
                text = draft.render(),
                style = LedgerType.source,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(Measure.indent)
                    .semantics { contentDescription = "Preview of the directive to be saved" },
            )
        }

        if (problems.isNotEmpty()) {
            Notice(NoticeTone.PROBLEM) {
                problems.forEach { Text(it.describe(), style = MaterialTheme.typography.bodyMedium) }
            }
        }

        Button(
            onClick = { onSave(draft) },
            enabled = problems.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save ${draft.label.lowercase()}") }

        saveState?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
