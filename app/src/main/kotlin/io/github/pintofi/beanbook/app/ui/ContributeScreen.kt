package io.github.pintofi.beanbook.app.ui

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
import io.github.pintofi.beanbook.core.entitlement.Entitlement
import io.github.pintofi.beanbook.core.entitlement.Grant
import io.github.pintofi.beanbook.app.billing.PurchaseController
import io.github.pintofi.beanbook.core.entitlement.PurchaseRoute
import io.github.pintofi.beanbook.core.entitlement.Tier

/**
 * Contribute: what is unlocked, why, and how to enter a key (docs/distribution.md §4.7).
 *
 * The screen states plainly that the app is GPL-2.0, that anyone can build it without a key,
 * and that a contribution is support rather than a technical restriction. There is no
 * countdown and no nagging modal.
 *
 * **One route per edition, and never both at once.** The Play edition sells through Google
 * Play's billing system and does not offer a key field: accepting a key bought elsewhere to
 * unlock features inside a Play-distributed app is the arrangement the Payments policy is
 * about, and offering both would be doing it in the open. The Expert edition is not on Play,
 * so it takes a key and shows no prices. Neither edition links out to a payment page.
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
    offers: List<PurchaseController.TierOffer> = emptyList(),
    onBuy: (Tier) -> Unit = {},
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
            "Beanbook is free software under the GPL-2.0, and you can build it from source " +
                "with every feature working. Contributing supports the work rather than " +
                "unlocking your data: the ledger, journal, balances, raw editing, import, " +
                "export, backup and restore are free in every tier.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        Text(route.label, style = MaterialTheme.typography.titleSmall)

        if (route.transactsInApp) {
            if (offers.isEmpty()) {
                // Billing could not start, or the store has not answered yet. Said plainly,
                // because a screen with a heading and nothing under it reads as broken.
                Text(
                    "Prices are not available right now. This needs Google Play, and " +
                        "everything you already have keeps working without it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "A one-time contribution, through Google Play. Each tier includes the " +
                        "ones before it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                offers.forEach { offer ->
                    val owned = entitlement.tier.includes(offer.tier)
                    Button(
                        onClick = { onBuy(offer.tier) },
                        enabled = !owned,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription =
                                    if (owned) "${offer.tier.label}, already unlocked"
                                    else "Contribute ${offer.formattedPrice} for ${offer.tier.label}"
                            },
                    ) {
                        Text(
                            if (owned) "${offer.tier.label} — unlocked"
                            else "${offer.tier.label} · ${offer.formattedPrice}",
                        )
                    }
                }
            }
        } else {
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
        }

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
        "Trial: ${trialDaysRemaining ?: 0} days left. Everything is unlocked."
    is Grant.TrialEnded ->
        "The trial has ended. Your ledger is untouched and everything free still works. " +
            "${io.github.pintofi.beanbook.core.entitlement.Tier.INSIGHT.label}, " +
            "${io.github.pintofi.beanbook.core.entitlement.Tier.WORKFLOW.label} and " +
            "${io.github.pintofi.beanbook.core.entitlement.Tier.POWER.label} are paid."
    is Grant.Key -> "${current.tier.label}, unlocked by your key."
    is Grant.Purchase ->
        "${current.tier.label}, bought through Google Play. Reinstalling restores it."
    Grant.Free -> "Free tier. Fully functional, and yours permanently."
}
