package io.github.pintofi.beanbook.app.ui

import io.github.pintofi.beanbook.app.backup.RestoreStatus
import io.github.pintofi.beanbook.app.backup.RestoreItem
import io.github.pintofi.beanbook.app.backup.Clash
import io.github.pintofi.beanbook.app.backup.AppDataItem
import io.github.pintofi.beanbook.core.data.AppDataGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.data.RestoreStaged
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SettingsBackupRestore

/**
 * Whether scheduled backups are encrypted, and how to change it: [onChange] gets the passphrase
 * to turn encryption on, or null to turn it off.
 */
class ScheduleEncryption(val enabled: Boolean, val onChange: (CharArray?) -> Unit)

/**
 * Back up, restore, and schedule (docs/storage-and-backup.md §7).
 *
 * The screen's job is to make one thing impossible: restoring without seeing what it would
 * change. A staged restore always shows its diff first, and the confirmation names what is
 * about to be lost rather than asking "are you sure?".
 */
@Composable
fun BackupScreen(
    projectTitle: String,
    pendingRestore: RestoreStaged.Ready?,
    passphraseNeeded: Boolean,
    scheduleEnabled: Boolean,
    scheduleFolder: String?,
    lastRun: String?,
    rollbackPoints: List<String>,
    onBackUpNow: (CharArray?, Set<String>?) -> Unit,
    onUnlockRestore: (CharArray) -> Unit,
    onChooseRestoreFile: () -> Unit,
    onAcceptRestore: (Set<String>, Clash) -> Unit,
    onDiscardRestore: () -> Unit,
    onChooseScheduleFolder: () -> Unit,
    onScheduleChanged: (Boolean) -> Unit,
    scheduleEncryption: ScheduleEncryption,
    modifier: Modifier = Modifier,
    message: String? = null,
    loadAppData: () -> AppDataUi = { AppDataUi() },
) {
    // Read when this screen opens and again after a backup or a restore, never from a list made at launch: a
    // template, plugin or script made since would be missing from it.
    val appData by androidx.compose.runtime.produceState(AppDataUi(), message, pendingRestore) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadAppData() }
    }
    var ticked by remember(appData.available) {
        mutableStateOf(appData.available.filter { it.onByDefault }.map { it.key }.toSet())
    }
    var includeAppData by remember { mutableStateOf(false) }
    var askingSchedulePassphrase by remember { mutableStateOf(false) }
    var schedulePassphrase by remember { mutableStateOf("") }
    var scheduleConfirmation by remember { mutableStateOf("") }
    val schedulePassphrasesMatch = schedulePassphrase.isNotEmpty() && schedulePassphrase == scheduleConfirmation
    var encrypt by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var unlockPassphrase by remember { mutableStateOf("") }

    val passphrasesMatch = passphrase.isNotEmpty() && passphrase == confirmation

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
    FolioPage(modifier = Modifier.readableMeasureCentred()) {
        FolioHeading("Back up now")
        FolioGroup {
            item {
                FolioSwitchRow(
                    title = "Encrypt with a passphrase",
                    checked = encrypt,
                    onCheckedChange = { encrypt = it },
                    icon = Icons.Outlined.Lock,
                    describe = "Encrypt with a passphrase",
                )
            }
            if (encrypt) {
                item {
                    FolioBody {
                        // The warning is deliberately blunt and sits *above* the fields, where it
                        // is read before a passphrase is chosen rather than after
                        // (docs/storage-and-backup.md 7).
                        Notice(NoticeTone.PROBLEM) {
                            Text(
                                "If you forget this passphrase, the backup cannot be opened. There is " +
                                    "no reset and no recovery code. Write it down before you continue.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        PasswordField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = "Passphrase",
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Passphrase" },
                        )
                        PasswordField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = "Passphrase again",
                            isError = confirmation.isNotEmpty() && !passphrasesMatch,
                            supportingText = if (confirmation.isNotEmpty() && !passphrasesMatch) {
                                "The two passphrases are different."
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Passphrase again" },
                        )
                    }
                }
            }
        }
        if (appData.available.isNotEmpty()) {
            FolioGroup {
                item {
                    FolioSwitchRow(
                        title = "Also include app data",
                        checked = includeAppData,
                        onCheckedChange = { includeAppData = it },
                        icon = Icons.Outlined.Tune,
                        describe = "Also include app data",
                    )
                }
                if (includeAppData) {
                    item {
                        FolioBody {
                            Text(
                                "Choose what to put in. Passwords and sign-ins are never included. " +
                                    "A backup with app data can only be opened by this version or a later one.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Sections(appData.available, { it.group }) { entry ->
                                TickRow(
                                    title = entry.title,
                                    detail = listOf(entry.detail, formatBytes(entry.bytes))
                                        .filter { it.isNotEmpty() }.joinToString(" · "),
                                    checked = entry.key in ticked,
                                    onChange = { on -> ticked = if (on) ticked + entry.key else ticked - entry.key },
                                )
                            }
                        }
                    }
                }
            }
        }
        FolioNote(
            "One .bcaz file holding the whole project. Without a passphrase it is an ordinary zip, " +
                "openable without this app.",
        )
        Button(
            onClick = {
                onBackUpNow(
                    if (encrypt) passphrase.toCharArray() else null,
                    if (includeAppData) ticked else null,
                )
            },
            enabled = !encrypt || passphrasesMatch,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics { contentDescription = "Back up $projectTitle now" },
        ) { Text(if (encrypt) "Back up, encrypted" else "Back up now") }

        message?.let { StatusLine(it, modifier = Modifier.padding(horizontal = Measure.indent)) }
        val installAgain = appData.installAgain
        if (appData.reinstall.isNotEmpty() && installAgain != null) {
            Button(
                onClick = installAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics { contentDescription = "Install the catalogue plugins again" },
            ) { Text("Install again: ${appData.reinstall.joinToString(", ")}") }
        }

        FolioHeading("Restore")
        FolioGroup {
            if (pendingRestore == null) {
                item {
                    FolioRow(
                        title = "Choose a backup file",
                        detail = "Nothing is overwritten until you confirm. You see what would change first.",
                        icon = Icons.Outlined.SettingsBackupRestore,
                        onClick = onChooseRestoreFile,
                        opens = true,
                    )
                }
                if (passphraseNeeded) {
                    item {
                        FolioBody {
                            PasswordField(
                                value = unlockPassphrase,
                                onValueChange = { unlockPassphrase = it },
                                label = "Passphrase for this backup",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "Passphrase for this backup" },
                            )
                            Button(
                                onClick = { onUnlockRestore(unlockPassphrase.toCharArray()) },
                                enabled = unlockPassphrase.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Open this backup") }
                        }
                    }
                }
            } else {
                item {
                    FolioBody {
                        RestorePreview(
                            staged = pendingRestore,
                            items = appData.restoreItems,
                            onAccept = onAcceptRestore,
                            onDiscard = onDiscardRestore,
                        )
                    }
                }
            }
        }

        if (rollbackPoints.isNotEmpty()) {
            FolioHeading("Kept from earlier restores")
            FolioGroup {
                rollbackPoints.forEach { name ->
                    item { FolioRow(title = name, icon = Icons.Outlined.History) }
                }
            }
        }

        // The folder first, then the switch that needs it (device audit 2026-10-10): the switch
        // sat above "Choose a folder", disabled, with nothing beside it saying why.
        FolioHeading("Scheduled backups")
        FolioGroup {
            item {
                // The whole row chooses or changes the folder: a button beside the words
                // squeezed them into a narrow column on a phone.
                FolioRow(
                    title = scheduleFolder?.let { humanFolder(it) } ?: "Choose a folder",
                    detail = if (scheduleFolder == null) {
                        "Where scheduled backups are written"
                    } else {
                        "Backups go to this folder. Tap to change it."
                    },
                    icon = Icons.Outlined.Folder,
                    tone = if (scheduleFolder == null) FolioTone.PLAIN else FolioTone.ACCENT,
                    onClick = onChooseScheduleFolder,
                    opens = true,
                    describe = if (scheduleFolder == null) {
                        "Choose a folder for scheduled backups"
                    } else {
                        "Backups go to ${humanFolder(scheduleFolder)}. Change the folder"
                    },
                )
            }
            item {
                FolioSwitchRow(
                    title = "Back up automatically",
                    checked = scheduleEnabled,
                    onCheckedChange = onScheduleChanged,
                    enabled = scheduleFolder != null,
                    // Said plainly rather than implied: Android decides when this actually runs.
                    detail = if (scheduleFolder == null) {
                        "Choose a folder first."
                    } else {
                        "About once a day. Android decides the exact moment."
                    },
                    icon = Icons.Outlined.Schedule,
                    describe = "Back up automatically",
                )
            }
            item {
                FolioSwitchRow(
                    title = "Encrypt scheduled backups",
                    checked = scheduleEncryption.enabled || askingSchedulePassphrase,
                    onCheckedChange = { on ->
                        if (on) {
                            askingSchedulePassphrase = true
                        } else {
                            askingSchedulePassphrase = false
                            schedulePassphrase = ""
                            scheduleConfirmation = ""
                            scheduleEncryption.onChange(null)
                        }
                    },
                    detail = if (askingSchedulePassphrase && !scheduleEncryption.enabled) {
                        "Choose a passphrase below."
                    } else if (scheduleEncryption.enabled) {
                        "On. The passphrase is kept on this phone, so the backups can be made while it is locked."
                    } else {
                        "Off. Scheduled backups are ordinary zip files that anyone with the folder can open."
                    },
                    icon = Icons.Outlined.Lock,
                    describe = "Encrypt scheduled backups",
                )
            }
            if (askingSchedulePassphrase) {
                item {
                    FolioBody {
                        Notice(NoticeTone.PROBLEM) {
                            Text(
                                "If you forget this passphrase, the backups cannot be opened. There is " +
                                    "no reset and no recovery code. Write it down before you continue. " +
                                    "A copy is kept on this phone, so this protects the backups in the " +
                                    "folder, not someone who can use your unlocked phone.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        PasswordField(
                            value = schedulePassphrase,
                            onValueChange = { schedulePassphrase = it },
                            label = "Passphrase for scheduled backups",
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Passphrase for scheduled backups" },
                        )
                        PasswordField(
                            value = scheduleConfirmation,
                            onValueChange = { scheduleConfirmation = it },
                            label = "Passphrase again",
                            isError = scheduleConfirmation.isNotEmpty() && !schedulePassphrasesMatch,
                            supportingText = if (scheduleConfirmation.isNotEmpty() && !schedulePassphrasesMatch) {
                                "The two passphrases are different."
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Scheduled backups passphrase again" },
                        )
                        Button(
                            onClick = {
                                val chars = schedulePassphrase.toCharArray()
                                scheduleEncryption.onChange(chars)
                                chars.fill('\u0000')
                                schedulePassphrase = ""
                                scheduleConfirmation = ""
                                askingSchedulePassphrase = false
                            },
                            enabled = schedulePassphrasesMatch,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Encrypt scheduled backups") }
                    }
                }
            }
        }
        lastRun?.let { FolioNote(it) }
    }
    }
}

/** What a restore would do, before it does it. */
@Composable
private fun RestorePreview(
    staged: RestoreStaged.Ready,
    items: List<RestoreItem>,
    onAccept: (Set<String>, Clash) -> Unit,
    onDiscard: () -> Unit,
) {
    // Nothing from the app data is brought back unless it is ticked here; clashing items start unticked.
    var ticked by remember(items) {
        mutableStateOf(items.filter { it.selectable && it.status == RestoreStatus.NEW }.map { it.key }.toSet())
    }
    var clash by remember { mutableStateOf(Clash.KEEP_MINE) }
    Column(verticalArrangement = Arrangement.spacedBy(Measure.snug)) {
        run {
            Text(
                "Backup of \"${staged.manifest.projectTitle}\"",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = Measure.snug),
            )
            Text(
                "Taken ${staged.manifest.createdAt}, ${staged.manifest.files.size} file(s).",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(staged.diff.describe(), style = MaterialTheme.typography.bodyMedium)

            // The list that matters most: files that exist now and are not in the backup.
            if (staged.diff.removed.isNotEmpty()) {
                MessageBox(
                    "These files are in your project but not in this backup, and restoring " +
                        "would leave them behind:",
                    kind = NoticeKind.ERROR,
                )
                staged.diff.removed.take(REMOVED_SHOWN).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
                if (staged.diff.removed.size > REMOVED_SHOWN) {
                    Text(
                        "· and ${staged.diff.removed.size - REMOVED_SHOWN} more",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (staged.diff.changed.isNotEmpty()) {
                Text("Replaced:", style = MaterialTheme.typography.bodySmall)
                staged.diff.changed.take(REMOVED_SHOWN).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (items.isNotEmpty()) {
                Text("App data in this backup", style = MaterialTheme.typography.titleSmall)
                Sections(items, { it.group }) { item ->
                    TickRow(
                        title = item.title,
                        detail = listOf(
                            item.detail,
                            if (item.status == RestoreStatus.CLASH) "Already here" else "",
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        checked = item.key in ticked,
                        enabled = item.selectable,
                        onChange = { on -> ticked = if (on) ticked + item.key else ticked - item.key },
                    )
                }
                if (items.any { it.status == RestoreStatus.CLASH }) {
                    Text("When one is already here:", style = MaterialTheme.typography.bodySmall)
                    Clash.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = clash == option, role = Role.RadioButton) { clash = option },
                        ) {
                            RadioButton(selected = clash == option, onClick = null)
                            Text(option.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Text(
                    "Scripts come back saved and turned off from running; nothing runs until you run it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Your current project is kept first, so you can go back to it. Nothing is deleted.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAccept(ticked, clash) },
                    modifier = Modifier.semantics {
                        contentDescription = "Restore this backup over the current project"
                    },
                ) { Text("Restore") }
                OutlinedButton(onClick = onDiscard) { Text("Cancel") }
            }
        }
    }
}

private const val REMOVED_SHOWN = 5

/** The headings app data is grouped under, in the order shown, with the groups each one holds. */
private val SECTIONS = listOf(
    "Entry templates" to setOf(AppDataGroup.TEMPLATES),
    "Plugins" to setOf(AppDataGroup.PLUGINS),
    "Catalogue plugins" to setOf(AppDataGroup.CATALOGUE),
    "Saved scripts" to setOf(AppDataGroup.SCRIPTS),
    "Preferences" to setOf(AppDataGroup.PREFERENCES, AppDataGroup.COLOUR),
    "Remote server" to setOf(AppDataGroup.REMOTE),
)

/** [items] under a heading for each kind, so all templates sit together, then all plugins, and so on. */
@Composable
private fun <T> Sections(items: List<T>, groupOf: (T) -> AppDataGroup, row: @Composable (T) -> Unit) {
    SECTIONS.forEach { (title, groups) ->
        val inSection = items.filter { groupOf(it) in groups }
        if (inSection.isNotEmpty()) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = Measure.snug).semantics { heading() },
            )
            inSection.forEach { row(it) }
        }
    }
}

/** What the Backup screen needs to offer app data: what can go in, and what a chosen backup holds. */
data class AppDataUi(
    /** Catalogue plugins the last restore listed; offered for installing again. */
    val reinstall: List<String> = emptyList(),
    /** Starts installing [reinstall]; null where this edition cannot download plugins. */
    val installAgain: (() -> Unit)? = null,
    val available: List<AppDataItem> = emptyList(),
    val restoreItems: List<RestoreItem> = emptyList(),
)

@Composable
private fun TickRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "${bytes / 1000} KB"
    else -> "$bytes B"
}
