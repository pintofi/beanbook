package io.github.pintofi.beanbook.core.domain

import kotlin.math.hypot

/** One side of the page, named by the corners at its ends in clockwise order. */
enum class CropEdge(val start: CropCorner, val end: CropCorner) {
    TOP(CropCorner.TOP_LEFT, CropCorner.TOP_RIGHT),
    RIGHT(CropCorner.TOP_RIGHT, CropCorner.BOTTOM_RIGHT),
    BOTTOM(CropCorner.BOTTOM_RIGHT, CropCorner.BOTTOM_LEFT),
    LEFT(CropCorner.BOTTOM_LEFT, CropCorner.TOP_LEFT),
}

/** What a finger can hold in the crop editor. */
sealed interface CropHandle {
    data class Corner(val corner: CropCorner) : CropHandle
    data class Edge(val edge: CropEdge) : CropHandle
}

/** The limits a crop has to stay within to be straightened. */
object CropLimits {
    /** Every corner's angle stays between this and 180 minus this. */
    const val MIN_CORNER_DEGREES = 15

    /** sin(15 degrees), written out so the rule needs no trigonometry. */
    const val MIN_TURN_SINE = 0.25881904f

    /** The shortest side a page may have, as a fraction of the photograph's shorter side. */
    const val MIN_EDGE_FRACTION = 0.02f

    /** Halvings when a drag would fold the page; 12 lands within 1/4096 of the way. */
    const val SEARCH_STEPS = 12

    /** An edge shows its middle handle only when it is at least this many touch radii long. */
    const val EDGE_HANDLE_RADII = 3f

    fun minEdge(width: Float, height: Float): Float = MIN_EDGE_FRACTION * minOf(width, height)
}

fun CropQuad.corners(): List<CropPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

fun CropQuad.at(corner: CropCorner): CropPoint = when (corner) {
    CropCorner.TOP_LEFT -> topLeft
    CropCorner.TOP_RIGHT -> topRight
    CropCorner.BOTTOM_RIGHT -> bottomRight
    CropCorner.BOTTOM_LEFT -> bottomLeft
}

fun CropQuad.withCorner(corner: CropCorner, point: CropPoint): CropQuad = when (corner) {
    CropCorner.TOP_LEFT -> copy(topLeft = point)
    CropCorner.TOP_RIGHT -> copy(topRight = point)
    CropCorner.BOTTOM_RIGHT -> copy(bottomRight = point)
    CropCorner.BOTTOM_LEFT -> copy(bottomLeft = point)
}

fun CropQuad.midpoint(edge: CropEdge): CropPoint {
    val a = at(edge.start)
    val b = at(edge.end)
    return CropPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)
}

/**
 * Whether this quad can be straightened: every side at least [minEdge] long, and every corner
 * between 15 and 165 degrees, turning the same way as the stored clockwise order. See the
 * design note: this one test rules out crossed, concave, collinear, mirrored and sliver shapes.
 */
fun CropQuad.isWarpable(minEdge: Float): Boolean {
    val p = corners()
    for (i in 0 until 4) {
        val prev = p[(i + 3) % 4]
        val here = p[i]
        val next = p[(i + 1) % 4]
        val ax = here.x - prev.x
        val ay = here.y - prev.y
        val bx = next.x - here.x
        val by = next.y - here.y
        val inLength = hypot(ax, ay)
        val outLength = hypot(bx, by)
        if (inLength < minEdge || outLength < minEdge) return false
        val cross = ax * by - ay * bx
        if (cross < CropLimits.MIN_TURN_SINE * inLength * outLength) return false
    }
    return true
}

/**
 * This quad with [corner] taken towards [to]: clamped inside the [width] x [height] image, and
 * stopped where going further would make the quad unwarpable.
 */
fun CropQuad.dragCorner(corner: CropCorner, to: CropPoint, width: Float, height: Float): CropQuad {
    val minEdge = CropLimits.minEdge(width, height)
    val target = CropPoint(to.x.coerceIn(0f, width), to.y.coerceIn(0f, height))
    val wanted = withCorner(corner, target)
    if (wanted.isWarpable(minEdge) || !isWarpable(minEdge)) return wanted
    val from = at(corner)
    val t = furthest { withCorner(corner, lerp(from, target, it)).isWarpable(minEdge) }
    return withCorner(corner, lerp(from, target, t))
}

/**
 * This quad with [edge] slid by [by], measured from where the drag started. Only the part of
 * [by] across the edge counts, so the edge moves parallel to itself; both ends stay inside the
 * image, and the slide stops where the quad would become unwarpable.
 */
