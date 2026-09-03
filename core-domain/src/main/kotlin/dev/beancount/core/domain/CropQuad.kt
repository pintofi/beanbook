package dev.beancount.core.domain

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

    /** True when the corners already form an upright rectangle, so cropping is a plain cut. */
    val isRectangular: Boolean
        get() = topLeft.y == topRight.y &&
            bottomLeft.y == bottomRight.y &&
            topLeft.x == bottomLeft.x &&
            topRight.x == bottomRight.x

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

    /** The corners in the order Android's `Matrix.setPolyToPoly` wants them. */
    fun asFloatArray(): FloatArray = floatArrayOf(
        topLeft.x, topLeft.y,
        topRight.x, topRight.y,
        bottomRight.x, bottomRight.y,
        bottomLeft.x, bottomLeft.y,
    )

    companion object {
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
