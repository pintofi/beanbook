package dev.beancount.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.beancount.core.domain.Amount
import dev.beancount.core.domain.DraftProblem
import dev.beancount.core.domain.EntryTemplate
import dev.beancount.core.domain.LedgerVocabulary
import dev.beancount.core.domain.PostingDraft
import dev.beancount.core.domain.TransactionDraft
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One editable posting row. Empty amount means elided — Beancount works it out.
 *
 * [currency] is per posting, blank meaning "the transaction's". A single currency for the
 * whole transaction could not express a currency exchange or a foreign-card purchase, which
 * are ordinary things to want to record (B4). Blank rather than a copy of the default, so
 * changing the transaction's currency still moves every posting that never disagreed.
 */
private class PostingRow(account: String = "", amount: String = "", currency: String = "") {
    var account by mutableStateOf(account)
    var amount by mutableStateOf(amount)
    var currency by mutableStateOf(currency)

    /** Keys describing this leg only (B16). Empty for almost every posting. */
    val metadata = mutableStateListOf<MetadataRow>()
}

/** One editable metadata pair, on a transaction or on a posting. */
private class MetadataRow(key: String = "", value: String = "") {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
}

/**
 * Transaction entry: fast for the common case, complete for the rest.
 *
 * The first two rows are prefilled so a shop purchase is amount-plus-two-taps, but any
 * number of postings can be added — a split bill, a paycheque with deductions, a transfer
 * with a fee. The running imbalance is shown as you type, and **the app never balances the
 * transaction for you**: it shows the residual and offers an elided posting, which is how
 * you ask Beancount to compute it (docs/ux.md §4).
 */
