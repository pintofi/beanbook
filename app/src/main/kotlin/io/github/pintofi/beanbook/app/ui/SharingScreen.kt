package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pintofi.beanbook.app.BeancountApplication
import io.github.pintofi.beanbook.app.sharing.SharingStatus
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.Measure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share on Wi-Fi: open this phone's Reports from a browser on another device (B93).
 *
 * The risk is said before the control, every time, as the content guide requires for a
 * cleartext connection: anyone on the network can reach the sign-in page, only someone with
 * the username and password gets past it, and the connection is not encrypted.
 *
 * Reads its state from the application rather than taking it as parameters, like the other
 * self-contained screens: threading more through `MainActivity` is what detekt's `LargeClass`
 * catches (CLAUDE.md).
 */
@Composable
fun SharingScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as BeancountApplication
    val sharing = app.sharingState
    val status by sharing.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var savedName by remember { mutableStateOf(sharing.settings.username) }
    var editing by remember { mutableStateOf(savedName == null) }
    var username by remember { mutableStateOf(savedName.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.gap, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Share on Wi-Fi",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Open your Reports in a browser on another device on the same Wi-Fi, such as a " +
                "laptop. It shows the same pages as the Reports tab, and changes made there " +
                "are saved to this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Notice(NoticeTone.ATTENTION) {
            Text(
                "Anyone on this network can reach the sign-in page. Only someone with the " +
                    "username and password gets past it. The connection is http://, which is " +
                    "not encrypted: someone watching the network could read the pages. Use it " +
                    "on a network you trust, such as your home Wi-Fi, and turn it off after.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (editing) {
            Text("Sign-in", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.trim() },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                supportingText = { Text("At least $MIN_PASSWORD characters. Kept on this phone only as a hash.") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    saving = true
                    val chars = password.toCharArray()
                    val name = username
                    password = ""
                    scope.launch {
                        // PBKDF2 is slow on purpose; off the main thread.
                        withContext(Dispatchers.Default) { sharing.settings.save(name, chars) }
                        savedName = name
                        saving = false
                        editing = false
                    }
                },
                enabled = !saving && username.isNotBlank() && password.length >= MIN_PASSWORD,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "Saving…" else "Save sign-in") }
            if (savedName != null) {
                TextButton(onClick = { editing = false; password = "" }) { Text("Cancel") }
            }
        } else {
            Text("Signs in as $savedName.", style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = {
                    sharing.stop()
                    editing = true
                },
                modifier = Modifier.semantics { contentDescription = "Change the username and password" },
            ) { Text("Change sign-in") }
        }

        when (val current = status) {
            is SharingStatus.On -> {
                RuledColumn {
                    LedgerHeading("Open this address on the other device")
                    SelectionContainer {
                        Text(current.address, style = LedgerType.source)
                    }
                }
                StatusLine("Sharing. Stops when you tap Stop sharing, or from the notification.")
                OutlinedButton(
                    onClick = { sharing.stop() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop sharing") }
            }
            SharingStatus.Starting -> StatusLine("Starting to share…")
            is SharingStatus.Failed -> {
                Notice(NoticeTone.PROBLEM) { Text(current.reason, style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = { sharing.start() },
                    enabled = !editing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Try again") }
            }
            SharingStatus.Off -> Button(
                onClick = { sharing.start() },
                enabled = !editing,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("Start sharing") }
        }
    }
}

private const val MIN_PASSWORD = 8
