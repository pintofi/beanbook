package io.github.pintofi.beanbook.scanner

import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import io.github.pintofi.beanbook.core.domain.PageCandidate
import io.github.pintofi.beanbook.core.domain.PageChoice
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
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

    /** Contours shorter than this are noise, whatever their area. */
    private const val MIN_PERIMETER = 100.0

    /** Only this many of the largest contours of a pass are looked at, which bounds a noisy pass. */
    private const val MAX_CONTOURS = 12

    /**
     * The page in [source], or null.
     *
     * [thorough] runs every pass, for a photograph that has been taken; the live preview passes
     * false and runs a handful, so it stays cheap enough to run several times a second. Every
     * native matrix made here is released before returning. A `Mat` frees its native memory only
     * when the garbage collector finalises it, and the collector sees a few bytes of Java object,
     * not the megabytes behind it; on the live preview, which calls this several times a second,
     * lets native memory climb between collections.
     */
    fun detect(source: Mat, thorough: Boolean = true): CropQuad? =
        report(source, if (thorough) DetectionPasses.FULL else DetectionPasses.LIVE)?.chosen

    /** What a detection run found and how, for tests and tuning. */
    internal class DetectionReport(
        /** The page, in the source's pixels. */
        val chosen: CropQuad?,
        /** What the first pass alone found, as the detector did before there were several. */
        val legacy: CropQuad?,
        /** Every shape every pass found, in the working image's pixels. */
        val candidates: List<PageCandidate>,
        val passesRun: Int,
    )

    internal fun report(source: Mat, passes: List<DetectionPasses.PassSpec>): DetectionReport? {
        if (source.empty()) return null
        val scratch = mutableListOf<Mat>()
        fun <T : Mat> T.tracked(): T = also { scratch += it }
        try {
            return run(source, passes) { Mat().tracked() }
        } finally {
            scratch.forEach { it.release() }
        }
    }

    private fun run(source: Mat, passes: List<DetectionPasses.PassSpec>, newMat: () -> Mat): DetectionReport {
        val scale = WORKING_HEIGHT / source.rows().toDouble()
        val small = newMat()
        if (scale < 1.0) {
            Imgproc.resize(source, small, Size(source.cols() * scale, WORKING_HEIGHT))
        } else {
            source.copyTo(small)
        }
        val width = small.cols().toFloat()
        val height = small.rows().toFloat()
        val channels = DetectionPasses.Channels(small, newMat)

        val candidates = mutableListOf<PageCandidate>()
        var legacy: CropQuad? = null
        var run = 0
        for (pass in passes) {
            val edged = DetectionPasses.edges(pass, channels, newMat)
            val found = findQuads(edged, pass.index, small, strict = pass.index != DetectionPasses.LEGACY_PASS)
            run += 1
            if (pass.index == DetectionPasses.LEGACY_PASS) legacy = largestPage(found, width, height)
            candidates += found
            if (PageChoice.settled(candidates, width, height)) break
        }

        val chosen = PageChoice.choose(candidates, width, height, legacy)
        val inverse = if (scale < 1.0) 1.0 / scale else 1.0
        return DetectionReport(chosen?.scaled(inverse), legacy?.scaled(inverse), candidates, run)
    }

    /** What the single-pass detector took as the page: the largest shape within the area limits. */
    private fun largestPage(found: List<PageCandidate>, width: Float, height: Float): CropQuad? =
        found.map { it.quad }
            .filter { it.area() > width * height * MIN_AREA_FRACTION && it.area() < width * height * MAX_AREA_FRACTION }
            .maxByOrNull { it.area() }

    /**
     * Every four-cornered convex shape in a binary image.
     *
     * [strict] adds the cheap filters that keep a noisy pass fast -- a minimum perimeter and area,
     * and only the largest few contours. The first pass runs without them, as it always has.
     */
    private fun findQuads(edged: Mat, pass: Int, small: Mat, strict: Boolean): List<PageCandidate> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        try {
            // RETR_LIST, not the outer contours only: a dark page on a light table is a hole in the mask.
            Imgproc.findContours(edged, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val frameArea = small.rows().toDouble() * small.cols().toDouble()
            val worth = if (strict) {
                contours
                    .map { it to Geometry.contourArea(it) }
                    .filter { (_, area) -> area >= frameArea * PRE_FILTER_AREA }
                    .sortedByDescending { (_, area) -> area }
                    .take(MAX_CONTOURS)
                    .map { it.first }
            } else {
                contours
            }
            return worth.mapNotNull { contour ->
                asQuadrilateral(contour, strict)?.let { PageCandidate(it, pass) }
            }
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
        }
    }

    /** A contour that reduces to four convex corners, ordered, or null. */
    private fun asQuadrilateral(contour: MatOfPoint, strict: Boolean): CropQuad? {
        val curve = MatOfPoint2f(*contour.toArray())
        val approximation = MatOfPoint2f()
        try {
            val perimeter = Geometry.arcLength(curve, true)
            if (strict && perimeter < MIN_PERIMETER) return null
            // 2% of the perimeter: enough to fold a slightly wavy paper edge into one straight
            // line, not enough to turn a pentagon into a rectangle.
            Geometry.approxPolyDP(curve, approximation, 0.02 * perimeter, true)

            val corners = approximation.toArray()
            if (corners.size != 4) return null
            val outline = MatOfPoint(*corners)
            try {
                if (!Geometry.isContourConvex(outline)) return null
            } finally {
                outline.release()
            }
            return PageChoice.orderCorners(corners.map { CropPoint(it.x.toFloat(), it.y.toFloat()) })
        } finally {
            curve.release()
            approximation.release()
        }
    }

    private fun CropQuad.scaled(factor: Double): CropQuad {
        val f = factor.toFloat()
        fun CropPoint.times() = CropPoint(x * f, y * f)
        return CropQuad(topLeft.times(), topRight.times(), bottomRight.times(), bottomLeft.times())
    }

    /** The corners as OpenCV points, for area tests inside this file. */
    internal fun CropQuad.asPoints(): List<Point> =
        listOf(topLeft, topRight, bottomRight, bottomLeft).map { Point(it.x.toDouble(), it.y.toDouble()) }

    /** Below this fraction of the frame it is a label or a stain, not the page. */
    private const val MIN_AREA_FRACTION = 0.12

    /** Above this it is the frame itself — a table edge, or the phone's own shadow. */
    private const val MAX_AREA_FRACTION = 0.98

    /** A contour smaller than this share of the frame is not worth approximating. */
    private const val PRE_FILTER_AREA = 0.05
}
