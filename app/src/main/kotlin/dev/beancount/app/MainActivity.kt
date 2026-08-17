package dev.beancount.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beancount.core.engine.EngineStatus
import dev.beancount.core.engine.PluginPolicy

/**
 * M1 shell: the app builds, runs, and reports which edition it is and what the engine is
 * doing. The project picker, engine startup and WebView host arrive in M2/M6 — this
 * screen deliberately claims nothing it cannot show.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val policy = (application as BeancountApplication).pluginPolicy

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EditionStatus(policy = policy, status = EngineStatus.Stopped)
                }
            }
        }
    }
}

@Composable
internal fun EditionStatus(
    policy: PluginPolicy,
    status: EngineStatus,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Beancount for Android",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Edition: ${policy.name}",
            modifier = Modifier.semantics { contentDescription = "Edition ${policy.name}" },
        )
        Text(text = "Engine: ${status.describe()}")
        Text(
            text = buildString {
                append("User Python: ")
                append(if (policy.allowsUserCode) "allowed" else "not available")
                if (policy.allowsUserCode && !policy.allowsNativeCode) {
                    append(" (pure Python only)")
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun EngineStatus.describe(): String = when (this) {
    EngineStatus.Stopped -> "stopped"
    EngineStatus.Starting -> "starting"
    is EngineStatus.Serving -> "serving $ledgerSlug (${startupMillis} ms)"
    is EngineStatus.Failed -> "failed: $message"
}
