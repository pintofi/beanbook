package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pintofi.beanbook.app.ui.theme.AmountTextStyle
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.core.domain.HeadlineFigures
import io.github.pintofi.beanbook.core.domain.LedgerFormat

/**
 * Figures that are never clipped.
 *
 * At 200% text Home's Income and Expenses tiles cut "2,345,678.90 PKR" to "2,345,678." with no
 * ellipsis (device audit 2026-10-10) -- the worst failure a money screen can have, because the
 * figure still looks like a figure. Layouts around figures now reflow first; this is what a
 * figure does when the space it is finally given is still too narrow.
 */

/**
 * How a figure fits: on one line if it can, stepping its size down to [minScale] of the style;
 * failing that, at the largest size that fits on two lines broken only at a space -- between
 * the number and its commodity -- so digits are never split and nothing is cut off.
 */
private class FigureFit(
    private val largest: Float,
    private val minScale: Float,
    private val step: TextUnit = 1.sp,
) : TextAutoSize {

    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val smallest = largest * minScale
        var size = largest
        while (size >= smallest) {
            val layout = performLayout(constraints, text, size.sp)
            if (layout.lineCount == 1 && !layout.hasVisualOverflow) return size.sp
            size -= step.value
        }
        // Two lines, broken at a space: "2,345,678.90" over "PKR" rather than a split number.
        size = largest
        while (size >= smallest) {
            val layout = performLayout(constraints, text, size.sp)
            val breaksAtSpaces = (0 until layout.lineCount - 1).all { line ->
                val end = layout.getLineEnd(line)
                end in 1..text.length &&
                    (text[end - 1].isWhitespace() || (end < text.length && text[end].isWhitespace()))
            }
            if (!layout.hasVisualOverflow && breaksAtSpaces) return size.sp
            size -= step.value
        }
        return smallest.sp
    }

    override fun equals(other: Any?): Boolean =
        other is FigureFit && other.largest == largest && other.minScale == minScale && other.step == step

    override fun hashCode(): Int = (31 * largest.hashCode() + minScale.hashCode()) * 31 + step.hashCode()
}

/**
 * Money that fits the space it is given, or wraps at the space before its commodity, and is
 * never cut off. Same face, alignment and colour rules as [MoneyText].
 */
@Composable
fun FitFigure(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LedgerType.figure,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Start,
    /** How far the size may step down before the figure wraps instead. */
    minScale: Float = 0.6f,
) {
    val size = style.fontSize.takeIf { it.isSpecified } ?: MaterialTheme.typography.bodyMedium.fontSize
    val fit = remember(size, minScale) { FigureFit(size.value, minScale) }
    Text(
        text = text,
        modifier = modifier,
        style = style.merge(AmountTextStyle).copy(textAlign = textAlign),
        color = color,
        maxLines = 2,
        // A figure that still does not fit ends in an ellipsis, so a cut is visible as a cut.
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        autoSize = fit,
    )
}

/**
 * Text that may break between the segments of an account name.
 *
 * "Assets:Current:Bank:Savings:Example-Bank" broke as "Savi / ngs" at 200% text, because the
 * name is one word to a line breaker. A zero-width space after every colon and hyphen gives it
 * the places a person would break it. Only for display: the name is never written anywhere
 * with these in it, and callers give a screen reader the plain name.
 */
fun breakableAccount(name: String): String = buildString(name.length + 8) {
    name.forEach { c ->
        append(c)
        if (c == ':' || c == '-') append('​')
    }
}

/**
 * A headline figure that may hold several commodities: up to three lines and "+N more"
 * (core-domain `HeadlineFigures`).
 *
 * One commodity is set at the full display size. Several are set a step smaller and left in a
 * column, because a stack of 34sp lines is a list pretending to be a headline. The rest are one
 * tap away on [onMore] -- Balances, which lists every holding.
 */
@Composable
fun HeadlineFigure(
    figures: HeadlineFigures,
    format: LedgerFormat,
    modifier: Modifier = Modifier,
    hideAmounts: Boolean = false,
    style: TextStyle = LedgerType.display,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onMore: (() -> Unit)? = null,
    /** The single-figure path, which rolls its digits in; null for a plain figure. */
    single: (@Composable (String) -> Unit)? = null,
) {
    val lines = figures.shown.map { if (hideAmounts) "•••" else format.format(it) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Measure.tight)) {
        when {
            lines.isEmpty() -> FitFigure("—", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lines.size == 1 && figures.more == 0 ->
                single?.invoke(lines.single()) ?: FitFigure(lines.single(), style = style, color = color)
            else -> {
                val smaller = style.copy(
                    fontSize = if (style.fontSize.isSpecified) style.fontSize * MULTI_LINE_SCALE else style.fontSize,
                    lineHeight = if (style.lineHeight.isSpecified) {
                        style.lineHeight * MULTI_LINE_SCALE
                    } else {
                        style.lineHeight
                    },
                )
                lines.forEach { FitFigure(it, style = smaller, color = color) }
            }
        }
        if (figures.more > 0) {
            val words = if (figures.more == 1) "1 more commodity" else "${figures.more} more commodities"
            if (onMore != null) {
                OutlinedButton(
                    onClick = onMore,
                    modifier = Modifier.semantics { contentDescription = "$words. Open Balances to see them all" },
                ) { Text("+${figures.more} more", style = MaterialTheme.typography.labelLarge) }
            } else {
                Text(
                    "+${figures.more} more",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = words },
                )
            }
        }
    }
}

/** How much smaller each line of a several-commodity headline is than a single figure. */
private const val MULTI_LINE_SCALE = 0.7f
