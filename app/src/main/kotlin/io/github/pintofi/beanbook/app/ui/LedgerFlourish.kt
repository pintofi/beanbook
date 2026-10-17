package io.github.pintofi.beanbook.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled
import io.github.pintofi.beanbook.app.ui.theme.Motion

/*
 * The few moments that are allowed to be loud.
 *
 * The app's everyday surfaces stay calm: a screen of figures is read, not watched. These are
 * the exceptions, each tied to something that is true about the ledger rather than to a screen
 * appearing -- the double rule a bookkeeper draws under a total, figures that roll when they
 * change, the stamp for a ledger that is in balance again, and a drawing where a screen has
 * nothing to show yet. With animation turned off, each is drawn in its finished state.
 */

/**
 * The double rule under a total, the way it is written in a paper ledger.
 *
 * Drawn under whatever it is applied to, inside that element's bounds (it adds its own room
 * below), so the rule is exactly as wide as the figures it totals. [progress] is how much of
 * the rule is drawn, from the start edge; 1 is the whole rule.
 */
fun Modifier.totalRule(colour: Color, progress: () -> Float = { 1f }): Modifier =
    this
        .drawBehind {
            val drawn = size.width * progress().coerceIn(0f, 1f)
            if (drawn <= 0f) return@drawBehind
            val thickness = TOTAL_RULE_THICKNESS.dp.toPx()
            val bottom = size.height
            drawRect(colour, Offset(0f, bottom - thickness), Size(drawn, thickness))
            drawRect(
                colour,
                Offset(0f, bottom - thickness * 2 - TOTAL_RULE_GAP.dp.toPx()),
                Size(drawn, thickness),
            )
        }
        .padding(bottom = (TOTAL_RULE_THICKNESS * 2 + TOTAL_RULE_GAP + TOTAL_RULE_CLEARANCE).dp)

/**
 * How much of a total rule to draw: all of it, or drawn in once when [drawIn] is set and
 * animation is on. Read through the returned lambda so drawing it never recomposes anything.
 */
@Composable
fun rememberRuleProgress(drawIn: Boolean): () -> Float {
    val animate = drawIn && LocalMotionEnabled.current
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(Motion.RULE_MS * 2, easing = Motion.enter))
        }
    }
    return { progress.value }
}

/**
 * A figure whose digits roll to their new value, one column at a time.
 *
 * The figure face is monospaced, so every character keeps its column and a changed digit can
 * move vertically without the rest of the figure shifting. Columns are counted from the end,
 * where the units are, so a figure that gains a digit rolls the new one in on the left rather
 * than sliding every other digit along.
 *
 * [rollInFrom] is what the figure first shows before rolling to [text] -- the opening
 * flourish -- or null to show [text] straight away. It is read for accessibility as [text]
 * only: a screen reader hears the figure, never its individual characters.
 */
@Composable
fun RollingFigure(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    rollInFrom: String? = null,
) {
    // A figure in several currencies is one line each. Rolled character by character the line
    // break drew as nothing, and "123,456.78 PKR" ran into "2,000.00 SAR".
    if ('\n' in text) {
        Text(text, style = style, color = color, modifier = modifier)
        return
    }
    // A still figure fits its space rather than being cut at its edge:
    // `maxLines = 1` alone clipped "2,345,678.90 PKR" to "2,345,678." at 200% text.
    val align = style.textAlign.takeIf { it != androidx.compose.ui.text.style.TextAlign.Unspecified }
        ?: androidx.compose.ui.text.style.TextAlign.Start
    if (!LocalMotionEnabled.current) {
        FitFigure(text, style = style, color = color, textAlign = align, modifier = modifier)
        return
    }
    // A column of characters cannot shrink the way a line of text does, so a figure wider than
    // its space -- a narrow tile, a large font -- is shown as a fitted figure and loses nothing
    // but the roll.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val needed = remember(text, style) { measurer.measure(text, style, maxLines = 1).size.width }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        if (constraints.hasBoundedWidth && needed > constraints.maxWidth) {
            FitFigure(text, style = style, color = color, textAlign = align)
        } else {
            RollingColumns(text, style, color, rollInFrom)
        }
    }
}

