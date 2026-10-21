package io.github.pintofi.beanbook.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.onboarding.AccountChoice
import io.github.pintofi.beanbook.app.onboarding.Creation
import io.github.pintofi.beanbook.app.onboarding.SetupDraft
import io.github.pintofi.beanbook.app.ui.AmountField
import io.github.pintofi.beanbook.app.ui.DateField
import io.github.pintofi.beanbook.app.ui.StatusLine
import io.github.pintofi.beanbook.app.ui.SuggestingField
import io.github.pintofi.beanbook.app.ui.theme.ThemeChoice
import io.github.pintofi.beanbook.core.domain.AccountName
import io.github.pintofi.beanbook.core.domain.CommodityName
import io.github.pintofi.beanbook.core.domain.LedgerBlueprint
import java.util.Currency
import java.util.Locale

private typealias Update = ((SetupDraft) -> SetupDraft) -> Unit

/** Currencies some country uses today; the full list also holds ADP, AFA and other retired codes. */
private val currencyCodes: List<String> by lazy {
    Locale.getAvailableLocales()
        .mapNotNull { runCatching { Currency.getInstance(it)?.currencyCode }.getOrNull() }
        .distinct().sorted()
}

private val PAGES = listOf(
    null to "Fava's own default",
    "balance_sheet/" to "Balance sheet",
    "income_statement/" to "Income statement",
    "journal/" to "Journal",
)

