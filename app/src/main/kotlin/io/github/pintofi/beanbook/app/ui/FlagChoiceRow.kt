package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.core.domain.BeancountText
import io.github.pintofi.beanbook.core.domain.DraftProblem
import io.github.pintofi.beanbook.core.domain.FlagChoice
import io.github.pintofi.beanbook.core.domain.FlagOption

/**
 * The transaction's flag: Cleared (`*`), Pending (`!`) or an Other flag the user types.
 *
 * One choice of three, side by side and wrapping at large text so none is cut off. Cleared and
 * Pending are fixed; choosing Other reveals a field for the flag itself (one capital letter, or one
 * of `& # ? %`, which is what Beancount takes). What was typed is kept when another option is chosen.
 * The field says what is wrong with a flag Beancount would refuse, as the form's problem list does.
 */
@Composable
internal fun FlagChoiceRow(
    choice: FlagChoice,
    onChoice: (FlagChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Measure.snug)) {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
            verticalArrangement = Arrangement.spacedBy(Measure.snug),
        ) {
            FlagOption.entries.forEach { option ->
                val selected = choice.option == option
                val mark = markFor(option)
                FilterChip(
                    selected = selected,
                    onClick = { onChoice(choice.choose(option)) },
                    label = { Text(labelFor(option, choice)) },
                    leadingIcon = { StampMark(mark, flag = choice.flag.takeIf { selected }, decorative = true) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics {
                            role = Role.RadioButton
                            contentDescription = describeOption(option, choice)
                        },
                )
            }
        }
        if (choice.option == FlagOption.OTHER) OtherFlagField(choice, onChoice)
    }
}

@Composable
private fun OtherFlagField(choice: FlagChoice, onChoice: (FlagChoice) -> Unit) {
    var touched by remember { mutableStateOf(false) }
    val flag = choice.flag
    val problem = if (BeancountText.TRANSACTION_FLAG.matches(flag)) null else DraftProblem.InvalidFlag(flag)
    // An empty field is not scolded until it has been left: the chip was only just chosen.
    val showProblem = problem != null && (flag.isNotEmpty() || touched)
    OutlinedTextField(
        value = choice.otherText,
        onValueChange = { onChoice(choice.withOther(it.take(MAX_TYPED))) },
        label = { Text("Other flag") },
        placeholder = { Text("P") },
        singleLine = true,
        isError = showProblem,
        supportingText = {
            Text(
                if (showProblem) {
                    problem.describe()
                } else {
                    "One capital letter, or & # ? %"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier
            .width(180.dp)
            .onFocusChanged { if (!it.isFocused && choice.otherText.isEmpty()) touched = true }
            .semantics { contentDescription = "Other flag: one capital letter, or one of & # ? %" },
    )
}

private const val MAX_TYPED = 4

private fun markFor(option: FlagOption): LedgerMark = when (option) {
    FlagOption.CLEARED -> LedgerMark.CLEARED
    FlagOption.PENDING -> LedgerMark.PENDING
    FlagOption.OTHER -> LedgerMark.FLAGGED
}

private fun labelFor(option: FlagOption, choice: FlagChoice): String = when (option) {
    FlagOption.CLEARED -> "Cleared *"
    FlagOption.PENDING -> "Pending !"
    FlagOption.OTHER -> if (choice.option == FlagOption.OTHER && choice.flag.isNotEmpty()) {
        "Other ${choice.flag}"
    } else {
        "Other"
    }
}

private fun describeOption(option: FlagOption, choice: FlagChoice): String = when (option) {
    FlagOption.CLEARED -> "Cleared, the flag *"
    FlagOption.PENDING -> "Pending, the flag !"
    FlagOption.OTHER -> if (choice.option == FlagOption.OTHER && choice.flag.isNotEmpty()) {
        "Other flag ${choice.flag}"
    } else {
        "Other flag, type which"
    }
}
