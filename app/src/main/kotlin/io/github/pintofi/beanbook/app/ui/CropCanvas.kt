package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.CropCorner
import io.github.pintofi.beanbook.core.domain.CropEdge
import io.github.pintofi.beanbook.core.domain.CropEditor
import io.github.pintofi.beanbook.core.domain.CropHandle
import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import io.github.pintofi.beanbook.core.domain.at
import io.github.pintofi.beanbook.core.domain.corners
import io.github.pintofi.beanbook.core.domain.edgeHandles
import io.github.pintofi.beanbook.core.domain.midpoint
import io.github.pintofi.beanbook.core.domain.shownAs
import io.github.pintofi.beanbook.core.domain.turned
import io.github.pintofi.beanbook.core.domain.unturned
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * How the photograph sits in the canvas: where its top-left corner is once it has been turned
 * [rotation] degrees, and how many screen pixels one photograph pixel takes. All the crop's
 * geometry stays in the photograph's own pixels; this is the only place that knows the screen.
 */
internal class CropLayout(
    val origin: Offset,
    val scale: Float,
    val rotation: Int,
    val width: Float,
    val height: Float,
) {
    val shownWidth: Float get() = if (rotation % 180 == 0) width else height
    val shownHeight: Float get() = if (rotation % 180 == 0) height else width

    fun toScreen(point: CropPoint): Offset {
        val turned = point.turned(rotation, width, height)
        return Offset(origin.x + turned.x * scale, origin.y + turned.y * scale)
    }

    fun toImage(screen: Offset): CropPoint =
        CropPoint((screen.x - origin.x) / scale, (screen.y - origin.y) / scale).unturned(rotation, width, height)

    /** The same picture [factor] times larger, with the screen point [about] moved to [onto]. */
    fun magnified(about: Offset, onto: Offset, factor: Float) = CropLayout(
        origin = Offset(onto.x - (about.x - origin.x) * factor, onto.y - (about.y - origin.y) * factor),
        scale = scale * factor,
        rotation = rotation,
        width = width,
        height = height,
    )

    companion object {
        fun fit(canvas: Size, width: Float, height: Float, rotation: Int, padding: Float): CropLayout {
            val shownW = if (rotation % 180 == 0) width else height
            val shownH = if (rotation % 180 == 0) height else width
            val availableW = (canvas.width - 2 * padding).coerceAtLeast(1f)
            val availableH = (canvas.height - 2 * padding).coerceAtLeast(1f)
            val scale = minOf(availableW / shownW, availableH / shownH)
            val origin = Offset(
                (canvas.width - shownW * scale) / 2f,
                (canvas.height - shownH * scale) / 2f,
            )
            return CropLayout(origin, scale, rotation, width, height)
        }
    }
}

/**
 * The page with its crop: handles at the corners and the middles of the longer sides, a loupe
 * while one is held, and the same handles as invisible, focusable nodes for TalkBack and keys.
 *
 * A press that lands on no handle starts nothing: the old behaviour of picking the nearest corner
 * from anywhere meant a finger resting on the page moved a corner.
 */
@Composable
internal fun CropCanvas(
    image: ImageBitmap,
    editor: CropEditor,
    onEditor: (CropEditor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val paddingPx = with(density) { CANVAS_PADDING.dp.toPx() }
    val touchRadiusPx = with(density) { TOUCH_RADIUS.dp.toPx() }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val current by rememberUpdatedState(editor)
    val currentOnEditor by rememberUpdatedState(onEditor)
    val layout = CropLayout.fit(canvasSize, editor.width, editor.height, editor.rotation, paddingPx)

    val haptic = LocalHapticFeedback.current
    LaunchedEffect(editor.limited) {
        if (editor.limited) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(modifier = modifier.onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "The page. Drag a corner, or the middle of a side, to move it."
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = CropLayout.fit(
                            Size(size.width.toFloat(), size.height.toFloat()),
                            current.width, current.height, current.rotation, paddingPx,
                        )
                        var working = current.press(start.toImage(down.position), touchRadiusPx / start.scale)
                        if (working.holding == null) return@awaitEachGesture
                        down.consume()
                        finger = down.position
                        currentOnEditor(working)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            finger = change.position
                            working = working.drag(start.toImage(change.position))
                            currentOnEditor(working)
                            change.consume()
                        }
                        currentOnEditor(working.release())
                        finger = null
                    }
                },
        ) {
            drawCrop(image, editor, layout, finger, touchRadiusPx)
        }
        if (canvasSize != Size.Zero) HandleNodes(editor, layout, onEditor, touchRadiusPx)
    }
}

