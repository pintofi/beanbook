package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.PageLayout
import io.github.pintofi.beanbook.core.domain.PageSize

/** What a page size is called on screen. */
internal fun PageSize.label(): String = when (this) {
    PageSize.AS_SCANNED -> "As scanned"
    PageSize.A4 -> "A4"
    PageSize.LETTER -> "Letter"
}

private fun PageSize.detail(): String = when (this) {
    PageSize.AS_SCANNED -> "Each page keeps the shape it was photographed."
    PageSize.A4 -> "210 × 297 mm. Pages are fitted and centred."
    PageSize.LETTER -> "8.5 × 11 in. Pages are fitted and centred."
}

/**
 * The paper the PDF is laid out on: as scanned, or A4 or Letter with every page fitted and centred.
 * One choice for the whole document. The local paper is offered second; nothing is chosen for you.
 */
@Composable
internal fun PageSizeDialog(
    current: PageSize,
    localPaper: PageSize,
    onUse: (PageSize) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page size") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                PageLayout.choices(localPaper).forEach { size ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(selected = picked == size, role = Role.RadioButton, onClick = { picked = size })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = picked == size, onClick = null)
                        Column {
                            Text(size.label(), style = MaterialTheme.typography.bodyLarge)
                            Text(size.detail(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onUse(picked) }) { Text("Use") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
