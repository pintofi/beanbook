package io.github.pintofi.beanbook.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.pintofi.beanbook.app.ui.FolioBody
import io.github.pintofi.beanbook.app.ui.FolioGroup
import io.github.pintofi.beanbook.app.ui.FolioHeading
import io.github.pintofi.beanbook.app.ui.FolioNote
import io.github.pintofi.beanbook.app.ui.StatusLine
import io.github.pintofi.beanbook.app.ui.theme.Measure

/**
 * "Connect to my Fava server" on the ways-in page, for somebody who already hosts one.
 *
 * Remote mode works beside a local ledger, which keeps the documents, the backups and the mirror,
 * so connecting here saves the server and the person then sets up (or imports) a ledger above.
 * Entries go to the server while it is connected.
 */
@Composable
internal fun RemoteCard(
    onConnect: (String, String?, CharArray?) -> Unit,
    note: String?,
    modifier: Modifier = Modifier,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    Column(modifier = modifier) {
        FolioHeading("Connect to my Fava server")
        FolioGroup {
            item {
                FolioBody {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Server address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Use https. An http address sends your password without encryption.")
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("User name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            val chars = secret.toCharArray()
                            onConnect(address.trim(), user.trim().ifEmpty { null }, chars.takeIf { it.isNotEmpty() })
                            secret = ""
                        },
                        enabled = address.isNotBlank(),
                    ) { Text("Connect") }
                }
            }
        }
        FolioNote(
            "For a server you host yourself. It sits beside a ledger on this phone, which keeps your " +
                "documents and backups: connect here, then set one up or import one above.",
        )
        note?.let { StatusLine(it, modifier = Modifier.padding(horizontal = Measure.indent)) }
    }
}
