package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.engine.remote.RemoteAddress
import io.github.pintofi.beanbook.engine.remote.ServerCapabilities
import io.github.pintofi.beanbook.app.ui.theme.Measure

/**
 * What an `http://` address actually means, said plainly and before the fact.
 *
 * Cleartext is allowed because LAN and VPN hosting are real and a certificate for
 * `192.168.1.5` is not a realistic thing to ask for. Allowed is not the same as unremarkable,
 * and a warning that appears only after the ledger has been sent is not a warning. It is
 * stated in terms of what happens to the user's data, not as a security abstraction.
 */
@Composable
private fun CleartextWarning(hasPassword: Boolean) {
    Notice(
        NoticeTone.PROBLEM,
        modifier = Modifier.semantics {
            contentDescription = "Warning about an unencrypted address"
        },
    ) {
        run {
            Text("This address is not encrypted", style = MaterialTheme.typography.titleSmall)
            Text(
                "With http://, everything between this device and your server can be read " +
                    "and changed by anything on the way: your whole ledger, and any entry " +
                    "you add. On your own home network or a VPN that may be a fair trade. " +
                    "On shared or public Wi-Fi it is not.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (hasPassword) {
                Text(
                    "The password you have typed would also be sent in the clear, on every " +
                        "single request.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Remote mode: point the app at your own Fava (SPEC.md J4, Tier 3).
 *
 * Two things this screen must never do, and does not: offer a way past a certificate error,
 * and imply the app keeps a copy of a remote ledger. Remote is a viewer plus quick-add — the
 * ledger stays on the server, which is the point of self-hosting it.
 *
 * `http://` is accepted, and saying so is this screen's job: see [CleartextWarning].
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

    // Derived from what is in the field right now, so the warning is there while the user is
    // still deciding rather than after they have already sent a ledger over the wire.
    val cleartext = remember(address) {
        RemoteAddress.parse(address).getOrNull()?.isCleartext == true
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.gap, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your own Fava server", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connect to a Fava server you host. The ledger stays there: this app shows it " +
                "and can add entries, but keeps no copy on the device.",
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
            "https:// is checked strictly: if the certificate cannot be verified, the " +
                "connection stops and there is no way past it. http:// also works, for a " +
                "server on your own network.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (cleartext) {
            CleartextWarning(hasPassword = secret.isNotEmpty())
        }

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
                Notice(NoticeTone.QUIET) {
                    Text(
                        capabilities?.describe() ?: "Connected.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Reports come from this server; the Reports tab shows it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            is EngineStatus.Failed ->
                Notice(NoticeTone.PROBLEM) {
                    Text(status.message, style = MaterialTheme.typography.bodyMedium)
                }

            EngineStatus.Stopped -> Unit
        }

        message?.takeIf { status !is EngineStatus.Failed }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        if (pendingRetry) {
            LedgerHairline()
            Notice(NoticeTone.PROBLEM) {
                run {
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
