package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.Measure

/**
 * The entry forms' save area, pinned under the form (design-direction §7).
 *
 * Save was at the very end of a form two and a half screens long, and the message that said
 * why a save was refused appeared *under* it, below the fold, where it was missed right after
 * the tap that caused it (device audit 2026-10-10). The bar stays on screen -- above the
 * keyboard while one is up, since the shell insets the whole app for it -- and what the form
 * has to say is said on the bar, beside the button that it is about.
 *
 * [problems] are what stops the save; [message] is what the last save did. A problem is in the
 * error colour with the attention mark; a result in ink. Both are announced politely.
 */
@Composable
fun FormSaveBar(
    problems: List<String>,
    message: String?,
    modifier: Modifier = Modifier,
    button: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            LedgerHairline()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Measure.gutter, vertical = Measure.gap),
                verticalArrangement = Arrangement.spacedBy(Measure.snug),
            ) {
                LedgerReveal(visible = problems.isNotEmpty(), label = "formProblems") {
                    // Held while it collapses, so the words do not vanish a frame before the room.
                    val shown = remember(problems) { problems }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.Top,
                    ) {
                        StampMark(LedgerMark.ATTENTION, modifier = Modifier.padding(top = 1.dp, end = Measure.snug))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            shown.take(MAX_SHOWN).forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (shown.size > MAX_SHOWN) {
                                Text(
                                    "and ${shown.size - MAX_SHOWN} more",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                LedgerReveal(visible = message != null && problems.isEmpty(), label = "formMessage") {
                    val shown = remember(message) { message }.orEmpty()
                    val refused = !shown.startsWith("Saved") && !isWorking(shown)
                    StatusLine(
                        shown,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (refused) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        ),
                    )
                }
                Column(modifier = Modifier.heightIn(min = 52.dp), content = button)
            }
        }
    }
}

/** A bar shows three problems and counts the rest; the form above lists every field. */
private const val MAX_SHOWN = 3
