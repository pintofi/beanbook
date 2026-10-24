package io.github.pintofi.beanbook.scanner

import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
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

    /** Black on white, from the grey scan. Clean print only: faint or thin strokes can break up. */
    BLACK_AND_WHITE,
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

        val transform = Geometry.getPerspectiveTransform(from, to)
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
    fun applyStyle(page: Mat, style: ScanStyle): Mat = PageStyles.apply(page, style)

    private fun CropPoint.asPoint() = Point(x.toDouble(), y.toDouble())

    private fun distance(a: CropPoint, b: CropPoint) =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
}
