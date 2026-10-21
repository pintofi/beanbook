package io.github.pintofi.beanbook.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.core.domain.Valuation

/**
 * Asks how a net-worth widget should read its figure, while it is placed or reconfigured.
 *
 * Reads and writes only the small widget-options file: placing a widget starts no engine, the same
 * rule as [BalanceWidgetConfigureActivity].
 */
class NetWorthWidgetConfigureActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Exported for the launcher, so the id is only accepted if it is one of our own net-worth
        // widgets, as in the balance widget's screen.
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            info?.provider?.className != NetWorthWidget::class.java.name ||
            info.provider?.packageName != packageName
        ) {
            finish()
            return
        }

        val current = WidgetOptions(this).optionFor(widgetId)
        setContent {
            BeancountTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChooseBasis(
                        initial = current.basis,
                        onChoose = { basis ->
                            WidgetOptions(this).setOptionFor(widgetId, current.copy(basis = basis))
                            WidgetRefresh.refreshAll(this)
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                            )
                            finish()
                        },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChooseBasis(
    initial: Valuation?,
    onChoose: (Valuation?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var basis by remember { mutableStateOf(initial) }
    Column(modifier = modifier) {
        Text(
            "How should net worth read?",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        Text(
            "Units shows each commodity as held. At cost values what was paid. Market value uses the " +
                "latest prices in your ledger.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        val choices: List<Pair<Valuation?, String>> = listOf(
            null to "Same as the app",
            Valuation.UNITS to "Units",
            Valuation.COST to "At cost",
            Valuation.VALUE to "Market value",
        )
        choices.forEach { (value, label) ->
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = { RadioButton(selected = basis == value, onClick = null) },
                modifier = Modifier.selectable(
                    selected = basis == value,
                    onClick = { basis = value },
                    role = Role.RadioButton,
                ),
            )
        }
        Button(
            onClick = { onChoose(basis) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Done") }
    }
}
