package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.SquircleShape

/**
 * A checkbox drawn as a squircle, in the same colours as Material's.
 *
 * With [onCheckedChange] null it only draws, and the row around it carries the toggling and the
 * label (see the accounts checklist); given one, it toggles itself. The box sits in a 48dp target.
 */
@Composable
fun SquircleCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colours = MaterialTheme.colorScheme
    val edge = if (checked) colours.primary else colours.onSurfaceVariant
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Box(
        modifier = modifier.size(48.dp).then(toggle).alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(SquircleShape)
                .background(if (checked) colours.primary else Color.Transparent)
                .border(2.dp, edge, SquircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = colours.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
