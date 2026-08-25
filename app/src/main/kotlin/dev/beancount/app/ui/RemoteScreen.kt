package dev.beancount.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.beancount.core.engine.EngineStatus
import dev.beancount.engine.remote.ServerCapabilities

/**
 * Remote mode: point the app at your own Fava (SPEC.md J4, Tier 3).
 *
 * Two things this screen must never do, and does not: offer a way past a certificate error,
 * and imply the app keeps a copy of a remote ledger. Remote is a viewer plus quick-add — the
 * ledger stays on the server, which is the point of self-hosting it.
 */
@Composable
fun RemoteScreen(
    status: EngineStatus,
    capabilities: ServerCapabilities?,
    savedAddress: String?,
    savedUsername: String?,
    pendingRetry: Boolean,
    onConnect: (String, String?, CharArray?) -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onRetryPending: () -> Unit,
    onDiscardPending: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    var address by remember(savedAddress) { mutableStateOf(savedAddress.orEmpty()) }
    var username by remember(savedUsername) { mutableStateOf(savedUsername.orEmpty()) }
    var secret by remember { mutableStateOf("") }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your own Fava server", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connect to a Fava you host yourself. The ledger stays on the server — this app " +
                "shows it and can add entries to it, and keeps no copy here.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Address") },
            placeholder = { Text("https://fava.example.com") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Server address" },
        )
        Text(
            "https only. There is no option to accept an untrusted certificate — if the " +
                "connection cannot be verified, it stops.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            "If your server is behind a login (Fava has none of its own), enter it here.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username (optional)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Username" },
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("Password (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Password" },
        )
        Text(
            "The password is kept in this device's keystore, where the app can use it but " +
                "cannot read it back, and it is never included in a backup.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = {
                val typed = secret.toCharArray()
                onConnect(address.trim(), username.trim().ifBlank { null }, typed.takeIf { it.isNotEmpty() })
                secret = ""
            },
            enabled = address.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Connect to the server" },
        ) { Text(if (status is EngineStatus.Serving) "Reconnect" else "Connect") }

        when (status) {
            is EngineStatus.Starting ->
                Text("Connecting…", style = MaterialTheme.typography.bodySmall)

            is EngineStatus.Serving ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            capabilities?.describe() ?: "Connected.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Reports come from this server; the Reports tab shows it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

            is EngineStatus.Failed ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        status.message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

            EngineStatus.Stopped -> Unit
        }

        message?.takeIf { status !is EngineStatus.Failed }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        if (pendingRetry) {
            HorizontalDivider()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("An entry did not get through", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "It is kept here. Retrying asks the server whether it arrived before " +
                            "sending anything, so trying again cannot enter it twice.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRetryPending,
                            modifier = Modifier.semantics {
                                contentDescription = "Retry the entry that did not get through"
                            },
                        ) { Text("Try again") }
                        TextButton(onClick = onDiscardPending) { Text("Discard it") }
                    }
                }
            }
        }

        if (savedAddress != null) {
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
                TextButton(
                    onClick = onForget,
                    modifier = Modifier.semantics {
                        contentDescription = "Forget this server and its password"
                    },
                ) { Text("Forget this server") }
            }
        }
    }
}
