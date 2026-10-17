package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's mark -- two brackets facing across a pair of rules (docs/icon.md) -- drawn in the
 * theme's own colours.
 *
 * The About screen and the welcome page used to show the launcher icon's two layers, which are
 * fixed teal: on a phone whose wallpaper made the app terracotta, the one place the app named
 * itself was in a different palette from everything around it (device audit 2026-10-10). This
 * is the same geometry, taken from `ic_launcher_foreground.xml` unaltered, filled with the
 * primary and inked with the colours that sit on it -- teal in the app's own palette, and
 * whatever the wallpaper gives when dynamic colour is on. The launcher icon itself is Android's
 * to theme and is not drawn here.
 *
 * Decorative: wherever it appears, the app's name is written beside it.
 */
@Composable
fun BeanbookMark(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val disc = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onPrimary
    val second = MaterialTheme.colorScheme.inversePrimary
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        drawCircle(disc)
        markStrokes(ink, second)
    }
}

/** The source viewport is 512 units, scaled by 2/3 about its centre, seen through the inner 72/108. */
private fun DrawScope.markStrokes(ink: Color, second: Color) {
    val visible = SOURCE * SAFE_FRACTION
    val origin = SOURCE / 2f - visible / 2f
    val unit = size.minDimension / visible
    fun x(p: Float) = (CENTRE + (p - CENTRE) * SCALE - origin) * unit
    fun stroke(width: Float) = Stroke(width = width * SCALE * unit, cap = StrokeCap.Round)

    fun bracket(outer: Float, inner: Float, corner: Float): Path = Path().apply {
        moveTo(x(inner), x(TOP))
        lineTo(x(corner), x(TOP))
        quadraticTo(x(outer), x(TOP), x(outer), x(TOP + CURVE))
        lineTo(x(outer), x(BOTTOM - CURVE))
        quadraticTo(x(outer), x(BOTTOM), x(corner), x(BOTTOM))
        lineTo(x(inner), x(BOTTOM))
    }

    drawPath(bracket(outer = 126f, inner = 211f, corner = 148f), ink, style = stroke(BRACKET_WIDTH))
    drawPath(bracket(outer = 386f, inner = 301f, corner = 364f), second, style = stroke(BRACKET_WIDTH))
    drawLine(ink, offset(x(213f), x(235f)), offset(x(299f), x(235f)), RULE_WIDTH * SCALE * unit, StrokeCap.Round)
    drawLine(second, offset(x(213f), x(285f)), offset(x(299f), x(285f)), RULE_WIDTH * SCALE * unit, StrokeCap.Round)
}

private fun offset(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)

private const val SOURCE = 512f
private const val CENTRE = 256f
private const val SCALE = 2f / 3f
private const val SAFE_FRACTION = 72f / 108f
private const val TOP = 150f
private const val BOTTOM = 362f
private const val CURVE = 22f
private const val BRACKET_WIDTH = 27f
private const val RULE_WIDTH = 22f
