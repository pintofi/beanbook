package dev.beancount.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.beancount.core.domain.BeancountText
import dev.beancount.core.domain.CapturedPages
import dev.beancount.core.domain.DocumentRow
import dev.beancount.core.domain.DocumentStatus
import dev.beancount.core.domain.DocumentRemoval
import dev.beancount.core.domain.LedgerVocabulary
import java.io.File

/** The two jobs the documents screen does, kept apart so neither is in the other's way. */
enum class DocumentsMode(val label: String) {
    ADD("Add"),
    ORGANIZE("Organize"),
}

/**
 * Attach a document: photograph the pages, put them in order, and file them against an
 * account (SPEC.md, J3).
 *
 * The camera is only bound while this screen is on top, and it is released the moment it
 * goes away — a finance app holding the camera open in the background would be indefensible,
 * whatever the reason.
 */
@Composable
fun DocumentsScreen(
    pages: CapturedPages,
    documents: List<DocumentRow>,
    vocabulary: LedgerVocabulary,
    onCapture: (File, (Boolean) -> Unit) -> Unit,
    captureFile: () -> File,
    onRotate: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onSave: (String) -> Unit,
    onDiscard: () -> Unit,
    onRefile: (String, String, String?) -> Unit,
    onUnlink: (String, String?) -> Unit,
    onDeleteFile: (String) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    /**
     * Whether the camera may be used. Hoisted rather than read from the context inside, so
     * the screen can be rendered — and its accessibility checked — without a camera.
     */
    hasCamera: Boolean = rememberCameraPermission(),
) {
    var account by remember {
        mutableStateOf(vocabulary.accounts.firstOrNull { it.startsWith("Expenses") }.orEmpty())
    }
    var granted by remember(hasCamera) { mutableStateOf(hasCamera) }
    var pendingRemoval by remember { mutableStateOf<Pair<DocumentRow, DocumentRemoval>?>(null) }
    var mode by rememberSaveable { mutableStateOf(DocumentsMode.ADD) }
    var refiling by remember { mutableStateOf<DocumentRow?>(null) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }

    val askForCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Two jobs, kept apart. Beyond being what the user asked for, it is what keeps the
        // camera unbound while somebody is only tidying up: CameraPane below is reached only
        // in ADD, so ORGANIZE never opens the device.
        TabRow(selectedTabIndex = mode.ordinal) {
            DocumentsMode.entries.forEach { entry ->
                Tab(
                    selected = mode == entry,
                    onClick = { mode = entry },
                    text = { Text(entry.label) },
                )
            }
        }

        if (mode == DocumentsMode.ADD) {
        Text("Attach a document", style = MaterialTheme.typography.titleMedium)

        if (!granted) {
            // Asked for at the moment it is needed, with the reason, rather than at first
            // launch: nothing else in this app needs the camera.
            Text(
                "Photographing a receipt needs the camera. Nothing else in the app uses it, " +
                    "and photos stay on this device.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { askForCamera.launch(Manifest.permission.CAMERA) }) {
                Text("Allow the camera")
            }
        } else {
            CameraPane(onCapture = onCapture, captureFile = captureFile)
        }

        if (!pages.isEmpty) {
            Text("Pages", style = MaterialTheme.typography.titleSmall)
            pages.pages.forEachIndexed { index, page ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Page ${index + 1}${if (page.rotation != 0) " · ${page.rotation}°" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onRotate(index) },
                        modifier = Modifier.semantics {
                            contentDescription = "Rotate page ${index + 1}"
                        },
                    ) { Text("Rotate") }
                    TextButton(
                        onClick = { onMove(index, index - 1) },
                        enabled = index > 0,
                        modifier = Modifier.semantics {
                            contentDescription = "Move page ${index + 1} up"
                        },
                    ) { Text("Up") }
                    TextButton(
                        onClick = { onMove(index, index + 1) },
                        enabled = index < pages.size - 1,
                        modifier = Modifier.semantics {
                            contentDescription = "Move page ${index + 1} down"
                        },
                    ) { Text("Down") }
                    TextButton(
                        onClick = { onRemovePage(index) },
                        modifier = Modifier.semantics {
                            contentDescription = "Remove page ${index + 1}"
                        },
                    ) { Text("Remove") }
                }
            }

            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("File under account") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Suggestions(vocabulary.accountsMatching(account), "account") { account = it }

            Button(
                onClick = { onSave(account.trim()) },
                enabled = account.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save as PDF and link") }

            TextButton(onClick = onDiscard) { Text("Discard these pages") }
        }
        } // end ADD

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (mode == DocumentsMode.ORGANIZE && documents.isEmpty()) {
            Text(
                "No documents yet. Add one from the other tab and it will be listed here.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (mode == DocumentsMode.ORGANIZE && documents.isNotEmpty()) {
            Text("Documents in this project", style = MaterialTheme.typography.titleSmall)
            documents.forEach { row ->
                val path = row.path
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Tapping opens the details rather than a separate screen: there is
                        // little enough to say that pushing a route for it would cost the
                        // user a navigation to read three lines.
                        val open = path in expanded
                        Text(
                            path.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expanded = if (open) expanded - path else expanded + path
                                }
                                .semantics {
                                    contentDescription = if (open) {
                                        "Hide details of $path"
                                    } else {
                                        "Show details of $path"
                                    }
                                },
                        )
                        Text(row.summary, style = MaterialTheme.typography.bodySmall)
                        if (open) {
                            Text(path, style = MaterialTheme.typography.bodySmall)
                            row.date?.let {
                                Text("Dated $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { onShare(path) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Share $path"
                                },
                            ) { Text("Share") }
                            // Two separate actions, deliberately worded differently: one
                            // removes the reference, the other removes the file.
                            // "File" and "Refile" are different acts: one gives a document
                            // its first directive, the other moves the one it has.
                            val unfiled = row.status == DocumentStatus.UNFILED
                            TextButton(
                                onClick = { refiling = row },
                                modifier = Modifier.semantics {
                                    contentDescription = if (unfiled) {
                                        "File $path under an account"
                                    } else {
                                        "File $path under a different account"
                                    }
                                },
                            ) { Text(if (unfiled) "File" else "Refile") }
                            // Each removal is offered only where there is something to
                            // remove: nothing refers to an unfiled document, and a missing
                            // one has no file left. Showing them anyway would offer an
                            // action whose only possible outcome is an error.
                            if (row.status != DocumentStatus.UNFILED) {
                                TextButton(
                                    onClick = { pendingRemoval = row to DocumentRemoval.UNLINK },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Unlink $path from its entry"
                                    },
                                ) { Text("Unlink") }
                            }
                            if (row.status != DocumentStatus.MISSING) {
                                TextButton(
                                    onClick = { pendingRemoval = row to DocumentRemoval.DELETE_FILE },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Delete the file $path"
                                    },
                                ) { Text("Delete file") }
                            }
                        }
                    }
                }
            }
        }
    }

    refiling?.let { row ->
        val path = row.path
        // The account is typed into the dialog rather than chosen from a list, because a
        // ledger's accounts are not a short list and the same field elsewhere in the app
        // works this way. Suggestions narrow it as the user types.
        // Pre-filled with where it is now, so the field says what it is changing from and a
        // slip of the finger cannot file it under an empty account.
        var target by remember(row) { mutableStateOf(row.account.orEmpty()) }
        AlertDialog(
            onDismissRequest = { refiling = null },
            title = {
                Text(
                    if (row.status == DocumentStatus.UNFILED) {
                        "File document"
                    } else {
                        "Refile document"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(path, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("File under account") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Suggestions(vocabulary.accountsMatching(target), "account") { target = it }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRefile(path, target.trim(), row.account)
                        refiling = null
                    },
                    // An account it is not would be written, fail the re-parse and roll back.
                    // Refusing it here says so before the user waits for that.
                    enabled = BeancountText.ACCOUNT.matches(target.trim()),
                ) { Text(if (row.status == DocumentStatus.UNFILED) "File" else "Refile") }
            },
            dismissButton = { TextButton(onClick = { refiling = null }) { Text("Cancel") } },
        )
    }

    pendingRemoval?.let { (row, removal) ->
        val path = row.path
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = {
                Text(
                    when (removal) {
                        DocumentRemoval.UNLINK -> "Unlink this document?"
                        DocumentRemoval.DELETE_FILE -> "Delete this file?"
                    },
                )
            },
            text = { Text(removal.describe(path)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (removal) {
                            DocumentRemoval.UNLINK -> onUnlink(path, row.account)
                            DocumentRemoval.DELETE_FILE -> onDeleteFile(path)
                        }
                        pendingRemoval = null
                    },
                ) {
                    Text(
                        when (removal) {
                            DocumentRemoval.UNLINK -> "Unlink"
                            DocumentRemoval.DELETE_FILE -> "Delete the file"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
        )
    }
}

/** Whether the camera permission is already held. */
@Composable
fun rememberCameraPermission(): Boolean {
    val context = LocalContext.current
    return remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
}

/** The viewfinder, bound to this composable's lifecycle and unbound when it leaves. */
@Composable
private fun CameraPane(
    onCapture: (File, (Boolean) -> Unit) -> Unit,
    captureFile: () -> File,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val capture = remember { ImageCapture.Builder().build() }
    var busy by remember { mutableStateOf(false) }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).also { preview ->
                // A PreviewView defaults to a SurfaceView, which is composited by the system
                // outside the view hierarchy — in Compose it draws straight over whatever is
                // below it, as it did over the capture button here. TextureView draws in the
                // hierarchy like everything else, at the cost of a little extra copying.
                preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        androidx.camera.core.Preview.Builder().build().also {
                            it.surfaceProvider = preview.surfaceProvider
                        },
                        capture,
                    )
                }, ContextCompat.getMainExecutor(viewContext))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            // Clipped explicitly: a camera preview will happily paint outside the bounds
            // Compose gave it, and it did — over the capture button and the page list.
            .clipToBounds()
            .semantics { contentDescription = "Camera viewfinder" },
    )

    DisposableEffect(Unit) {
        onDispose {
            val provider = runCatching { ProcessCameraProvider.getInstance(context).get() }
            provider.getOrNull()?.unbindAll()
        }
    }

    Button(
        onClick = {
            if (busy) return@Button
            busy = true
            val target = captureFile()
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(target).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        busy = false
                        onCapture(target) { }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        busy = false
                        onCapture(target) { false }
                    }
                },
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Take a photo of this page" },
    ) { Text("Capture page") }
}