private fun DrawScope.drawTurnedImage(image: ImageBitmap, layout: CropLayout) {
    val w = layout.width * layout.scale
    val h = layout.height * layout.scale
    val centreX = layout.origin.x + layout.shownWidth * layout.scale / 2f
    val centreY = layout.origin.y + layout.shownHeight * layout.scale / 2f
    translate(centreX, centreY) {
        rotate(layout.rotation.toFloat(), Offset.Zero) {
            drawImage(
                image,
                dstOffset = IntOffset((-w / 2f).roundToInt(), (-h / 2f).roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            )
        }
    }
}

private fun DrawScope.drawCrop(
    image: ImageBitmap,
    editor: CropEditor,
    layout: CropLayout,
    finger: Offset?,
    touchRadiusPx: Float,
) {
    drawTurnedImage(image, layout)
    val points = editor.quad.corners().map { layout.toScreen(it) }
    val outline = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    // What will be cut away is dimmed, so the page you are keeping stands out.
    val outside = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(0f, 0f, size.width, size.height))
        addPath(outline)
    }
    drawPath(outside, Color.Black.copy(alpha = CROP_DIM))
    val ink = if (editor.limited) CROP_ERROR else CROP_INK
    drawPath(outline, ink.copy(alpha = 0.8f), style = Stroke(width = 1.5.dp.toPx()))
    // Bracketed corners, as the scanner draws them, with a dot on the exact point.
    pageBrackets(ink, points, arm = CROP_BRACKET_ARM.dp.toPx(), thickness = 6.dp.toPx())
    val radiusInImage = touchRadiusPx / layout.scale
    editor.quad.edgeHandles(radiusInImage).forEach { edge ->
        val from = layout.toScreen(editor.quad.at(edge.start))
        val to = layout.toScreen(editor.quad.at(edge.end))
        val middle = layout.toScreen(editor.quad.midpoint(edge))
        val angle = Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())).toFloat()
        val held = (editor.holding as? CropHandle.Edge)?.edge == edge
        val grow = if (held) HELD_GROWTH else 1f
        rotate(angle, middle) {
            val pill = Size(EDGE_PILL_LENGTH.dp.toPx() * grow, EDGE_PILL_THICKNESS.dp.toPx() * grow)
            val corner = CornerRadius(pill.height / 2f)
            drawRoundRect(
                Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(middle.x - pill.width / 2f - 1.dp.toPx(), middle.y - pill.height / 2f - 1.dp.toPx()),
                size = Size(pill.width + 2.dp.toPx(), pill.height + 2.dp.toPx()),
                cornerRadius = corner,
            )
            drawRoundRect(
                if (held) ink else Color.White,
                topLeft = Offset(middle.x - pill.width / 2f, middle.y - pill.height / 2f),
                size = pill,
                cornerRadius = corner,
            )
        }
    }
    CropCorner.entries.forEachIndexed { index, corner ->
        val point = points[index]
        val held = (editor.holding as? CropHandle.Corner)?.corner == corner
        val grow = if (held) HELD_GROWTH else 1f
        drawCircle(Color.Black.copy(alpha = 0.5f), radius = 7.dp.toPx() * grow, center = point)
        drawCircle(if (held) ink else Color.White, radius = 5.dp.toPx() * grow, center = point)
    }
    val held = editor.holding
    if (held != null && finger != null) drawLoupe(image, editor, layout, finger, held, ink)
}

/** A round window above the finger showing the held handle three times larger, with its two sides. */
private fun DrawScope.drawLoupe(
    image: ImageBitmap,
    editor: CropEditor,
    layout: CropLayout,
    finger: Offset,
    held: CropHandle,
    ink: Color,
) {
    val diameter = minOf(LOUPE_DIAMETER.dp.toPx(), minOf(size.width, size.height) / 2.5f)
    val centre = loupeCentre(finger, size, diameter, LOUPE_GAP.dp.toPx())
    val heldPoint = when (held) {
        is CropHandle.Corner -> editor.quad.at(held.corner)
        is CropHandle.Edge -> editor.quad.midpoint(held.edge)
    }
    val magnified = layout.magnified(layout.toScreen(heldPoint), centre, LOUPE_MAGNIFICATION)
    val window = Path().apply { addOval(Rect(centre, diameter / 2f)) }
    clipPath(window) {
        drawRect(
            Color.Black,
            topLeft = Offset(centre.x - diameter / 2f, centre.y - diameter / 2f),
            size = Size(diameter, diameter),
        )
        drawTurnedImage(image, magnified)
        val points = editor.quad.corners().map { magnified.toScreen(it) }
        val outline = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(outline, ink, style = Stroke(width = 1.5.dp.toPx()))
        val arm = diameter / 2f
        val hair = 1.dp.toPx()
        drawLine(Color.White, Offset(centre.x - arm, centre.y), Offset(centre.x + arm, centre.y), strokeWidth = hair)
        drawLine(Color.White, Offset(centre.x, centre.y - arm), Offset(centre.x, centre.y + arm), strokeWidth = hair)
    }
    drawCircle(
        Color.Black.copy(alpha = 0.4f),
        radius = diameter / 2f + 2.dp.toPx(),
        center = centre,
        style = Stroke(4.dp.toPx()),
    )
    drawCircle(Color.White, radius = diameter / 2f, center = centre, style = Stroke(2.dp.toPx()))
}

/**
 * Each visible handle again as an invisible, focusable node for TalkBack and a keyboard: dragging
 * is not available to either. A step is 1% of the photograph's shorter side, through
 * [CropEditor.nudge], so the same limits apply.
 */
