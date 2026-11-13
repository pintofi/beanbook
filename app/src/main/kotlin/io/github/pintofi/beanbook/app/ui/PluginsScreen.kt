package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.data.PluginIntegrity
import io.github.pintofi.beanbook.core.domain.PluginPolicy
import io.github.pintofi.beanbook.app.packages.PackagesStatus
import io.github.pintofi.beanbook.app.packages.PackagesUi
import io.github.pintofi.beanbook.app.plugins.PluginRow
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred

/**
 * Installed plugins: what is here, where it came from, and whether it runs.
 *
 * The screen's job is to be honest about one thing above all. A plugin is somebody else's
 * program running with this app's permissions — it can read and change the ledger, and
 * reach the network if the app can. Android offers no way to isolate it and still let Fava
 * use it in-process, so the app does not claim safety (docs/threat-model.md §3.2). That
 * warning sits above the list, not behind a link.
 *
 * Built from folios like every other page of controls; every installed
 * plugin has a status line, filled from [status] (PluginStatus).
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
    /**
     * Whether each installed plugin loaded, by plugin id. Unknown until the caller can say
     * (the engine's and Fava's load errors name the module that failed); unknown draws nothing.
     */
    status: (String) -> PluginStatus = { PluginStatus.Unknown },
) {
    // Replacing an added plugin picks its own file, so the card needs no wiring through the
    // activity: the state is the application's, like the other self-contained screens.
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as? io.github.pintofi.beanbook.app.BeancountApplication
    var replacingId by remember { mutableStateOf<String?>(null) }
    val found by (app?.pluginsState?.found ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))
        .collectAsStateWithLifecycle()
    val working by (app?.pluginsState?.working ?: kotlinx.coroutines.flow.MutableStateFlow(null))
        .collectAsStateWithLifecycle()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val id = replacingId
        replacingId = null
        if (uri != null && id != null) app?.pluginsState?.replaceFrom(id, uri, displayNameOf(context, uri))
    }
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
    FolioPage(modifier = Modifier.readableMeasureCentred()) {

        UnsandboxedWarning(policy)

        // Adding a plugin from a file is only for the edition that cannot download one:
        // in Expert the list below installs everything it offers, and the file route was
        // noise beside it.
        if (!packages.catalogue.canInstall) {
            Button(
                onClick = onInstall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics { contentDescription = "Install a plugin from a file" },
            ) { Text("Install from a file") }
        }

        message?.let {
            Notice(NoticeTone.QUIET) {
                StatusLine(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        CatalogueSection(packages.catalogue, status)

        // Plugins added from a file, which only the list above used to lack a route for.
        if (rows.isNotEmpty()) {
            FolioHeading("Added from a file")
            FolioGroup {
                rows.forEach { row ->
                    item {
                        PluginCard(
                            row = row,
                            dimmed = safeMode,
                            onSetEnabled = onSetEnabled,
                            onRemove = onRemove,
                            onReplace = { id -> replacingId = id; picker.launch(arrayOf("*/*")) },
                            onGoBack = { id -> app?.pluginsState?.goBack(id) },
                            source = app?.pluginsState?.let { state ->
                                PluginSourceControls(
                                    available = state.canCheckSources,
                                    found = found[row.plugin.id]?.version,
                                    working = working == row.plugin.id,
                                    onAttach = { project -> state.attachSource(row.plugin.id, project) },
                                    onForget = { state.forgetSource(row.plugin.id) },
                                    onCheck = { state.checkSource(row.plugin.id) },
                                    onReplace = { state.replaceFromSource(row.plugin.id) },
                                )
                            },
                            status = status(row.plugin.id),
                        )
                    }
                }
            }
        }

        PackagesSection(policy, packages, noPluginsLeft = rows.isEmpty() && packages.catalogue.installed.isEmpty())

        // Last, because it is the way out when a plugin breaks loading, not something set
        // on every visit. The whole row toggles, not just the switch at its edge.
        FolioHeading("When a plugin stops the ledger opening")
        FolioGroup {
            item {
                FolioSwitchRow(
                    title = "Safe mode",
                    detail = "Load the ledger with no plugins, for when one stops it from opening.",
                    checked = safeMode,
                    onCheckedChange = onSetSafeMode,
                    icon = Icons.Outlined.HealthAndSafety,
                    describe = "Safe mode: hold every plugin back",
                )
            }
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
 * could do. In the attention tone: always on screen, so a risk that is read, not an alarm.
 */
@Composable
private fun UnsandboxedWarning(policy: PluginPolicy) {
    Notice(
        NoticeTone.ATTENTION,
        modifier = Modifier.semantics { contentDescription = "What running a plugin means" },
    ) {
        Text("A plugin is a program you are trusting", style = MaterialTheme.typography.titleSmallEmphasized)
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

/**
 * The status line under an installed plugin's name: a mark and words, never colour alone.
 * Nothing at all while the status is unknown -- the card does not claim what nobody has said.
 */
@Composable
internal fun PluginStatusLine(status: PluginStatus) {
    when (status) {
        PluginStatus.Unknown -> Unit
        PluginStatus.Checking -> Text(
            "Checking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        )
        PluginStatus.Loaded -> Row(verticalAlignment = Alignment.CenterVertically) {
            StampMark(LedgerMark.CLEARED, decorative = true, modifier = Modifier.padding(end = Measure.snug))
            Text(
                "Loaded with your ledger",
                style = MaterialTheme.typography.bodySmall,
                color = BeancountTheme.ledger.rule,
            )
        }
        is PluginStatus.Failed -> Row(verticalAlignment = Alignment.Top) {
            StampMark(LedgerMark.ATTENTION, decorative = true, modifier = Modifier.padding(end = Measure.snug))
            Text(
                "Did not load: ${status.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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
    onReplace: (String) -> Unit,
    onGoBack: (String) -> Unit,
    source: PluginSourceControls? = null,
    status: PluginStatus = PluginStatus.Unknown,
) {
    var confirming by remember(row.plugin.id) { mutableStateOf(false) }
    var replacing by remember(row.plugin.id) { mutableStateOf(false) }
    val plugin = row.plugin

    Column {
        FolioSwitchRow(
            title = plugin.name,
            checked = plugin.enabled,
            onCheckedChange = { onSetEnabled(plugin.id, it) },
            icon = Icons.Outlined.Extension,
            describe = "Load ${plugin.name}",
        )
        FolioBody(modifier = Modifier.padding(top = 0.dp)) {
            PluginStatusLine(status)
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
                    OutlinedButton(onClick = { confirming = false }) { Text("Cancel") }
                }
            } else if (replacing) {
                Text(
                    "Replace ${plugin.name} with a newer file? The new one stays off until you turn it " +
                        "on, and the copy you have now is kept so you can go back.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { replacing = false; onReplace(plugin.id) },
                        modifier = Modifier.semantics {
                            contentDescription = "Choose the file to replace ${plugin.name}"
                        },
                    ) { Text("Choose file") }
                    OutlinedButton(onClick = { replacing = false }) { Text("Cancel") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { replacing = true },
                        modifier = Modifier.semantics { contentDescription = "Replace ${plugin.name} with a file" },
                    ) { Text("Replace with a file") }
                    OutlinedButton(
                        onClick = { confirming = true },
                        modifier = Modifier.semantics { contentDescription = "Remove ${plugin.name}" },
                    ) { Text("Remove") }
                }
                if (source?.available == true) PluginSource(plugin, source)
                if (row.previous != null) {
                    OutlinedButton(
                        onClick = { onGoBack(plugin.id) },
                        modifier = Modifier.semantics {
                            contentDescription = "Go back to the earlier copy of ${plugin.name}"
                        },
                    ) { Text("Go back to the earlier copy") }
                }
            }
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

    FolioHeading("Extra Python packages")

    if (!policy.allowsPackageDownload) {
        FolioNote(
            "This edition installs no packages beyond the ones built in. A plugin " +
                "that needs numpy or pandas will name the missing module and stop.",
        )
        return
    }

    var wanted by remember { mutableStateOf("") }

    if (installed.isNotEmpty() || packages.canGoBack) PackageUpdates(packages)

    FolioGroup {
        item {
            FolioBody {
                when (status) {
                    is PackagesStatus.Resolving -> StatusLine("Working out what ${status.request} needs…")

                    is PackagesStatus.Fetching -> StatusLine("Downloading ${status.request}…")

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
                                OutlinedButton(onClick = onCancel) { Text("Not now") }
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
                    Text(
                        it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
    FolioNote(
        "Some plugins need libraries the app does not carry, such as numpy or pandas. They " +
            "download only when you ask, and what would be downloaded is listed first. Downloading " +
            "these, and plugins from the list above, is the only network use while your ledger is " +
            "on this device.",
    )

    if (installed.isNotEmpty()) {
        // One row per package, and one per *package*, not per folder (device audit
        // 2026-10-10): `numpy`, `numpy-2.1.3.dist-info` and `numpy.libs` are one thing to a
        // person, "numpy 2.1.3".
        val grouped = remember(installed) { groupFetched(installed) }
        FolioHeading("Fetched and available to plugins")
        FolioGroup(
            modifier = Modifier.semantics { contentDescription = "${grouped.size} fetched package(s)" },
        ) {
            grouped.forEach { pkg ->
                item {
                    FolioRow(
                        title = pkg.name,
                        detail = pkg.version?.let { "Version $it" },
                        icon = Icons.Outlined.Inventory2,
                    )
                }
            }
        }
        if (noPluginsLeft) {
            // As far as this can honestly go: a package is fetched by
            // name because the user asked for it, not declared by a plugin, so the app
            // has no dependency graph to work out which packages a removed plugin was the
            // only user of. What it can say truthfully is that nothing is left to use
            // them, and offer the removal that already exists.
            FolioNote("No plugins are installed any more, and these are only useful to plugins.")
        }
        var confirmingRemoval by remember { mutableStateOf(false) }
        if (confirmingRemoval) {
            ConfirmRemoval(
                title = "Remove every fetched package?",
                body = "All ${grouped.size} package(s) fetched for plugins are deleted from this " +
                    "device. Plugins that need them stop working until you fetch them again, " +
                    "which downloads them again. Your ledger and the plugins themselves are not touched.",
                action = "Remove",
                onConfirm = onRemoveAll,
                onDismiss = { confirmingRemoval = false },
            )
        }
        OutlinedButton(
            onClick = { confirmingRemoval = true },
            modifier = Modifier.semantics {
                contentDescription = "Remove every fetched package"
            },
        ) { Text("Remove fetched packages") }
    }
}

/**
 * The curated catalogue: every plugin on awesome-beancount, with one button each.
 *
 * A row says what the plugin is for and nothing else: licence, test status and download
 * source were detail nobody choosing a plugin needed, and they buried the one line that
 * mattered. Installing is one tap; the only thing shown after it is the outcome.
 */
@Composable
internal fun CatalogueSection(
    catalogue: io.github.pintofi.beanbook.app.plugins.CatalogueUi,
    status: (String) -> PluginStatus = { PluginStatus.Unknown },
) {
    if (catalogue.entries.isEmpty()) return
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(PluginKind.ALL) }
    // The whole catalogue is longer than anybody scrolls through to reach the settings under
    // it, so it opens at five and the rest is one tap away. Searching or filtering is
    // already a narrowing, so the list starts short again whenever either changes.
    var expanded by remember(query, kind) { mutableStateOf(false) }

    // What is installed has a section of its own, above the list to choose from. Sorting
    // installed plugins to the top of the whole catalogue mixed "what I have" with "what I
    // could add", and a search or a filter could hide an installed plugin altogether.
    val installed = catalogue.entries.filter { it.id in catalogue.installed }
    if (installed.isNotEmpty()) {
        FolioHeading("Installed plugins")
        if (catalogue.canCheck) CheckForUpdates(catalogue)
        FolioGroup {
            installed.forEach { entry -> item { CatalogueRow(entry, catalogue, status(entry.id)) } }
        }
    }

    FolioHeading(if (catalogue.canInstall) "Plugins you can install" else "Plugins people use")
    if (!catalogue.canInstall) {
        FolioNote(
            "This edition cannot download plugins. Add one from a file above, or use the " +
                "Expert edition, which installs them from this list.",
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
    ConnectedChoiceRow(
        options = PluginKind.entries,
        selected = kind,
        onSelect = { kind = it },
        label = { it.label },
        describe = { "Show ${it.described}" },
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
    catalogue.message?.let {
        Notice(NoticeTone.QUIET, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = catalogue.entries
        .filter { it.id !in catalogue.installed }
        .filter { kind.includes(it.kind) }
        .filter { entry ->
            val text = "${entry.name} ${entry.description}".lowercase()
            words.all { it in text }
        }
    if (shown.isEmpty()) {
        FolioNote("No plugin matches.")
    }
    val visible = if (expanded) shown else shown.take(CATALOGUE_PREVIEW)
    FolioGroup {
        visible.forEach { entry -> item { CatalogueRow(entry, catalogue, PluginStatus.Unknown) } }
    }
    val hidden = shown.size - visible.size
    if (hidden > 0) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics {
                contentDescription = "Show the other $hidden plugins"
            },
        ) { Text("Show more ($hidden)") }
    } else if (expanded && shown.size > CATALOGUE_PREVIEW) {
        OutlinedButton(
            onClick = { expanded = false },
            modifier = Modifier.semantics { contentDescription = "Show fewer plugins" },
        ) { Text("Show fewer") }
    }
}

/** How many plugins the list shows before "Show more". */
private const val CATALOGUE_PREVIEW = 5

/** The three ways to narrow the plugin list. */
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
    status: PluginStatus,
) {
    val installed = entry.id in catalogue.installed
    val installedVersion = catalogue.installed[entry.id].orEmpty()
    val upstreamVersion = catalogue.updates[entry.id]
    val updatable = installed && (upstreamVersion != null || entry.updatesFrom(installedVersion))
    val updateTo = upstreamVersion ?: entry.install?.version
    val working = catalogue.status
    FolioBody {
        Row(verticalAlignment = Alignment.Top) {
            FolioBadge(
                Icons.Outlined.Extension,
                tone = if (installed) FolioTone.ACCENT else FolioTone.PLAIN,
                modifier = Modifier.padding(end = Measure.indent),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Measure.tight),
            ) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The one exception to "what it does": a plugin known not to run here says so,
                // so nobody installs it to find out.
                if (entry.status == "unsupported") {
                    Text(
                        "Does not work on Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (installed) PluginStatusLine(status)
                if (updatable) {
                    Text(
                        "Update available: $installedVersion → $updateTo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        val busy = (working as? io.github.pintofi.beanbook.app.plugins.CatalogueStatus.Installing)?.id == entry.id
        when {
            busy -> StatusLine(if (updatable) "Updating…" else "Installing…")
            installed -> androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Measure.snug),
                verticalArrangement = Arrangement.spacedBy(Measure.snug),
            ) {
                if (updatable && catalogue.canInstall) {
                    Button(
                        onClick = { catalogue.onUpdate(entry.id) },
                        enabled = working is io.github.pintofi.beanbook.app.plugins.CatalogueStatus.Idle,
                        modifier = Modifier.semantics { contentDescription = "Update ${entry.name}" },
                    ) { Text("Update") }
                }
                catalogue.previous[entry.id]?.let { earlier ->
                    OutlinedButton(
                        onClick = { catalogue.onRollback(entry.id) },
                        enabled = working is io.github.pintofi.beanbook.app.plugins.CatalogueStatus.Idle,
                        modifier = Modifier.semantics {
                            contentDescription = "Go back to $earlier of ${entry.name}"
                        },
                    ) { Text("Go back to $earlier") }
                }
                OutlinedButton(
                    onClick = { catalogue.onRemove(entry.id) },
                    modifier = Modifier.semantics { contentDescription = "Remove ${entry.name}" },
                ) { Text("Remove") }
            }
            catalogue.canInstall && entry.install != null && entry.status != "unsupported" -> Button(
                onClick = { catalogue.onInstall(entry.id) },
                enabled = working is io.github.pintofi.beanbook.app.plugins.CatalogueStatus.Idle,
                modifier = Modifier.semantics { contentDescription = "Install ${entry.name}" },
            ) { Text("Install") }
            else -> Unit
        }
    }
}

/** What the card of a plugin added from a file can do about where its newer versions come from. */
internal class PluginSourceControls(
    val available: Boolean,
    /** The newer version the last check found. */
    val found: String?,
    val working: Boolean,
    val onAttach: (String) -> Unit,
    val onForget: () -> Unit,
    val onCheck: () -> Unit,
    val onReplace: () -> Unit,
)

/**
 * A source for a plugin added from a file: the name of a project on PyPI, which a check asks for its
 * newest version. Expert only. Nothing is asked until the person taps Check, and what it finds was not
 * tested with this app, which the card says; replacing keeps the copy that is there.
 */
@Composable
private fun PluginSource(plugin: io.github.pintofi.beanbook.core.data.InstalledPlugin, source: PluginSourceControls) {
    var editing by remember(plugin.id) { mutableStateOf(false) }
    var project by remember(plugin.id) { mutableStateOf("") }
    if (editing) {
        OutlinedTextField(
            value = project,
            onValueChange = { project = it },
            label = { Text("Name of the project on PyPI") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { editing = false; source.onAttach(project) },
                enabled = project.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Save the source of ${plugin.name}" },
            ) { Text("Save") }
            OutlinedButton(onClick = { editing = false }) { Text("Cancel") }
        }
        return
    }
    val upstream = plugin.upstream
    if (upstream == null) {
        OutlinedButton(
            onClick = { editing = true },
            modifier = Modifier.semantics { contentDescription = "Set where ${plugin.name} comes from" },
        ) { Text("Set where it comes from") }
        return
    }
    Text(
        "Source: $upstream on PyPI. Newer versions are not tested with this app.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = source.onCheck,
            enabled = !source.working,
            modifier = Modifier.semantics { contentDescription = "Check ${plugin.name} for a newer version" },
        ) { Text(if (source.working) "Checking…" else "Check for a newer version") }
        OutlinedButton(
            onClick = source.onForget,
            modifier = Modifier.semantics { contentDescription = "Forget the source of ${plugin.name}" },
        ) { Text("Forget source") }
    }
    source.found?.let { version ->
        Button(
            onClick = source.onReplace,
            enabled = !source.working,
            modifier = Modifier.semantics { contentDescription = "Replace ${plugin.name} with version $version" },
        ) { Text("Replace with $version") }
    }
}

/**
 * Whether the packages that were fetched have newer versions, asked of PyPI and Chaquopy's index when
 * the person taps; the update, with the earlier packages kept; and the way back.
 */
@Composable
private fun PackageUpdates(packages: PackagesUi) {
    val idle = packages.status is PackagesStatus.Idle
    val last = packages.lastChecked?.let {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(it))
    }
    FolioBody {
        if (packages.canCheck && packages.installed.isNotEmpty()) {
            OutlinedButton(
                onClick = packages.onCheck,
                enabled = idle && !packages.checking,
                modifier = Modifier.semantics { contentDescription = "Check the fetched packages for updates" },
            ) { Text(if (packages.checking) "Checking…" else "Check for package updates") }
            Text(
                "Asks pypi.org and chaquo.com, where they were fetched from. " +
                    (last?.let { "Last checked $it." } ?: "Not checked yet."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (packages.updates.isNotEmpty()) {
            Notice(
                NoticeTone.ATTENTION,
                modifier = Modifier.semantics { contentDescription = "Package updates available" },
            ) {
                Text(
                    packages.updates.joinToString("\n") {
                        if (it.from == null) "${it.name} ${it.to} (new, needed by the update)"
                        else "${it.name} ${it.from} → ${it.to}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The versions you have now are kept so you can go back. Plugins and scripts that use " +
                        "these packages may behave differently. If the new ones don't load, the earlier " +
                        "ones come back by themselves.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = packages.onUpdate,
                    enabled = idle,
                    modifier = Modifier.semantics { contentDescription = "Update the fetched packages" },
                ) { Text("Update packages") }
                OutlinedButton(onClick = packages.onDismissUpdates) { Text("Not now") }
            }
        }
        if (packages.canGoBack) {
            OutlinedButton(
                onClick = packages.onGoBack,
                enabled = idle,
                modifier = Modifier.semantics { contentDescription = "Go back to the earlier packages" },
            ) { Text("Go back to the earlier packages") }
        }
    }
}

/**
 * Asks where each installed plugin came from whether there is a newer version. Only on a tap, and it
 * says which hosts it will ask; nothing checks on its own.
 */
@Composable
private fun CheckForUpdates(catalogue: io.github.pintofi.beanbook.app.plugins.CatalogueUi) {
    val last = catalogue.lastChecked?.let {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(it))
    }
    FolioBody {
        OutlinedButton(
            onClick = catalogue.onCheck,
            enabled = !catalogue.checking &&
                catalogue.status is io.github.pintofi.beanbook.app.plugins.CatalogueStatus.Idle,
            modifier = Modifier.semantics { contentDescription = "Check installed plugins for updates" },
        ) { Text(if (catalogue.checking) "Checking…" else "Check for updates") }
        Text(
            "Asks pypi.org and github.com, where the plugins came from. " +
                (last?.let { "Last checked $it." } ?: "Not checked yet."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The name the provider gives a picked document, for the label shown against the plugin. */
private fun displayNameOf(context: android.content.Context, uri: android.net.Uri): String =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
