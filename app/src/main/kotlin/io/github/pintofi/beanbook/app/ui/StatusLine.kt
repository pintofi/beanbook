package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme

/**
 * A line saying what the app is doing, with a spinner beside it while it is still doing it
 * (B93).
 *
 * The app's status lines are written as the work goes -- "Downloading beangrow…", "Backing
 * up…", "Connecting…" -- and a line of text alone looks the same whether the work is moving
 * or stuck. Work in progress is the text ending in an ellipsis, which is how every one of them
 * is already worded; a finished result ends in a full stop and gets no spinner.
 *
 * Announced politely, so a screen reader hears the progress and then the outcome.
 */
@Composable
fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    working: Boolean = isWorking(text),
) {
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = BeancountTheme.ledger.rule,
            )
        }
        Text(text, style = style)
    }
}

/** Whether [text] describes work still under way: the app writes those ending in "…". */
fun isWorking(text: String): Boolean = text.trimEnd().endsWith("…")
