package dev.beancount.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.beancount.core.domain.Amount
import dev.beancount.core.domain.LedgerVocabulary
import dev.beancount.core.domain.PostingDraft
import dev.beancount.core.domain.TransactionDraft
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Quick-add: the fast path for the common case — one amount, two accounts.
 *
 * Two product rules are visible in the code:
 *
 *  * **The generated text is shown before it is written** (docs/ux.md §4). What the user
 *    approves is exactly what lands in the file.
 *  * **Nothing is silently balanced.** The second posting is left elided so Beancount works
 *    out the counter-amount, which is the honest way to do it — the app does not compute a
 *    number and pretend Beancount did.
 */
@Composable
fun QuickAddScreen(
    vocabulary: LedgerVocabulary,
    onSave: (TransactionDraft) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    saveState: String? = null,
) {
    var date by remember { mutableStateOf(today.toString()) }
    var payee by remember { mutableStateOf("") }
    var narration by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var currency by remember {
        mutableStateOf(vocabulary.currencies.firstOrNull() ?: "USD")
    }
    var fromAccount by remember {
        mutableStateOf(vocabulary.accounts.firstOrNull { it.startsWith("Assets") }.orEmpty())
    }
    var toAccount by remember {
        mutableStateOf(vocabulary.accounts.firstOrNull { it.startsWith("Expenses") }.orEmpty())
    }

    val parsedAmount = remember(amount, currency) {
        runCatching { Amount(BigDecimal(amount.trim()), currency) }.getOrNull()
    }

    val draft = TransactionDraft(
        date = date.trim(),
        payee = payee.trim().ifBlank { null },
        narration = narration.trim().ifBlank { null },
        postings = listOfNotNull(
            parsedAmount?.let { PostingDraft(toAccount.trim(), it) },
            PostingDraft(fromAccount.trim()),
        ),
    )
    val problems = draft.validate()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        OutlinedTextField(
            value = narration,
            onValueChange = { narration = it },
            label = { Text("Description") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount") },
                singleLine = true,
                // Decimal keyboard, never a float anywhere in the path: the text is parsed
                // straight into BigDecimal (invariant 1).
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = currency,
                onValueChange = { currency = it.uppercase() },
                label = { Text("Currency") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = toAccount,
            onValueChange = { toAccount = it },
            label = { Text("Category (debit)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Suggestions(vocabulary.accountsMatching(toAccount), "category") { toAccount = it }

        OutlinedTextField(
            value = fromAccount,
            onValueChange = { fromAccount = it },
            label = { Text("Paid from (credit)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Suggestions(vocabulary.accountsMatching(fromAccount), "account") { fromAccount = it }

        // The preview is not decoration: it is the contract with the user about what will
        // be written.
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

        Button(
            onClick = { onSave(draft) },
            enabled = problems.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save entry")
        }

        saveState?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun Suggestions(values: List<String>, kind: String, onPick: (String) -> Unit) {
    if (values.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.take(6).forEach { value ->
            AssistChip(
                onClick = { onPick(value) },
                label = { Text(value.substringAfterLast(':')) },
                modifier = Modifier.semantics { contentDescription = "Use $kind $value" },
            )
        }
    }
}