@Composable
private fun HandleNodes(
    editor: CropEditor,
    layout: CropLayout,
    onEditor: (CropEditor) -> Unit,
    touchRadiusPx: Float,
) {
    val half = with(LocalDensity.current) { 24.dp.toPx() }
    val step = 0.01f * minOf(editor.width, editor.height)
    val handles: List<Pair<CropHandle, CropPoint>> =
        CropCorner.entries.map { CropHandle.Corner(it) to editor.quad.at(it) } +
            editor.quad.edgeHandles(touchRadiusPx / layout.scale)
                .map { CropHandle.Edge(it) to editor.quad.midpoint(it) }

    handles.forEach { (handle, point) ->
        val screen = layout.toScreen(point)
        val shown = point.turned(editor.rotation, editor.width, editor.height)
        val fromLeft = (shown.x / layout.shownWidth * 100).roundToInt()
        val fromTop = (shown.y / layout.shownHeight * 100).roundToInt()
        // A screen direction as a direction in the photograph, since the picture may be turned.
        fun nudge(sx: Float, sy: Float, multiple: Int = 1): CropEditor {
            val zero = CropPoint(0f, 0f).unturned(editor.rotation, editor.width, editor.height)
            val moved = CropPoint(sx, sy).unturned(editor.rotation, editor.width, editor.height)
            return editor.nudge(handle, (moved.x - zero.x) * step * multiple, (moved.y - zero.y) * step * multiple)
        }
        fun nudgeAcross(sign: Float): CropEditor {
            val edge = (handle as CropHandle.Edge).edge
            val a = editor.quad.at(edge.start)
            val b = editor.quad.at(edge.end)
            val length = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1f)
            // Clockwise order puts the inside of every edge on its left: (-dy, dx).
            return editor.nudge(handle, -(b.y - a.y) / length * step * sign, (b.x - a.x) / length * step * sign)
        }
        val actions = when (handle) {
            is CropHandle.Corner -> listOf(
                CustomAccessibilityAction("Move left") { onEditor(nudge(-1f, 0f)); true },
                CustomAccessibilityAction("Move right") { onEditor(nudge(1f, 0f)); true },
                CustomAccessibilityAction("Move up") { onEditor(nudge(0f, -1f)); true },
                CustomAccessibilityAction("Move down") { onEditor(nudge(0f, 1f)); true },
            )
            is CropHandle.Edge -> listOf(
                CustomAccessibilityAction("Move in") { onEditor(nudgeAcross(1f)); true },
                CustomAccessibilityAction("Move out") { onEditor(nudgeAcross(-1f)); true },
            )
        }
        val name = when (handle) {
            is CropHandle.Corner -> handle.corner.shownAs(editor.rotation).spoken() + " corner"
            is CropHandle.Edge -> handle.edge.shownAs(editor.rotation).spoken() + " side"
        }
        Box(
            Modifier
                .offset { IntOffset((screen.x - half).roundToInt(), (screen.y - half).roundToInt()) }
                .size(48.dp)
                .semantics {
                    contentDescription = name
                    stateDescription = "$fromLeft percent from the left, $fromTop percent from the top"
                    customActions = actions
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val times = if (event.isShiftPressed) 5 else 1
                    val moved = when (event.key) {
                        Key.DirectionLeft -> nudge(-1f, 0f, times)
                        Key.DirectionRight -> nudge(1f, 0f, times)
                        Key.DirectionUp -> nudge(0f, -1f, times)
                        Key.DirectionDown -> nudge(0f, 1f, times)
                        else -> return@onPreviewKeyEvent false
                    }
                    onEditor(moved)
                    true
                }
                .focusable(),
        )
    }
}

private fun CropCorner.spoken(): String = when (this) {
    CropCorner.TOP_LEFT -> "Top left"
    CropCorner.TOP_RIGHT -> "Top right"
    CropCorner.BOTTOM_RIGHT -> "Bottom right"
    CropCorner.BOTTOM_LEFT -> "Bottom left"
}

private fun CropEdge.spoken(): String = when (this) {
    CropEdge.TOP -> "Top"
    CropEdge.RIGHT -> "Right"
    CropEdge.BOTTOM -> "Bottom"
    CropEdge.LEFT -> "Left"
}

/** Which side [this] one is shown as once the picture is turned [rotation] degrees clockwise. */
private fun CropEdge.shownAs(rotation: Int): CropEdge = CropEdge.entries[(ordinal + rotation / 90) % 4]

/** Room around the photograph so a handle on its border is fully visible and reachable. */
private const val CANVAS_PADDING = 24

/** A press within this of a handle holds it: a 48 dp target. */
private const val TOUCH_RADIUS = 24
private const val EDGE_PILL_LENGTH = 28
private const val EDGE_PILL_THICKNESS = 6
private const val HELD_GROWTH = 1.25f
private const val LOUPE_DIAMETER = 112
private const val LOUPE_GAP = 40
private const val LOUPE_MAGNIFICATION = 3f
private const val CROP_DIM = 0.45f
private const val CROP_BRACKET_ARM = 36
private val CROP_INK = Color(0xFF80D5D4)
private val CROP_ERROR = Color(0xFFFFB4AB)
