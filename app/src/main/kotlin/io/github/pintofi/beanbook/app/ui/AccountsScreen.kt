package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.onboarding.Hint
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.AccountName
import io.github.pintofi.beanbook.core.domain.AccountNames
import io.github.pintofi.beanbook.core.domain.DirectiveDraft
import java.time.LocalDate

/**
 * The ledger's accounts: the open ones by tree, a form to open one, and a way to close one that
 * is empty. Closed accounts stay listed under their own heading, since Beancount keeps them and
 * old entries still point at them.
 *
 * Opening and closing write one line to `files/accounts.beancount` (or the main file when the
 * ledger does not read that one), and Beancount reads the ledger again before it is kept.
 */
@Composable
fun AccountsScreen(
    accounts: List<AccountBalance>,
    names: AccountNames,
    onSave: ((DirectiveDraft) -> Unit)?,
    message: String?,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    var closing by rememberSaveable { mutableStateOf<String?>(null) }
    val (closed, open) = accounts.partition { it.isClosed }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .readableMeasureCentred()
            .padding(horizontal = Measure.gutter, vertical = Measure.gap),
        verticalArrangement = Arrangement.spacedBy(Measure.gap),
    ) {
        message?.let { StatusLine(it) }

        Text("Open accounts", style = MaterialTheme.typography.titleMedium)
        if (open.isEmpty()) Hint("No account is open yet.")
        names.roots.forEach { root ->
            val rows = open.filter { it.name.substringBefore(':') == root }
            if (rows.isEmpty()) return@forEach
            Text(root, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Measure.snug))
            rows.forEach { account ->
                AccountRow(account, canClose = onSave != null, onClose = { closing = account.name })
            }
        }
        if (onSave != null) OpenAccountForm(names, accounts.map { it.name }, today, onSave)
        if (closed.isNotEmpty()) {
            Text(
                "Closed accounts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Measure.gap),
            )
            closed.forEach { account ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(account.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Hint("Closed on ${account.closed}")
                }
            }
        }
    }
    closing?.let { name ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text("Close $name?") },
            text = { Text("Beancount will refuse new entries to it from today. Its old entries stay.") },
            confirmButton = {
                TextButton(onClick = {
                    onSave?.invoke(DirectiveDraft.Close(today.toString(), name))
                    closing = null
                }) { Text("Close it") }
            },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("Keep it open") } },
        )
    }
}

@Composable
private fun AccountRow(account: AccountBalance, canClose: Boolean, onClose: () -> Unit) {
    val empty = account.positions.all { it.number.signum() == 0 }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(account.name)
            val held = account.positions.filter { it.number.signum() != 0 }
            Hint(
                buildString {
                    append(if (held.isEmpty()) "Empty" else held.joinToString(", ") { it.format() })
                    if (account.allowed.isNotEmpty()) append(" · only ${account.allowed.joinToString(", ")}")
                },
            )
        }
        if (canClose && empty && account.opened != null) {
            SecondaryButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "Close the account ${account.name}" },
            ) { Text("Close") }
        }
    }
}

@Composable
private fun OpenAccountForm(
    names: AccountNames,
    existing: List<String>,
    today: LocalDate,
    onSave: (DirectiveDraft) -> Unit,
) {
    var root by rememberSaveable { mutableStateOf(names.roots.first()) }
    var typed by rememberSaveable { mutableStateOf("") }
    var currencies by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    Text("Open an account", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.roots.forEach { FilterChip(selected = root == it, onClick = { root = it }, label = { Text(it) }) }
    }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text("Name under $root") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val full = "$root:${AccountName.normalise(typed)}"
    val problems = if (typed.isBlank()) emptyList()
    else AccountName.problems(full, names, existing).map { it.describe(names) }
    if (typed.isNotBlank()) Hint(problems.firstOrNull() ?: "Will be opened as $full")
    OutlinedTextField(
        value = currencies,
        onValueChange = { currencies = it },
        label = { Text("Currencies (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Hint("Leave empty to accept any. Otherwise Beancount refuses other currencies in this account.")
    DateField(value = date, onValueChange = { date = it }, label = "Open from", modifier = Modifier.fillMaxWidth())
    val codes = currencies.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    val draft = DirectiveDraft.Open(date = date, account = full, currencies = codes)
    val invalid = draft.validate().map { it.describe() }
    if (typed.isNotBlank() && problems.isEmpty() && invalid.isNotEmpty()) Hint(invalid.first())
    Button(
        onClick = {
            onSave(draft)
            typed = ""
            currencies = ""
        },
        enabled = typed.isNotBlank() && problems.isEmpty() && invalid.isEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Open account") }
}