@Composable
internal fun BasicsStep(draft: SetupDraft, update: Update) {
    OutlinedTextField(
        value = draft.name,
        onValueChange = { v -> update { it.copy(name = v) } },
        label = { Text("Ledger name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SuggestingField(
        value = draft.mainCurrency,
        onValueChange = { v -> update { it.copy(mainCurrency = v.uppercase(Locale.ROOT)) } },
        label = "Main currency",
        suggestions = currencyCodes,
    )
    val locale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    val named = Currency.getAvailableCurrencies().firstOrNull { it.currencyCode == draft.mainCurrency }
    Hint(
        if (named != null) "${named.getDisplayName(locale)}. Every account counts in this unless you say otherwise."
        else "A currency code such as EUR, USD or JPY. Letters and digits, starting with a capital.",
    )
    var extra by rememberSaveable { mutableStateOf("") }
    SuggestingField(
        value = extra,
        onValueChange = { extra = it.uppercase(Locale.ROOT) },
        label = "Other currencies",
        suggestions = currencyCodes - draft.currencies.toSet(),
        trailingIcon = {
            TextButton(
                onClick = {
                    val code = extra.trim()
                    if (CommodityName.isValid(code) && code !in draft.currencies) {
                        update { it.copy(otherCurrencies = it.otherCurrencies + code) }
                    }
                    extra = ""
                },
                enabled = CommodityName.isValid(extra.trim()),
            ) { Text("Add") }
        },
    )
    Hint("Only if you hold or spend in more than one currency. You can add more later.")
    if (draft.otherCurrencies.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            draft.otherCurrencies.forEach { code ->
                InputChip(
                    selected = true,
                    onClick = {
                        update { it.copy(otherCurrencies = it.otherCurrencies - code, reportIn = it.reportIn - code) }
                    },
                    label = { Text("$code  ✕") },
                )
            }
        }
    }
    DateField(
        value = draft.start,
        onValueChange = { v -> update { it.copy(start = v) } },
        label = "Books begin on",
        modifier = Modifier.fillMaxWidth(),
    )
    Hint("Entries before this date are not expected. Opening balances are dated the day before.")

    var fine by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { fine = !fine }) { Text(if (fine) "Hide fine-tuning" else "Fine-tune") }
    if (!fine) return
    draft.currencies.forEach { code ->
        OutlinedTextField(
            value = (draft.places[code] ?: SetupDraft.usualPlaces(code)).toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.takeIf { it in 0..8 }
                    ?.let { n -> update { it.copy(places = it.places + (code to n)) } }
            },
            label = { Text("Decimal places for $code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Hint("How many places amounts show. Japanese yen uses 0, most currencies use 2.")
    Text("Booking method", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("STRICT", "FIFO", "LIFO", "HIFO", "STRICT_WITH_SIZE").forEach { method ->
            FilterChip(
                selected = draft.booking == method,
                onClick = { update { it.copy(booking = method) } },
                label = { Text(method) },
            )
        }
    }
    Hint("Which lot a sale takes when you hold the same thing bought at different prices. Strict asks you to say.")
    OutlinedTextField(
        value = draft.fiscalYearEnd.orEmpty(),
        onValueChange = { v -> update { it.copy(fiscalYearEnd = v.trim().ifEmpty { null }) } },
        label = { Text("Financial year ends (MM-DD)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Hint("Leave empty for 31 December.")
    Text("Reports open on", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PAGES.forEach { (path, label) ->
            FilterChip(
                selected = draft.reportsOpenOn == path,
                onClick = { update { it.copy(reportsOpenOn = path) } },
                label = { Text(label) },
            )
        }
    }
    if (draft.otherCurrencies.isNotEmpty()) {
        Text("Also total reports in", style = MaterialTheme.typography.titleSmall)
        draft.otherCurrencies.forEach { code ->
            SwitchRow(code, draft.reportIn.contains(code)) { on ->
                update { it.copy(reportIn = if (on) it.reportIn + code else it.reportIn - code) }
            }
        }
    }
    Text("Checks", style = MaterialTheme.typography.titleSmall)
    LedgerBlueprint.Check.entries.forEach { check ->
        SwitchRow(check.label, check in draft.checks) { on ->
            update { it.copy(checks = if (on) it.checks + check else it.checks - check) }
        }
    }
    Hint(
        "Each one is a Beancount plugin that refuses a kind of mistake. " +
            "All are off by default, so nothing stops you at first.",
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun AccountsStep(draft: SetupDraft, update: Update) {
    Text(
        "Pick the accounts you will use. You can add more at any time.",
        style = MaterialTheme.typography.bodyMedium,
    )
    SwitchRow("Every account uses ${draft.mainCurrency}", draft.everyAccountUsesMain) { on ->
        update { it.copy(everyAccountUsesMain = on) }
    }
    Hint(
        if (draft.everyAccountUsesMain) "Beancount will refuse any other currency in these accounts."
        else "Type the currencies each account accepts. Leave empty to accept any.",
    )
    listOf("Assets", "Liabilities", "Income", "Expenses", "Equity").forEach { root ->
        val rows = draft.accounts.filter { it.name.substringBefore(':') == root }
        if (rows.isEmpty()) return@forEach
        Text(root, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        rows.forEach { choice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = choice.on || choice.locked,
                    enabled = !choice.locked,
                    onCheckedChange = { on ->
                        update { d ->
                            d.copy(accounts = d.accounts.map { if (it.name == choice.name) it.copy(on = on) else it })
                        }
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(choice.name)
                    if (choice.locked) Hint("Always kept: opening balances come from here.")
                }
            }
            if (!draft.everyAccountUsesMain && !choice.locked && choice.on) {
                OutlinedTextField(
                    value = choice.currencies,
                    onValueChange = { v ->
                        update { d ->
                            d.copy(
                                accounts = d.accounts.map {
                                    if (it.name == choice.name) it.copy(currencies = v) else it
                                },
                            )
                        }
                    },
                    label = { Text("Currencies for ${choice.name.substringAfterLast(':')}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
                )
            }
        }
    }
    HorizontalDivider()
    AddAccount(draft, update)
    if (!draft.accounts.any { it.on && it.name.startsWith("Assets:") }) {
        Hint("Choose at least one Assets account, so there is somewhere for money to be.")
    }
}

@Composable
private fun AddAccount(draft: SetupDraft, update: Update) {
    var root by rememberSaveable { mutableStateOf("Assets") }
    var typed by rememberSaveable { mutableStateOf("") }
    Text("Add an account", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Assets", "Liabilities", "Income", "Expenses", "Equity").forEach {
            FilterChip(selected = root == it, onClick = { root = it }, label = { Text(it) })
        }
    }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text("Name under $root") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val tidy = AccountName.normalise(typed)
    val full = "$root:$tidy"
    val problems = if (typed.isBlank()) emptyList()
    else AccountName.problems(full, existing = draft.accounts.map { it.name }).map { it.describe() }
    if (typed.isNotBlank()) Hint(problems.firstOrNull() ?: "Will be added as $full")
    OutlinedButton(
        onClick = {
            update { it.copy(accounts = it.accounts + AccountChoice(full, on = true, user = true)) }
            typed = ""
        },
        enabled = typed.isNotBlank() && problems.isEmpty(),
    ) { Text("Add account") }
}

@Composable
internal fun GetSetStep(
    draft: SetupDraft,
    update: Update,
    themeChoice: ThemeChoice,
    actions: SetupActions,
) {
    val balanceable = draft.accounts.filter {
        it.on && (it.name.startsWith("Assets:") || it.name.startsWith("Liabilities:"))
    }
    Text("What they hold on the first day", style = MaterialTheme.typography.titleMedium)
    Hint(
        "Optional. Type what each account held when the books begin; leave blank for none. " +
            "A credit card is what you owe, typed as a positive number.",
    )
    balanceable.forEach { choice ->
        AmountField(
            value = draft.opening[choice.name].orEmpty(),
            onValueChange = { v -> update { it.copy(opening = it.opening + (choice.name to v)) } },
            label = choice.name,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    HorizontalDivider()
    Text("Appearance", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeChoice.entries.forEach {
            FilterChip(
                selected = themeChoice == it,
                onClick = { actions.onThemeChoice(it) },
                label = { Text(it.label) },
            )
        }
    }
    HorizontalDivider()
    Text("Reminders", style = MaterialTheme.typography.titleMedium)
    Hint(
        "Android needs your permission to show the notice that long jobs are running. " +
            "Nothing else uses notifications.",
    )
    OutlinedButton(onClick = actions.onAskNotifications) { Text("Allow notifications") }
    HorizontalDivider()
    Text("A copy in a folder", style = MaterialTheme.typography.titleMedium)
    Hint("Keep a plain copy of the files in a folder you choose, such as one a sync app watches. Optional.")
    OutlinedButton(onClick = actions.onPickMirrorFolder) {
        Text(if (draft.mirrorFolder == null) "Choose folder" else "Change folder")
    }
    draft.mirrorFolder?.let { Hint("Chosen. Copied after every save.") }
}

@Composable
internal fun FinishPage(
    creation: Creation,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDefaults: () -> Unit,
    onFinish: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (creation) {
            is Creation.Idle, is Creation.Working -> {
                Text("Making your ledger", style = MaterialTheme.typography.headlineSmall)
                StatusLine((creation as? Creation.Working)?.what ?: "Starting")
            }
            is Creation.Done -> {
                Text("Your ledger is ready", style = MaterialTheme.typography.headlineSmall)
                Text(creation.summary, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { onFinish(creation.projectId, true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add your first entry")
                }
                OutlinedButton(onClick = { onFinish(creation.projectId, false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Go to Home")
                }
            }
            is Creation.Failed -> {
                Text("That did not work", style = MaterialTheme.typography.headlineSmall)
                Text(creation.message, style = MaterialTheme.typography.bodyMedium)
                Text("Nothing was kept. Your answers are still here.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                OutlinedButton(onClick = onDefaults, modifier = Modifier.fillMaxWidth()) {
                    Text("Create with defaults instead")
                }
                TextButton(onClick = onBack) { Text("Back to my answers") }
            }
        }
    }
}

private fun Modifier.fillMaxSize(): Modifier = this.then(Modifier.fillMaxWidth())
