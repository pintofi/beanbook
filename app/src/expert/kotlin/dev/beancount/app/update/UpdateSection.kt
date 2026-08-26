package dev.beancount.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.beancount.core.entitlement.UpdateCheck
import dev.beancount.core.entitlement.UpdateManifest
import dev.beancount.core.entitlement.UpdateVerifier
import java.io.File
import kotlinx.coroutines.launch

/**
 * Sideloaded edition: check for a signed update, then decide twice.
 *
 * **Nothing here happens on its own.** There is no background check, no automatic download
 * and no automatic install: the user pastes or fetches a manifest, reads what it offers,
 * chooses to download it, and then chooses to install it — after which Android shows its own
 * confirmation, which this app cannot skip and does not pretend to.
 *
 * Two decisions rather than one, because they are different: agreeing to fetch a file is not
 * agreeing to run it, and between them the app can say whether the file it got is the file
 * the signature promised.
 */
@Composable
fun UpdateSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { ApkUpdate(context) }
    val installed = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
    }

    var input by remember { mutableStateOf("") }
    var offered by remember { mutableStateOf<UpdateManifest?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Text(
            "This edition is installed from a file rather than a store, so updates are " +
                "checked here. An update is only offered if it was signed by the same key " +
                "as the app you have — an unsigned or altered one is refused and says so.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Installed version code: $installed", style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = input,
            onValueChange = { input = it; offered = null; downloaded = null; message = null },
            label = { Text("Update file") },
            placeholder = { Text("BCU1.…") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Signed update file" },
        )

        Button(
            onClick = {
                when (val check = UpdateVerifier().check(input, installed)) {
                    is UpdateCheck.Offered -> {
                        offered = check.manifest
                        message = null
                    }
                    is UpdateCheck.Rejected -> {
                        offered = null
                        message = check.problem.describe()
                    }
                }
            },
            enabled = input.isNotBlank() && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Check this update" },
        ) { Text("Check it") }

        offered?.let { manifest ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Version ${manifest.versionName} (${manifest.versionCode})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (manifest.notes.isNotBlank()) {
                        Text(manifest.notes, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Signed by this app's developer. It will be downloaded from " +
                            "${manifest.url}, and checked against the signature before " +
                            "anything is installed.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (downloaded == null) {
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    when (val result = updater.download(manifest)) {
                                        is ApkUpdate.Result.ReadyToInstall -> {
                                            downloaded = result.apk
                                            message = "Downloaded and checked. Nothing has " +
                                                "been installed yet."
                                        }
                                        is ApkUpdate.Result.Failed ->
                                            message = result.reason
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.semantics {
                                contentDescription = "Download version ${manifest.versionName}"
                            },
                        ) { Text(if (busy) "Downloading…" else "Download it") }
                    } else {
                        Button(
                            onClick = {
                                val result = updater.requestInstall(downloaded!!)
                                if (result is ApkUpdate.Result.Failed) message = result.reason
                            },
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "Install version ${manifest.versionName}. Android will ask you to confirm"
                            },
                        ) { Text("Install it") }
                        Text(
                            "Android will ask you to confirm. This app cannot install " +
                                "anything on its own.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        message?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (offered == null) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
