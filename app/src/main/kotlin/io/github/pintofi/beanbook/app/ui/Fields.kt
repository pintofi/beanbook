package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A date, typed or picked (B53).
 *
 * The text field stays: a date in a ledger is `YYYY-MM-DD` and somebody who knows that types
 * it faster than any calendar. What was missing is the other half — the calendar itself, for
 * "the Tuesday before last", which nobody holds in their head as a number. Both write the
 * same string, so nothing downstream has to know which was used.
 *
 * The picker opens on whatever the field currently holds when that parses, and on today when
 * it does not, because a half-typed date should not send the calendar to 1970.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Date",
    today: LocalDate = LocalDate.now(),
) {
    var picking by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = {
            IconButton(
                onClick = { picking = true },
                modifier = Modifier.semantics { contentDescription = "Pick $label from a calendar" },
            ) { Icon(Icons.Filled.DateRange, contentDescription = null) }
        },
        modifier = modifier,
    )

    if (picking) {
        val start = runCatching { LocalDate.parse(value.trim()) }.getOrDefault(today)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // UTC throughout: the picker hands back midnight UTC for the day the
                        // user tapped, and reading it back in the device's zone can land on
                        // the day before. A ledger date is a calendar day, not an instant.
                        state.selectedDateMillis?.let { millis ->
                            onValueChange(
                                Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC)
                                    .toLocalDate().toString(),
                            )
                        }
                        picking = false
                    },
                ) { Text("Use this date") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

/**
 * A text field that offers what the ledger already contains, narrowing as you type (B53).
 *
 * The app knew every account, payee, narration and commodity in the open ledger and offered
 * them as a row of chips under some fields and nowhere else, so most typing was still
 * typing. This shows the whole list on focus and filters it letter by letter — which is what
 * makes a long account name a two-tap job rather than forty characters.
 *
 * **Never for an amount.** A suggestion list of numbers you have entered before invites
 * tapping one that is nearly right, and a nearly-right amount is a wrong ledger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    describe: String = label,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val needle = value.trim()
    val offered = remember(suggestions, needle) {
        val matching = if (needle.isEmpty()) {
            suggestions
        } else {
            suggestions.filter { it.contains(needle, ignoreCase = true) }
        }
        // A single suggestion identical to what is typed says nothing; showing it makes the
        // menu look stuck open at the end of every entry.
        matching.filterNot { it.equals(needle, ignoreCase = true) }.take(MAX_SUGGESTIONS)
    }

    ExposedDropdownMenuBox(
        expanded = expanded && offered.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = trailingIcon,
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable)
                .fillMaxWidth()
                .semantics { contentDescription = describe },
        )
        ExposedDropdownMenu(
            expanded = expanded && offered.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp),
        ) {
            offered.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onValueChange(suggestion)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    modifier = Modifier.semantics { contentDescription = "Use $suggestion" },
                )
            }
        }
    }
}

/** Enough to be useful, few enough that the menu does not become its own screen. */
private const val MAX_SUGGESTIONS = 40
