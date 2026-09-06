package io.github.pintofi.beanbook.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.core.data.WidgetCache

/**
 * Asks which account a balance widget should show, while it is being placed (B1).
 *
 * Before this, `WidgetSnapshot.primaryBalance` chose for everyone — assets first, then
 * alphabetically — so somebody with two current accounts could not put the *other* one on
 * their home screen, and two balance widgets could only ever show the same figure.
 *
 * **Reads the widget cache, and nothing else.** No engine, no ledger load: placing a widget
 * must not start a Python process, which is what `WidgetEngineIsolationTest` asserts and
 * what makes widgets free of background work. The accounts offered are the ones the last
 * refresh recorded, which is also exactly the set the widget can actually display.
 */
class BalanceWidgetConfigureActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Cancelled unless the user actually chooses. The launcher contract is that a
        // configuration activity finishing any other way means "do not place it", so
        // pressing Back leaves the home screen as it was rather than adding a widget the
        // user never confirmed.
        setResult(Activity.RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // This activity is exported, because a launcher in another process has to start it.
        // So the id it is handed is not trusted: it is only accepted if it really is one of
        // *our* balance widgets. Without this, any app could invoke this screen and have a
        // person set an account against an arbitrary widget id (B34's rule, applied to the
        // second exported component this app has ever had).
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            info?.provider?.className != BalanceWidget::class.java.name ||
            info.provider?.packageName != packageName
        ) {
            finish()
            return
        }

        val accounts = WidgetCache(filesDir.resolve("state")).read()
            ?.balances?.keys?.sorted()
            .orEmpty()

        setContent {
            BeancountTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChooseAccounts(
                        accounts = accounts,
                        onChoose = ::choose,
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }

    private fun choose(chosen: List<String>) {
        WidgetAccounts(this).setAccountsFor(widgetId, chosen)
        // Drawn before returning, so the widget shows the chosen account immediately rather
        // than an em dash until something else happens to refresh it.
        WidgetRefresh.refreshAll(this)
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        )
        finish()
    }
}

@Composable
internal fun ChooseAccounts(
    accounts: List<String>,
    onChoose: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picked = remember { mutableStateListOf<String>() }
    var filter by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        Text(
            "Which balances?",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        if (accounts.isEmpty()) {
            // The cache is written when the app loads a ledger, so this is what a widget
            // placed before the app has ever been opened sees. Says what to do rather than
            // presenting an empty list as though the ledger had no accounts.
            Text(
                "Open the app once so it can read your ledger, then add this widget again.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }
        Text(
            "Pick one account, or several — the widget shows a row for each.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        // Every account in the ledger reaches this list now, which is long enough to need
        // finding rather than scrolling (B48).
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter accounts") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = "Filter accounts by name" },
        )
        val needle = filter.trim()
        val offered = accounts.filter { needle.isEmpty() || it.contains(needle, ignoreCase = true) }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(offered) { account ->
                val isPicked = account in picked
                ListItem(
                    headlineContent = { Text(account) },
                    trailingContent = {
                        Checkbox(
                            checked = isPicked,
                            // Null, so the row owns the click: a checkbox with its own
                            // handler beside a clickable row gives a screen reader two
                            // targets that do the same thing.
                            onCheckedChange = null,
                        )
                    },
                    modifier = Modifier
                        .toggleable(
                            value = isPicked,
                            onValueChange = { on ->
                                if (on) picked.add(account) else picked.remove(account)
                            },
                        )
                        .semantics { contentDescription = "Show the balance of $account" },
                )
                HorizontalDivider()
            }
        }
        Button(
            onClick = { onChoose(picked.toList()) },
            // Nothing picked is not a widget: it would be placed showing the default account
            // rather than what the person came here to choose.
            enabled = picked.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                if (picked.size <= 1) "Add this widget"
                else "Add this widget with ${picked.size} balances",
            )
        }
    }
}
