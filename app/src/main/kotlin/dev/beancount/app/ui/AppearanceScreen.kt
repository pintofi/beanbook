package dev.beancount.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.beancount.app.ui.theme.BeancountTheme
import dev.beancount.app.ui.theme.ThemeChoice

/**
 * How the app looks.
 *
 * Small on purpose. Every option here is one someone might genuinely need — dark mode for
 * reading at night, dynamic colour off when a wallpaper produces a palette that fights a
 * table of numbers — and none of it touches a ledger.
 */
@Composable
fun AppearanceScreen(
    choice: ThemeChoice,
    dynamicColour: Boolean,
    dynamicColourSupported: Boolean,
    onChoice: (ThemeChoice) -> Unit,
    onDynamicColour: (Boolean) -> Unit,
    hideWidgetAmounts: Boolean,
    onHideWidgetAmounts: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)

        Text("Theme", style = MaterialTheme.typography.titleSmall)
        ThemeChoice.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The whole row selects, so the target is the row and not a 20dp circle.
                    .selectable(
                        selected = choice == option,
                        onClick = { onChoice(option) },
                        role = Role.RadioButton,
                    )
                    .semantics { contentDescription = "Theme: ${option.label}" }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = choice == option, onClick = null)
                Text(option.label, style = MaterialTheme.typography.bodyMedium)
            }
        }

        HorizontalDivider()

        if (dynamicColourSupported) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = dynamicColour,
                        onValueChange = onDynamicColour,
                        role = Role.Switch,
                    )
                    .semantics { contentDescription = "Use colours from my wallpaper" }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // weight(1f), not padding: with SpaceBetween alone the text column takes
                // whatever width it wants and pushes the switch off the right edge of the
                // screen. It did exactly that, and the switch was unreachable.
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Colours from my wallpaper", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Match the rest of your phone. Turn it off if your wallpaper gives " +
                            "the app a palette that makes the numbers harder to read.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = dynamicColour, onCheckedChange = null)
            }
        } else {
            Text(
                "Colours from your wallpaper need Android 12 or newer. This device uses the " +
                    "app's own palette.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = hideWidgetAmounts,
                    onValueChange = onHideWidgetAmounts,
                    role = Role.Switch,
                )
                .semantics { contentDescription = "Hide amounts on the home screen" }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Hide amounts in widgets", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Widgets show a label and a time but no figures. They are hidden on the " +
                        "lock screen regardless, where anyone who picks up your phone can " +
                        "read them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = hideWidgetAmounts, onCheckedChange = null)
        }

        HorizontalDivider()

        // A preview, because a colour choice you cannot see the effect of is a colour choice
        // people make once and never revisit.
        Text("Preview", style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The two sides of one transaction, because that is where the app actually
                // colours a figure: a posting is a movement and its direction is the point.
                // Balances are left in the ordinary colour, as Fava leaves them — an Income
                // or Equity account is negative all the time, and colouring balances by sign
                // would mark the healthiest accounts in the ledger as though they were wrong.
                //
                // The previous preview showed "Income:Salary 3,000.00" in green, which is
                // neither what the app does nor what Beancount reports: income is negative.
                AmountRow("Expenses:Food:Groceries", "262.00", positive = true)
                AmountRow("Assets:Bank:Checking", "-262.00", positive = false)
                Text(
                    "Money in and money out, on the two sides of one entry. Balances are not " +
                        "coloured: in double-entry a negative balance is ordinary.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Figures use a fixed-width face so decimal points line up down a column.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AmountRow(account: String, amount: String, positive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            account,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            amount,
            style = BeancountTheme.amount,
            textAlign = TextAlign.End,
            color = if (positive) BeancountTheme.ledger.positive else BeancountTheme.ledger.negative,
        )
    }
}
