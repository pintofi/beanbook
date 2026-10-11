package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled
import io.github.pintofi.beanbook.app.ui.theme.Measure

/**
 * The ledger's rule, filling: the progress mark for reading the ledger, mirroring and drawing a
 * report (docs/material-expressive.md -- flat, 2dp, never wavy).
 *
 * **Honours reduce-motion (design-direction §9).** An indeterminate bar is a loop, and Material's
 * runs whatever the system says; the audit found the app's bars still sliding with animations
 * turned off. With motion off this draws a still rule -- a third of the track in the rule colour
 * -- and still reports indeterminate progress to accessibility services, so nothing is lost but
 * the movement. A [progress] value is drawn as it is either way: a count filling is information,
 * not decoration.
 */
@Composable
fun LedgerProgressRule(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    colour: Color = BeancountTheme.ledger.rule,
    track: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    val bar = modifier.fillMaxWidth().height(Measure.rule)
    when {
        progress != null -> LinearProgressIndicator(
            progress = progress,
            modifier = bar,
            color = colour,
            trackColor = track,
            strokeCap = StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        LocalMotionEnabled.current -> LinearProgressIndicator(
            modifier = bar,
            color = colour,
            trackColor = track,
            strokeCap = StrokeCap.Butt,
            gapSize = 0.dp,
        )
        else -> Box(bar.progressSemantics().background(track)) {
            Box(
                Modifier
                    .fillMaxWidth(STILL_FRACTION)
                    .fillMaxHeight()
                    .background(colour),
            )
        }
    }
}

/** How much of the track the still rule fills: enough to read as "under way", not as "done". */
private const val STILL_FRACTION = 0.33f