@Composable
fun QuickAddScreen(
    vocabulary: LedgerVocabulary,
    onSave: (TransactionDraft) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    saveState: String? = null,
    /** A draft to start from -- an entry being entered again (B11). Applied once. */
    prefill: TransactionDraft? = null,
    onPrefillApplied: () -> Unit = {},
    templates: List<EntryTemplate> = emptyList(),
    onSaveTemplate: (EntryTemplate) -> Unit = {},
    onDeleteTemplate: (String) -> Unit = {},
) {
    var date by remember { mutableStateOf(today.toString()) }
    var payee by remember { mutableStateOf("") }
    var narration by remember { mutableStateOf("") }
    // "*" cleared, "!" pending. Beancount treats the second as "look at this again", and the
    // form had no way to say it, so every entry claimed to be settled (B15).
    var flag by remember { mutableStateOf("*") }
    // Transaction-level metadata, as editable rows so a half-typed key cannot be committed
    // by accident (B16).
    val metaRows = remember { mutableStateListOf<MetadataRow>() }
    var currency by remember {
        mutableStateOf(vocabulary.defaultCurrency ?: vocabulary.currencies.firstOrNull() ?: "USD")
    }

    val rows = remember {
        mutableStateListOf(
            PostingRow(vocabulary.accounts.firstOrNull { it.startsWith("Expenses") }.orEmpty()),
            // The second posting is elided by default: the counter-amount is Beancount's
            // to compute, not ours to guess.
            PostingRow(vocabulary.accounts.firstOrNull { it.startsWith("Assets") }.orEmpty()),
        )
    }

    val draft = TransactionDraft(
        date = date.trim(),
        flag = flag,
        payee = payee.trim().ifBlank { null },
        narration = narration.trim().ifBlank { null },
        metadata = metaRows
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value.trim() },
        postings = rows.filter { it.account.isNotBlank() }.map { row ->
            PostingDraft(
                metadata = row.metadata
                    .filter { it.key.isNotBlank() }
                    .associate { it.key.trim() to it.value.trim() },
                account = row.account.trim(),
                amount = row.amount.trim().takeIf { it.isNotBlank() }?.let { value ->
                    val denomination = row.currency.trim().ifBlank { currency.trim() }
                    runCatching { Amount(BigDecimal(value), denomination) }.getOrNull()
                },
            )
        },
    )
    val problems = draft.validate()
    val residuals = draft.residuals()

    var namingTemplate by remember { mutableStateOf(false) }

    /**
     * Applies a template through the same domain call the tests exercise, so there is one
     * definition of what a template becomes. The date stays as typed when it is a date at
     * all; a half-typed one falls back to today rather than being carried into the form.
     */
    fun applyTemplate(template: EntryTemplate) {
        val applied = template.apply(
            runCatching { LocalDate.parse(date.trim()) }.getOrDefault(today),
        )
        date = applied.date
        payee = applied.payee.orEmpty()
        narration = applied.narration.orEmpty()
        currency = template.currency
        rows.clear()
        applied.postings.forEach { posting ->
            rows.add(PostingRow(posting.account, posting.amount?.number?.toPlainString().orEmpty()))
        }
    }

    // Applied once, then reported back so re-entering the tab does not silently overwrite
    // what the user has typed since.
    LaunchedEffect(prefill) {
        prefill?.let { draft ->
            date = draft.date
            payee = draft.payee.orEmpty()
            narration = draft.narration.orEmpty()
            draft.postings.firstOrNull()?.amount?.currency?.let { currency = it }
            rows.clear()
            draft.postings.forEach { posting ->
                // Each posting keeps its own currency, so cloning a multi-currency entry
                // does not quietly redenominate half of it into the transaction's default.
                val denomination = posting.amount?.currency.orEmpty()
                rows.add(
                    PostingRow(
                        posting.account,
                        posting.amount?.number?.toPlainString().orEmpty(),
                        if (denomination == currency) "" else denomination,
                    )
                )
            }
            onPrefillApplied()
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Templates come first because that is when they are useful: before anything has
        // been typed. Applying one fills the form and stops there — the entry is still
        // previewed and validated like any other, so a template cannot write anything the
        // user has not seen (docs/ux.md 4).
        if (templates.isNotEmpty()) {
            Text("Templates", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                templates.forEach { template ->
                    AssistChip(
                        onClick = { applyTemplate(template) },
                        label = { Text(template.name) },
                        modifier = Modifier.semantics {
                            contentDescription = "Start from the template ${template.name}"
                        },
                    )
                }
            }
        }

        // Cleared or pending, as two chips rather than free text: these are the only two
        // flags Beancount accepts on a transaction, and typing is not the right way to pick
        // from a closed set of two (B15).
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = flag == "*",
                onClick = { flag = "*" },
                label = { Text("Cleared") },
                modifier = Modifier.semantics { contentDescription = "Cleared transaction" },
            )
            FilterChip(
                selected = flag == "!",
                onClick = { flag = "!" },
                label = { Text("Pending") },
                modifier = Modifier.semantics { contentDescription = "Pending transaction" },
            )
        }

        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            label = { Text("Date") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = payee,
            onValueChange = { payee = it },
            label = { Text("Payee") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Suggestions(vocabulary.payeesMatching(payee), "payee") { payee = it }

        // "Use what you used last time" — offered, never applied silently, because reusing
        // an old amount without being asked is a way to record the wrong number.
        vocabulary.lastEntryFor(payee.trim())?.let { last ->
            AssistChip(
                onClick = {
                    narration = last.narration.orEmpty()
                    // Grow to fit: reusing a three-posting entry into two rows would drop a
                    // posting silently, which is exactly the kind of quiet loss the app
                    // must not do.
                    while (rows.size < last.postings.size) rows.add(PostingRow())
                    last.postings.forEachIndexed { index, posting ->
                        rows[index].account = posting.account
                        rows[index].amount =
                            posting.amount?.number?.abs()?.toPlainString().orEmpty()
                    }
                    // Only the first posting keeps an amount; the rest stay elided, so
                    // Beancount recomputes them rather than us reusing stale numbers.
                    rows.drop(1).forEach { it.amount = "" }
                },
                label = { Text("Use last: ${last.postings.firstOrNull()?.account?.substringAfterLast(':')}") },
                modifier = Modifier.semantics {
                    contentDescription = "Reuse the last entry for ${payee.trim()}"
                },
            )
        }

        OutlinedTextField(
            value = narration,
            onValueChange = { narration = it },
            label = { Text("Description") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = currency,
            onValueChange = { currency = it.uppercase() },
            label = { Text("Currency") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Every commodity the ledger declares or holds, matched like any other field (B6).
        Suggestions(vocabulary.currenciesMatching(currency), "currency") { currency = it }

        MetadataEditor(
            rows = metaRows,
            label = "Details about the whole transaction",
            describe = { "transaction" },
        )

        Text("Postings", style = MaterialTheme.typography.titleSmall)

        // Side by side, an account and an amount stop fitting once the text is large: at
        // 200% font the amount field is narrow enough that its own label wraps mid-word.
        // Above 130% they stack instead, which costs a line and keeps both readable.
        val stacked = LocalDensity.current.fontScale > 1.3f

        rows.forEachIndexed { index, row ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val accountField: @Composable (Modifier) -> Unit = { fieldModifier ->
                    OutlinedTextField(
                        value = row.account,
                        onValueChange = { row.account = it },
                        label = { Text("Account ${index + 1}") },
                        singleLine = true,
                        modifier = fieldModifier,
                    )
                }
                val amountField: @Composable (Modifier) -> Unit = { fieldModifier ->
                    OutlinedTextField(
                        value = row.amount,
                        onValueChange = { row.amount = it },
                        label = { Text(if (index == 0) "Amount" else "Amount (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = fieldModifier,
                    )
                }
                val currencyField: @Composable (Modifier) -> Unit = { fieldModifier ->
                    OutlinedTextField(
                        value = row.currency,
                        onValueChange = { row.currency = it.uppercase() },
                        label = { Text("Cur.") },
                        placeholder = { Text(currency) },
                        singleLine = true,
                        modifier = fieldModifier.semantics {
                            contentDescription = "Currency for posting ${index + 1}"
                        },
                    )
                }
                val removeButton: @Composable () -> Unit = {
                    if (rows.size > 2) {
                        TextButton(
                            onClick = { rows.removeAt(index) },
                            modifier = Modifier.semantics {
                                contentDescription = "Remove posting ${index + 1}"
                            },
                        ) { Text("×") }
                    }
                }

                if (stacked) {
                    accountField(Modifier.fillMaxWidth())
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        amountField(Modifier.weight(2f))
                        currencyField(Modifier.weight(1f))
                        removeButton()
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        accountField(Modifier.weight(3f))
                        amountField(Modifier.weight(2f))
                        currencyField(Modifier.weight(1f))
                        removeButton()
                    }
                }
                Suggestions(vocabulary.accountsMatching(row.account), "account") {
                    row.account = it
                }
                MetadataEditor(
                    rows = row.metadata,
                    label = "Details about this posting only",
                    describe = { "posting ${index + 1}" },
                )
            }
        }

        TextButton(onClick = { rows.add(PostingRow()) }) { Text("Add posting") }

        // The imbalance is information, not an error: an elided posting is the normal way
        // to let Beancount finish the arithmetic.
        if (residuals.isNotEmpty()) {
            val elided = draft.postings.count { it.isElided }
            Text(
                text = if (elided == 1) {
                    "Beancount will put ${residuals.joinToString(", ") { it.format() }} on the " +
                        "posting with no amount."
                } else {
                    "Does not balance: ${residuals.joinToString(", ") { it.format() }}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (elided == 1) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Will be written as", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = draft.render(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics { contentDescription = "Preview of the entry to be saved" },
                )
            }
        }

        problems.forEach { problem ->
            Text(
                text = problem.describe(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Invariant 6: a retried write never creates a duplicate entry. `ProjectWriter.append`
        // cannot enforce that on its own -- it computes `expectedSha` from the file at call
        // time, so the stale-hash check that protects `replace` sees nothing wrong with the
        // second write, and the write mutex serialises the two taps rather than collapsing
        // them. Two taps on this button therefore produced two identical transactions, both
        // reported as saved.
        //
        // The guard is the draft itself: once this exact draft has been submitted the button
        // is disabled, and it comes back the moment any field changes. Somebody entering two
        // genuinely identical transactions edits something and edits it back, which is a
        // deliberate act rather than a slip.
        var submitted by remember { mutableStateOf<TransactionDraft?>(null) }

        Button(
            onClick = {
                submitted = draft
                onSave(draft)
            },
            enabled = problems.isEmpty() && draft != submitted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save entry") }

        TextButton(
            onClick = { namingTemplate = true },
            enabled = rows.any { it.account.isNotBlank() },
        ) { Text("Save as template") }

        if (namingTemplate) {
            TemplateDialog(
                draft = draft,
                fallbackCurrency = currency.trim(),
                existing = templates,
                onSave = { template ->
                    onSaveTemplate(template)
                    namingTemplate = false
                },
                onDelete = onDeleteTemplate,
                onDismiss = { namingTemplate = false },
            )
        }

        saveState?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun Suggestions(values: List<String>, kind: String, onPick: (String) -> Unit) {
    if (values.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // No cap: this row scrolls horizontally, so taking six hid matches the vocabulary had
        // already found rather than saving any space (B7).
        values.forEach { value ->
            AssistChip(
                onClick = { onPick(value) },
                label = { Text(value.substringAfterLast(':')) },
                modifier = Modifier.semantics { contentDescription = "Use $kind $value" },
            )
        }
    }
}

/**
 * Names a template, and manages the ones already saved.
 *
 * Deleting is here rather than behind a long-press because a destructive action has to name
 * its scope (CLAUDE.md, invariant 7): the row says which template goes, and nothing else in
 * the app is touched — a template is prefill, so removing one cannot affect the ledger.
 */
@Composable
private fun TemplateDialog(
    draft: TransactionDraft,
    fallbackCurrency: String,
    existing: List<EntryTemplate>,
    onSave: (EntryTemplate) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val candidate = EntryTemplate.from(name, draft, fallbackCurrency)
    val problems = candidate.validate()
    val replaces = existing.any { it.name.equals(name.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The accounts, payee, description and any amounts on the form are kept. " +
                        "The date is not: a template is applied to the date you are entering.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Template name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (replaces) {
                    Text(
                        "Replaces the template already called \"${name.trim()}\".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Shown while typing rather than on save: a template that could never
                // produce a valid entry is refused now, not on the day it is used.
                problems.filterNot { it is DraftProblem.MissingText }.forEach { problem ->
                    Text(
                        problem.describe(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (existing.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Saved templates", style = MaterialTheme.typography.titleSmall)
                    existing.forEach { template ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(template.name, style = MaterialTheme.typography.bodyMedium)
                            TextButton(
                                onClick = { onDelete(template.name) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Delete the template ${template.name}"
                                },
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(candidate) },
                enabled = problems.isEmpty(),
            ) { Text("Save template") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/**
 * Metadata pairs, on a transaction or on one posting (B16).
 *
 * Collapsed until asked for. Almost no entry carries metadata, and a pair of empty fields on
 * every posting would make the common case worse to serve the rare one.
 *
 * Keys are validated by `TransactionDraft.validate`, so a key Beancount cannot parse is
 * reported beside the others rather than written into the file and discovered on the next
 * load.
 */
@Composable
private fun MetadataEditor(
    rows: MutableList<MetadataRow>,
    label: String,
    describe: () -> String,
) {
    if (rows.isEmpty()) {
        TextButton(
            onClick = { rows.add(MetadataRow()) },
            modifier = Modifier.semantics { contentDescription = "Add a detail to ${describe()}" },
        ) { Text("Add a detail") }
        return
    }

    Text(label, style = MaterialTheme.typography.labelMedium)
    rows.forEachIndexed { index, meta ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = meta.key,
                onValueChange = { meta.key = it },
                label = { Text("Key") },
                singleLine = true,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "Detail ${index + 1} key for ${describe()}"
                },
            )
            OutlinedTextField(
                value = meta.value,
                onValueChange = { meta.value = it },
                label = { Text("Value") },
                singleLine = true,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "Detail ${index + 1} value for ${describe()}"
                },
            )
            TextButton(
                onClick = { rows.removeAt(index) },
                modifier = Modifier.semantics {
                    contentDescription = "Remove detail ${index + 1} from ${describe()}"
                },
            ) { Text("\u00d7") }
        }
    }
    TextButton(
        onClick = { rows.add(MetadataRow()) },
        modifier = Modifier.semantics { contentDescription = "Add another detail to ${describe()}" },
    ) { Text("Add another detail") }
}
