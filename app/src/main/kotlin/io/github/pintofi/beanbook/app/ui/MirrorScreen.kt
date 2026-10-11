package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.mirror.MirrorStatus
import io.github.pintofi.beanbook.core.data.MirrorAction
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Sync

/**
 * Auto-mirror (docs/storage-and-backup.md §6).
 *
 * The screen exists mostly to make conflicts unmissable and their resolution deliberate.
 * Everything else — a file changed here, a file changed there — happens without asking,
 * because there is only one sensible answer. When there is more than one, the app stops.
 */
@Composable
fun MirrorScreen(
    enabled: Boolean,
    folder: String?,
    status: MirrorStatus,
    conflicts: List<MirrorAction.Conflict>,
    onChooseFolder: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onKeepMine: (String) -> Unit,
    onKeepTheirs: (String) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        FolioPage(modifier = Modifier.readableMeasureCentred()) {
            // The state first, before any control. A screen whose top half is settings cannot
            // answer the question people open it to ask -- is my folder current right now -- and
            // the answer used to exist only as a message left over from the last pass (B65).
            MirrorStatusCard(status)

            FolioHeading("Mirror to a folder")
            // The folder first, then what to do about it. The switch cannot be turned on without
            // a folder, so offering it above the folder was offering a dead control.
            FolioGroup {
                item {
                    FolioRow(
                        // The folder by its name, not by Android's handle for it (device audit
                        // 2026-10-10: the raw content:// grant was printed here).
                        title = folder?.let { humanFolder(it) } ?: "No folder chosen",
                        detail = if (folder == null) {
                            "A synced folder works well: Syncthing, Nextcloud or Drive."
                        } else {
                            "Mirroring to this folder"
                        },
                        icon = Icons.Outlined.Folder,
                        tone = if (folder == null) FolioTone.PLAIN else FolioTone.ACCENT,
                    ) {
                        TextButton(
                            onClick = onChooseFolder,
                            modifier = Modifier.semantics {
                                contentDescription =
                                    if (folder == null) "Choose a folder to mirror into" else "Change the folder"
                            },
                        ) { Text(if (folder == null) "Choose a folder" else "Change folder") }
                    }
                }
                item {
                    FolioSwitchRow(
                        title = "Mirror after every change",
                        checked = enabled,
                        onCheckedChange = onEnabledChanged,
                        enabled = folder != null,
                        detail = if (folder == null) {
                            "Choose a folder first."
                        } else {
                            "Both ways: writes here go out, and changes there come back."
                        },
                        icon = Icons.Outlined.Sync,
                        describe = "Mirror after every change",
                    )
                }
            }
            FolioNote(
                "The version on this device stays the original: anything the app cannot decide by " +
                    "itself waits for you, with both versions kept.",
            )

            Button(
                onClick = onSyncNow,
                enabled = folder != null && !status.isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics { contentDescription = "Mirror now" },
            ) { Text("Mirror now") }

            message?.let { StatusLine(it, modifier = Modifier.padding(horizontal = Measure.tight)) }

            if (conflicts.isNotEmpty()) {
                // One summary, then one compact row per file (device audit 2026-10-10: four loud
                // red cards stacked). The status card above already says how many; this is where
                // each one is decided.
                FolioHeading(
                    if (conflicts.size == 1) {
                        "1 file needs your decision"
                    } else {
                        "${conflicts.size} files need your decision"
                    },
                )
                FolioGroup {
                    conflicts.forEach { conflict ->
                        item { ConflictRow(conflict, onKeepMine, onKeepTheirs) }
                    }
                }
                FolioNote(
                    "Nothing has been overwritten. The folder's copy is saved beside yours with a " +
                        "\".conflict-\" suffix until you choose.",
                )
            }
        }
    }
}

