package io.github.pintofi.beanbook.scanner

import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc


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

    /**
     * Every native matrix made here is released before returning. A `Mat` frees its native
     * memory only when the garbage collector finalises it, and the collector sees a few bytes of
     * Java object, not the megabytes behind it; on the live preview, which calls this several
     * times a second, lets native memory climb between collections.
     */
    fun detect(source: Mat): CropQuad? {
        if (source.empty()) return null
        val scratch = mutableListOf<Mat>()
        fun <T : Mat> T.tracked(): T = also { scratch += it }
        try {
            return findPage(source) { Mat().tracked() }
        } finally {
            scratch.forEach { it.release() }
        }
    }

    private fun findPage(source: Mat, newMat: () -> Mat): CropQuad? {
        val scale = WORKING_HEIGHT / source.rows().toDouble()
        val small = newMat()
        if (scale < 1.0) {
            Imgproc.resize(source, small, Size(source.cols() * scale, WORKING_HEIGHT))
        } else {
            source.copyTo(small)
        }

        val prepared = newMat()
        Imgproc.cvtColor(small, prepared, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(prepared, prepared, Size(5.0, 5.0), 0.0)
        // Canny before contours: a page edge is a brightness step, and thresholds relative to
        // the blurred image survive both a bright desk and a dim one better than a fixed pair.
        Imgproc.Canny(prepared, prepared, 50.0, 150.0)
        // Close first. A page photographed on a similar-coloured surface produces a broken
        // outline, and four almost-touching arcs are four contours, none of them a rectangle.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(prepared, prepared, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = newMat()
        Imgproc.findContours(
            prepared, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val frameArea = small.rows().toDouble() * small.cols().toDouble()
        val best = try {
            contours
                .asSequence()
                .mapNotNull { asQuadrilateral(it) }
                .map { quad -> quad to areaOf(quad) }
                .filter { (_, area) ->
                    area > frameArea * MIN_AREA_FRACTION && area < frameArea * MAX_AREA_FRACTION
                }
                .maxByOrNull { (_, area) -> area }
                ?.first
        } finally {
            contours.forEach { it.release() }
        }

        val inverse = if (scale < 1.0) 1.0 / scale else 1.0
        return best?.scaled(inverse)
    }

    /** A contour that reduces to four convex corners, ordered, or null. */
    private fun asQuadrilateral(contour: MatOfPoint): CropQuad? {
        val curve = MatOfPoint2f(*contour.toArray())
        val approximation = MatOfPoint2f()
        try {
            val perimeter = Imgproc.arcLength(curve, true)
            // 2% of the perimeter: enough to fold a slightly wavy paper edge into one straight
            // line, not enough to turn a pentagon into a rectangle.
            Imgproc.approxPolyDP(curve, approximation, 0.02 * perimeter, true)

            val corners = approximation.toArray()
            if (corners.size != 4) return null
            val outline = MatOfPoint(*corners)
            try {
                if (!Imgproc.isContourConvex(outline)) return null
            } finally {
                outline.release()
            }
            return order(corners)
        } finally {
            curve.release()
            approximation.release()
        }
    }

    private fun areaOf(quad: CropQuad): Double {
        val outline = MatOfPoint(*quad.asPoints().toTypedArray())
        return try {
            Imgproc.contourArea(outline)
        } finally {
            outline.release()
        }
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
    private fun order(corners: Array<Point>): CropQuad {
        val bySum = corners.sortedBy { it.x + it.y }
        val byDiff = corners.sortedBy { it.y - it.x }
        return CropQuad(
            topLeft = bySum.first().asCropPoint(),
            topRight = byDiff.first().asCropPoint(),
            bottomRight = bySum.last().asCropPoint(),
            bottomLeft = byDiff.last().asCropPoint(),
        )
    }

    private fun Point.asCropPoint() = CropPoint(x.toFloat(), y.toFloat())

    private fun CropQuad.scaled(factor: Double): CropQuad {
        val f = factor.toFloat()
        fun CropPoint.times() = CropPoint(x * f, y * f)
        return CropQuad(topLeft.times(), topRight.times(), bottomRight.times(), bottomLeft.times())
    }

    /** The corners as OpenCV points, for area tests inside this file. */
    internal fun CropQuad.asPoints(): List<Point> =
        listOf(topLeft, topRight, bottomRight, bottomLeft).map { Point(it.x.toDouble(), it.y.toDouble()) }
}
