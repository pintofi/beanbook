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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import dev.beancount.core.domain.CapturedPages
import dev.beancount.core.domain.DocumentRemoval
import dev.beancount.core.domain.LedgerVocabulary
import java.io.File

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
    documents: List<String>,
    vocabulary: LedgerVocabulary,
    onCapture: (File, (Boolean) -> Unit) -> Unit,
    captureFile: () -> File,
    onRotate: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onSave: (String) -> Unit,
    onDiscard: () -> Unit,
    onUnlink: (String) -> Unit,
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
    var pendingRemoval by remember { mutableStateOf<Pair<String, DocumentRemoval>?>(null) }

    val askForCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (documents.isNotEmpty()) {
            HorizontalDivider()
            Text("Documents in this project", style = MaterialTheme.typography.titleSmall)
            documents.forEach { path ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(path, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { onShare(path) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Share $path"
                                },
                            ) { Text("Share") }
                            // Two separate actions, deliberately worded differently: one
                            // removes the reference, the other removes the file.
                            TextButton(
                                onClick = { pendingRemoval = path to DocumentRemoval.UNLINK },
                                modifier = Modifier.semantics {
                                    contentDescription = "Unlink $path from its entry"
                                },
                            ) { Text("Unlink") }
                            TextButton(
                                onClick = { pendingRemoval = path to DocumentRemoval.DELETE_FILE },
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

    pendingRemoval?.let { (path, removal) ->
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
                            DocumentRemoval.UNLINK -> onUnlink(path)
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
