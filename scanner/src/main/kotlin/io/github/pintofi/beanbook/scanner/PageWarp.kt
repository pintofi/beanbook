package io.github.pintofi.beanbook.scanner

import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import org.opencv.core.Core
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
    fun straighten(source: Mat, quad: CropQuad, style: ScanStyle = ScanStyle.COLOUR): Mat {
        val width = max(
            distance(quad.topLeft, quad.topRight),
            distance(quad.bottomLeft, quad.bottomRight),
        )
        val height = max(
            distance(quad.topLeft, quad.bottomLeft),
            distance(quad.topRight, quad.bottomRight),
        )
        if (width < 1 || height < 1) return source

        val from = MatOfPoint2f(
            quad.topLeft.asPoint(),
            quad.topRight.asPoint(),
            quad.bottomRight.asPoint(),
            quad.bottomLeft.asPoint(),
        )
        val to = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width - 1, 0.0),
            Point(width - 1, height - 1),
            Point(0.0, height - 1),
        )

        val transform = Imgproc.getPerspectiveTransform(from, to)
        val straightened = Mat(height.toInt(), width.toInt(), source.type())
        try {
            Imgproc.warpPerspective(source, straightened, transform, Size(width, height))
            val styled = applyStyle(straightened, style)
            // The straightened page is only a stepping stone when the style made a new one.
            if (styled !== straightened) straightened.release()
            return styled
        } catch (failure: Throwable) {
            straightened.release()
            throw failure
        } finally {
            from.release()
            to.release()
            transform.release()
        }
    }

    /** The style pass, applied after warping so the filter sees a flat page. */
    fun applyStyle(page: Mat, style: ScanStyle): Mat = when (style) {
        ScanStyle.COLOUR -> page
        ScanStyle.GREYSCALE -> Mat().also { Imgproc.cvtColor(page, it, Imgproc.COLOR_RGBA2GRAY) }
        ScanStyle.DOCUMENT -> documentise(page)
    }

    /**
     * Paper white, ink dark, shadows gone -- and the edges of the letters kept.
     *
     * A photograph of a page is never evenly lit: one corner is in the phone's own shadow.
     * This used to answer that with an adaptive *threshold*, which does remove the shadow but
     * turns every pixel black or white: thin strokes broke up, small print filled in, a faint
     * stamp vanished, and the edges of every letter came out jagged. What a scanner app means
     * by "document" is flatter lighting, not fewer grey levels.
     *
     * So the lighting is estimated and divided out. Dilating the page with a kernel wider than
     * a stroke erases the ink and leaves the paper; a median blur smooths that into the light
     * falling on the sheet. The difference between the page and its own lighting is the ink,
     * on white, whatever the lamp did -- and the anti-aliased edge of each letter survives. A
     * final stretch pushes the paper to white and the ink to black.
     *
     * Kernel sizes follow the page's width so a receipt and a full sheet are treated alike.
     */
    private fun documentise(page: Mat): Mat {
        val grey = Mat()
        val background = Mat()
        val ink = Mat()
        try {
            Imgproc.cvtColor(page, grey, Imgproc.COLOR_RGBA2GRAY)
            val stroke = odd(maxOf(7, grey.cols() / 120))
            val light = odd(maxOf(21, minOf(grey.cols() / 40, 101)))
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(stroke.toDouble(), stroke.toDouble()),
            )
            try {
                Imgproc.dilate(grey, background, kernel)
            } finally {
                kernel.release()
            }
            Imgproc.medianBlur(background, background, light)

            Core.absdiff(grey, background, ink)
        } catch (failure: Throwable) {
            ink.release()
            throw failure
        } finally {
            grey.release()
            background.release()
        }
        Core.bitwise_not(ink, ink)
        Core.normalize(ink, ink, 0.0, 255.0, Core.NORM_MINMAX)
        // Paper is everything close to white; lifting it the rest of the way clears the grain
        // and the last of the shadow without touching the ink.
        Imgproc.threshold(ink, ink, PAPER_LEVEL, 255.0, Imgproc.THRESH_TRUNC)
        Core.normalize(ink, ink, 0.0, 255.0, Core.NORM_MINMAX)
        return ink
    }

    private fun odd(value: Int) = if (value % 2 == 0) value + 1 else value

    /** Above this grey level a pixel is paper. */
    private const val PAPER_LEVEL = 225.0

    private fun CropPoint.asPoint() = Point(x.toDouble(), y.toDouble())

    private fun distance(a: CropPoint, b: CropPoint) =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
}
