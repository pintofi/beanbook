package io.github.pintofi.beanbook.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.Motion
import io.github.pintofi.beanbook.app.ui.theme.rememberAnimationsEnabled

/**
 * The parts a ledger page is made of (B74).
 *
 * The design's one structural idea: a financial surface is **ruled paper**, not a stack of
 * cards. A rule runs down the left of the data, headings hang off it, rows sit against it, and
 * the only rounded objects on the page are the ones interrupting it -- a notice, a dialog, the
 * button that writes to the file. Everything here exists so that idea is written once and used
 * everywhere rather than approximated screen by screen.
 */

/**
 * The vertical rule a financial list is hung from, and the app's signature.
 *
 * It draws down once when the surface arrives -- about a third of a second, then still. Not per
 * row: twenty rows animating one after another is twenty distractions on a surface whose job is
 * to be scanned. When the user has animations off, it is simply there.
 *
 * Purely decorative in the semantics sense, so it is hidden from screen readers: it says the
 * same thing as the heading structure it sits beside.
 */
@Composable
fun RuledColumn(
    modifier: Modifier = Modifier,
    colour: Color = BeancountTheme.ledger.rule,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val animate = rememberAnimationsEnabled()
    var arrived by remember { mutableStateOf(!animate) }
    val extent by animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = Motion.arriving(),
        label = "rule",
    )
    androidx.compose.runtime.LaunchedEffect(animate) { if (animate) arrived = true }

    val ruleWidth = Measure.rule
    val gap = Measure.ruleGap
    Column(
        modifier = modifier
            // Painted behind the content rather than laid out beside it: the rule is then
            // exactly as tall as whatever it is ruling, without measuring the column twice,
            // and the same composable works around a lazy list where intrinsics do not.
            .drawBehind {
                drawRect(
                    color = colour,
                    size = Size(width = ruleWidth.toPx(), height = size.height * extent),
                )
            }
            .padding(start = ruleWidth + gap),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * A heading that hangs off the rule.
 *
 * The tick is the detail: a short horizontal mark at the heading's baseline, so a section
 * reads as being attached to the rule rather than floating beside it. It is drawn rather than
 * given a border, because a border would have to guess where the text sits.
 */
@Composable
fun LedgerHeading(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // No space above: a heading is the first thing in its ruled section, and the
            // page already spaces one section from the next. Padding here would double it.
            .padding(bottom = Measure.snug),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * The mark beside an entry: what the file's flag means, in a shape rather than a character.
 *
 * A Beancount transaction carries `*` or `!`, which are exact and unreadable at a glance. The
 * mark says the same thing in the app's own vocabulary: **two equal rules for a cleared
 * entry** -- the same pair as the launcher icon, and the same claim, that the two sides
 * balance -- and **one rule over a gap for a pending one**, which reads as a line still to be
 * drawn.
 *
 * Colour reinforces and never carries: the two shapes differ, and the description names the
 * state in words.
 */
enum class LedgerMark { CLEARED, PENDING, ATTENTION }

@Composable
fun StampMark(mark: LedgerMark, modifier: Modifier = Modifier) {
    val ledger = BeancountTheme.ledger
    val colour = when (mark) {
        LedgerMark.CLEARED -> ledger.rule
        LedgerMark.PENDING -> ledger.pending
        LedgerMark.ATTENTION -> MaterialTheme.colorScheme.error
    }
    val described = when (mark) {
        LedgerMark.CLEARED -> "Cleared"
        LedgerMark.PENDING -> "Pending"
        LedgerMark.ATTENTION -> "Needs attention"
    }
    Canvas(
        modifier = modifier
            .size(width = Measure.stamp, height = Measure.stamp)
            .semantics { contentDescription = described },
    ) {
        val thickness = size.minDimension * 0.16f
        val inset = size.minDimension * 0.12f
        val full = size.width - inset * 2

        fun bar(top: Float, width: Float, height: Float = thickness) = drawRect(
            color = colour,
            topLeft = Offset(inset, size.height * top),
            size = Size(width, height),
        )

        when (mark) {
            // Two equal rules: the balance.
            LedgerMark.CLEARED -> {
                bar(top = 0.34f, width = full)
                bar(top = 0.58f, width = full)
            }
            // One rule, and the second half-drawn: the entry is not finished.
            LedgerMark.PENDING -> {
                bar(top = 0.34f, width = full)
                bar(top = 0.58f, width = full * 0.45f)
            }
            // An upright mark: something here needs a person.
            LedgerMark.ATTENTION -> {
                bar(top = 0.2f, width = thickness * 1.4f, height = size.height * 0.44f)
                bar(top = 0.74f, width = thickness * 1.4f, height = thickness * 1.4f)
            }
        }
    }
}

/**
 * A figure and what it is: the app's way of stating a number without a dashboard card.
 *
 * The label sits *under* the figure, not above it. Reading order on a screen of money is the
 * number first -- a person opens the app to see where they stand, not to read the word "net
 * worth" -- and the label then tells them which number they just read.
 */
@Composable
fun KeyFigure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    colour: Color = MaterialTheme.colorScheme.onSurface,
    /** Roll the digits in from zero and draw the total rule, once (B87). */
    introduce: Boolean = false,
) {
    Column(
        modifier = modifier.semantics { contentDescription = "$label: $value" },
        verticalArrangement = Arrangement.spacedBy(Measure.tight),
    ) {
        // The figure a screen is about is a total, so it is double-ruled the way a bookkeeper
        // closes a column (B87), and its digits roll when it changes.
        val rule = BeancountTheme.ledger.rule
        val ruleDrawn = rememberRuleProgress(drawIn = introduce)
        RollingFigure(
            text = value,
            style = if (emphasis) LedgerType.display else LedgerType.figure.copy(
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            ),
            color = colour,
            rollInFrom = if (introduce) zeroed(value) else null,
            modifier = if (emphasis) Modifier.totalRule(rule, ruleDrawn) else Modifier,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How loudly a notice speaks. */
enum class NoticeTone { QUIET, ATTENTION, PROBLEM }

/**
 * The app interrupting the page.
 *
 * Everything that is the *app* talking rather than the *ledger* showing goes through here: a
 * recovery report, a trial state, an unreconciled warning, a write that was refused. They are
 * rounded and tinted, where data is square and on paper, so the difference is legible before a
 * word is read.
 */
@Composable
fun Notice(
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val container = when (tone) {
        NoticeTone.QUIET -> MaterialTheme.colorScheme.surfaceContainerHigh
        NoticeTone.ATTENTION -> MaterialTheme.colorScheme.tertiaryContainer
        NoticeTone.PROBLEM -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = when (tone) {
        NoticeTone.QUIET -> MaterialTheme.colorScheme.onSurface
        NoticeTone.ATTENTION -> MaterialTheme.colorScheme.onTertiaryContainer
        NoticeTone.PROBLEM -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = BeanbookShape.notice,
        color = container,
        contentColor = onContainer,
    ) {
        Column(
            modifier = Modifier.padding(Measure.indent),
            verticalArrangement = Arrangement.spacedBy(Measure.snug),
            content = content,
        )
    }
}

/** The receiver [Notice] hands its content, aliased so callers need no extra import. */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/**
 * Nothing here yet, and what to do about it.
 *
 * An empty surface is an instruction, not an apology: the heading says what is missing and the
 * line under it says the one action that fills it. Drawn on the page rather than in a card,
 * with the rule beside it, so an empty screen still looks like part of the app.
 */
@Composable
fun EmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    /** A drawing of what is missing, above the words (B87). */
    art: EmptyArt? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Measure.chapter),
        verticalArrangement = Arrangement.spacedBy(Measure.snug),
    ) {
        art?.let {
            EmptyIllustration(it)
            Spacer(Modifier.height(Measure.gap))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.let {
            Spacer(Modifier.height(Measure.tight))
            it()
        }
    }
}

/** The hairline between two data rows. Its colour is the one thing the whole page shares. */
@Composable
fun LedgerHairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = Measure.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * A tile that holds a figure inside a band or a group of figures.
 *
 * Square-cornered on purpose: it is data, and the shape rule says data has no corners. What
 * separates it from the page is a tint one step off the ground, not a radius.
 */
@Composable
fun FigureTile(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(BeanbookShape.data)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .heightIn(min = Measure.rowMinHeight)
            .padding(horizontal = Measure.indent, vertical = Measure.gap),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Measure.tight), content = content)
    }
}
