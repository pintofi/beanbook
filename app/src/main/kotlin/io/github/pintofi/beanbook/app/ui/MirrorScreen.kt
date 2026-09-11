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
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.gap, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(Measure.gap),
    ) {
        // The state first, before any control. A screen whose top half is settings cannot
        // answer the question people open it to ask -- is my folder current right now -- and
        // the answer used to exist only as a message left over from the last pass (B65).
        MirrorStatusCard(status)

        Text("Mirror to a folder", style = MaterialTheme.typography.titleMedium)
        Text(
            "A copy of your project is kept in a folder you choose, and changes made in that " +
                "folder are brought back in. The version on this device stays the original: " +
                "anything the app cannot decide by itself waits for you, with both versions " +
                "kept.",
            style = MaterialTheme.typography.bodySmall,
        )

        // The folder first, then what to do about it. The switch cannot be turned on without
        // a folder, so offering it above the folder button was offering a dead control and
        // making the reader work out why.
        OutlinedButton(
            onClick = onChooseFolder,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        if (folder == null) "Choose a folder to mirror into" else "Change the folder"
                },
        ) { Text(if (folder == null) "Choose a folder" else "Change folder") }
        folder?.let { Text("Mirroring to $it", style = MaterialTheme.typography.bodySmall) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mirror after every change", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (folder == null) {
                        "Choose a folder first."
                    } else {
                        "Both ways: writes here go out, and changes there come back."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
                enabled = folder != null,
                modifier = Modifier.semantics { contentDescription = "Mirror after every change" },
            )
        }

        Button(
            onClick = onSyncNow,
            enabled = folder != null && !status.isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Mirror now" },
        ) { Text("Mirror now") }

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (conflicts.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Needs your decision",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Nothing has been overwritten. The folder's copy is saved beside yours with " +
                    "a \".conflict-\" suffix until you choose.",
                style = MaterialTheme.typography.bodySmall,
            )

            conflicts.forEach { conflict ->
                Notice(NoticeTone.PROBLEM) {
                    Text(conflict.path, style = LedgerType.small)
                    Text(conflict.describe(), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(Measure.snug)) {
                            // Outlined, not text: these two decide which copy of a file
                            // survives, and a decision that destructive must look like a
                            // control rather than a caption (B66).
                            OutlinedButton(
                                onClick = { onKeepMine(conflict.path) },
                                modifier = Modifier.semantics {
                                    contentDescription =
                                        "Keep this device's version of ${conflict.path}"
                                },
                            ) { Text("Keep this device's") }
                            OutlinedButton(
                                onClick = { onKeepTheirs(conflict.path) },
                                modifier = Modifier.semantics {
                                    contentDescription =
                                        "Keep the folder's version of ${conflict.path}"
                                },
                            ) { Text("Keep the folder's") }
                    }
                }
            }
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
        Text(headline, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        // A rule filling, in the app's own measure: a pass that is counting files fills it
        // by count, and one that is still comparing draws it without a figure to fill to.
        when (status) {
            is MirrorStatus.Copying -> LinearProgressIndicator(
                progress = {
                    if (status.total <= 0) 0f else status.done.toFloat() / status.total
                },
                modifier = Modifier.fillMaxWidth().height(Measure.rule),
                color = BeancountTheme.ledger.rule,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )

            MirrorStatus.Comparing -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(Measure.rule),
                color = BeancountTheme.ledger.rule,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
                gapSize = 0.dp,
            )

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
