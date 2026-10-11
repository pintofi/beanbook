package io.github.pintofi.beanbook.app.ui

import androidx.compose.ui.graphics.compositeOver
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
        // The Expressive emphasized title: one weight heavier than the prose title, so a
        // section's name holds its own against the figures under it without growing.
        Text(
            text,
            style = MaterialTheme.typography.titleSmallEmphasized,
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
 * Colour reinforces and never carries: the shapes differ, and the description names the
 * state in words.
 *
 * **Any other flag is its own mark (FLAGGED).** Beancount allows a letter -- `P` for a
 * transaction a price plugin wrote, `S`, `T`, whatever a file uses -- and the app drew every one
 * of them as cleared, and the entry page said "Cleared" (device audit 2026-10-10). It is a small
 * pennant on a staff: something marked, which is all the app can honestly say about a letter it
 * does not interpret. The letter itself is spoken and, on the entry page, printed.
 */
enum class LedgerMark { CLEARED, PENDING, FLAGGED, ATTENTION }

/**
 * What an entry with no payee and no narration is called, everywhere it is named: the Journal
 * row, Home, the entry page and the delete confirmation. The list showed a blank line where the
 * entry page said this (device audit 2026-10-10).
 */
const val NO_DESCRIPTION = "(no description)"

/** The mark for an entry's flag: `*` cleared, `!` pending, any other character flagged. */
fun markForFlag(flag: String?): LedgerMark = when (flag) {
    "!" -> LedgerMark.PENDING
    null, "", "*", "txn" -> LedgerMark.CLEARED
    else -> LedgerMark.FLAGGED
}

/** What an entry's flag means, in words: "Cleared", "Pending", or "Flagged P". */
fun describeFlag(flag: String?): String = when (markForFlag(flag)) {
    LedgerMark.PENDING -> "Pending"
    LedgerMark.FLAGGED -> "Flagged $flag"
    else -> "Cleared"
}

@Composable
fun StampMark(
    mark: LedgerMark,
    modifier: Modifier = Modifier,
    /** The file's own flag, so a FLAGGED mark can say which one. */
    flag: String? = null,
    /** True where the control it sits in already says the state in words. */
    decorative: Boolean = false,
) {
    val ledger = BeancountTheme.ledger
    val colour = when (mark) {
        LedgerMark.CLEARED -> ledger.rule
        LedgerMark.PENDING -> ledger.pending
        LedgerMark.FLAGGED -> ledger.flagged
        LedgerMark.ATTENTION -> MaterialTheme.colorScheme.error
    }
    val described = when (mark) {
        LedgerMark.CLEARED -> "Cleared"
        LedgerMark.PENDING -> "Pending"
        LedgerMark.FLAGGED -> if (flag.isNullOrBlank()) "Flagged" else "Flagged $flag"
        LedgerMark.ATTENTION -> "Needs attention"
    }
    Canvas(
        modifier = modifier
            .size(width = Measure.stamp, height = Measure.stamp)
            .then(
                if (decorative) {
                    Modifier.clearAndSetSemantics { }
                } else {
                    Modifier.semantics { contentDescription = described }
                },
            ),
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
            // A pennant on a staff: marked, for a reason the file knows.
            LedgerMark.FLAGGED -> {
                bar(top = 0.16f, width = thickness * 1.2f, height = size.height * 0.68f)
                bar(top = 0.16f, width = full * 0.78f, height = size.height * 0.3f)
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
    val scheme = MaterialTheme.colorScheme
    val container = when (tone) {
        NoticeTone.QUIET -> scheme.surfaceContainerHigh
        // A tint of the accent on the band, not the accent's container (B98): a wallpaper
        // palette on Android 16+ can hand back a light tertiary container in dark mode, and a
        // pale slab of body text in a dark app read as a different app. A tint keeps the
        // notice in the page's own lightness in every theme, with ink that always reads.
        NoticeTone.ATTENTION -> scheme.tertiary.copy(alpha = ATTENTION_TINT)
            .compositeOver(scheme.surfaceContainerLow)
        NoticeTone.PROBLEM -> scheme.errorContainer
    }
    val onContainer = when (tone) {
        NoticeTone.QUIET -> scheme.onSurface
        NoticeTone.ATTENTION -> scheme.onSurface
        NoticeTone.PROBLEM -> scheme.onErrorContainer
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

/** How much of the accent an attention notice carries over the band. */
private const val ATTENTION_TINT = 0.16f

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
    // Centred, art and words on one axis (design-direction §7). Left-aligned inside a block that
    // was itself centred, it read as accidentally off-centre (device audit 2026-10-10).
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Measure.chapter),
        verticalArrangement = Arrangement.spacedBy(Measure.snug),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        art?.let {
            EmptyIllustration(it)
            Spacer(Modifier.height(Measure.gap))
        }
        // Emphasized: the heading is the instruction, and the line under it the detail.
        Text(
            title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
