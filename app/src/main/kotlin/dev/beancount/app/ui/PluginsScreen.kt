package dev.beancount.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.beancount.core.data.PluginIntegrity
import dev.beancount.core.domain.PluginPolicy
import dev.beancount.app.plugins.PluginRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installed plugins: what is here, where it came from, and whether it runs.
 *
 * The screen's job is to be honest about one thing above all. A plugin is somebody else's
 * program running with this app's permissions — it can read and change the ledger, and
 * reach the network if the app can. Android offers no way to isolate it and still let Fava
 * use it in-process, so the app does not claim safety (docs/threat-model.md §3.2). That
 * warning sits above the list, not behind a link.
 */
@Composable
fun PluginsScreen(
    rows: List<PluginRow>,
    safeMode: Boolean,
    policy: PluginPolicy,
    onInstall: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onSetSafeMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Plugins", style = MaterialTheme.typography.titleMedium)

        UnsandboxedWarning(policy)

        Button(
            onClick = onInstall,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Install a plugin from a file" },
        ) { Text("Install from a file") }

        if (!policy.allowsNativeCode) {
            Text(
                "This edition installs pure-Python plugins only. A package containing " +
                    "compiled code is refused, and the reason is shown rather than a " +
                    "generic failure.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        message?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()

        // The whole row toggles, not just the switch at the far edge. A row that reads as
        // one control but only responds within 48dp of its right-hand side is a row people
        // tap twice and conclude is broken — and on a wide screen the switch is a long way
        // from the words that explain it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = safeMode,
                    onValueChange = onSetSafeMode,
                    role = Role.Switch,
                )
                .semantics { contentDescription = "Safe mode: hold every plugin back" }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.padding(end = 12.dp)) {
                Text("Safe mode", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Load no plugins at all, without forgetting which ones you had on. " +
                        "Turn it off and they come back exactly as they were.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // null: the row above owns the interaction, so assistive technology announces
            // one control rather than two that do the same thing.
            Switch(checked = safeMode, onCheckedChange = null)
        }

        HorizontalDivider()

        if (rows.isEmpty()) {
            Text(
                "No plugins installed. Your ledger works perfectly well without any — they " +
                    "add extra checks and transformations that Beancount itself does not.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            rows.forEach { row ->
                PluginCard(
                    row = row,
                    dimmed = safeMode,
                    onSetEnabled = onSetEnabled,
                    onRemove = onRemove,
                )
            }
        }
    }
}

/**
 * Says what running a plugin actually means, before the user does it.
 *
 * Worded in terms of what the code can reach, not as a security abstraction, and it does not
 * differ between editions in substance — the Play restrictions are policy compliance, not a
 * sandbox, and implying otherwise would be the one genuinely dishonest thing this screen
 * could do.
 */
@Composable
private fun UnsandboxedWarning(policy: PluginPolicy) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "What running a plugin means" },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("A plugin is a program you are trusting", style = MaterialTheme.typography.titleSmall)
            Text(
                "It runs with this app's permissions. It can read your whole ledger, change " +
                    "it, and use the network. There is no way to run one and not give it " +
                    "that, so only install plugins from somewhere you would take a program " +
                    "from.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!policy.allowsAndroidBridge) {
                Text(
                    "This edition also keeps plugins away from Android's own APIs. That is a " +
                        "store rule, not protection for your ledger — the paragraph above " +
                        "still applies in full.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PluginCard(
    row: PluginRow,
    dimmed: Boolean,
    onSetEnabled: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    var confirming by remember(row.plugin.id) { mutableStateOf(false) }
    val plugin = row.plugin

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(plugin.name, style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = { onSetEnabled(plugin.id, it) },
                    modifier = Modifier.semantics {
                        contentDescription = "Run ${plugin.name} when the ledger loads"
                    },
                )
            }

            if (dimmed && plugin.enabled) {
                Text(
                    "On, but held back while safe mode is on.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Provenance, because "where did this come from" is the question a user asks
            // about executable code six months later (docs/threat-model.md §3.2).
            Text(
                "From ${plugin.sourceLabel} · added ${dateOf(plugin.installedAtEpochMs)} · " +
                    "installed under the ${plugin.installedUnderPolicy} edition",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Checksum ${plugin.sourceSha256.take(12)}…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics {
                    contentDescription =
                        "Checksum of the file as installed, ${plugin.sourceSha256.take(12)}"
                },
            )

            when (val integrity = row.integrity) {
                PluginIntegrity.Unchanged -> Unit
                is PluginIntegrity.Changed -> Warning(
                    "The files have changed since you installed this. That may have been you " +
                        "editing it, or it may not — if you did not change it, turn it off " +
                        "until you know why it is different.",
                )
                PluginIntegrity.Missing -> Warning(
                    "Its files are gone. It cannot run; remove it to tidy up.",
                )
            }

            if (plugin.modules.isNotEmpty()) {
                Text(
                    "Provides: " + plugin.modules.joinToString(", ").take(200),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (confirming) {
                Text(
                    "Remove \"${plugin.name}\" and delete its files from this project? Your " +
                        "ledger is not touched.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { confirming = false; onRemove(plugin.id) },
                        modifier = Modifier.semantics {
                            contentDescription = "Confirm removing ${plugin.name}"
                        },
                    ) { Text("Remove it") }
                    TextButton(onClick = { confirming = false }) { Text("Keep it") }
                }
            } else {
                TextButton(
                    onClick = { confirming = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Remove ${plugin.name}"
                    },
                ) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun dateOf(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMillis))
