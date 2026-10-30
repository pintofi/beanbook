package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled
import io.github.pintofi.beanbook.app.ui.theme.Measure

// The Material 3 Expressive components this app composes with, in its own proportions.
// Each one honours LocalMotionEnabled: the theme hands
// Material a still motion scheme when animation is off, and the two indicators below, whose
// animation is a loop rather than a spring, draw a still mark instead.

/**
 * One of a few, chosen in a connected row: the Expressive connected button group.
 *
 * Replaces the segmented rows (valuation basis, spending period, page style, entry kind).
 * Each segment is a [ToggleButton] whose shape morphs when it is pressed and when it is
 * chosen -- seams tighten under a finger, the chosen segment takes the control radius on every
 * corner ([BeanbookShape.Connected]) -- and the chosen one also carries a check, so the
 * choice is said in shape and mark as well as in colour, as the segmented row's check did.
 *
 * Read as a set of radio buttons: the row is a selectable group and every segment is a radio
 * button that says whether it is selected. [describe] supplies the spoken description where a
 * caller had one; otherwise the visible label is what is read.
 */
@Composable
fun <T> ConnectedChoiceRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    describe: ((T) -> String)? = null,
    colors: ToggleButtonColors = connectedChoiceColours(),
    border: BorderStroke? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    BoxWithConstraints(modifier = modifier) {
        // A label may wrap between words, but never inside one: "Mar / ket / val / ue" is not a
        // label. When even the longest word does not fit its share of the row, as with three
        // choices at 200% text on a narrow phone, the segments stack, one to a line.
        val stacked = maxLines == Int.MAX_VALUE && !wordsFit(options.map(label), maxWidth)
        if (stacked) {
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                options.forEach { option ->
                    ChoiceButton(
                        option, option == selected, onSelect, label, describe, colors, border, maxLines,
                        shapes = connectedShapes(0, 1),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            // Intrinsic height, so a label that wraps at 200% text makes its whole row taller rather
            // than leaving one segment standing above the others.
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { index, option ->
                    ChoiceButton(
                        option, option == selected, onSelect, label, describe, colors, border, maxLines,
                        shapes = connectedShapes(index, options.size),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** Whether the longest word of every label fits its share of a row [width] wide, at the current text size. */
@Composable
private fun wordsFit(labels: List<String>, width: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    return remember(labels, width, style, density) {
        val gaps = ButtonGroupDefaults.ConnectedSpaceBetween * (labels.size - 1)
        val room = (width - gaps) / labels.size - ConnectedChoicePadding.horizontal() - CHECK_ROOM
        val share = with(density) { room.toPx() }
        labels.flatMap { it.split(' ') }.all { measurer.measure(it, style).size.width <= share }
    }
}

@Composable
private fun <T> ChoiceButton(
    option: T,
    chosen: Boolean,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    describe: ((T) -> String)?,
    colors: ToggleButtonColors,
    border: BorderStroke?,
    maxLines: Int,
    shapes: ToggleButtonShapes,
    modifier: Modifier,
) {
    ToggleButton(
        checked = chosen,
        // A chosen segment stays chosen when tapped again: one option is always on,
        // as it was in the segmented row this replaces.
        onCheckedChange = { onSelect(option) },
        modifier = modifier.semantics {
            role = Role.RadioButton
            this.selected = chosen
            describe?.let { contentDescription = it(option) }
        },
        shapes = shapes,
        colors = colors,
        // Flat: the row sits on ruled paper, and a shadow under each segment would
        // make five objects of what is one control.
        elevation = null,
        border = border,
        contentPadding = ConnectedChoicePadding,
        icon = if (chosen) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
    ) {
        Text(
            label(option),
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** What the tick on a chosen segment takes from the label: its icon and the space beside it. */
private val CHECK_ROOM = 26.dp

private fun androidx.compose.foundation.layout.PaddingValues.horizontal(): Dp =
    calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) +
        calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)

/**
 * Narrower side padding than a standalone button's 16dp: three segments share a phone's width,
 * and the label is what needs the room. The same 12dp a segmented button gave its label.
 */
private val ConnectedChoicePadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 12.dp,
    vertical = 8.dp,
)

/**
 * The connected row's colours: paper raised a step for an unchosen segment, the sage container
 * for the chosen one -- the pair the segmented rows used, so the change is in shape and motion,
 * not in what the colours mean. The teal stays a rule rather than a filled block.
 */
@Composable
fun connectedChoiceColours(): ToggleButtonColors = ToggleButtonDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurface,
    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

/** Leading, middle or trailing, by position, in this app's connected shapes. */
private fun connectedShapes(index: Int, count: Int): ToggleButtonShapes = when {
    count == 1 -> ToggleButtonShapes(
        shape = BeanbookShape.control,
        pressedShape = BeanbookShape.control,
        checkedShape = BeanbookShape.Connected.chosen,
    )
    index == 0 -> ToggleButtonShapes(
        shape = BeanbookShape.Connected.leading,
        pressedShape = BeanbookShape.Connected.leadingPressed,
        checkedShape = BeanbookShape.Connected.chosen,
    )
    index == count - 1 -> ToggleButtonShapes(
        shape = BeanbookShape.Connected.trailing,
        pressedShape = BeanbookShape.Connected.trailingPressed,
        checkedShape = BeanbookShape.Connected.chosen,
    )
    else -> ToggleButtonShapes(
        shape = BeanbookShape.Connected.middle,
        pressedShape = BeanbookShape.Connected.middlePressed,
        checkedShape = BeanbookShape.Connected.chosen,
    )
}

/**
 * Work under way, of unknown length: the Expressive loading indicator, in place of the
 * spinning ring.
 *
 * Its shapes morph from one to the next as it turns, which is the one looping animation the
 * app allows, and only while something is genuinely working. With animation off it is a single
 * still shape that is still announced as indeterminate progress: the words beside it say what
 * is happening, and a person who asked for no motion gets none.
 *
 * Sized by [size]: Material's own 48dp is too large beside a line of text or inside a button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LedgerLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = LOADING_INDICATOR_SIZE.dp,
    color: Color = BeancountTheme.ledger.rule,
) {
    if (LocalMotionEnabled.current) {
        LoadingIndicator(modifier = modifier.size(size), color = color)
    } else {
        val still = MaterialShapes.Cookie9Sided.toShape()
        Box(
            modifier = modifier.size(size).progressSemantics(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize(STILL_INDICATOR_FRACTION)
                    .background(color, still),
            )
        }
    }
}

/** Material's loading indicator container, in dp. */
private const val LOADING_INDICATOR_SIZE = 48

/** How much of its box the still indicator fills: Material's 38dp active shape in 48dp. */
private const val STILL_INDICATOR_FRACTION = 38f / 48f

/**
 * A long piece of work with no count to fill to, outside the ledger -- a script running.
 *
 * The Expressive wavy bar, because a script is not the ledger and a wave says "moving" more
 * plainly than a sliding block. Deliberately not used for reading the ledger or mirroring,
 * where the bar is the app's rule filling and stays flat. With
 * animation off it is the flat track, announced as indeterminate progress.
 */
@Composable
fun WorkingBar(modifier: Modifier = Modifier) {
    if (LocalMotionEnabled.current) {
        LinearWavyProgressIndicator(modifier = modifier.fillMaxWidth())
    } else {
        Box(
            modifier
                .fillMaxWidth()
                .height(Measure.rule)
                .progressSemantics()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}
