package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import kotlinx.coroutines.launch

/** Up to this many templates are shown as buttons on the form; more are chosen from a list. */
internal const val TEMPLATES_SHOWN_DIRECTLY = 3

/** The list gets a search box once it is longer than this. */
internal const val TEMPLATES_SEARCHABLE_FROM = 8

/**
 * The templates saved with Save as template, as the entry form offers them.
 *
 * A few are buttons in a wrapping row, one tap each, and every one stays visible at large text. Past
 * that, a row of buttons pushes the form down the screen (or off the side of it) and a screen reader
 * has to pass every one before the first field, so a single button opens an alphabetical list instead,
 * with a search box once it is long. Choosing a template fills the form and stops there; the entry is
 * still previewed and checked like any other.
 */
@Composable
internal fun TemplatePicker(
    templates: List<EntryTemplate>,
    onApply: (EntryTemplate) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (templates.isEmpty()) return
    val ordered = remember(templates) { templates.sortedBy { it.name.lowercase() } }
    if (ordered.size <= TEMPLATES_SHOWN_DIRECTLY) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
            verticalArrangement = Arrangement.spacedBy(Measure.snug),
        ) {
            ordered.forEach { template ->
                AssistChip(
                    onClick = { onApply(template) },
                    // The visible name is what is read and what voice control says; nothing replaces it.
                    label = { Text(template.name) },
                )
            }
        }
    } else {
        var open by rememberSaveable { mutableStateOf(false) }
        OutlinedButton(
            onClick = { open = true },
            modifier = modifier.semantics { contentDescription = "Templates, ${ordered.size} saved. Choose one" },
        ) { Text("Templates (${ordered.size})") }
        if (open) {
            TemplateSheet(
                templates = ordered,
                onChoose = {
                    open = false
                    onApply(it)
                },
                onDelete = onDelete,
                onClose = { open = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateSheet(
    templates: List<EntryTemplate>,
    onChoose: (EntryTemplate) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<EntryTemplate?>(null) }
    val shown = remember(templates, query) {
        val words = query.trim().split(' ').filter { it.isNotBlank() }
        templates.filter { template -> words.all { template.searchText().contains(it, ignoreCase = true) } }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(modifier = Modifier.padding(horizontal = Measure.gutter)) {
            Text(
                "Templates",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (templates.size > TEMPLATES_SEARCHABLE_FROM) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search templates") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Measure.snug)
                        .semantics { contentDescription = "Search the saved templates" },
                )
            }
            if (shown.isEmpty()) {
                Text("No template matches \"${query.trim()}\".", modifier = Modifier.padding(vertical = Measure.indent))
            }
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                items(shown, key = { it.name }) { template ->
                    ListItem(
                        headlineContent = { Text(template.name) },
                        supportingContent = template.hint()?.let { hint -> { Text(hint) } },
                        trailingContent = {
                            val describe = "Delete the template ${template.name}"
                            IconButton(
                                onClick = { deleting = template },
                                modifier = Modifier.semantics { contentDescription = describe },
                            ) { Icon(Icons.Filled.Delete, contentDescription = null) }
                        },
                        modifier = Modifier
                            .clickable(role = Role.Button, onClickLabel = "Start the entry from this template") {
                                scope.launch { sheet.hide() }.invokeOnCompletion { onChoose(template) }
                            },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    deleting?.let { template ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete the template ${template.name}?") },
            text = { Text("Entries you have already saved are not changed.") },
            confirmButton = {
                Button(onClick = { onDelete(template.name); deleting = null }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { deleting = null }) { Text("Keep it") } },
        )
    }
}

/** What tells two templates apart besides their name: who is paid and the first account. */
private fun EntryTemplate.hint(): String? =
    listOfNotNull(payee?.takeIf { it.isNotBlank() }, postings.firstOrNull()?.account)
        .joinToString(" · ").ifBlank { null }

private fun EntryTemplate.searchText(): String = listOfNotNull(name, payee, narration, hint()).joinToString(" ")
