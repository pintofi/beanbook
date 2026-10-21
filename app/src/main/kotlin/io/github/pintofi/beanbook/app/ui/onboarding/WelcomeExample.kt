package io.github.pintofi.beanbook.app.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.BeanbookMark
import io.github.pintofi.beanbook.app.ui.InBalanceStamp
import io.github.pintofi.beanbook.app.ui.RollingFigure
import io.github.pintofi.beanbook.app.ui.theme.rememberAnimationsEnabled
import io.github.pintofi.beanbook.app.ui.theme.Measure
import kotlinx.coroutines.delay

/** The mark, eased in when motion is on and simply there when it is off. */
@Composable
internal fun ArrivingMark() {
    val animate = rememberAnimationsEnabled()
    val arrival = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) { if (animate) arrival.animateTo(1f, tween(500)) }
    BeanbookMark(
        size = 72.dp,
        modifier = Modifier.graphicsLayer {
            alpha = arrival.value
            val scale = 0.85f + 0.15f * arrival.value
            scaleX = scale
            scaleY = scale
        },
    )
}

/**
 * One example entry, to show what a ledger is before asking anything of the reader: a coffee
 * leaves Cash and lands under Food, the two figures cancel, and that is "in balance".
 *
 * The figure rolls in from zero and the stamp lands after it; with animations off both are simply
 * there. Read aloud as one sentence, since the layout is for the eye.
 */
@Composable
internal fun WelcomeExample(modifier: Modifier = Modifier) {
    val animate = rememberAnimationsEnabled()
    var shown by remember { mutableStateOf(!animate) }
    LaunchedEffect(animate) {
        if (animate) {
            delay(600)
            shown = true
        }
    }
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "An example entry. A coffee for 4.50 leaves Cash and is filed under Food. " +
                    "The two sides add up to zero, so the books are in balance."
            },
        verticalArrangement = Arrangement.spacedBy(Measure.tight),
    ) {
        Text("A ledger is a list of entries like this one", style = MaterialTheme.typography.labelLarge)
        Text("2026-10-21 * \"Coffee\"", style = mono.copy(color = MaterialTheme.colorScheme.onSurface))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("  Expenses:Food", style = mono)
            RollingFigure(
                text = if (shown) "4.50 USD" else "0.00 USD",
                style = mono,
                rollInFrom = if (animate) "0.00 USD" else null,
            )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("  Assets:Cash", style = mono)
            Text("-4.50 USD", style = mono)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Measure.gap),
            modifier = Modifier.padding(top = Measure.snug),
        ) {
            if (shown) InBalanceStamp(modifier = Modifier.clearAndSetSemantics { })
            Text(
                "What leaves one account arrives in another, so every entry adds up to zero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
