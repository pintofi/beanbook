package dev.beancount.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.beancount.core.domain.BeancountText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beancount.core.domain.LedgerError

/** One file the user can edit, as the picker lists it. */
data class EditableFile(
    val relativePath: String,
    val absolutePath: String,
    val sizeBytes: Long,
)

/** What the editor is currently doing, so the UI never guesses. */
sealed interface EditorState {
    data object Idle : EditorState
    data class Editing(
        val file: EditableFile,
        val text: String,
        val baseSha: String,
        val restoredDraft: Boolean,
        val draftIsStale: Boolean,
    ) : EditorState
}

/**
 * Raw editing of every file in the project — free forever, and the reason this app can
 * claim not to lock anyone in (docs/distribution.md §4.4).
 *
 * Three rules are visible here:
 *
 *  * **The user's text is never refused.** Saving uses `KEEP_AND_REPORT`: if what they
 *    typed has errors, it is still saved and the errors are shown. Refusing to save would
 *    lose their work, which is worse than an invalid ledger — Beancount tolerates errors,
 *    and so do we.
 *  * **A stale save is refused, not merged.** If the file changed underneath, the save is
 *    rejected with an explanation rather than clobbering the other change.
 *  * **Unsaved text is kept across process death**, and its restoration is announced.
 */
@Composable
fun RawEditorScreen(
    files: List<EditableFile>,
    state: EditorState,
    errors: List<LedgerError>,
    message: String?,
    onOpen: (EditableFile) -> Unit,
    onClose: () -> Unit,
    onTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDiscardDraft: () -> Unit,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
) {
    if (twoPane) {
        // List and editor side by side. On a wide window the one-at-a-time flow means
        // closing the file you are editing to see which other files exist, which is exactly
        // the comparison someone editing a ledger across several files wants to make.
        Row(modifier) {
            FileList(files, errors, message, onOpen, Modifier.weight(0.38f))
            androidx.compose.material3.VerticalDivider()
            Box(Modifier.weight(0.62f)) {
                when (state) {
                    EditorState.Idle -> Text(
                        "Choose a file to edit.",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is EditorState.Editing -> Editor(
                        state = state,
                        errors = errors,
                        message = message,
                        onClose = onClose,
                        onTextChanged = onTextChanged,
                        onSave = onSave,
                        onDiscardDraft = onDiscardDraft,
                        modifier = Modifier,
                    )
                }
            }
        }
        return
    }

    when (state) {
        EditorState.Idle -> FileList(files, errors, message, onOpen, modifier)
        is EditorState.Editing -> Editor(
            state = state,
            errors = errors,
            message = message,
            onClose = onClose,
            onTextChanged = onTextChanged,
            onSave = onSave,
            onDiscardDraft = onDiscardDraft,
            modifier = modifier,
        )
    }
}

@Composable
private fun FileList(
    files: List<EditableFile>,
    errors: List<LedgerError>,
    message: String?,
    onOpen: (EditableFile) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (errors.isNotEmpty()) item { ErrorBanner(errors) }
        message?.let { item { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) } }

        item {
            Text(
                "Files",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(files, key = { it.absolutePath }) { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(file) }
                    .padding(16.dp)
                    .semantics { contentDescription = "Edit ${file.relativePath}" },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(file.relativePath, style = MaterialTheme.typography.bodyLarge)
                Text("${file.sizeBytes} B", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Editor(
    state: EditorState.Editing,
    errors: List<LedgerError>,
    message: String?,
    onClose: () -> Unit,
    onTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDiscardDraft: () -> Unit,
    modifier: Modifier,
) {
    // The draft is written when the editor leaves the screen — including when the process
    // is about to be killed, which is the case that matters.
    val latest by rememberUpdatedState(state.text)
    DisposableEffect(state.file.absolutePath) {
        onDispose { onTextChanged(latest) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // The same arrow as the section top bar, for the same act. It carries its own
            // description because there is no longer a label to supply one, and it says
            // where it goes: "Back" alone does not, when a file is open inside a section
            // that is itself inside More.
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "Back to the file list" },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                state.file.relativePath,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 14.dp),
            )
            Button(onClick = onSave) { Text("Save") }
        }

        if (state.restoredDraft) {
            Text(
                text = if (state.draftIsStale) {
                    "Restored unsaved changes — but this file has changed since. Saving will " +
                        "be refused so nothing is overwritten; copy what you need first."
                } else {
                    "Restored unsaved changes from your last session."
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onDiscardDraft) { Text("Discard restored changes") }
        }

        message?.let {
            Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        }

        if (errors.isNotEmpty()) ErrorBanner(errors)

        BasicTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .semantics { contentDescription = "Source of ${state.file.relativePath}" },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = beancountHighlighting(),
        )
    }
}


/**
 * Colours Beancount source in the editor without touching a character of it (B12).
 *
 * A `VisualTransformation` rather than a styled value, because the field's text stays a plain
 * `String` the write protocol can hash and compare. `OffsetMapping.Identity` is correct here
 * for the same reason: no character is added, removed or reordered, so a cursor at offset *n*
 * is still at offset *n*. Any transformation that changed length would need a real mapping,
 * and getting that wrong puts the caret in the wrong place on every keystroke.
 *
 * Colours come from the theme rather than being fixed, so the editor stays readable in dark
 * mode and under a dynamic palette.
 */
@Composable
internal fun beancountHighlighting(): VisualTransformation {
    val scheme = MaterialTheme.colorScheme
    val palette = mapOf(
        BeancountText.TokenKind.COMMENT to scheme.onSurfaceVariant,
        BeancountText.TokenKind.STRING to scheme.tertiary,
        BeancountText.TokenKind.DATE to scheme.primary,
        BeancountText.TokenKind.KEYWORD to scheme.primary,
        BeancountText.TokenKind.ACCOUNT to scheme.secondary,
        BeancountText.TokenKind.NUMBER to scheme.onSurface,
        BeancountText.TokenKind.CURRENCY to scheme.onSurfaceVariant,
        BeancountText.TokenKind.TAG to scheme.tertiary,
    )

    return VisualTransformation { text ->
        val annotated = buildAnnotatedString {
            append(text.text)
            BeancountText.highlight(text.text).forEach { token ->
                // Defensive: a span past the end would throw inside the renderer, where the
                // stack trace says nothing about the highlighter.
                if (token.end <= text.text.length) {
                    palette[token.kind]?.let { colour ->
                        addStyle(SpanStyle(color = colour), token.start, token.end)
                    }
                }
            }
        }
        TransformedText(annotated, OffsetMapping.Identity)
    }
}
