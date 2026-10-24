package io.github.pintofi.beanbook.core.domain

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** A point in image pixels. */
data class CropPoint(val x: Float, val y: Float)

/** Which corner a drag is moving. */
enum class CropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/**
 * The four corners of a page within a photograph, in image pixels.
 *
 * A receipt photographed on a table is shot at an angle, so its outline on the sensor is a
 * general quadrilateral rather than a rectangle: the near edge is longer than the far one and
 * the sides lean. Straightening that — mapping the quadrilateral onto a rectangle — is what
 * turns a snapshot into something that reads like a scan.
 *
 * The geometry lives here, in pure Kotlin, because it is arithmetic and can be checked
 * without a camera, a bitmap or a screen. Applying the transform needs Android's `Matrix`
 * and belongs in the app.
 */
data class CropQuad(
    val topLeft: CropPoint,
    val topRight: CropPoint,
    val bottomRight: CropPoint,
    val bottomLeft: CropPoint,
) {

    /**
     * The size to flatten onto, taking the longer of each opposing pair.
     *
     * Perspective makes the near edge longer than the far one. Using the longer of each pair
     * keeps the detail that edge actually captured, rather than resampling the whole page
     * down to its most foreshortened side. Never smaller than 1×1: corners dragged onto each
     * other would otherwise ask for a zero-sized bitmap, which throws.
     */
    fun outputSize(): Pair<Int, Int> {
        val width = max(distance(topLeft, topRight), distance(bottomLeft, bottomRight))
        val height = max(distance(topLeft, bottomLeft), distance(topRight, bottomRight))
        return max(1, width.roundToInt()) to max(1, height.roundToInt())
    }

    /** This quad with one corner moved, clamped inside the image. */
    fun move(corner: CropCorner, to: CropPoint, width: Float, height: Float): CropQuad {
        val clamped = CropPoint(to.x.coerceIn(0f, width), to.y.coerceIn(0f, height))
        return when (corner) {
            CropCorner.TOP_LEFT -> copy(topLeft = clamped)
            CropCorner.TOP_RIGHT -> copy(topRight = clamped)
            CropCorner.BOTTOM_RIGHT -> copy(bottomRight = clamped)
            CropCorner.BOTTOM_LEFT -> copy(bottomLeft = clamped)
        }
    }

    private fun points(): List<CropPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** The area inside the corners, by the shoelace formula; the same whichever way round they are listed. */
    fun area(): Float {
        val p = points()
        var twice = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            twice += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(twice) / 2f
    }

    /**
     * How far from square the sharpest corner is: the largest |cos| of any corner's angle. 0 for a
     * rectangle, about 0.5 for 60 degrees, towards 1 for a sliver.
     */
    fun maxCornerCosine(): Float {
        val p = points()
        var worst = 0f
        for (i in p.indices) {
            val here = p[i]
            val before = p[(i + p.size - 1) % p.size]
            val after = p[(i + 1) % p.size]
            val ax = before.x - here.x
            val ay = before.y - here.y
            val bx = after.x - here.x
            val by = after.y - here.y
            val lengths = (ax * ax + ay * ay) * (bx * bx + by * by)
            val cosine = kotlin.math.abs((ax * bx + ay * by) / kotlin.math.sqrt(lengths + TINY))
            if (cosine > worst) worst = cosine
        }
        return worst
    }

    /**
     * Whether [point] is inside this convex quad, after shrinking the quad towards its centre by
     * [tolerance] pixels -- so a point that is only just inside does not count as contained.
     */
    fun contains(point: CropPoint, tolerance: Float = 0f): Boolean {
        val p = points()
        val cx = p.sumOf { it.x.toDouble() }.toFloat() / p.size
        val cy = p.sumOf { it.y.toDouble() }.toFloat() / p.size
        val shrunk = p.map { corner ->
            val dx = cx - corner.x
            val dy = cy - corner.y
            val length = hypot(dx, dy)
            if (length <= tolerance || length == 0f) CropPoint(cx, cy) else CropPoint(
                corner.x + dx / length * tolerance,
                corner.y + dy / length * tolerance,
            )
        }
        var sign = 0
        for (i in shrunk.indices) {
            val a = shrunk[i]
            val b = shrunk[(i + 1) % shrunk.size]
            val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (sign != s) return false
            }
        }
        return true
    }

    /** Whether every corner is within [limit] of the same corner of [other]. */
    fun isNear(other: CropQuad, limit: Float): Boolean =
        points().zip(other.points()).all { (a, b) -> distance(a, b) <= limit }

    /** The corners in the order Android's `Matrix.setPolyToPoly` wants them. */
    fun asFloatArray(): FloatArray = floatArrayOf(
        topLeft.x, topLeft.y,
        topRight.x, topRight.y,
        bottomRight.x, bottomRight.y,
        bottomLeft.x, bottomLeft.y,
    )

    companion object {
        private const val TINY = 1e-10f

        /** The whole image, which is where a crop starts before anything is dragged. */
        fun whole(width: Float, height: Float) = CropQuad(
            topLeft = CropPoint(0f, 0f),
            topRight = CropPoint(width, 0f),
            bottomRight = CropPoint(width, height),
            bottomLeft = CropPoint(0f, height),
        )
    }
}

private fun distance(a: CropPoint, b: CropPoint) = hypot(b.x - a.x, b.y - a.y)
