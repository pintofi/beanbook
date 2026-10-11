package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A ceiling on how wide a column of text and figures is allowed to get.
 *
 * On a phone this changes nothing — the window is already narrower than the limit. It is for
 * the expanded widths, where a journal row stretched the full window and left about 1100dp of
 * blank space between an account name on the left and its amount on the right. Both ends were
 * legible and the pair was not: nothing connects a label to a value across that distance, and
 * the eye has to track back along an empty line to find out which account a figure belongs to.
 *
 * 720dp rather than a typographic measure in characters, because these lines are not prose —
 * a row is a label at one end and a number at the other, and what matters is the gap between
 * them rather than the words per line.
 *
 * This is not the expanded two-pane layout that ux.md §7 describes and N8 tracks as unbuilt.
 * It is the cheap half of the same problem: a second pane would use the space, and until there
 * is one, not stretching a single column across it is better than stretching it.
 */
fun Modifier.readableMeasure(): Modifier = this.widthIn(max = 720.dp)

/**
 * The readable measure, centred in a wider window: for pages of controls (More and its
 * sections), which on a tablet otherwise hugged the left edge and left the rest of the window
 * empty. Ledger pages keep [readableMeasure], where the start edge lines up with the rail.
 */
fun Modifier.readableMeasureCentred(): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(androidx.compose.ui.Alignment.CenterHorizontally)
    .widthIn(max = 720.dp)
