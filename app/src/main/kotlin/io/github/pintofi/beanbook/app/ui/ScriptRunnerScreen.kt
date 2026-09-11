package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.plugins.SavedScript
import io.github.pintofi.beanbook.app.plugins.ScriptOutcome
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.LedgerType

/**
 * Run a script against the open ledger (Tier 3, N10).
 *
 * The engine could do this from M11 and nothing called it. What this screen adds beyond a
 * button is the honesty the engine already provides and had no way to show: whether a script
 * finished, was stopped, or ignored its deadline and kept going.
 */
@Composable
fun ScriptRunnerScreen(
    running: String?,
    outcome: ScriptOutcome?,
    message: String?,
    bridgeAvailable: Boolean,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Scripts kept from earlier runs (B49). */
    saved: List<SavedScript> = emptyList(),
    onRunSaved: (String) -> Unit = {},
    onForgetSaved: (String) -> Unit = {},
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.gap, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Run a script",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "A Python script, run once against the ledger you have open. It is given the " +
                "path in ledger_path; anything it prints appears below.",
            style = MaterialTheme.typography.bodySmall,
        )

        Notice(NoticeTone.PROBLEM) {
            run {
                Text("What running a script means", style = MaterialTheme.typography.titleSmall)
                Text(
                    "It runs with this app's permissions and is not sandboxed. It can read " +
                        "and change your ledger and anything else the app can reach" +
                        if (bridgeAvailable) ", and can call Android APIs." else ".",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "It is stopped after 30 seconds. A script stuck inside one call finishes " +
                        "that call first.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (running != null) {
            Text(
                "Running $running…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Running the script $running"
                },
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "Stop the running script" },
            ) { Text("Stop it") }
        } else {
            Button(
                onClick = onPick,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Choose a script file and run it" },
            ) { Text("Choose a script…") }
        }

        if (saved.isNotEmpty()) {
            Text("Your scripts", style = MaterialTheme.typography.titleSmall)
            Text(
                "Kept when you ran them, so you do not have to find the file again. They are " +
                    "copies: editing the original does not change these.",
                style = MaterialTheme.typography.bodySmall,
            )
            saved.forEach { script -> SavedScriptCard(script, running == null, onRunSaved, onForgetSaved) }
        }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        outcome?.let { Outcome(it) }
    }
}

/**
 * One kept script: what it is, when it was kept, and the checksum of the copy.
 *
 * The same three facts a plugin shows, for the same reason — this is code the user chose to
 * trust, and "where did this come from" is the question asked six months later.
 */
@Composable
private fun SavedScriptCard(
    script: SavedScript,
    runnable: Boolean,
    onRun: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    RuledColumn(verticalArrangement = Arrangement.spacedBy(Measure.snug)) {
        run {
            Text(script.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "Kept ${dateOf(script.savedAtEpochMs)} · checksum ${script.sha256.take(12)}…",
                style = LedgerType.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onRun(script.id) },
                    enabled = runnable,
                    modifier = Modifier.semantics {
                        contentDescription = "Run the saved script ${script.name}"
                    },
                ) { Text("Run") }
                TextButton(
                    onClick = { onForget(script.id) },
                    modifier = Modifier.semantics {
                        contentDescription = "Remove the saved script ${script.name}"
                    },
                ) { Text("Remove") }
            }
        }
    }
}

private fun dateOf(epochMillis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date(epochMillis))

@Composable
private fun Outcome(outcome: ScriptOutcome) {
    Notice(
        if (outcome.ok) NoticeTone.QUIET else NoticeTone.PROBLEM,
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = "${outcome.name}: ${outcome.summary()}"
        },
    ) {
        run {
            Text(outcome.name, style = MaterialTheme.typography.titleSmall)
            Text(outcome.summary(), style = MaterialTheme.typography.bodyMedium)

            if (outcome.output.isNotBlank()) {
                Text("Output", style = MaterialTheme.typography.labelLarge)
                // Monospace and scrolling sideways: script output is aligned text more often
                // than it is prose, and rewrapping it makes a table unreadable.
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        outcome.output.trimEnd(),
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}
