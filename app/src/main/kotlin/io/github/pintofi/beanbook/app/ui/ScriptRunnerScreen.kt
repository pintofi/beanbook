package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred
import androidx.compose.material.icons.outlined.Terminal
import io.github.pintofi.beanbook.app.ui.theme.LedgerType

/**
 * Run a script against the open ledger (N10).
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
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
    FolioPage(modifier = Modifier.readableMeasureCentred()) {
        Text(
            "A Python script, run once against the ledger you have open. It is given the " +
                "path in ledger_path and runs in the folder your main file is in, so a " +
                "relative path names a file beside your ledger. Anything it prints appears " +
                "below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Measure.tight),
        )

        // The risk, read before the button (content style guide §6), in the attention tone:
        // always on screen, so it should read as a statement, not an alarm.
        Notice(NoticeTone.ATTENTION) {
            run {
                Text("What running a script means", style = MaterialTheme.typography.titleSmallEmphasized)
                Text(
                    "It runs with this app's permissions and is not sandboxed. It can read " +
                        "and change your ledger and anything else the app can reach" +
                        if (bridgeAvailable) ", and can call Android APIs." else ".",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "It runs until it finishes or you cancel it. A script stuck inside one " +
                        "call finishes that call first.",
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
            WorkingBar()
            LiveTerminal(running)
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Cancel the running script" },
            ) { Text("Cancel") }
        } else {
            Button(
                onClick = onPick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics { contentDescription = "Choose a script file and run it" },
            ) { Text("Choose a script…") }
        }

        // The result directly under the button that produced it (B89). It used to come last,
        // below every saved script, so after a run it was off the bottom of the screen and
        // the run looked as if it had printed nothing.
        message?.let { StatusLine(it, modifier = Modifier.padding(horizontal = Measure.tight)) }
        outcome?.let { Outcome(it) }

        if (saved.isNotEmpty()) {
            FolioHeading("Your scripts")
            FolioGroup {
                saved.forEach { script ->
                    item { SavedScriptCard(script, running == null, onRunSaved, onForgetSaved) }
                }
            }
            FolioNote(
                "Kept when you ran them, so you do not have to find the file again. They are " +
                    "copies: editing the original does not change these.",
            )
        }
    }
    }
}

/**
 * One kept script: what it is, when it was kept, and the checksum of the copy.
 *
 * The same three facts a plugin shows, for the same reason — this is code the user chose to
 * trust, and "where did this come from" is the question asked six months later. Run is tonal
 * rather than filled: with several scripts, a column of filled buttons said nothing about which
 * one mattered (device audit 2026-10-10).
 */
@Composable
private fun SavedScriptCard(
    script: SavedScript,
    runnable: Boolean,
    onRun: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    FolioBody {
        Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
            FolioBadge(
                androidx.compose.material.icons.Icons.Outlined.Terminal,
                modifier = Modifier.padding(end = Measure.indent),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Measure.tight)) {
                Text(script.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Kept ${dateOf(script.savedAtEpochMs)} · checksum ${script.sha256.take(12)}…",
                    style = LedgerType.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilledTonalButton(
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

private fun dateOf(epochMillis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date(epochMillis))

@Composable
private fun Outcome(outcome: ScriptOutcome) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
        ScriptTerminal(outcome)
    }
}

/**
 * The script's output exactly as it printed it, in a box that reads like a terminal (B93).
 *
 * Its own area rather than a line inside the result notice: output is often long -- a
 * generated configuration runs to hundreds of lines -- and a notice that grew to hold it
 * pushed everything else off the screen. The box has a fixed height and scrolls both ways, so
 * aligned columns stay aligned, and the text can be selected and copied.
 *
 * Dark in both themes, like a terminal: it marks the text inside as the script's rather than
 * the app's. A failure's error line is shown after the output, as a terminal would show it.
 */
@Composable
private fun ScriptTerminal(outcome: ScriptOutcome) {
    val ink = androidx.compose.ui.graphics.Color(0xFFE4EAE7)
    val dim = androidx.compose.ui.graphics.Color(0xFF8A9A94)
    val fault = androidx.compose.ui.graphics.Color(0xFFFFB4A9)
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val lines = outcome.output.trimEnd()
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 360.dp)
            .background(
                androidx.compose.ui.graphics.Color(0xFF0D1211),
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .padding(12.dp)
            .semantics { contentDescription = "Output of ${outcome.name}" },
    ) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text("$ python ${outcome.name}", style = mono, color = dim)
                if (lines.isNotEmpty()) Text(lines, style = mono, color = ink)
                if (!outcome.ok && outcome.error.isNotBlank()) {
                    Text("${outcome.errorType}: ${outcome.error}", style = mono, color = fault)
                }
                if (lines.isEmpty() && outcome.ok) {
                    Text("(nothing printed)", style = mono, color = dim)
                }
            }
        }
    }
}

/**
 * The running script's output as it prints, in the same terminal box, following the end unless
 * the person has scrolled up to read something (B102).
 */
@Composable
private fun LiveTerminal(name: String) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
        as io.github.pintofi.beanbook.app.BeancountApplication
    val text by app.scriptRunner.live.collectAsState()
    val ink = androidx.compose.ui.graphics.Color(0xFFE4EAE7)
    val dim = androidx.compose.ui.graphics.Color(0xFF8A9A94)
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val scroll = rememberScrollState()
    val following = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(scroll.value, scroll.maxValue) {
        // At (or near) the bottom means "follow"; anywhere else means the person is reading.
        following.value = scroll.maxValue - scroll.value < 48
    }
    androidx.compose.runtime.LaunchedEffect(text) {
        if (following.value) scroll.scrollTo(scroll.maxValue)
    }
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 360.dp)
            .background(
                androidx.compose.ui.graphics.Color(0xFF0D1211),
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .padding(12.dp)
            .semantics { contentDescription = "Output of $name so far" },
    ) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.verticalScroll(scroll).horizontalScroll(rememberScrollState())) {
                Text("$ python $name", style = mono, color = dim)
                Text(
                    text.trimEnd().ifEmpty { "(nothing printed yet)" },
                    style = mono,
                    color = if (text.isEmpty()) dim else ink,
                )
            }
        }
    }
}
