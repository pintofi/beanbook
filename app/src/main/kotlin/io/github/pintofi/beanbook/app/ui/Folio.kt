package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.Measure

/**
 * Folios: the grouped rows every page a person *acts* on is built from.
 *
 * The ledger's own pages are ruled paper -- square rows hung from a rule -- and stay that way.
 * Everything else the app shows is a control: a setting, a section to open, an action with a
 * consequence. Those were assembled per screen from whatever was to hand -- a `ListItem` on
 * More, a `Notice` on Backup, bare buttons on Remote, a raw directory listing on Plugins -- and
 * the audit read them as seven different apps. A folio is the one way they are drawn now: rows
 * grouped into a rounded object, separated by a seam, each row a full-width target of its own.
 *
 * Drawn by the app rather than with Material's segmented list items, so the padding, the
 * 200% text behaviour and the semantics are this app's and stay put across library versions
 * (the reason the old `ListItem` overload was kept, D11).
 */

/** The heading a folio hangs under: outside the group, in the emphasized label style. */
@Composable
fun FolioHeading(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Measure.indent, end = Measure.indent, top = Measure.gap, bottom = Measure.snug)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** A line of explanation under a folio: what the group above does, or what it will not do. */
@Composable
fun FolioNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = Measure.indent, vertical = Measure.snug),
    )
}

/** What a [FolioGroup] collects: one entry per row, drawn in order with seams between. */
class FolioScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()

    /** A row of the group. Its content is laid on the folio's surface, already shaped. */
    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

/**
 * A group of rows as one rounded object (`BeanbookShape.Folio`).
 *
 * The builder is not composable, like a lazy list's: it says which rows exist, and the group
 * then knows which is first and last and shapes each one.
 */
@Composable
fun FolioGroup(
    modifier: Modifier = Modifier,
    content: FolioScope.() -> Unit,
) {
    val scope = FolioScope().apply(content)
    val count = scope.items.size
    if (count == 0) return
    val container = folioContainer()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Measure.seam),
    ) {
        scope.items.forEachIndexed { index, row ->
            Surface(
                shape = BeanbookShape.Folio.at(index, count),
                color = container,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                row()
            }
        }
    }
}

/**
 * The ground of a folio: one step off the paper in each direction.
 *
 * On light paper the rows are white slips -- the lowest container -- because a tint darker
 * than the page reads as disabled there; on the dark and black grounds they are raised a step,
 * because a slip darker than the page would read as a hole in it.
 */
@Composable
@ReadOnlyComposable
fun folioContainer(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() > 0.5f) scheme.surfaceContainerLowest else scheme.surfaceContainerHigh
}

/** How a folio row's icon is tinted: by what the row is for, never by colour alone. */
enum class FolioTone { PLAIN, ACCENT, ATTENTION, PROBLEM }

/** The tinted square an icon sits on at the start of a row. Decorative: the row is named. */
@Composable
fun FolioBadge(icon: ImageVector, tone: FolioTone = FolioTone.PLAIN, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (ground, ink) = when (tone) {
        FolioTone.PLAIN -> scheme.secondaryContainer to scheme.onSecondaryContainer
        FolioTone.ACCENT -> scheme.primaryContainer to scheme.onPrimaryContainer
        FolioTone.ATTENTION -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        FolioTone.PROBLEM -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(
        shape = BeanbookShape.badge,
        color = ground,
        contentColor = ink,
        modifier = modifier.size(Measure.badge),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * One row of a folio: an optional badge, a title, an optional detail line, and an optional
 * trailing control or value.
 *
 * The whole row is the target when it does something, and it is at least 56dp tall -- 64 with
 * a detail line -- so it is a full finger at any text size. The title and detail wrap rather
 * than clip: at 200% text a row grows, it does not cut its own words off.
 */
@Composable
fun FolioRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    tone: FolioTone = FolioTone.PLAIN,
    onClick: (() -> Unit)? = null,
    /** What a screen reader says for the row; the visible words when null. */
    describe: String? = null,
    enabled: Boolean = true,
    /** A chevron says "opens a page"; omit it for a row that acts in place. */
    opens: Boolean = false,
    titleColor: Color = Color.Unspecified,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(
                if (describe != null) {
                    Modifier.semantics(mergeDescendants = true) { contentDescription = describe }
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .heightIn(min = if (detail != null) 64.dp else Measure.rowMinHeight)
            .padding(horizontal = Measure.indent, vertical = Measure.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            FolioBadge(icon, tone)
            Spacer(Modifier.width(Measure.indent))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) titleColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Measure.gap))
            trailing()
        }
        if (opens) {
            Spacer(Modifier.width(Measure.snug))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A row that is a check box: the standard for a list of things to choose.
 *
 * The box's own square starts where every other row's content starts ([Measure.indent] in from
 * the card's edge), not 13dp further in where its 48dp touch target would put it, and the label
 * follows at one gap, so a list of choices lines up with the rows above and below it. The box and
 * the label are one target, named once. [trailing] sits outside that target, for buttons that
 * do something else, and [below] is laid under the row (a field that belongs to the choice).
 */
@Composable
fun FolioCheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    describe: String = title,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (detail != null) 64.dp else Measure.rowMinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = onCheckedChange,
                    )
                    .semantics(mergeDescendants = true) { contentDescription = describe }
                    .heightIn(min = if (detail != null) 64.dp else Measure.rowMinHeight)
                    .padding(
                        start = Measure.indent - CHECK_SLOT_INSET,
                        end = Measure.indent,
                        top = Measure.gap,
                        bottom = Measure.gap,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquircleCheckbox(checked = checked, onCheckedChange = null, enabled = enabled)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val dimmed = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) Color.Unspecified else dimmed,
                    )
                    if (detail != null) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            trailing?.invoke(this)
        }
        below?.invoke(this)
    }
}

/** How far the square of a [SquircleCheckbox] sits inside its 48dp slot: (48 - 22) / 2. */
private val CHECK_SLOT_INSET = 13.dp

/**
 * A row that is a switch: the whole row toggles, and says its state.
 *
 * [describe] names the control and what turning it on means; the switch itself carries no
 * description of its own, so a screen reader says it once.
 */
@Composable
fun FolioSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    describe: String = title,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) { contentDescription = describe }
            .heightIn(min = if (detail != null) 64.dp else Measure.rowMinHeight)
            .padding(horizontal = Measure.indent, vertical = Measure.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            FolioBadge(icon)
            Spacer(Modifier.width(Measure.indent))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(Measure.gap))
        // Not focusable or clickable on its own: the row is the control.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Free content on a folio surface -- a field, a paragraph, a row of buttons -- with the same
 * inset as a [FolioRow], so a group mixing the two keeps one left edge.
 */
@Composable
fun FolioBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Measure.indent, vertical = Measure.indent),
        verticalArrangement = Arrangement.spacedBy(Measure.gap),
        content = content,
    )
}

/**
 * The column a folio page is laid in: the page gutter, a readable measure on a wide window, and
 * room under the last group.
 */
@Composable
fun FolioPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .padding(horizontal = Measure.gutter)
            .padding(top = Measure.snug, bottom = Measure.section),
        verticalArrangement = Arrangement.spacedBy(Measure.snug),
        content = content,
    )
}
