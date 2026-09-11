package io.github.pintofi.beanbook.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.pintofi.beanbook.core.domain.BeancountText
import io.github.pintofi.beanbook.core.domain.DocumentRow
import io.github.pintofi.beanbook.core.domain.DocumentStatus
import io.github.pintofi.beanbook.core.domain.DocumentRemoval
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import java.io.File
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.LedgerType

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
    /**
     * The camera, the captured pages and the page controls, as one thing.
     *
     * These used to be six separate parameters here *and* a [DocumentCaptureHandle] beside
     * them, because the handle was added for transaction entry and this screen was not moved
     * over. Two ways to say the same thing, threaded side by side through three composables.
     */
    capture: DocumentCaptureHandle,
    documents: List<DocumentRow>,
    vocabulary: LedgerVocabulary,
    onSave: (account: String, label: String?) -> Unit,
    onDiscard: () -> Unit,
    onRefile: (String, String, String?) -> Unit,
    onUnlink: (String, String?) -> Unit,
    onDeleteFile: (String) -> Unit,
    onShare: (String) -> Unit,
    /** The document on disk, so it can be read here rather than handed to another app (B57). */
    fileForDocument: ((String) -> java.io.File)? = null,
    modifier: Modifier = Modifier,
    message: String? = null,
    /**
     * Whether the camera may be used. Hoisted rather than read from the context inside, so
     * the screen can be rendered — and its accessibility checked — without a camera.
     */
    hasCamera: Boolean = rememberCameraPermission(),
    /** What this ledger calls its five trees, for the account this screen pre-selects (B70). */
    names: io.github.pintofi.beanbook.core.domain.AccountNames =
        io.github.pintofi.beanbook.core.domain.AccountNames.DEFAULT,
) {
    var account by remember {
        mutableStateOf(
            vocabulary.accounts.firstOrNull { it.startsWith(names.expenses) }.orEmpty(),
        )
    }
    // What to call it (B57). Optional: the name only changes how a folder of documents
    // reads, and a document with no name is filed exactly as it always was.
    var label by rememberSaveable { mutableStateOf("") }
    var granted by remember(hasCamera) { mutableStateOf(hasCamera) }
    var pendingRemoval by remember { mutableStateOf<Pair<DocumentRow, DocumentRemoval>?>(null) }
    var mode by rememberSaveable { mutableStateOf(DocumentsMode.ADD) }
    var refiling by remember { mutableStateOf<DocumentRow?>(null) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var cropping by remember { mutableStateOf<Int?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var editingPages by remember { mutableStateOf<Int?>(null) }

    val askForCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.gap, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(Measure.gap),
    ) {
        // Two jobs, kept apart. Beyond being what the user asked for, it is what keeps the
        // camera unbound while somebody is only tidying up: CameraPane below is reached only
        // in ADD, so ORGANIZE never opens the device.
        // SecondaryTabRow, not PrimaryTabRow: plain `TabRow` is deprecated in favour of
        // both, and the secondary one keeps the indicator these tabs already had. The
        // primary variant draws a wider, rounded indicator, which would be a visual
        // change smuggled in under a deprecation fix.
        SecondaryTabRow(selectedTabIndex = mode.ordinal) {
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
            // Opens the fullscreen scanner rather than embedding a viewfinder in the form.
            // The camera is the whole job once you are in it, and a preview boxed between a
            // heading and a page list is neither a good viewfinder nor a good form.
            Button(
                onClick = capture.onStartScanning,
                modifier = Modifier.semantics {
                    contentDescription = "Open the scanner and photograph pages"
                },
            ) { Text(if (capture.pages.isEmpty) "Scan pages" else "Scan more pages") }
        }

        if (!capture.pages.isEmpty) {
            LedgerHeading("Pages")
            // Thumbnails, and one way in (B58): tapping a page opens the editor, where a
            // page is rotated, cropped and re-rendered on the page itself rather than
            // through four text buttons in a form row.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                capture.pages.pages.forEachIndexed { index, page ->
                    androidx.compose.material3.Surface(
                        onClick = { editingPages = index },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.semantics {
                            contentDescription = "Edit page ${index + 1} of ${capture.pages.size}"
                        },
                    ) {
                        PageThumbnail(file = capture.fileFor(page.source), label = "Page ${index + 1}")
                    }
                }
            }
            Text(
                "${capture.pages.size} page${if (capture.pages.size == 1) "" else "s"}. " +
                    "Tap one to rotate, crop or change how it is rendered.",
                style = MaterialTheme.typography.bodySmall,
            )

            SuggestingField(
                value = account,
                onValueChange = { account = it },
                label = "File under account",
                suggestions = vocabulary.accounts,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name (optional)") },
                placeholder = { Text("car-insurance") },
                singleLine = true,
                supportingText = {
                    Text(
                        "Goes in the filename, between the date and the checksum. " +
                            "Letters, numbers and dashes.",
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "What to call this document" },
            )

            Button(
                onClick = { onSave(account.trim(), label.trim().ifBlank { null }) },
                enabled = account.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save as PDF and link") }

            TextButton(onClick = onDiscard) { Text("Discard these pages") }
        }
        } // end ADD

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (mode == DocumentsMode.ORGANIZE && documents.isEmpty()) {
            EmptyState(
                title = "No documents yet",
                detail = "Photograph a receipt on the Add tab and it is filed against an entry.",
            )
        }

        if (mode == DocumentsMode.ORGANIZE && documents.isNotEmpty()) {
            LedgerHeading("Documents in this project")
            documents.forEachIndexed { index, row ->
                val path = row.path
                if (index > 0) LedgerHairline()
                run {
                    Column(
                        Modifier.padding(vertical = Measure.snug),
                        verticalArrangement = Arrangement.spacedBy(Measure.tight),
                    ) {
                        // Tapping opens the details rather than a separate screen: there is
                        // little enough to say that pushing a route for it would cost the
                        // user a navigation to read three lines.
                        val open = path in expanded
                        // A row that reads as a control rather than as a line of text: it
                        // toggles a disclosure, so it carries a chevron and announces itself
                        // as a button (B54).
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    expanded = if (open) expanded - path else expanded + path
                                }
                                .semantics {
                                    contentDescription = if (open) {
                                        "Hide details of $path"
                                    } else {
                                        "Show details of $path"
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Where a document stands, in the same mark the journal uses for
                            // an entry: filed and found, filed under nothing yet, or named by
                            // the ledger with no file behind it.
                            StampMark(
                                when (row.status) {
                                    DocumentStatus.MISSING -> LedgerMark.ATTENTION
                                    DocumentStatus.UNFILED -> LedgerMark.PENDING
                                    else -> LedgerMark.CLEARED
                                },
                                modifier = Modifier.padding(end = Measure.gap),
                            )
                            Text(
                                path.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (open) {
                                    Icons.Filled.ExpandLess
                                } else {
                                    Icons.Filled.ExpandMore
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            row.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (open) {
                            Text(path, style = LedgerType.small)
                            row.date?.let {
                                Text("Dated $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (fileForDocument != null && row.status != DocumentStatus.MISSING) {
                                TextButton(
                                    onClick = { viewing = path },
                                    modifier = Modifier.semantics {
                                        contentDescription = "View $path"
                                    },
                                ) { Text("View") }
                            }
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
                            // Anything the ledger says about this file counts as a link, not
                            // just a `document` directive (B69): an entry that attaches it as
                            // `document:` metadata is a link too, and hiding Unlink for those
                            // left a document listed as attached with no way to detach it.
                            if (row.status != DocumentStatus.UNFILED || row.attachedTo.isNotEmpty()) {
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

    editingPages?.let { index ->
        PageEditorScreen(
            pages = capture.pages,
            fileFor = capture.fileFor,
            startAt = index,
            onRotate = capture.onRotateBy,
            onCrop = capture.onCrop,
            onRestyle = capture.onRestyle,
            onRemove = capture.onRemovePage,
            onMove = capture.onMove,
            onDone = { editingPages = null },
        )
    }

    viewing?.let { path ->
        fileForDocument?.let { resolve ->
            DocumentViewer(
                file = resolve(path),
                onShare = {
                    onShare(path)
                    viewing = null
                },
                onDismiss = { viewing = null },
            )
        }
    }

    cropping?.let { index ->
        capture.pages.pages.getOrNull(index)?.let { page ->
            // Over the whole screen, not in a card (B73). This screen scrolls and is not
            // full-bleed itself, so the crop comes as a dialog with the platform's width
            // limit turned off -- the ordinary way to give a screen-sized task a screen.
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { cropping = null },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                ),
            ) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Black,
                ) {
                    CropPageScreen(
                        file = capture.fileFor(page.source),
                        initial = page.crop,
                        onCropped = { quad ->
                            capture.onCrop(index, quad)
                            cropping = null
                        },
                        onDismiss = { cropping = null },
                    )
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
                    SuggestingField(
                        value = target,
                        onValueChange = { target = it },
                        label = "File under account",
                        suggestions = vocabulary.accounts,
                        modifier = Modifier.fillMaxWidth(),
                    )
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

