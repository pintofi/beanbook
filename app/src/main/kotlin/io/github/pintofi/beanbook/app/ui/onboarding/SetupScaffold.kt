package io.github.pintofi.beanbook.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred
import io.github.pintofi.beanbook.app.ui.theme.Measure

/** How many steps the setup has after "ways in": Basics, Accounts, Get set. */
internal const val SETUP_STEPS = 3

/**
 * The frame every setup step sits in: a back arrow, the step and its title, two equal rules that
 * fill as the steps go by, the step's own content, and a bar that stays put at the bottom.
 *
 * The two rules are the app's mark again, used as the progress bar instead of a generic one.
 * "Back" is the system gesture as well as the arrow, and always goes one step back with the
 * answers kept.
 */
@Composable
internal fun SetupPage(
    step: Int,
    title: String,
    onBack: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "Back" },
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Step $step of $SETUP_STEPS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (secondaryLabel != null) OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
        StepRules(step)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .readableMeasureCentred()
                // The folio page's own spacing, so the steps sit like every other page of cards.
                .padding(horizontal = Measure.gutter)
                .padding(top = Measure.snug, bottom = Measure.section),
            verticalArrangement = Arrangement.spacedBy(Measure.snug),
            content = content,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = Modifier
                    .readableMeasureCentred()
                    .fillMaxWidth()
                    .padding(horizontal = Measure.gutter, vertical = Measure.gap),
            ) { Text(primaryLabel) }
        }
    }
}

/** Two equal rules, each filled a third further at every step. */
@Composable
internal fun StepRules(step: Int) {
    val done = MaterialTheme.colorScheme.primary
    val todo = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Measure.gutter)
            .semantics { stateDescription = "Step $step of $SETUP_STEPS" },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(SETUP_STEPS) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(if (index < step) done else todo),
                    )
                }
            }
        }
    }
}

/** An explanation under a field, in the quiet colour, flush with the field's own text. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp),
    )
}