fun CropQuad.dragEdge(edge: CropEdge, by: CropPoint, width: Float, height: Float): CropQuad {
    val minEdge = CropLimits.minEdge(width, height)
    val a = at(edge.start)
    val b = at(edge.end)
    val length = hypot(b.x - a.x, b.y - a.y)
    if (length == 0f) return this
    val nx = -(b.y - a.y) / length
    val ny = (b.x - a.x) / length
    val across = by.x * nx + by.y * ny
    val reach = minOf(
        reach(a, nx * across, ny * across, width, height),
        reach(b, nx * across, ny * across, width, height),
    )
    val dx = nx * across * reach
    val dy = ny * across * reach
    fun slid(t: Float) = withCorner(edge.start, CropPoint(a.x + dx * t, a.y + dy * t))
        .withCorner(edge.end, CropPoint(b.x + dx * t, b.y + dy * t))
    val wanted = slid(1f)
    if (wanted.isWarpable(minEdge) || !isWarpable(minEdge)) return wanted
    return slid(furthest { slid(it).isWarpable(minEdge) })
}

/**
 * The handle under [point], or null. Corners win over edge middles; the nearest wins among
 * each. [radius] is the touch radius in the same pixels as the quad.
 */
fun CropQuad.handleAt(point: CropPoint, radius: Float): CropHandle? {
    val limit = radius * radius
    fun distance(p: CropPoint): Float = (p.x - point.x) * (p.x - point.x) + (p.y - point.y) * (p.y - point.y)
    CropCorner.entries.map { it to distance(at(it)) }.filter { it.second <= limit }.minByOrNull { it.second }
        ?.let { return CropHandle.Corner(it.first) }
    return edgeHandles(radius).map { it to distance(midpoint(it)) }.filter { it.second <= limit }
        .minByOrNull { it.second }?.let { CropHandle.Edge(it.first) }
}

/** The edges long enough to show a middle handle without crowding the corners. */
fun CropQuad.edgeHandles(radius: Float): List<CropEdge> = CropEdge.entries.filter { edge ->
    val a = at(edge.start)
    val b = at(edge.end)
    hypot(b.x - a.x, b.y - a.y) >= CropLimits.EDGE_HANDLE_RADII * radius
}

/** Where an image point is drawn once the image is turned [rotation] degrees clockwise. */
fun CropPoint.turned(rotation: Int, width: Float, height: Float): CropPoint = when (rotation) {
    0 -> this
    90 -> CropPoint(height - y, x)
    180 -> CropPoint(width - x, height - y)
    270 -> CropPoint(y, width - x)
    else -> throw IllegalArgumentException("rotation must be a quarter turn, got $rotation")
}

/** The image point under [this] point of the image turned [rotation] degrees clockwise. */
fun CropPoint.unturned(rotation: Int, width: Float, height: Float): CropPoint = when (rotation) {
    0 -> this
    90 -> CropPoint(y, height - x)
    180 -> CropPoint(width - x, height - y)
    270 -> CropPoint(width - y, x)
    else -> throw IllegalArgumentException("rotation must be a quarter turn, got $rotation")
}

/** Which corner [this] one is shown as once the image is turned [rotation] degrees clockwise. */
fun CropCorner.shownAs(rotation: Int): CropCorner =
    CropCorner.entries[(ordinal + rotation / 90) % 4]

private fun lerp(a: CropPoint, b: CropPoint, t: Float) = CropPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** The largest s in 0..1 for which p + s(dx, dy) stays inside the image. */
private fun reach(p: CropPoint, dx: Float, dy: Float, width: Float, height: Float): Float {
    var s = 1f
    if (dx < 0f) s = minOf(s, p.x / -dx)
    if (dx > 0f) s = minOf(s, (width - p.x) / dx)
    if (dy < 0f) s = minOf(s, p.y / -dy)
    if (dy > 0f) s = minOf(s, (height - p.y) / dy)
    return s.coerceIn(0f, 1f)
}

/** The largest t in 0..1 with [valid] true, by bisection, given that valid(0) holds. */
private inline fun furthest(valid: (Float) -> Boolean): Float {
    var lo = 0f
    var hi = 1f
    repeat(CropLimits.SEARCH_STEPS) {
        val mid = (lo + hi) / 2f
        if (valid(mid)) lo = mid else hi = mid
    }
    return lo
}

