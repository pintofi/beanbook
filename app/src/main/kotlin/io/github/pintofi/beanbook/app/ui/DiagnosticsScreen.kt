package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred

/**
 * What the app has been doing, for somebody trying to say what went wrong.
 *
 * A person who meets a bug had nothing to send and nothing to read: the app kept no record,
 * and `android.util.Log` is gone by the time anyone asks. This is that record, on the device,
 * in front of the person it belongs to.
 *
 * **Nothing is sent from here.** Copy and Share hand the report to whatever the user chooses,
 * when they choose it. The app itself uploads nothing, in any edition, which is the promise
 * this screen has to be built around rather than an obstacle to work past.
 */
@Composable
fun DiagnosticsScreen(
    read: () -> DiagnosticsRecord,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    var confirmingClear by remember { mutableStateOf(false) }

    // Read when the screen opens, and again after Clear. Reading once where the screen is
    // built instead showed whatever the log held at start-up, which is never what is wanted.
    var reads by remember { mutableStateOf(0) }
    val record = remember(reads) { read() }
    val lines = record.lines

    // Newest first: the reason anybody opens this is something that just happened.
    val newestFirst = remember(lines) { lines.asReversed() }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .readableMeasureCentred(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Measure.gutter,
            end = Measure.gutter,
            top = Measure.snug,
            bottom = Measure.section,
        ),
    ) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(Measure.snug)) {
                Text(
                    "What the app recorded while it ran. Passwords, keys and access codes are " +
                        "removed before anything is written, and your ledger's entries are never in " +
                        "here. Nothing is sent anywhere unless you share it yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Measure.tight),
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = onShare,
                        modifier = Modifier.semantics {
                            contentDescription = "Share the diagnostic report"
                        },
                    ) { Text("Share report") }
                    androidx.compose.material3.OutlinedButton(
                        onClick = onCopy,
                        modifier = Modifier.semantics {
                            contentDescription = "Copy the diagnostic report"
                        },
                    ) { Text("Copy") }
                    TextButton(
                        onClick = { confirmingClear = true },
                        modifier = Modifier.semantics { contentDescription = "Clear the recorded log" },
                    ) { Text("Clear") }
                }
                message?.let {
                    Notice(NoticeTone.QUIET) {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FolioHeading(
                    if (lines.isEmpty()) {
                        "Nothing recorded yet."
                    } else {
                        val count = if (lines.size == 1) "1 line" else "${lines.size} lines"
                        "$count · ${describeSize(record.sizeBytes)} kept on this device"
                    },
                )
            }
        }
        // Wrapped, with a hairline between lines (device audit 2026-10-10): lines that scrolled
        // sideways one by one were cut at the right edge, and nothing said they went further --
        // the useful end of "script beangrow_config -> F..." could not be read on the phone.
        // Each line keeps its own row, so the timestamp still starts every entry.
        items(newestFirst) { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = Measure.tight),
            )
            LedgerHairline()
        }
    }

    if (confirmingClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear the log?") },
            text = {
                Text(
                    "This removes what has been recorded so far. Your ledger, documents and " +
                        "settings are untouched.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        onClear()
                        reads++
                    },
                    modifier = Modifier.semantics { contentDescription = "Confirm clearing the log" },
                ) { Text("Clear it") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("Keep it") }
            },
        )
    }
}

/** Bytes as a person reads them, so a small log does not report itself as "0 KB". */
private fun describeSize(bytes: Long): String =
    if (bytes < 1024) "$bytes bytes" else "${bytes / 1024} KB"

/** What the log holds right now: the lines to show, and what they cost on the device. */
data class DiagnosticsRecord(val lines: List<String>, val sizeBytes: Long)

/**
 * What the diagnostics screen needs, as one value.
 *
 * Built where the log and the share sheet live, so the screen stays a screen: it renders
 * lines and reports taps, and never reads a file or starts an intent itself.
 */
data class DiagnosticsUi(
    val read: () -> DiagnosticsRecord,
    val onCopy: () -> Unit,
    val onShare: () -> Unit,
    val onClear: () -> Unit,
    val message: String? = null,
)
