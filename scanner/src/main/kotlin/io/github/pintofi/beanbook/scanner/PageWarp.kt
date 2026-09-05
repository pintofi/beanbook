package io.github.pintofi.beanbook.scanner

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

/** How a captured page should be rendered once its corners are known. */
enum class ScanStyle {
    /** The photograph, straightened. Keeps colour — a receipt with a stamp, a coloured logo. */
    COLOUR,

    /** Straightened and flattened to near-white paper with dark text. The scanner look. */
    DOCUMENT,

    /** Straightened and greyscale. Smaller than colour, kinder to faint pencil than DOCUMENT. */
    GREYSCALE,
}

/**
 * Turns a photographed page into a rectangular one.
 *
 * Perspective correction, not cropping. A page photographed at an angle is a trapezium, and
 * cutting the trapezium out leaves it a trapezium — the four corners have to be mapped onto
 * a rectangle for the text to sit straight, which is what `warpPerspective` does.
 */
object PageWarp {

    /**
     * The straightened page.
     *
     * The output size is taken from the quadrilateral's own longest sides rather than fixed,
     * so a receipt does not come back the shape of A4. Both dimensions use `max` of the two
     * opposing edges: a page seen at an angle has one short edge and one long one, and the
     * long one is closer to the truth about how much detail is there.
     */
    fun straighten(source: Mat, quad: Quad, style: ScanStyle = ScanStyle.COLOUR): Mat {
        val width = max(
            distance(quad.topLeft, quad.topRight),
            distance(quad.bottomLeft, quad.bottomRight),
        )
        val height = max(
            distance(quad.topLeft, quad.bottomLeft),
            distance(quad.topRight, quad.bottomRight),
        )
        if (width < 1 || height < 1) return source

        val from = MatOfPoint2f(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        val to = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width - 1, 0.0),
            Point(width - 1, height - 1),
            Point(0.0, height - 1),
        )

        val transform = Imgproc.getPerspectiveTransform(from, to)
        val straightened = Mat(height.toInt(), width.toInt(), source.type())
        Imgproc.warpPerspective(source, straightened, transform, Size(width, height))
        return applyStyle(straightened, style)
    }

    /** The style pass, applied after warping so the filter sees a flat page. */
    fun applyStyle(page: Mat, style: ScanStyle): Mat = when (style) {
        ScanStyle.COLOUR -> page
        ScanStyle.GREYSCALE -> Mat().also { Imgproc.cvtColor(page, it, Imgproc.COLOR_RGBA2GRAY) }
        ScanStyle.DOCUMENT -> documentise(page)
    }

    /**
     * Paper white, ink black, shadows gone.
     *
     * Adaptive rather than a single threshold: a photograph of a page is never evenly lit —
     * one corner is in the phone's own shadow — and a global cut turns that corner solid
     * black. Comparing each pixel with the mean of its neighbourhood is what makes the
     * result independent of where the lamp is.
     *
     * The block is large and odd, and the constant is small, because text strokes are thin
     * relative to the page. A small block finds the texture of the paper as well as the ink.
     */
    private fun documentise(page: Mat): Mat {
        val grey = Mat()
        Imgproc.cvtColor(page, grey, Imgproc.COLOR_RGBA2GRAY)
        // Blur before thresholding: paper grain is high-frequency, and adaptive thresholding
        // is happy to render every fibre of it as a speck of ink.
        Imgproc.medianBlur(grey, grey, 3)
        val out = Mat(grey.rows(), grey.cols(), CvType.CV_8UC1)
        Imgproc.adaptiveThreshold(
            grey, out, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
            /* blockSize = */ 31, /* C = */ 10.0,
        )
        return out
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
}
