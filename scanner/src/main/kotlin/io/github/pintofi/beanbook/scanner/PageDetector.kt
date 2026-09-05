package io.github.pintofi.beanbook.scanner

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** A page found in a frame, as four corners in the frame's own pixel coordinates. */
data class Quad(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point,
) {
    val points: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/**
 * Finds the page in a photograph.
 *
 * The pipeline is the one every open-source scanner converges on, and it converges for a
 * reason: a sheet of paper against a darker surface is the largest convex quadrilateral in
 * the frame. Greyscale, blur away the paper's texture, close the gaps in the edges so a
 * broken border still forms one contour, find contours, keep the ones that reduce to four
 * convex corners, take the biggest.
 *
 * Read against OSS-DocumentScanner's `cpp/src/DocumentDetector.cpp` (MIT, Martin Guillon),
 * which is a working instance of it, rather than invented here. MIT permits reuse in a
 * GPL-2.0-only work. No code is copied — that file is NativeScript-facing C++ — but the
 * operation order and the convexity and area tests come from reading it, and it deserves
 * the credit.
 *
 * **Detection is advisory.** A page it cannot find leaves the user cropping by hand, which
 * is why nothing here throws and why [detect] returns null rather than a guess. A wrong
 * quadrilateral silently applied to a receipt is worse than none.
 */
object PageDetector {

    /**
     * Work at this height, whatever the camera gives.
     *
     * Detection does not get better with more pixels — the edges it looks for survive
     * downscaling, and the blur below would throw the detail away regardless — but it does
     * get slower, quadratically. The quad is scaled back to full resolution before return,
     * so the caller never sees this.
     */
    private const val WORKING_HEIGHT = 500.0

    /** Below this fraction of the frame it is a label or a stain, not the page. */
    private const val MIN_AREA_FRACTION = 0.12

    /** Above this it is the frame itself — a table edge, or the phone's own shadow. */
    private const val MAX_AREA_FRACTION = 0.98

    fun detect(source: Mat): Quad? {
        if (source.empty()) return null

        val scale = WORKING_HEIGHT / source.rows().toDouble()
        val small = Mat()
        if (scale < 1.0) {
            Imgproc.resize(source, small, Size(source.cols() * scale, WORKING_HEIGHT))
        } else {
            source.copyTo(small)
        }

        val prepared = Mat()
        Imgproc.cvtColor(small, prepared, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(prepared, prepared, Size(5.0, 5.0), 0.0)
        // Canny before contours: a page edge is a brightness step, and thresholds relative to
        // the blurred image survive both a bright desk and a dim one better than a fixed pair.
        Imgproc.Canny(prepared, prepared, 50.0, 150.0)
        // Close first. A page photographed on a similar-coloured surface produces a broken
        // outline, and four almost-touching arcs are four contours, none of them a rectangle.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(prepared, prepared, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            prepared, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val frameArea = small.rows().toDouble() * small.cols().toDouble()
        val best = contours
            .asSequence()
            .mapNotNull { asQuadrilateral(it) }
            .filter { quad ->
                val area = Imgproc.contourArea(MatOfPoint(*quad.points.toTypedArray()))
                area > frameArea * MIN_AREA_FRACTION && area < frameArea * MAX_AREA_FRACTION
            }
            .maxByOrNull { Imgproc.contourArea(MatOfPoint(*it.points.toTypedArray())) }

        val inverse = if (scale < 1.0) 1.0 / scale else 1.0
        return best?.let { quad ->
            Quad(
                topLeft = quad.topLeft.scaled(inverse),
                topRight = quad.topRight.scaled(inverse),
                bottomRight = quad.bottomRight.scaled(inverse),
                bottomLeft = quad.bottomLeft.scaled(inverse),
            )
        }
    }

    /** A contour that reduces to four convex corners, ordered, or null. */
    private fun asQuadrilateral(contour: MatOfPoint): Quad? {
        val curve = MatOfPoint2f(*contour.toArray())
        val perimeter = Imgproc.arcLength(curve, true)
        val approximation = MatOfPoint2f()
        // 2% of the perimeter: enough to fold a slightly wavy paper edge into one straight
        // line, not enough to turn a pentagon into a rectangle.
        Imgproc.approxPolyDP(curve, approximation, 0.02 * perimeter, true)

        val corners = approximation.toArray()
        if (corners.size != 4) return null
        if (!Imgproc.isContourConvex(MatOfPoint(*corners))) return null
        return order(corners)
    }

    /**
     * Corners in a known order, so the warp does not mirror or rotate the page.
     *
     * `approxPolyDP` returns them in traversal order, which depends on where the contour
     * happened to start — so the same page can come back rotated between frames. Sorting by
     * the sum and difference of the coordinates fixes each corner independently of that:
     * the top-left has the smallest x+y, the bottom-right the largest, and the other two
     * separate on y-x.
     */
    private fun order(corners: Array<Point>): Quad {
        val bySum = corners.sortedBy { it.x + it.y }
        val byDiff = corners.sortedBy { it.y - it.x }
        return Quad(
            topLeft = bySum.first(),
            bottomRight = bySum.last(),
            topRight = byDiff.first(),
            bottomLeft = byDiff.last(),
        )
    }

    private fun Point.scaled(factor: Double) = Point(x * factor, y * factor)
}