@Composable
private fun RollingColumns(text: String, style: TextStyle, color: Color, rollInFrom: String?) {
    val shown = remember { androidx.compose.runtime.mutableStateOf(rollInFrom ?: text) }
    LaunchedEffect(text) { shown.value = text }
    val current = shown.value
    Row(Modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        current.forEachIndexed { index, character ->
            key(current.length - index) {
                AnimatedContent(
                    targetState = character,
                    transitionSpec = {
                        if (initialState.isDigit() && targetState.isDigit()) {
                            val rising = targetState > initialState
                            val roll = tween<androidx.compose.ui.unit.IntOffset>(ROLL_MS, easing = Motion.enter)
                            (slideInVertically(roll) { if (rising) it else -it } + fadeIn(tween(ROLL_MS))) togetherWith
                                (slideOutVertically(roll) { if (rising) -it else it } + fadeOut(tween(ROLL_MS)))
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    modifier = Modifier.clipToBounds(),
                    label = "digit",
                ) { digit ->
                    Text(digit.toString(), style = style, color = color, maxLines = 1)
                }
            }
        }
    }
}

/** The same figure with every digit written as 0: where a figure rolls in from. */
fun zeroed(figure: String): String = figure.map { if (it.isDigit()) '0' else it }.joinToString("")

/**
 * The stamp for a ledger that is in balance again.
 *
 * Shown on Home only when the ledger went from having errors to having none while the app was
 * open -- the moment somebody fixes the last error -- because that is the one time the app has
 * something true to celebrate that the save sweep has not already marked. It lands like a rubber
 * stamp: a little large and turned, then pressed flat, and it stays for as long as Home is on
 * screen. Said in words to accessibility services, as a polite live region, when it appears.
 *
 * "In balance" rather than "Balances": Balances is a tab in this app, and a stamp with a tab's
 * name on it would read as a link to it.
 */
@Composable
fun InBalanceStamp(modifier: Modifier = Modifier, size: Dp = STAMP_SIZE, land: Boolean = true) {
    val animate = land && LocalMotionEnabled.current
    val press = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (press.value < 1f) {
            press.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }
    val ink = BeancountTheme.ledger.rule
    Box(
        modifier = modifier
            .size(size)
            .semantics(mergeDescendants = true) {
                contentDescription = "In balance. The ledger loads with no errors again."
                liveRegion = LiveRegionMode.Polite
            }
            .graphicsLayer {
                val p = press.value
                val scale = STAMP_START_SCALE - (STAMP_START_SCALE - 1f) * p
                scaleX = scale
                scaleY = scale
                alpha = p.coerceIn(0f, 1f)
                rotationZ = STAMP_TILT + STAMP_START_TURN * (1f - p)
            },
        contentAlignment = Alignment.Center,
    ) {
        // The stamp alone: it says what it means. The words are for accessibility services.
        val scale = size / STAMP_SIZE
        Canvas(Modifier.size(size)) { stampRings(ink, scale) }
        Text(
            "In balance",
            color = ink,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = (STAMP_TEXT_SP * scale).sp,
            ),
        )
    }
}

private fun DrawScope.stampRings(ink: Color, scale: Float) {
    val outer = size.minDimension / 2 - 2.dp.toPx()
    drawCircle(ink, radius = outer, style = Stroke(width = 3.dp.toPx() * scale))
    drawCircle(ink, radius = outer - 6.dp.toPx() * scale, style = Stroke(width = 1.dp.toPx()))
    // The two rules of a balance, across the middle of the stamp, under the words.
    val half = outer * 0.62f
    val cy = center.y + 11.dp.toPx() * scale
    listOf(0f, 4.dp.toPx() * scale).forEach { dy ->
        drawLine(ink, Offset(center.x - half, cy + dy), Offset(center.x + half, cy + dy), strokeWidth = 1.5.dp.toPx())
    }
}

/** What an empty screen is missing, as a drawing. */
enum class EmptyArt {
    /** A ruled ledger sheet with nothing written on it: no ledger, no entries. */
    SHEET,

    /** A receipt with crop corners at its edges: no documents. */
    RECEIPT,

    /** A ruled sheet under a lens: nothing matches a search. */
    SEARCH,
}

/**
 * The drawing above an empty state. Decorative: the heading and line under it say
 * everything, so this carries no semantics. Drawn in the rule colour and the outline tone, so
 * it belongs to the page in every theme, including wallpaper colours.
 */
@Composable
fun EmptyIllustration(art: EmptyArt, modifier: Modifier = Modifier, height: Dp = EMPTY_ART_HEIGHT) {
    val rule = BeancountTheme.ledger.rule
    val line = MaterialTheme.colorScheme.outline
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    val pending = BeancountTheme.ledger.pending
    Canvas(modifier.size(width = height * EMPTY_ART_ASPECT, height = height)) {
        when (art) {
            EmptyArt.SHEET -> sheet(rule, line, paper, pending, lens = false)
            EmptyArt.SEARCH -> sheet(rule, line, paper, pending, lens = true)
            EmptyArt.RECEIPT -> receipt(rule, line, paper)
        }
    }
}

private fun DrawScope.sheet(rule: Color, line: Color, paper: Color, pending: Color, lens: Boolean) {
    val w = size.height * 0.72f
    val h = size.height * 0.9f
    val left = (size.width - w) / 2
    val top = size.height * 0.05f
    val stroke = 1.5.dp.toPx()
    rotate(-4f, Offset(left + w / 2, top + h / 2)) {
        drawRect(paper, Offset(left, top), Size(w, h))
        drawRect(line, Offset(left, top), Size(w, h), style = Stroke(stroke))
        // The teal band across the top of a surface, and the margin rule down the side.
        drawRect(rule, Offset(left, top), Size(w, 4.dp.toPx()))
        drawLine(rule, Offset(left + w * 0.18f, top), Offset(left + w * 0.18f, top + h), strokeWidth = stroke)
        val rows = 7
        for (i in 1..rows) {
            val y = top + h * (0.12f + i * 0.1f)
            drawLine(
                line.copy(alpha = 0.45f),
                Offset(left + stroke, y),
                Offset(left + w - stroke, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (!lens) {
            // An empty total, double-ruled, waiting for figures -- and the dashed line of an
            // entry not yet written, in the colour the app keeps for unfinished things.
            val y = top + h * 0.9f
            drawLine(rule, Offset(left + w * 0.55f, y), Offset(left + w * 0.9f, y), strokeWidth = stroke)
            drawLine(
                rule,
                Offset(left + w * 0.55f, y + 3.dp.toPx()),
                Offset(left + w * 0.9f, y + 3.dp.toPx()),
                strokeWidth = stroke,
            )
            val entry = top + h * 0.32f
            drawLine(
                pending,
                Offset(left + w * 0.26f, entry),
                Offset(left + w * 0.8f, entry),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
    }
    if (lens) {
        val c = Offset(left + w * 0.78f, top + h * 0.62f)
        val r = size.height * 0.2f
        drawCircle(paper.copy(alpha = 0.6f), radius = r, center = c)
        drawCircle(rule, radius = r, center = c, style = Stroke(3.dp.toPx()))
        drawLine(
            rule,
            Offset(c.x + r * 0.7f, c.y + r * 0.7f),
            Offset(c.x + r * 1.5f, c.y + r * 1.5f),
            strokeWidth = 5.dp.toPx(),
        )
    }
}

private fun DrawScope.receipt(rule: Color, line: Color, paper: Color) {
    val w = size.height * 0.62f
    val h = size.height * 0.84f
    val left = (size.width - w) / 2
    val top = size.height * 0.08f
    val stroke = 1.5.dp.toPx()
    val teeth = 8
    val tooth = w / teeth
    val outline = androidx.compose.ui.graphics.Path().apply {
        moveTo(left, top)
        lineTo(left + w, top)
        lineTo(left + w, top + h)
        for (i in teeth downTo 1) {
            lineTo(left + tooth * (i - 0.5f), top + h - tooth * 0.5f)
            lineTo(left + tooth * (i - 1), top + h)
        }
        close()
    }
    rotate(3f, Offset(left + w / 2, top + h / 2)) {
        drawPath(outline, paper)
        drawPath(outline, line, style = Stroke(stroke))
        for (i in 0 until 5) {
            val y = top + h * (0.18f + i * 0.12f)
            val end = if (i == 4) 0.5f else if (i % 2 == 0) 0.85f else 0.7f
            drawLine(
                line.copy(alpha = 0.55f),
                Offset(left + w * 0.14f, y),
                Offset(left + w * end, y),
                strokeWidth = 2.dp.toPx(),
            )
        }
        val y = top + h * 0.8f
        drawLine(rule, Offset(left + w * 0.5f, y), Offset(left + w * 0.86f, y), strokeWidth = stroke)
        drawLine(
            rule,
            Offset(left + w * 0.5f, y + 3.dp.toPx()),
            Offset(left + w * 0.86f, y + 3.dp.toPx()),
            strokeWidth = stroke,
        )
    }
    // The scanner's corner brackets, just outside the receipt.
    val pad = 8.dp.toPx()
    val arm = w * 0.2f
    val thick = 3.dp.toPx()
    listOf(
        Offset(left - pad, top - pad) to Offset(1f, 1f),
        Offset(left + w + pad, top - pad) to Offset(-1f, 1f),
        Offset(left + w + pad, top + h + pad) to Offset(-1f, -1f),
        Offset(left - pad, top + h + pad) to Offset(1f, -1f),
    ).forEach { (corner, direction) -> cornerBracket(rule, corner, direction, arm, thick) }
}

/**
 * One L-shaped corner bracket: [corner] is its point, and [direction] the signs along which
 * its two arms run. Shared with the scanner and the crop editor, so all three draw the same
 * mark for "the edge of the page is here".
 */
fun DrawScope.cornerBracket(colour: Color, corner: Offset, direction: Offset, arm: Float, thickness: Float) {
    drawLine(
        colour,
        corner,
        Offset(corner.x + arm * direction.x, corner.y),
        strokeWidth = thickness,
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
    drawLine(
        colour,
        corner,
        Offset(corner.x, corner.y + arm * direction.y),
        strokeWidth = thickness,
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
}

/**
 * Corner brackets on a page outline whose corners are [points], in order round the page.
 * Each bracket's arms run along the page's own two edges, so a page photographed at an angle
 * gets brackets at that angle rather than square ones floating off its corners. A darker edge
 * is drawn under each, so the mark reads against a white receipt as well as a dark table.
 * [colour] must be opaque: a see-through colour shows the darker edge through its middle.
 */
fun DrawScope.pageBrackets(colour: Color, points: List<Offset>, arm: Float, thickness: Float) {
    if (points.size != 4) return
    points.forEachIndexed { index, corner ->
        val previous = points[(index + 3) % 4]
        val next = points[(index + 1) % 4]
        listOf(previous, next).forEach { toward ->
            val dx = toward.x - corner.x
            val dy = toward.y - corner.y
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            if (length <= 0f) return@forEach
            val reach = minOf(arm, length * BRACKET_MAX_SHARE) / length
            val end = Offset(corner.x + dx * reach, corner.y + dy * reach)
            val round = androidx.compose.ui.graphics.StrokeCap.Round
            drawLine(
                Color.Black.copy(alpha = BRACKET_SHADOW),
                corner,
                end,
                strokeWidth = thickness + BRACKET_EDGE.dp.toPx(),
                cap = round,
            )
            drawLine(colour, corner, end, strokeWidth = thickness, cap = round)
        }
    }
}

private const val BRACKET_MAX_SHARE = 0.35f
private const val BRACKET_SHADOW = 0.25f
private const val BRACKET_EDGE = 2f
private const val TOTAL_RULE_THICKNESS = 1.5f
private const val TOTAL_RULE_GAP = 2f
private const val TOTAL_RULE_CLEARANCE = 4f
private const val ROLL_MS = 420
private val STAMP_SIZE = 92.dp
private const val STAMP_TEXT_SP = 13f
private const val STAMP_START_SCALE = 1.6f
private const val STAMP_TILT = -8f
private const val STAMP_START_TURN = -14f
private val EMPTY_ART_HEIGHT = 120.dp
private const val EMPTY_ART_ASPECT = 1.4f