/** One file both sides changed: where it is, what differs, and the two ways to settle it. */
@Composable
private fun ConflictRow(
    conflict: MirrorAction.Conflict,
    onKeepMine: (String) -> Unit,
    onKeepTheirs: (String) -> Unit,
) {
    FolioBody {
        Row(verticalAlignment = Alignment.Top) {
            StampMark(LedgerMark.ATTENTION, modifier = Modifier.padding(top = 2.dp, end = Measure.gap))
            Column(verticalArrangement = Arrangement.spacedBy(Measure.tight)) {
                Text(conflict.path, style = LedgerType.small)
                Text(
                    conflict.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Outlined, not text: these two decide which copy of a file survives, and a decision
        // that destructive must look like a control rather than a caption (B66).
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
            verticalArrangement = Arrangement.spacedBy(Measure.tight),
        ) {
            OutlinedButton(
                onClick = { onKeepMine(conflict.path) },
                modifier = Modifier.semantics {
                    contentDescription = "Keep this device's version of ${conflict.path}"
                },
            ) { Text("Keep this device's") }
            OutlinedButton(
                onClick = { onKeepTheirs(conflict.path) },
                modifier = Modifier.semantics {
                    contentDescription = "Keep the folder's version of ${conflict.path}"
                },
            ) { Text("Keep the folder's") }
        }
    }
}

/**
 * One card that always says what mirroring is doing (B65).
 *
 * A progress bar only while a pass is running, and a determinate one wherever there is a
 * count: a spinner that never fills says "something is happening" for as long as it is on
 * screen, which is indistinguishable from stuck.
 */
@Composable
private fun MirrorStatusCard(status: MirrorStatus) {
    val headline = when (status) {
        MirrorStatus.Off -> "Mirroring is off"
        is MirrorStatus.Manual -> "Mirroring by hand"
        is MirrorStatus.Watching -> "Mirroring after every change"
        MirrorStatus.Comparing -> "Comparing both sides…"
        is MirrorStatus.Copying -> "Copying ${status.done} of ${status.total}…"
        is MirrorStatus.NeedsDecision ->
            if (status.count == 1) "1 file needs your decision" else "${status.count} files need your decision"
        is MirrorStatus.Failed -> "The last pass did not finish"
    }
    val detail = when (status) {
        MirrorStatus.Off -> "Choose a folder to keep a copy of this project in."
        is MirrorStatus.Manual -> lastPass(status.lastPassAtEpochMs) +
            " Turn on \"Mirror after every change\" to keep it in step by itself."
        is MirrorStatus.Watching -> lastPass(status.lastPassAtEpochMs)
        MirrorStatus.Comparing -> "Reading what is here and what is in your folder."
        is MirrorStatus.Copying -> "Nothing is overwritten without asking."
        is MirrorStatus.NeedsDecision -> "Both versions are kept until you choose, below."
        is MirrorStatus.Failed -> "${status.reason}. Your project is unchanged."
    }
    val tone = when (status) {
        is MirrorStatus.NeedsDecision, is MirrorStatus.Failed -> NoticeTone.PROBLEM
        else -> NoticeTone.QUIET
    }

    Notice(
        tone,
        modifier = Modifier.semantics {
            contentDescription = "Mirroring status: $headline. $detail"
        },
    ) {
        // A pass changes what this says several times in a few seconds -- comparing, copying
        // 3 of 40, done -- and text that is replaced between frames reads as flicker. The
        // words dissolve into each other instead (B76).
        io.github.pintofi.beanbook.app.ui.LedgerContent(
            targetState = headline to detail,
            label = "mirrorStatus",
            move = { _, _ -> io.github.pintofi.beanbook.app.ui.Move.none },
        ) { (shownHeadline, shownDetail) ->
            Column(verticalArrangement = Arrangement.spacedBy(Measure.snug)) {
                Text(shownHeadline, style = MaterialTheme.typography.titleSmall)
                Text(shownDetail, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // A rule filling, in the app's own measure: a pass that is counting files fills it
        // by count, and one that is still comparing draws it without a figure to fill to.
        when (status) {
            is MirrorStatus.Copying -> LedgerProgressRule(
                progress = {
                    if (status.total <= 0) 0f else status.done.toFloat() / status.total
                },
            )

            // Still, with animations off (LedgerProgressRule).
            MirrorStatus.Comparing -> LedgerProgressRule()

            else -> Unit
        }
    }
}

/** "Last mirrored 4 minutes ago", in the words a person would use. */
private fun lastPass(atEpochMs: Long?): String {
    if (atEpochMs == null) return "Not mirrored yet on this device."
    val minutes = (System.currentTimeMillis() - atEpochMs) / 60_000
    return when {
        minutes < 1L -> "Last mirrored a moment ago."
        minutes < 60L -> "Last mirrored $minutes minute${if (minutes == 1L) "" else "s"} ago."
        minutes < 24L * 60L -> {
            val hours = minutes / 60L
            "Last mirrored $hours hour${if (hours == 1L) "" else "s"} ago."
        }
        else -> {
            val days = minutes / (24L * 60L)
            "Last mirrored $days day${if (days == 1L) "" else "s"} ago."
        }
    }
}
