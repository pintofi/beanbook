package dev.beancount.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import dev.beancount.core.entitlement.Entitlement
import dev.beancount.core.entitlement.Grant
import dev.beancount.core.entitlement.PurchaseRoute

/**
 * Contribute: what is unlocked, why, and how to enter a key (docs/distribution.md §4.7).
 *
 * The screen states plainly that the app is GPL-2.0, that anyone can build it without a key,
 * and that a key is a contribution rather than a technical restriction. There is no
 * countdown, no nagging modal, and — because [PurchaseRoute.EnterKeyOnly] neither transacts
 * nor links out — no payment method is named or linked from anywhere in the app.
 */
@Composable
fun ContributeScreen(
    entitlement: Entitlement,
    onApplyKey: (String) -> Unit,
    onRemoveKey: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    route: PurchaseRoute = PurchaseRoute.EnterKeyOnly,
) {
    var typed by remember { mutableStateOf("") }
    var confirmingRemoval by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Contribute", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What you have now", style = MaterialTheme.typography.titleSmall)
                Text(entitlement.describeGrant())
            }
        }

        Text(
            "This app is free software under the GPL-2.0. You can build it yourself from " +
                "source and every feature works, with no key. A key is a way to support the " +
                "work, not a lock on your data — your ledger, the journal, balances, raw " +
                "editing, import, export, backup and restore are free and always will be.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        Text(route.label, style = MaterialTheme.typography.titleSmall)
        Text(
            "Paste the key from your confirmation email.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Contribution key") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Contribution key" },
        )
        Button(
            onClick = { onApplyKey(typed) },
            enabled = typed.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Apply key") }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        (entitlement.grant as? Grant.Key)?.let { key ->
            HorizontalDivider()
            Text("Your key", style = MaterialTheme.typography.titleSmall)
            // The email is shown so the holder can recognise their own key. The key itself is
            // not printed here: it is in their email, and a screen anyone can photograph is
            // not the place for it.
            Text(
                "${key.tier.label} · issued to ${key.email} · order ${key.orderId}",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { confirmingRemoval = true }) { Text("Remove this key") }
        }

        TextButton(onClick = onClose) { Text("Close") }
    }

    if (confirmingRemoval) {
        val key = entitlement.grant as? Grant.Key
        AlertDialog(
            onDismissRequest = { confirmingRemoval = false },
            title = { Text("Remove this key?") },
            // Names its scope, like every destructive action in the app — and says the part
            // that stops this being frightening: nothing is consumed by removing it.
            text = {
                Text(
                    "The ${key?.tier?.label ?: ""} key issued to ${key?.email ?: ""} will be " +
                        "removed from this device. Your ledger is untouched, and the key " +
                        "keeps working — you can enter it again here or on another device.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveKey()
                        confirmingRemoval = false
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoval = false }) { Text("Keep it") }
            },
        )
    }
}

/** The reason for the current tier, in the user's words. */
internal fun Entitlement.describeGrant(): String = when (val current = grant) {
    is Grant.Trial ->
        "Trial: ${trialDaysRemaining ?: 0} days left — everything is unlocked."
    is Grant.TrialEnded ->
        "The trial has ended. Everything free is still here and your ledger is untouched; " +
            "the ${dev.beancount.core.entitlement.Tier.INSIGHT.label}, " +
            "${dev.beancount.core.entitlement.Tier.WORKFLOW.label} and " +
            "${dev.beancount.core.entitlement.Tier.POWER.label} features are waiting for a key."
    is Grant.Key -> "${current.tier.label}, unlocked by your key."
    Grant.Free -> "The free tier — fully functional, and yours permanently."
}
