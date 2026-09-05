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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import io.github.pintofi.beanbook.app.plugins.ScriptOutcome

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
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
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

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable
private fun Outcome(outcome: ScriptOutcome) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "${outcome.name}: ${outcome.summary()}"
            },
        colors = CardDefaults.cardColors(
            containerColor = if (outcome.ok) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(outcome.name, style = MaterialTheme.typography.titleSmall)
            Text(outcome.summary(), style = MaterialTheme.typography.bodySmall)

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
