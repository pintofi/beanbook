package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The button for an action that is not the main one on its page: Cancel beside Save, Clear beside a
 * filter, View and Share on a document.
 *
 * It used to be a text button, which reads as a plain word: people could not tell it was something
 * to press. This one is outlined, so it is plainly a control, and unfilled, so it never competes
 * with the filled button that is the page's main action. The padding is tighter than an outlined
 * button's own so a row of them fits, and the target stays 48dp tall.
 *
 * Dialog actions keep Material's own text buttons: a dialog's footer is where people expect them.
 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors = colors,
        border = border ?: ButtonDefaults.outlinedButtonBorder(enabled),
        contentPadding = contentPadding,
        content = content,
    )
}
