package io.github.pintofi.beanbook.app.engine

import android.util.Log
import io.github.pintofi.beanbook.app.ui.EditableFile
import io.github.pintofi.beanbook.app.ui.EditorState
import io.github.pintofi.beanbook.core.data.DraftStore
import io.github.pintofi.beanbook.core.data.ErrorPolicy
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.ProjectWriter
import io.github.pintofi.beanbook.core.data.WriteJournal
import io.github.pintofi.beanbook.core.data.WriteResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the raw editor: opening a file, keeping drafts, and saving through the write protocol.
 *
 * Saving uses [ErrorPolicy.KEEP_AND_REPORT]. This is the one place in the app where a write
 * that introduces errors is *kept*: the user typed it deliberately, and refusing to save
 * would lose their work. Generated writes (quick-add, imports) still roll back, because
 * broken output there is our bug rather than their intent.
 */
class EditorController(
    private val workspace: ProjectWorkspace,
    private val writer: ProjectWriter,
    private val drafts: DraftStore,
    private val scope: CoroutineScope,
    /** Told when a save committed, so an Undo can be offered for it. */
    private val onCommitted: (projectId: String, result: WriteResult.Committed) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow<EditorState>(EditorState.Idle)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The project's files, from the engine's own list of what it loaded. */
    fun filesFor(projectId: String, includedFiles: List<String>): List<EditableFile> {
        val projectDir = workspace.projectDir(projectId)
        val main = workspace.mainFile(projectId)
        return (listOf(main.absolutePath) + includedFiles)
            .distinct()
            .map(::File)
            // With the separator: without it "projects/foo-other" passes the check for
            // project "foo", and Beancount permits `include "../..."`, so a crafted ledger
            // could list a neighbouring project's files. Writes are refused by ProjectWriter
            // regardless, so this was read exposure rather than a write path.
            .filter {
                it.isFile &&
                    it.canonicalPath.startsWith(projectDir.canonicalPath + File.separator)
            }
            .map {
                EditableFile(
                    relativePath = it.relativeTo(projectDir).path,
                    absolutePath = it.absolutePath,
                    sizeBytes = it.length(),
                )
            }
            .sortedBy { it.relativePath }
    }

    fun open(projectId: String, file: EditableFile) {
        scope.launch {
            val onDisk = withContext(Dispatchers.IO) { File(file.absolutePath) }
            val currentSha = withContext(Dispatchers.IO) { WriteJournal.sha256(onDisk) }
            val diskText = withContext(Dispatchers.IO) { onDisk.readText() }
            val draft = withContext(Dispatchers.IO) { drafts.load(projectId, file.relativePath) }

            _message.value = null
            _state.value = EditorState.Editing(
                file = file,
                text = draft?.text ?: diskText,
                baseSha = draft?.baseSha ?: currentSha,
                restoredDraft = draft != null,
                // A draft taken against an older version of the file: restoring it blindly
                // would revert whatever changed in between, so the UI says so.
                draftIsStale = draft?.isStaleAgainst(currentSha) == true,
            )
        }
    }

    fun updateText(projectId: String, text: String) {
        val editing = _state.value as? EditorState.Editing ?: return
        _state.value = editing.copy(text = text)
        scope.launch {
            withContext(Dispatchers.IO) {
                drafts.save(projectId, editing.file.relativePath, text, editing.baseSha)
            }
        }
    }

    fun discardDraft(projectId: String) {
        val editing = _state.value as? EditorState.Editing ?: return
        scope.launch {
            withContext(Dispatchers.IO) { drafts.clear(projectId, editing.file.relativePath) }
            open(projectId, editing.file)
        }
    }

    fun close(projectId: String) {
        (_state.value as? EditorState.Editing)?.let { editing ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    drafts.save(projectId, editing.file.relativePath, editing.text, editing.baseSha)
                }
            }
        }
        _state.value = EditorState.Idle
    }

    fun save(projectId: String, onSaved: () -> Unit) {
        val editing = _state.value as? EditorState.Editing ?: return
        // One save at a time: a second tap while the first runs would be refused as stale,
        // after the first had succeeded, and its message would replace "Saved.".
        if (_message.value == SAVING) return
        scope.launch {
            _message.value = SAVING
            val result = writer.replace(
                projectId = projectId,
                file = File(editing.file.absolutePath),
                expectedSha = editing.baseSha,
                newContent = editing.text,
                operation = "raw-edit",
                policy = ErrorPolicy.KEEP_AND_REPORT,
            )

            _message.value = when (result) {
                is WriteResult.Committed -> {
                    onCommitted(projectId, result)
                    withContext(Dispatchers.IO) { drafts.clear(projectId, editing.file.relativePath) }
                    val newSha = withContext(Dispatchers.IO) {
                        WriteJournal.sha256(File(editing.file.absolutePath))
                    }
                    // The text as it is now, not as it was when Save was tapped: a write and its
                    // re-parse take seconds on a large ledger, and what was typed meanwhile is
                    // not in the file. Keeping the older text here, and clearing the draft
                    // above, lost those keystrokes.
                    val now = (_state.value as? EditorState.Editing)
                        ?.takeIf { it.file == editing.file } ?: editing
                    _state.value = now.copy(
                        baseSha = newSha,
                        restoredDraft = false,
                        draftIsStale = false,
                    )
                    if (now.text != editing.text) {
                        withContext(Dispatchers.IO) {
                            drafts.save(projectId, editing.file.relativePath, now.text, newSha)
                        }
                    }
                    onSaved()
                    if (result.warnings.isEmpty()) "Saved."
                    else "Saved. Beancount reports ${result.warnings.size} error(s) — see above."
                }

                is WriteResult.Refused ->
                    "Not saved: ${result.reason}. Your text is still here; nothing was overwritten."

                is WriteResult.RolledBack ->
                    "Not saved: ${result.reason}. The file is unchanged."
            }
            Log.i(TAG, "raw edit ${editing.file.relativePath}: ${_message.value}")
        }
    }

    private companion object {
        const val TAG = "EditorController"
        const val SAVING = "Saving…"
    }
}
