package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background

/**
 * A date, typed or picked.
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
        shape = io.github.pintofi.beanbook.app.ui.theme.BeanbookShape.control,
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
 * A text field that offers what the ledger already contains, narrowing as you type.
 *
 * The app knew every account, payee, narration and commodity in the open ledger and offered
 * them as a row of chips under some fields and nowhere else, so most typing was still
 * typing. This shows the list on focus and filters it letter by letter -- which is what
 * makes a long account name a two-tap job rather than forty characters.
 *
 * **Inline, not a popup.** The suggestions used to open in an `ExposedDropdownMenu`,
 * which is a separate window laid over the keyboard. A second window over the IME is what
 * left the app with a blank band where the keyboard had been: the popup and the activity
 * disagreed about the keyboard's insets as it moved, the activity's inset animation never
 * reached its end, and `imePadding` stayed at a fraction of the keyboard's height until the
 * process died -- which is why closing the app was the only thing that fixed it. The list is
 * now part of the page, directly under the field it belongs to, so there is one window and
 * one set of insets, and nothing can be left behind.
 *
 * **The cursor goes to the end of what was chosen.** The field used to take a plain
 * `String`, so a picked suggestion replaced the text and Compose kept the cursor at the index
 * it had been at -- mid-word, where the person stopped typing. The field now owns its
 * selection, and choosing a suggestion puts the cursor after the last character, which is
 * where the next keystroke belongs.
 *
 * **Never for an amount.** A suggestion list of numbers you have entered before invites
 * tapping one that is nearly right, and a nearly-right amount is a wrong ledger.
 */
@Composable
fun SuggestingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    describe: String = label,
    trailingIcon: @Composable (() -> Unit)? = null,
    /** Applied to what is typed, before it reaches the caller: tags refuse spaces, say. */
    transform: (String) -> String = { it },
    /**
     * What an empty field stands for, shown faintly inside it: the commodity a posting falls
     * back to, say. Never the only label: the label stays above it.
     */
    placeholder: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    // The field's own copy, so the selection survives the caller's round trip. It follows the
    // caller whenever the caller's value is something the field did not produce -- a template
    // applied, a draft cloned -- and keeps the cursor at the end in that case too.
    var field by remember {
        mutableStateOf(TextFieldValue(value, selection = TextRange(value.length)))
    }
    // Read, not written back: a state write during composition would recompose again for
    // nothing. The next edit starts from this, so the stale copy is simply never used.
    val current = if (field.text == value) {
        field
    } else {
        TextFieldValue(value, selection = TextRange(value.length))
    }
    var focused by remember { mutableStateOf(false) }
    // Set when a suggestion is chosen, cleared by the next keystroke: a list that reopens
    // under a value the person just picked from it is the list getting in the way.
    var chosen by remember { mutableStateOf(false) }

    val needle = value.trim()
    val offered = remember(suggestions, needle) {
        val matching = if (needle.isEmpty()) {
            suggestions
        } else {
            suggestions.filter { it.contains(needle, ignoreCase = true) }
        }
        // The suggestion that is exactly what was typed stays, first. It used to be dropped as
        // "saying nothing", so typing "Samue" showed Samuel and typing the last letter took it
        // away, with nothing left to tap. It does say something: the ledger already has this
        // name, which for an account is the difference between valid and "not an account in
        // this ledger". Tapping it also settles the capitalisation to the ledger's own.
        matching.sortedBy { !it.equals(needle, ignoreCase = true) }.take(MAX_SUGGESTIONS)
    }
    val open = focused && !chosen && offered.isNotEmpty()

    androidx.compose.foundation.layout.Column(modifier = modifier) {
        OutlinedTextField(
            value = current,
            onValueChange = { next ->
                val text = transform(next.text)
                field = next.copy(
                    text = text,
                    selection = if (text == next.text) {
                        next.selection
                    } else {
                        TextRange(text.length)
                    },
                )
                if (text != value) {
                    chosen = false
                    onValueChange(text)
                }
            },
            // A resting, empty field shows its label in the box, where Material hides the
            // placeholder; so the fallback rides on the label until the field is touched
            // ("Commodity · PKR"), then moves into the box as the placeholder.
            label = {
                Text(
                    if (placeholder != null && value.isEmpty() && !focused) "$label · $placeholder" else label,
                    maxLines = 1,
                )
            },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            trailingIcon = trailingIcon,
            leadingIcon = leadingIcon,
            shape = io.github.pintofi.beanbook.app.ui.theme.BeanbookShape.control,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = describe },
        )
        LedgerReveal(visible = open, label = "suggestions") {
            val shown = remember(offered) { offered }
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .heightIn(max = SUGGESTION_LIST_HEIGHT)
                    .clip(io.github.pintofi.beanbook.app.ui.theme.BeanbookShape.control)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .verticalScroll(rememberScrollState()),
            ) {
                shown.forEach { suggestion ->
                    Text(
                        suggestion,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                chosen = true
                                field = TextFieldValue(
                                    suggestion,
                                    selection = TextRange(suggestion.length),
                                )
                                onValueChange(suggestion)
                            }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .semantics { contentDescription = "Use $suggestion" },
                    )
                }
            }
        }
    }
}

/** About five rows: enough to choose from, not so much that the form scrolls away under it. */
private val SUGGESTION_LIST_HEIGHT = 240.dp

/** Enough to be useful, few enough that the menu does not become its own screen. */
private const val MAX_SUGGESTIONS = 40

/**
 * `value` with its sign switched: a leading minus is removed, a leading plus becomes a minus, and
 * anything else gets a minus in front. An empty box becomes a lone "-" to type the digits after.
 */
internal fun withSignToggled(value: String): String {
    val text = value.trim()
    return when {
        text.startsWith("-") -> text.removePrefix("-").trimStart()
        text.startsWith("+") -> "-" + text.removePrefix("+").trimStart()
        else -> "-$text"
    }
}

/**
 * An amount box with a button that switches it between positive and negative.
 *
 * The number keyboard Android shows for a decimal field has digits and a point and no minus, so a
 * posting could be typed positive but never negative without switching to the full keyboard.
 * Compose has no flag for the signed number keyboard, and not every keyboard app honours it, so
 * the sign is a control of the form's own: always there, whichever keyboard is installed.
 */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Amount",
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        trailingIcon = {
            IconButton(
                onClick = { onValueChange(withSignToggled(value)) },
                modifier = Modifier.semantics {
                    contentDescription = "Switch the $label between positive and negative"
                },
            ) { Text("\u00B1", style = MaterialTheme.typography.titleMedium) }
        },
        modifier = modifier,
    )
}
