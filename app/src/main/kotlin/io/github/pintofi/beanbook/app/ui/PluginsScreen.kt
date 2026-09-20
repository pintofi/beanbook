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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import io.github.pintofi.beanbook.app.packages.PackagesStatus
import io.github.pintofi.beanbook.app.packages.PackagesUi
import io.github.pintofi.beanbook.app.plugins.PluginRow
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme

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
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.gap, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        UnsandboxedWarning(policy)

        // Adding a plugin from a file is only for the edition that cannot download one (B89):
        // in Expert the list below installs everything it offers, and the file route was
        // noise beside it.
        if (!packages.catalogue.canInstall) {
            Button(
                onClick = onInstall,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Install a plugin from a file" },
            ) { Text("Install from a file") }
        }

        message?.let {
            Notice(NoticeTone.QUIET) {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        CatalogueSection(packages.catalogue)

        // Plugins added from a file, which only the list above used to lack a route for.
        if (rows.isNotEmpty()) {
            LedgerHairline()
            Text("Added from a file", style = MaterialTheme.typography.titleSmall)
            rows.forEach { row ->
                PluginCard(row = row, dimmed = safeMode, onSetEnabled = onSetEnabled, onRemove = onRemove)
            }
        }

        LedgerHairline()

        PackagesSection(policy, packages, noPluginsLeft = rows.isEmpty() && packages.catalogue.installed.isEmpty())

        LedgerHairline()

        // Last, because it is the way out when a plugin breaks loading, not something set
        // on every visit (B89). The whole row toggles, not just the switch at its edge.
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
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Safe mode", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Load the ledger with no plugins, for when one stops it from opening.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = safeMode, onCheckedChange = null)
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
    Notice(
        NoticeTone.PROBLEM,
        modifier = Modifier.semantics { contentDescription = "What running a plugin means" },
    ) {
        Text("A plugin is a program you are trusting", style = MaterialTheme.typography.titleSmall)
        Text(
            "It runs with this app's permissions: it can read and change your whole " +
                "ledger, and use the network. Install plugins only from a source you trust.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!policy.allowsAndroidBridge) {
            Text(
                "This edition also keeps plugins away from Android's own features. That is a store " +
                    "rule, not protection for your ledger.",
                style = MaterialTheme.typography.bodyMedium,
            )
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

    RuledColumn(
        modifier = Modifier.padding(vertical = Measure.snug),
        colour = if (plugin.enabled) BeancountTheme.ledger.rule else MaterialTheme.colorScheme.outlineVariant,
        verticalArrangement = Arrangement.spacedBy(Measure.snug),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = plugin.enabled, onValueChange = { onSetEnabled(plugin.id, it) }, role = Role.Switch)
                .semantics { contentDescription = "Load ${plugin.name}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(plugin.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = plugin.enabled, onCheckedChange = null)
        }
        if (dimmed && plugin.enabled) {
            Text("Held back while safe mode is on.", style = MaterialTheme.typography.bodySmall)
        }
        when (row.integrity) {
            PluginIntegrity.Unchanged -> Unit
            is PluginIntegrity.Changed -> Warning(
                "Its files changed after you added it, so it is not loaded. Add it again to use it.",
            )
            PluginIntegrity.Missing -> Warning("Its files are gone, so it is not loaded. Remove it.")
        }
        if (confirming) {
            Text(
                "Remove ${plugin.name}? Your ledger is not changed.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { confirming = false; onRemove(plugin.id) },
                    modifier = Modifier.semantics { contentDescription = "Confirm removing ${plugin.name}" },
                ) { Text("Remove") }
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        } else {
            TextButton(
                onClick = { confirming = true },
                modifier = Modifier.semantics { contentDescription = "Remove ${plugin.name}" },
            ) { Text("Remove") }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Notice(NoticeTone.PROBLEM) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

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
                "pandas. They download only when you ask, and what would be downloaded is " +
                "listed first. Downloading these, and plugins from the list above, is the " +
                "only network use while your ledger is on this device.",
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

            is PackagesStatus.Confirming -> Notice(
                NoticeTone.ATTENTION,
                modifier = Modifier.semantics {
                    contentDescription = "What fetching would download"
                },
            ) {
                val resolution = status.resolution
                run {
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
            Notice(NoticeTone.QUIET) {
                Text(
                    it,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (installed.isNotEmpty()) {
            // One row per package, not one sentence naming them all (B66). Joined with
            // commas, eleven fetched packages were a paragraph four lines long that had to be
            // read to find out whether a name was in it -- and the longer it got, the less it
            // looked like a list of things the user could act on.
            Text(
                "Fetched and available to plugins",
                style = MaterialTheme.typography.titleSmall,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "${installed.size} fetched package(s)" },
            ) {
                installed.forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Inventory2,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                    }
                    LedgerHairline()
                }
            }
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
            OutlinedButton(
                onClick = onRemoveAll,
                modifier = Modifier.semantics {
                    contentDescription = "Remove every fetched package"
                },
            ) { Text("Remove fetched packages") }
        }
    }
}

/**
 * The curated catalogue: every plugin on awesome-beancount, with one button each (B82, B89).
 *
 * A row says what the plugin is for and nothing else: licence, test status and download
 * source were detail nobody choosing a plugin needed, and they buried the one line that
 * mattered. Installing is one tap; the only thing shown after it is the outcome.
 */
@Composable
internal fun CatalogueSection(catalogue: io.github.pintofi.beanbook.app.plugins.CatalogueUi) {
    if (catalogue.entries.isEmpty()) return
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(PluginKind.ALL) }
    // The whole catalogue is longer than anybody scrolls through to reach the settings under
    // it, so it opens at five and the rest is one tap away (B92). Searching or filtering is
    // already a narrowing, so the list starts short again whenever either changes.
    var expanded by remember(query, kind) { mutableStateOf(false) }

    Text(
        if (catalogue.canInstall) "Plugins you can install" else "Plugins people use",
        style = MaterialTheme.typography.titleSmall,
    )
    if (!catalogue.canInstall) {
        Text(
            "This edition cannot download plugins. Add one from a file above, or use the " +
                "Expert edition, which installs them from this list.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search plugins") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PluginKind.entries.forEach { option ->
            androidx.compose.material3.FilterChip(
                selected = kind == option,
                onClick = { kind = option },
                label = { Text(option.label) },
                modifier = Modifier.semantics { contentDescription = "Show ${option.described}" },
            )
        }
    }
    catalogue.message?.let {
        Notice(NoticeTone.QUIET, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = catalogue.entries
        .filter { kind.includes(it.kind) }
        .filter { entry ->
            val text = "${entry.name} ${entry.description}".lowercase()
            words.all { it in text }
        }
        // What is installed first, then the rest in the catalogue's order.
        .sortedBy { it.id !in catalogue.installed }
    if (shown.isEmpty()) {
        Text("No plugin matches.", style = MaterialTheme.typography.bodyMedium)
    }
    val visible = if (expanded) shown else shown.take(CATALOGUE_PREVIEW)
    visible.forEach { entry -> CatalogueRow(entry, catalogue) }
    val hidden = shown.size - visible.size
    if (hidden > 0) {
        androidx.compose.material3.TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics {
                contentDescription = "Show the other $hidden plugins"
            },
        ) { Text("Show more ($hidden)") }
    } else if (expanded && shown.size > CATALOGUE_PREVIEW) {
        androidx.compose.material3.TextButton(
            onClick = { expanded = false },
            modifier = Modifier.semantics { contentDescription = "Show fewer plugins" },
        ) { Text("Show fewer") }
    }
}

/** How many plugins the list shows before "Show more". */
private const val CATALOGUE_PREVIEW = 5

/** The three ways to narrow the plugin list (B89). */
internal enum class PluginKind(val label: String, val described: String) {
    ALL("All", "all plugins"),
    BEANCOUNT("Beancount", "plugins that change how the ledger loads"),
    FAVA("Fava", "plugins that add pages to Reports"),
    ;

    fun includes(kind: String): Boolean = when (this) {
        ALL -> true
        BEANCOUNT -> kind != "fava-extension"
        FAVA -> kind == "fava-extension"
    }
}

@Composable
private fun CatalogueRow(
    entry: io.github.pintofi.beanbook.core.data.PluginCatalogue.Entry,
    catalogue: io.github.pintofi.beanbook.app.plugins.CatalogueUi,
) {
    val installed = entry.id in catalogue.installed
    val status = catalogue.status
    RuledColumn(
        modifier = Modifier.padding(vertical = Measure.snug),
        colour = if (installed) BeancountTheme.ledger.rule else MaterialTheme.colorScheme.outlineVariant,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(entry.name, style = MaterialTheme.typography.titleMedium)
        Text(entry.description, style = MaterialTheme.typography.bodyMedium)
        // The one exception to "what it does": a plugin known not to run here says so, so
        // nobody installs it to find out.
        if (entry.status == "unsupported") {
            Text(
                "Does not work on Android.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            (status as? io.github.pintofi.beanbook.app.plugins.CatalogueStatus.Installing)?.id == entry.id ->
                Text(
                    "Installing…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            installed -> OutlinedButton(
                onClick = { catalogue.onRemove(entry.id) },
                modifier = Modifier.semantics { contentDescription = "Remove ${entry.name}" },
            ) { Text("Remove") }
            catalogue.canInstall && entry.install != null && entry.status != "unsupported" -> OutlinedButton(
                onClick = { catalogue.onInstall(entry.id) },
                enabled = status is io.github.pintofi.beanbook.app.plugins.CatalogueStatus.Idle,
                modifier = Modifier.semantics { contentDescription = "Install ${entry.name}" },
            ) { Text("Install") }
            else -> Unit
        }
    }
}
