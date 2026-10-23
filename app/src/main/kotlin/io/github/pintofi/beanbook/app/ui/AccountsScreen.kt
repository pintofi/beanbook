package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        FolioPage(modifier = Modifier.readableMeasureCentred()) {
            message?.let { StatusLine(it, modifier = Modifier.padding(horizontal = Measure.tight)) }

            if (open.isEmpty()) FolioNote("No account is open yet.")
            names.roots.forEach { root ->
                val rows = open.filter { it.name.substringBefore(':') == root }
                if (rows.isEmpty()) return@forEach
                FolioHeading(root)
                FolioGroup {
                    rows.forEach { account ->
                        item {
                            AccountRow(account, canClose = onSave != null, onClose = { closing = account.name })
                        }
                    }
                }
            }

            if (onSave != null) {
                FolioHeading("Open an account")
                FolioGroup {
                    item { FolioBody { OpenAccountForm(names, accounts.map { it.name }, today, onSave) } }
                }
            }

            if (closed.isNotEmpty()) {
                FolioHeading("Closed accounts")
                FolioGroup {
                    closed.forEach { account ->
                        item { FolioRow(title = account.name, detail = "Closed on ${account.closed}") }
                    }
                }
                FolioNote("Beancount keeps a closed account, and old entries still point at it.")
            }
        }
    }
    closing?.let { name ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text("Close $name?") },
            text = { Text("Beancount will refuse new entries to it from today. Its old entries stay.") },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    onSave?.invoke(DirectiveDraft.Close(today.toString(), name))
                    closing = null
                }) { Text("Close it") }
            },
            dismissButton = { OutlinedButton(onClick = { closing = null }) { Text("Keep it open") } },
        )
    }
}

@Composable
private fun AccountRow(account: AccountBalance, canClose: Boolean, onClose: () -> Unit) {
    val empty = account.positions.all { it.number.signum() == 0 }
    val held = account.positions.filter { it.number.signum() != 0 }
    val detail = buildString {
        append(if (held.isEmpty()) "Empty" else held.joinToString(", ") { it.format() })
        if (account.allowed.isNotEmpty()) append(" · only ${account.allowed.joinToString(", ")}")
    }
    FolioRow(title = account.name, detail = detail) {
        if (canClose && empty && account.opened != null) {
            OutlinedButton(
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
