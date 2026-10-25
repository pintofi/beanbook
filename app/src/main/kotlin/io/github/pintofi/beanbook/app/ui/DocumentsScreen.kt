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
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.collectAsState
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.pintofi.beanbook.core.data.DocumentStore
import io.github.pintofi.beanbook.core.domain.BeancountText
import io.github.pintofi.beanbook.core.domain.DocumentRow
import io.github.pintofi.beanbook.core.domain.DocumentStatus
import io.github.pintofi.beanbook.core.domain.DocumentRemoval
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import java.io.File
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import androidx.compose.ui.graphics.graphicsLayer

/** The two jobs the documents screen does, kept apart so neither is in the other's way. */
enum class DocumentsMode(val label: String) {
    ADD("Add"),
    ORGANIZE("Organize"),
}

/**
 * Attach a document: photograph the pages, put them in order, and file them against an
 * account.
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
    /** The document on disk, so it can be read here rather than handed to another app. */
    fileForDocument: ((String) -> java.io.File)? = null,
    modifier: Modifier = Modifier,
    message: String? = null,
    /**
     * Whether the camera may be used. Hoisted rather than read from the context inside, so
     * the screen can be rendered — and its accessibility checked — without a camera.
     */
    hasCamera: Boolean = rememberCameraPermission(),
    /** What this ledger calls its five trees, for the account this screen pre-selects. */
    names: io.github.pintofi.beanbook.core.domain.AccountNames =
        io.github.pintofi.beanbook.core.domain.AccountNames.DEFAULT,
    /**
     * The tab, when the caller keeps it. Home's card for a document that is not linked sends
     * people to Organize, where it is fixed, and a request that the screen consumes once is lost
     * whenever the screen is composed more than once on the way in. Held by the caller, the tab is
     * simply what it was set to. Null leaves the screen to remember it itself.
     */
    mode: DocumentsMode? = null,
    onModeChange: (DocumentsMode) -> Unit = {},
    /** Documents deleted in the last month, to put back or delete for good. */
    trash: DocumentTrash = DocumentTrash(),
) {
    var account by remember {
        mutableStateOf(
            vocabulary.accounts.firstOrNull { it.startsWith(names.expenses) }.orEmpty(),
        )
    }
    // What to call it. Optional: the name only changes how a folder of documents
    // reads, and a document with no name is filed exactly as it always was.
    var label by rememberSaveable { mutableStateOf("") }
    var granted by remember(hasCamera) { mutableStateOf(hasCamera) }
    var pendingRemoval by remember { mutableStateOf<Pair<DocumentRow, DocumentRemoval>?>(null) }
    var ownMode by rememberSaveable { mutableStateOf(DocumentsMode.ADD) }
    val shownMode = mode ?: ownMode
    fun showMode(next: DocumentsMode) {
        ownMode = next
        onModeChange(next)
    }
    var refiling by remember { mutableStateOf<DocumentRow?>(null) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var cropping by remember { mutableStateOf<Int?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var editingPages by remember { mutableStateOf<Int?>(null) }

    val askForCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    // A file the person already has -- an emailed invoice, a saved PDF -- filed as it is,
    // the way the entry form also offers. The picker needs no permission.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) capture.onAttachFile(uri)
    }
    val attachedFiles by capture.attachedFiles.collectAsState()
    val hasDocuments = !capture.pages.isEmpty || attachedFiles.isNotEmpty()
    // Back to how the page opened once what was being filed is gone -- filed, or discarded -- so
    // the next document does not start with the last one's account and name already in the boxes.
    androidx.compose.runtime.LaunchedEffect(hasDocuments) {
        if (!hasDocuments) {
            label = ""
            account = vocabulary.accounts.firstOrNull { it.startsWith(names.expenses) }.orEmpty()
        }
    }

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
        SecondaryTabRow(selectedTabIndex = shownMode.ordinal) {
            DocumentsMode.entries.forEach { entry ->
                Tab(
                    selected = shownMode == entry,
                    onClick = { showMode(entry) },
                    text = { Text(entry.label) },
                )
            }
        }

        if (shownMode == DocumentsMode.ADD) {
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
        OutlinedButton(
            onClick = { pickFile.launch(arrayOf("application/pdf", "image/*")) },
            modifier = Modifier.semantics { contentDescription = "Attach a file from this phone" },
        ) { Text("Attach a file") }

        attachedFiles.forEachIndexed { index, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { capture.onRemoveFile(index) },
                    modifier = Modifier.semantics { contentDescription = "Remove the attached file $name" },
                ) { Text("Remove") }
            }
        }

        if (!capture.pages.isEmpty) {
            LedgerHeading("Pages")
            // Thumbnails, and one way in: tapping a page opens the editor, where a
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
                        PageThumbnail(file = capture.fileFor(page.source), label = "Page ${index + 1}", page = page)
                    }
                }
            }
            Text(
                "${capture.pages.size} page${if (capture.pages.size == 1) "" else "s"}. " +
                    "Tap one to rotate, crop or change how it is rendered.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (hasDocuments) {
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
                        "Added to the file name so you can tell documents apart. " +
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
            ) { Text(if (capture.pages.isEmpty) "File under this account" else "Save as PDF and file") }

            OutlinedButton(onClick = onDiscard) {
                Text(if (attachedFiles.isEmpty()) "Discard these pages" else "Discard")
            }
        }
        } // end ADD

        message?.let { StatusLine(it) }

        if (shownMode == DocumentsMode.ORGANIZE && documents.isEmpty()) {
            EmptyState(
                title = "No documents yet",
                detail = "Scan a receipt or attach a file on the Add tab to file it under an account.",
                art = EmptyArt.RECEIPT,
            )
        }

        if (shownMode == DocumentsMode.ORGANIZE && documents.isNotEmpty()) {
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
                        // as a button.
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
                                when {
                                    row.status == DocumentStatus.MISSING -> LedgerMark.ATTENTION
                                    // Not linked is not filed, however the folder makes it
                                    // count: the mark has to agree with the line under it.
                                    !row.linked -> LedgerMark.PENDING
                                    else -> LedgerMark.CLEARED
                                },
                                modifier = Modifier.padding(end = Measure.gap),
                            )
                            Text(
                                path.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            val turn by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (open) 180f else 0f,
                                animationSpec = if (
                                    io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled.current
                                ) {
                                    io.github.pintofi.beanbook.app.ui.theme.MotionRole.local()
                                } else {
                                    androidx.compose.animation.core.snap()
                                },
                                label = "documentChevron",
                            )
                            Icon(
                                imageVector = Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.graphicsLayer { rotationZ = turn },
                            )
                        }
                        Text(
                            row.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The detail pushes the rows below it down rather than appearing in a
                        // gap that was already there.
                        LedgerReveal(visible = open, label = "documentDetail") {
                            Column {
                                Text(path, style = LedgerType.small)
                                row.date?.let {
                                    Text("Dated $it", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (fileForDocument != null && row.status != DocumentStatus.MISSING) {
                                OutlinedButton(
                                    onClick = { viewing = path },
                                    modifier = Modifier.semantics {
                                        contentDescription = "View $path"
                                    },
                                ) { Text("View") }
                            }
                            OutlinedButton(
                                onClick = { onShare(path) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Share $path"
                                },
                            ) { Text("Share") }
                            // Two separate actions, deliberately worded differently: one
                            // removes the reference, the other removes the file.
                            // "File" and "Refile" are different acts: one gives a document
                            // its first directive, the other moves the one it has.
                            val unfiled = !row.linked
                            OutlinedButton(
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
                            // just a `document` directive: an entry that attaches it as
                            // `document:` metadata is a link too, and hiding Unlink for those
                            // left a document listed as attached with no way to detach it.
                            if (row.linked) {
                                OutlinedButton(
                                    onClick = { pendingRemoval = row to DocumentRemoval.UNLINK },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Unlink $path from its entry"
                                    },
                                ) { Text("Unlink") }
                            }
                            if (row.status != DocumentStatus.MISSING) {
                                OutlinedButton(
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

        if (shownMode == DocumentsMode.ORGANIZE) DocumentTrashSection(trash)
    }

    editingPages?.let { index ->
        PageReviewScreen(
            pages = capture.pages,
            fileFor = capture.fileFor,
            startAt = index,
            review = capture.review,
            onDone = { editingPages = null },
            onAddPage = { editingPages = null; capture.onStartScanning() },
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
            // Over the whole screen, not in a card. This screen scrolls and is not
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
                        initialRotation = page.rotation,
                        onCropped = { quad, rotation ->
                            capture.onCrop(index, quad)
                            repeat(((rotation - page.rotation).mod(360)) / 90) { capture.onRotateBy(index, true) }
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
                    if (row.linked) "Refile document" else "File document",
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
                androidx.compose.material3.Button(
                    onClick = {
                        // A directive Beancount made from the folder is not one that can be
                        // rewritten, so this writes the first one instead of moving it.
                        onRefile(path, target.trim(), row.account.takeIf { row.linked })
                        refiling = null
                    },
                    // An account it is not would be written, fail the re-parse and roll back.
                    // Refusing it here says so before the user waits for that.
                    enabled = BeancountText.ACCOUNT.matches(target.trim()),
                ) { Text(if (row.linked) "Refile" else "File") }
            },
            dismissButton = { OutlinedButton(onClick = { refiling = null }) { Text("Cancel") } },
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
            text = {
                // Unlinking cannot undo the folder. A dated file in an account folder is read
                // back as filed there on the next load, so the dialog says so before the act
                // rather than leaving the row looking unchanged.
                val folderNote = if (
                    removal == DocumentRemoval.UNLINK && inAccountFolder(path)
                ) {
                    " This file is in an account folder, so Beancount goes on counting it " +
                        "under that account until you move it."
                } else {
                    ""
                }
                Text(removal.describe(path) + folderNote)
            },
            confirmButton = {
                androidx.compose.material3.Button(
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
                OutlinedButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
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

/**
 * Whether [path] sits in one of the account folders Beancount reads documents from.
 *
 * `documents/Expenses/Food/2026-01-02.pdf` is filed under `Expenses:Food` by where it is, with
 * nothing written in the ledger -- so an unlink there removes a link the file does not need.
 */
private fun inAccountFolder(path: String): Boolean {
    val parts = path.split('/')
    return parts.size > 2 &&
        parts[0] == io.github.pintofi.beanbook.core.domain.DocumentName.ROOT &&
        parts[1] in setOf("Assets", "Liabilities", "Equity", "Income", "Expenses")
}

/** The documents deleted from this project, and what can be done with them. */
class DocumentTrash(
    val items: List<DocumentStore.TrashedFile> = emptyList(),
    val onRestore: (DocumentStore.Trashed) -> Unit = {},
    val onDeleteForever: (DocumentStore.Trashed) -> Unit = {},
    val onEmpty: () -> Unit = {},
)

/**
 * Deleted documents are kept for 30 days. Tapping one puts it back where it was; the bin button
 * deletes it for good. Draws nothing when the trash is empty.
 */
@Composable
internal fun DocumentTrashSection(trash: DocumentTrash) {
    var pendingForever by remember {
        mutableStateOf<DocumentStore.TrashedFile?>(null)
    }
    var confirmEmpty by remember { mutableStateOf(false) }
    if (trash.items.isEmpty()) return
    FolioHeading("Trash")
    FolioGroup {
        trash.items.forEach { entry ->
            val name = entry.trashed.relativePath.substringAfterLast('/')
            val deleted = java.time.Instant.ofEpochMilli(entry.trashedAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            item {
                FolioRow(
                    title = name,
                    detail = "Deleted $deleted · ${entry.bytes / 1000}\u00a0KB",
                    icon = Icons.Outlined.Description,
                    onClick = { trash.onRestore(entry.trashed) },
                    describe = "Restore ${entry.trashed.relativePath}",
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { pendingForever = entry },
                        modifier = Modifier.semantics { contentDescription = "Delete $name for good" },
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item {
            FolioRow(
                title = "Empty the trash",
                detail = "${trash.items.size} document${if (trash.items.size == 1) "" else "s"}, deleted for good",
                icon = Icons.Outlined.Delete,
                onClick = { confirmEmpty = true },
            )
        }
    }
    FolioNote("Tap a deleted document to put it back where it was. Anything in here is deleted for good after 30 days.")

    pendingForever?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingForever = null },
            title = { Text("Delete this document for good?") },
            text = { Text("${item.trashed.relativePath} will be deleted and cannot be brought back.") },
            confirmButton = {
                Button(onClick = { trash.onDeleteForever(item.trashed); pendingForever = null }) {
                    Text("Delete for good")
                }
            },
            dismissButton = { OutlinedButton(onClick = { pendingForever = null }) { Text("Keep it") } },
        )
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the trash?") },
            text = { Text("This deletes ${trash.items.size} document(s) for good. It cannot be undone.") },
            confirmButton = {
                Button(onClick = { trash.onEmpty(); confirmEmpty = false }) { Text("Empty the trash") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmEmpty = false }) { Text("Keep them") } },
        )
    }
}
