package io.github.pintofi.beanbook.app.ui

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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.data.PluginIntegrity
import io.github.pintofi.beanbook.core.domain.PluginPolicy
import io.github.pintofi.beanbook.app.engine.EngineRestartUi
import io.github.pintofi.beanbook.app.packages.PackagesStatus
import io.github.pintofi.beanbook.app.packages.PackagesUi
import io.github.pintofi.beanbook.app.plugins.PluginRow
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
    packages: PackagesUi,
    restart: EngineRestartUi,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        UnsandboxedWarning(policy)

        Button(
            onClick = onInstall,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Install a plugin from a file" },
        ) { Text("Install from a file") }

        if (!policy.allowsNativeCode) {
            Text(
                "This edition installs pure-Python plugins only. Anything with compiled " +
                    "code is refused.",
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
            // weight(1f) so the switch cannot be pushed off the right edge by the text
            // beside it — see AppearanceScreen for the same fix.
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Safe mode", style = MaterialTheme.typography.titleSmall)
                Text(
                    "No plugins load. Your choices are remembered.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // null: the row above owns the interaction, so assistive technology announces
            // one control rather than two that do the same thing.
            Switch(checked = safeMode, onCheckedChange = null)
        }

        RestartCard(restart)

        HorizontalDivider()

        PackagesSection(policy, packages, noPluginsLeft = rows.isEmpty())

        HorizontalDivider()

        Text("Plugins", style = MaterialTheme.typography.titleSmall)

        if (rows.isEmpty()) {
            // Says *plugin files*, because the section above lists installed **packages** and
            // the two sat one after the other: fetching beangrow put "Installed: beangrow"
            // on the screen directly above "No plugins installed", which reads as the app
            // contradicting itself (B49). A package is a library; a plugin is a file you add
            // here. Both headings now say which is which.
            Text(
                "No plugins added. A plugin is a Python file you install here; it adds extra " +
                    "checks and transformations to a ledger. Packages above are libraries a " +
                    "plugin can use, and are not plugins themselves.",
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
                "It runs with this app's permissions: it can read and change your whole " +
                    "ledger, and use the network. Install plugins only from a source you trust.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!policy.allowsAndroidBridge) {
                Text(
                    "This edition also keeps plugins away from Android APIs. That is a store " +
                        "rule, not protection for your ledger.",
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
                    "The files have changed since you installed this, so it is not loaded. " +
                        "Install it again to check and run it.",
                )
                PluginIntegrity.Missing -> Warning(
                    "Its files are gone, so it is not being loaded. Remove it to tidy up.",
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

/**
 * Offers the only thing that actually picks up a newly installed plugin or package (B49).
 *
 * "Reopen the ledger" was the previous advice and it does not work: reopening re-parses
 * inside the same interpreter, and Python has already cached the answer to every import it
 * has been asked. A new engine process is what reads the files on disk.
 *
 * Shown only when something has changed under the running engine, so it is a consequence of
 * what the user just did rather than a button that is always there.
 */
@Composable
private fun RestartCard(restart: EngineRestartUi) {
    if (!restart.needed) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "The engine is running older code than is installed" },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Restart needed", style = MaterialTheme.typography.titleSmall)
            Text(
                "What you just changed is on disk, but the running engine has already loaded " +
                    "Python and will not see it. Restarting takes a few seconds and touches " +
                    "nothing in your ledger.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(
                onClick = restart.onRestart,
                modifier = Modifier.semantics { contentDescription = "Restart the engine now" },
            ) { Text("Restart the engine") }
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

/**
 * Extra Python packages, fetched when the user asks (docs/on-demand-packages.md).
 *
 * This is the only place in the app that reaches the network in local mode, so it says so,
 * and it says what it will cost before it costs anything. The order is deliberate:
 * the user types a name, the app resolves the closure and *stops*, showing how many packages
 * and how many megabytes; only then is there a button that downloads. A confirmation that
 * appears after the download has started is not a confirmation.
 *
 * Absent entirely in the Play edition, which cannot fetch — but the enforcement is that
 * edition's `WheelFetcher` refusing, not this composable being hidden.
 */
@Composable
private fun PackagesSection(
    policy: PluginPolicy,
    packages: PackagesUi,
    noPluginsLeft: Boolean,
) {
    val installed = packages.installed
    val status = packages.status
    val message = packages.message
    val onRequest = packages.onRequest
    val onConfirm = packages.onConfirm
    val onCancel = packages.onCancel
    val onRemoveAll = packages.onRemoveAll

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Extra Python packages (libraries)", style = MaterialTheme.typography.titleSmall)

        if (!policy.allowsPackageDownload) {
            Text(
                "This edition installs no packages beyond the ones built in. A plugin " +
                    "that needs numpy or pandas will name the missing module and stop.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        Text(
            "Some plugins need libraries the app does not carry, such as numpy or " +
                "pandas. They download only when you ask, and the size is shown first. " +
                "This is the only network use while your ledger is on this device.",
            style = MaterialTheme.typography.bodySmall,
        )

        var wanted by remember { mutableStateOf("") }

        when (status) {
            is PackagesStatus.Resolving -> Text(
                "Working out what ${status.request} needs…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            is PackagesStatus.Fetching -> Text(
                "Downloading ${status.request}…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            is PackagesStatus.Confirming -> Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "What fetching would download" },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                val resolution = status.resolution
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${resolution.wheels.size} packages to download",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        resolution.wheels.joinToString(", ") { "${it.name} ${it.version}" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Said here because it is the number people are short of. Wheels are
                    // compressed, so what unpacks onto the device is several times what
                    // crosses the network, and quoting only the download would understate it.
                    Text(
                        "They take noticeably more room unpacked than they do to download.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (resolution.unavailable.isNotEmpty()) {
                        Text(
                            "Not available for this device: " +
                                resolution.unavailable.joinToString(", ") +
                                ". Anything that needs one of those will fail with its name " +
                                "rather than silently.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "From the Python package index. Somebody else's code, running with this " +
                            "app's permissions, exactly as a plugin does.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier.semantics {
                                contentDescription = "Download these packages"
                            },
                        ) { Text("Download") }
                        TextButton(onClick = onCancel) { Text("Not now") }
                    }
                }
            }

            PackagesStatus.Idle -> {
                OutlinedTextField(
                    value = wanted,
                    onValueChange = { wanted = it },
                    label = { Text("Package name") },
                    placeholder = { Text("beangrow") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Name of a package to fetch" },
                )
                Button(
                    onClick = { onRequest(wanted); wanted = "" },
                    enabled = wanted.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "See what this would download" },
                ) { Text("See what it needs") }
            }
        }

        message?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    it,
                    Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (installed.isNotEmpty()) {
            Text(
                "Fetched and available to plugins: " + installed.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
            )
            if (noPluginsLeft) {
                // Item 21's other half, as far as it can honestly go. A package is fetched by
                // name because the user asked for it, not declared by a plugin, so the app
                // has no dependency graph to work out which packages a removed plugin was the
                // only user of. What it can say truthfully is that nothing is left to use
                // them, and offer the removal that already exists.
                Text(
                    "No plugins are installed any more, and these are only useful to plugins.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = onRemoveAll,
                modifier = Modifier.semantics {
                    contentDescription = "Remove every fetched package"
                },
            ) { Text("Remove fetched packages") }
        }
    }
}
