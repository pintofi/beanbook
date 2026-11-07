package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme

/**
 * A line saying what the app is doing. While the work is still going it becomes a banner with a
 * loading indicator and a moving bar.
 *
 * The app's status lines are written as the work goes -- "Downloading beangrow…", "Backing
 * up…", "Connecting…" -- and a line of text alone looks the same whether the work is moving
 * or stuck. Work in progress is the text ending in an ellipsis, which is how every one of them
 * is already worded; a finished result ends in a full stop and gets no indicator.
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
    if (working) {
        WorkingBanner(text, modifier)
        return
    }
    Text(
        text,
        style = style,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Work under way, where a small spinner beside small text was easy to miss: a tinted band with
 * the words at reading size, a larger indicator, and a bar that keeps moving along its foot.
 */
@Composable
private fun WorkingBanner(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The Expressive loading indicator: its shapes turning say "still moving" where
                // a ring could be mistaken for stuck, and with animation off it is one still
                // shape (LedgerLoadingIndicator).
                LedgerLoadingIndicator(size = 28.dp, color = BeancountTheme.ledger.rule)
                Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Whether [text] describes work still under way: the app writes those ending in "…". */
fun isWorking(text: String): Boolean = text.trimEnd().endsWith("…")