/** The crop screen's state, in the pixels of the image it shows (unturned). */
data class CropEditor(
    val quad: CropQuad,
    val width: Float,
    val height: Float,
    val rotation: Int = 0,
    /** Where the screen opened, for Reset. */
    val initial: CropQuad = quad,
    /** Quads before each finished gesture, newest last, at most [HISTORY] long. */
    val history: List<CropQuad> = emptyList(),
    val holding: CropHandle? = null,
    /** Handle position minus finger position at press, so the handle does not jump. */
    val grab: CropPoint = CropPoint(0f, 0f),
    val pressedAt: CropPoint = CropPoint(0f, 0f),
    val quadAtPress: CropQuad = quad,
    /** True while the finger is past where the handle could follow. */
    val limited: Boolean = false,
) {
    val canConfirm: Boolean get() = quad.isWarpable(CropLimits.minEdge(width, height))
    val canUndo: Boolean get() = history.isNotEmpty()

    /** A finger went down at [at]; it holds the handle there, or nothing when no handle is within [radius]. */
    fun press(at: CropPoint, radius: Float): CropEditor {
        val handle = quad.handleAt(at, radius)
        val held = when (handle) {
            is CropHandle.Corner -> quad.at(handle.corner)
            is CropHandle.Edge -> quad.midpoint(handle.edge)
            null -> at
        }
        return copy(
            holding = handle,
            grab = CropPoint(held.x - at.x, held.y - at.y),
            pressedAt = at,
            quadAtPress = quad,
            limited = false,
        )
    }

    /** The finger moved to [to]. Corners follow it from where they are; edges slide from where the press began. */
    fun drag(to: CropPoint): CropEditor = when (val handle = holding) {
        is CropHandle.Corner -> {
            val wanted = CropPoint(to.x + grab.x, to.y + grab.y)
            val moved = quad.dragCorner(handle.corner, wanted, width, height)
            val reached = moved.at(handle.corner)
            val inside = CropPoint(wanted.x.coerceIn(0f, width), wanted.y.coerceIn(0f, height))
            copy(quad = moved, limited = reached != inside)
        }
        is CropHandle.Edge -> {
            val by = CropPoint(to.x - pressedAt.x, to.y - pressedAt.y)
            val moved = quadAtPress.dragEdge(handle.edge, by, width, height)
            val unlimited = quadAtPress.edgeSlidBy(handle.edge, by)
            copy(quad = moved, limited = moved != unlimited)
        }
        null -> this
    }

    /** The finger lifted. A gesture that changed the quad becomes one step of history. */
    fun release(): CropEditor {
        val changed = holding != null && quad != quadAtPress
        return copy(
            history = if (changed) (history + quadAtPress).takeLast(HISTORY) else history,
            holding = null,
            limited = false,
        )
    }

    /** One step from the keyboard or an accessibility action, as a whole gesture. */
    fun nudge(handle: CropHandle, dx: Float, dy: Float): CropEditor {
        val before = quad
        val after = when (handle) {
            is CropHandle.Corner -> {
                val at = quad.at(handle.corner)
                quad.dragCorner(handle.corner, CropPoint(at.x + dx, at.y + dy), width, height)
            }
            is CropHandle.Edge -> quad.dragEdge(handle.edge, CropPoint(dx, dy), width, height)
        }
        return if (after == before) this else copy(quad = after, history = (history + before).takeLast(HISTORY))
    }

    fun undo(): CropEditor =
        if (history.isEmpty()) this else copy(quad = history.last(), history = history.dropLast(1), holding = null)

    /** Replaces the quad (Reset, the whole photo, Detect again); one undoable step. */
    fun reset(to: CropQuad): CropEditor =
        if (to == quad) this else copy(quad = to, history = (history + quad).takeLast(HISTORY), holding = null)

    /** Turns the picture a quarter; the quad keeps its place in the photograph. */
    fun turn(clockwise: Boolean): CropEditor =
        copy(rotation = (rotation + if (clockwise) 90 else 270) % 360)

    companion object {
        const val HISTORY = 20
    }
}

/** Where an edge would be after sliding [by] across itself with no limit but the image border folded in. */
private fun CropQuad.edgeSlidBy(edge: CropEdge, by: CropPoint): CropQuad {
    val a = at(edge.start)
    val b = at(edge.end)
    val length = hypot(b.x - a.x, b.y - a.y)
    if (length == 0f) return this
    val across = by.x * (-(b.y - a.y) / length) + by.y * ((b.x - a.x) / length)
    val dx = -(b.y - a.y) / length * across
    val dy = (b.x - a.x) / length * across
    return withCorner(edge.start, CropPoint(a.x + dx, a.y + dy)).withCorner(edge.end, CropPoint(b.x + dx, b.y + dy))
}
