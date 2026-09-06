package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import io.github.pintofi.beanbook.core.domain.PostingDraft
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import java.math.BigDecimal

/** One posting being edited, as three boxes rather than a rendered line. */
private class EditRow(account: String, amount: String, currency: String) {
    var account by mutableStateOf(account)
    var amount by mutableStateOf(amount)
    var currency by mutableStateOf(currency)
}

/** One metadata pair being edited. */
private class MetaRow(key: String, value: String) {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
}

/**
 * Edits one entry as fields, the way Add composes one (B45).
 *
 * Offered only for an entry the app can prove it describes completely: [io.github.pintofi
 * .beanbook.core.domain.StructuredEdit] renders the draft and compares it with the bytes in
 * the file first, and an entry that does not match is edited as text instead. That check is
 * what makes a form safe here at all — decomposing an entry the draft only partly models
 * would drop the rest on the way back out, which invariant 4 forbids.
 *
 * Tags and links are carried through untouched and shown, so an entry that has them can be
 * edited without them quietly disappearing and without this dialog growing a chip editor.
 */
@Composable
fun EntryFormDialog(
    original: TransactionDraft,
    vocabulary: LedgerVocabulary,
    onSave: (TransactionDraft) -> Unit,
    onEditAsText: () -> Unit,
    onDismiss: () -> Unit,
) {
    var date by remember(original) { mutableStateOf(original.date) }
    var flag by remember(original) { mutableStateOf(original.flag) }
    var payee by remember(original) { mutableStateOf(original.payee.orEmpty()) }
    var narration by remember(original) { mutableStateOf(original.narration.orEmpty()) }
    val rows = remember(original) {
        mutableStateListOf(
            *original.postings.map { posting ->
                EditRow(
                    account = posting.account,
                    amount = posting.amount?.number?.toPlainString().orEmpty(),
                    currency = posting.amount?.currency.orEmpty(),
                )
            }.toTypedArray(),
        )
    }
    val metaRows = remember(original) {
        mutableStateListOf(*original.metadata.map { MetaRow(it.key, it.value) }.toTypedArray())
    }
    val fallbackCurrency = original.postings.firstNotNullOfOrNull { it.amount?.currency }
        ?: vocabulary.defaultCurrency
        ?: vocabulary.currencies.firstOrNull()
        ?: ""

    val draft = original.copy(
        date = date.trim(),
        flag = flag,
        payee = payee.trim().ifBlank { null },
        narration = narration.trim().ifBlank { null },
        metadata = metaRows.filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value.trim() },
        postings = rows.filter { it.account.isNotBlank() }.map { row ->
            PostingDraft(
                account = row.account.trim(),
                amount = row.amount.trim().takeIf { it.isNotBlank() }?.let { value ->
                    runCatching {
                        Amount(BigDecimal(value), row.currency.trim().ifBlank { fallbackCurrency })
                    }.getOrNull()
                },
            )
        },
    )
    val problems = draft.validate()
    val stacked = LocalDensity.current.fontScale > 1.3f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit this entry") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DateField(
                    value = date,
                    onValueChange = { date = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("*" to "Cleared", "!" to "Pending").forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = flag == value,
                            onClick = { flag = value },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
                SuggestingField(
                    value = payee,
                    onValueChange = { payee = it },
                    label = "Payee",
                    suggestions = vocabulary.payees,
                    modifier = Modifier.fillMaxWidth(),
                )
                SuggestingField(
                    value = narration,
                    onValueChange = { narration = it },
                    label = "Narration",
                    suggestions = vocabulary.narrations,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (original.tags.isNotEmpty() || original.links.isNotEmpty()) {
                    Text(
                        "Kept as they are: " +
                            (original.tags.map { "#$it" } + original.links.map { "^$it" })
                                .joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text("Postings", style = MaterialTheme.typography.titleSmall)
                rows.forEachIndexed { index, row ->
                    val accountField: @Composable (Modifier) -> Unit = { fieldModifier ->
                        SuggestingField(
                            value = row.account,
                            onValueChange = { row.account = it },
                            label = "Account ${index + 1}",
                            suggestions = vocabulary.accounts,
                            describe = "Account for posting ${index + 1}",
                            modifier = fieldModifier,
                        )
                    }
                    val amountField: @Composable (Modifier) -> Unit = { fieldModifier ->
                        OutlinedTextField(
                            value = row.amount,
                            onValueChange = { row.amount = it },
                            label = { Text("Amount") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = fieldModifier,
                        )
                    }
                    val currencyField: @Composable (Modifier) -> Unit = { fieldModifier ->
                        SuggestingField(
                            value = row.currency,
                            onValueChange = { row.currency = it.uppercase() },
                            label = "Cur.",
                            suggestions = vocabulary.currencies,
                            describe = "Currency for posting ${index + 1}",
                            modifier = fieldModifier,
                        )
                    }
                    if (stacked) {
                        accountField(Modifier.fillMaxWidth())
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            amountField(Modifier.weight(2f))
                            currencyField(Modifier.weight(1f))
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            accountField(Modifier.weight(1f))
                            amountField(Modifier.width(AMOUNT_FIELD_WIDTH))
                            currencyField(Modifier.width(CURRENCY_FIELD_WIDTH))
                        }
                    }
                }
                TextButton(onClick = { rows.add(EditRow("", "", "")) }) { Text("Add a posting") }

                if (metaRows.isNotEmpty()) {
                    Text("Details", style = MaterialTheme.typography.titleSmall)
                    metaRows.forEach { meta ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            OutlinedTextField(
                                value = meta.key,
                                onValueChange = { meta.key = it },
                                label = { Text("Key") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = meta.value,
                                onValueChange = { meta.value = it },
                                label = { Text("Value") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                TextButton(onClick = { metaRows.add(MetaRow("", "")) }) { Text("Add a detail") }

                // The exact text this becomes, before it is written -- the same promise the
                // Add form makes: what the user approves is what lands in the file.
                Text("This entry will be written as", style = MaterialTheme.typography.titleSmall)
                Text(
                    draft.render(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    modifier = Modifier.heightIn(max = 200.dp),
                )
                problems.forEach { problem ->
                    Text(
                        problem.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onEditAsText) { Text("Edit as text instead") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = problems.isEmpty()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Puts the right entry editor on screen: fields when the draft was proved complete, text
 * otherwise (B45).
 *
 * A composable rather than a block inside the activity, so the choice between the two lives
 * beside the dialogs it chooses between.
 */
@Composable
fun EntryEditHost(
    slice: io.github.pintofi.beanbook.engine.local.EntrySlice,
    draft: TransactionDraft?,
    ledger: io.github.pintofi.beanbook.core.domain.LedgerSnapshot?,
    onSave: (String) -> Unit,
    onEditAsText: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (draft != null && ledger != null) {
        EntryFormDialog(
            original = draft,
            vocabulary = LedgerVocabulary.from(ledger),
            onSave = { edited -> onSave(edited.render()) },
            onEditAsText = onEditAsText,
            onDismiss = onDismiss,
        )
    } else {
        EntryEditDialog(original = slice.text, onSave = onSave, onDismiss = onDismiss)
    }
}
