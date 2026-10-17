package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.Measure
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.engine.ReportPath

/** The reports worth reaching in one tap, in the order someone actually reads them. */
private val LISTED = listOf(
    ReportPath.INCOME_STATEMENT to "Income statement",
    ReportPath.BALANCE_SHEET to "Balance sheet",
    ReportPath.TRIAL_BALANCE to "Trial balance",
    ReportPath.JOURNAL to "Journal",
    ReportPath.HOLDINGS to "Holdings",
    ReportPath.COMMODITIES to "Commodities",
    ReportPath.QUERY to "Query",
    ReportPath.ERRORS to "Errors",
)

/**
 * Reports on a wide window: a narrow list of reports, and the Fava WebView in the wider pane.
 *
 * ux.md §7 asks for exactly this and it was never built — Reports rendered as a single pane at
 * every width, so on a tablet the report text ran the full width of the window and Fava's own
 * navigation stayed folded away inside the page.
 *
 * The list is native rather than part of the page because it is the one piece of navigation
 * that should not depend on the WebView having loaded: when a report fails, the way to another
 * report is still there.
 */
@Composable
fun ReportsPane(
    selected: ReportPath,
    onSelect: (ReportPath) -> Unit,
    modifier: Modifier = Modifier,
    report: @Composable () -> Unit,
) {
    Row(modifier) {
        Column(
            Modifier
                .weight(0.28f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Reports",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Measure.gutter, vertical = Measure.indent)
                    .semantics { heading() },
            )
            LISTED.forEach { (path, label) ->
                val isSelected = path == selected
                val rule = BeancountTheme.ledger.rule
                ListItem(
                    headlineContent = { Text(label) },
                    colors = if (isSelected) {
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        )
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // Which report is open is said by a rule down the edge of the row,
                        // the same mark the navigation bar uses, rather than by a tinted
                        // pill: one vocabulary for "you are here" across the app.
                        .drawBehind {
                            if (isSelected) {
                                drawRect(
                                    color = rule,
                                    size = Size(width = Measure.rule.toPx(), height = size.height),
                                )
                            }
                        }
                        .clickable(role = Role.Button) { onSelect(path) }
                        .semantics {
                            // `selected` rather than folding "selected" into the label: a
                            // screen reader announces state itself, and putting it in the
                            // text makes it part of the name instead (m14-part6.md).
                            this.selected = isSelected
                            contentDescription = "$label report"
                        },
                )
                LedgerHairline()
            }
        }
        VerticalDivider()
        // The wider pane, as ux.md §7 puts it.
        Box(Modifier.weight(0.72f).fillMaxSize()) { report() }
    }
}
