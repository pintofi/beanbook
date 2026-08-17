package dev.beancount.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beancount.app.web.FavaWebView
import dev.beancount.app.web.clearSessionToken
import dev.beancount.app.web.installSessionToken
import dev.beancount.core.engine.EngineStatus
import dev.beancount.core.engine.OriginAllowlist
import dev.beancount.core.engine.ReportPath

/**
 * M2 shell: start the embedded engine for a project and render Fava in the hardened
 * WebView.
 *
 * The project picker, guided entry and native journal arrive in M6. Until then this
 * screen uses a single starter project and says exactly what it is doing — it does not
 * pretend to be the finished app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BeancountApplication

        val projectId = STARTER_PROJECT
        if (!app.workspace.exists(projectId)) {
            app.workspace.createProject(projectId, title = "My Ledger")
        }
        app.engineManager.start(app.workspace.mainFile(projectId).absolutePath)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val status by app.engineManager.status.collectAsStateWithLifecycle()
                    EngineScreen(
                        status = status,
                        editionName = app.pluginPolicy.name,
                        token = app.engine.sessionToken,
                        debuggingAllowed = BuildConfig.DEBUG,
                        onOpenExternally = ::openInBrowser,
                    )
                }
            }
        }
    }

    private fun openInBrowser(uri: Uri) {
        // External links leave the app rather than loading inside the Fava WebView.
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    private companion object {
        const val STARTER_PROJECT = "my-ledger"
    }
}

@Composable
internal fun EngineScreen(
    status: EngineStatus,
    editionName: String,
    token: String?,
    debuggingAllowed: Boolean,
    onOpenExternally: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (status) {
        is EngineStatus.Serving -> {
            if (token != null) {
                installSessionToken(status.origin, token)
            }
            FavaWebView(
                url = "${status.origin}/${status.ledgerSlug}/${ReportPath.JOURNAL.path}/",
                allowlist = OriginAllowlist(setOf(status.origin)),
                modifier = modifier.fillMaxSize(),
                debuggingAllowed = debuggingAllowed,
                onExternalLink = onOpenExternally,
            )
        }

        else -> EngineStatusMessage(status, editionName, modifier)
    }
}

@Composable
private fun EngineStatusMessage(
    status: EngineStatus,
    editionName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Beancount for Android", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Edition: $editionName",
            modifier = Modifier.semantics { contentDescription = "Edition $editionName" },
        )
        when (status) {
            EngineStatus.Starting -> {
                CircularProgressIndicator()
                Text("Starting the accounting engine…")
            }

            EngineStatus.Stopped -> Text("Engine stopped.")

            is EngineStatus.Failed -> Text(
                text = "The engine could not start: ${status.message}",
                style = MaterialTheme.typography.bodyMedium,
            )

            is EngineStatus.Serving -> Unit
        }
    }
}

/** Clears the session cookie when a serving origin goes away. */
internal fun forgetSession(origin: String) = clearSessionToken(origin)
